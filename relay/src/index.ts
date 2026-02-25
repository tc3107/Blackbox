import {
  badRequest,
  canonicalAclMessage,
  canonicalClearMessage,
  canonicalPullMessage,
  canonicalPushMessage,
  canonicalSelfStatusMessage,
  ClearLocationRequest,
  decodeBase64Url,
  encodeBase64Url,
  ensureRequestFresh,
  jsonResponse,
  nowMs,
  normalizeReceiverIds,
  normalizeRecipientCiphertexts,
  parseJson,
  PullBatchRequest,
  PullRecord,
  PushLocationRequest,
  SenderStateDocument,
  SelfStatusRequest,
  unauthorized,
  UpsertAclRequest,
  validateReceiverIds,
  validateSenderId,
  validateSeq,
} from "./protocol";

const DOC_KEY = "doc";
const MAX_REQUEST_SKEW_MS = 5 * 60_000;
const NONCE_TTL_MS = 15 * 60_000;
const MAX_REQUEST_BODY_BYTES = 300_000;

export interface Env {
  SENDER_STATE: DurableObjectNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "POST") {
      return badRequest("Only POST is supported.");
    }

    const rawBody = await request.text();
    if (rawBody.length > MAX_REQUEST_BODY_BYTES) {
      return jsonResponse({ ok: false, message: "Request too large." }, { status: 413 });
    }

    if (url.pathname === "/v1/location/pull") {
      return handlePullBatch(rawBody, env);
    }

    const routing = routeToSenderObject(url.pathname);
    if (!routing) {
      return jsonResponse({ ok: false, message: "Endpoint not found." }, { status: 404 });
    }

    const senderId = extractSenderId(routing.path, rawBody);
    if (!senderId || !validateSenderId(senderId)) {
      return badRequest("Invalid senderId in request body.");
    }

    const objectId = env.SENDER_STATE.idFromName(senderId);
    const stub = env.SENDER_STATE.get(objectId);
    return stub.fetch(`https://sender.internal${routing.internalPath}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: rawBody,
    });
  },
};

async function handlePullBatch(rawBody: string, env: Env): Promise<Response> {
  let body: PullBatchRequest;
  try {
    body = JSON.parse(rawBody) as PullBatchRequest;
  } catch {
    return badRequest("Invalid JSON body.");
  }

  if (!validateSenderId(body.receiverId)) {
    return badRequest("Invalid receiverId.");
  }
  if (!validateReceiverIds(body.senderIds)) {
    return badRequest("Invalid senderIds list.");
  }
  if (!ensureRequestFresh(body.timestampMs, MAX_REQUEST_SKEW_MS)) {
    return badRequest("Pull request timestamp out of allowed window.");
  }
  if (!body.nonceB64Url || !body.signatureB64Url || !body.receiverSignPublicKeySpkiB64Url) {
    return badRequest("Missing pull authentication fields.");
  }

  const receiverIdFromKey = await senderIdFromSignPublicKey(body.receiverSignPublicKeySpkiB64Url);
  if (!timingSafeEqual(body.receiverId, receiverIdFromKey)) {
    return unauthorized("Receiver identity does not match signing key.");
  }

  const pullMessage = canonicalPullMessage(
    body.receiverId,
    body.senderIds,
    body.timestampMs,
    body.nonceB64Url,
  );
  const pullSignatureValid = await verifyEd25519Signature(
    body.receiverSignPublicKeySpkiB64Url,
    pullMessage,
    body.signatureB64Url,
  );
  if (!pullSignatureValid) {
    return unauthorized("Pull signature validation failed.");
  }

  const normalizedSenderIds = normalizeReceiverIds(body.senderIds);
  const records = await Promise.all(
    normalizedSenderIds.map(async (senderId): Promise<PullRecord> => {
      try {
        const objectId = env.SENDER_STATE.idFromName(senderId);
        const stub = env.SENDER_STATE.get(objectId);
        const response = await stub.fetch("https://sender.internal/internal/location/pull-one", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ ...body, senderIds: normalizedSenderIds, senderId }),
        });
        const payload = (await response.json()) as PullRecord;
        return payload;
      } catch (error) {
        return {
          senderId,
          status: "error",
          message: error instanceof Error ? error.message : "Unknown pull error",
        };
      }
    }),
  );

  return jsonResponse({
    ok: true,
    serverTimestampMs: nowMs(),
    records,
  });
}

function routeToSenderObject(path: string): { path: string; internalPath: string } | null {
  switch (path) {
    case "/v1/acl/upsert":
      return { path, internalPath: "/internal/acl/upsert" };
    case "/v1/location/push":
      return { path, internalPath: "/internal/location/push" };
    case "/v1/location/self-status":
      return { path, internalPath: "/internal/location/self-status" };
    case "/v1/location/clear":
      return { path, internalPath: "/internal/location/clear" };
    default:
      return null;
  }
}

function extractSenderId(path: string, rawBody: string): string | null {
  try {
    const parsed = JSON.parse(rawBody) as Record<string, unknown>;
    if (path === "/v1/acl/upsert") {
      const acl = parsed.acl as Record<string, unknown> | undefined;
      return (acl?.senderId as string) ?? null;
    }
    if (path === "/v1/location/push") {
      const push = parsed.push as Record<string, unknown> | undefined;
      const envelope = push?.envelope as Record<string, unknown> | undefined;
      return (envelope?.senderId as string) ?? null;
    }
    return (parsed.senderId as string) ?? null;
  } catch {
    return null;
  }
}

export class SenderStateDO {
  private state: DurableObjectState;

  constructor(state: DurableObjectState) {
    this.state = state;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    switch (url.pathname) {
      case "/internal/acl/upsert":
        return this.handleUpsertAcl(request);
      case "/internal/location/push":
        return this.handlePush(request);
      case "/internal/location/self-status":
        return this.handleSelfStatus(request);
      case "/internal/location/clear":
        return this.handleClear(request);
      case "/internal/location/pull-one":
        return this.handlePullOne(request);
      default:
        return jsonResponse({ ok: false, message: "Unknown DO route." }, { status: 404 });
    }
  }

  private async handleUpsertAcl(request: Request): Promise<Response> {
    const body = await parseJson<UpsertAclRequest>(request);
    if (!validateSenderId(body.acl?.senderId)) {
      return badRequest("Invalid senderId.");
    }
    if (!validateSeq(body.acl?.aclSeq)) {
      return badRequest("Invalid aclSeq.");
    }
    if (!validateReceiverIds(body.acl?.receiverIds)) {
      return badRequest("Invalid receiverIds.");
    }

    const senderIdFromKey = await senderIdFromSignPublicKey(body.senderSignPublicKeySpkiB64Url);
    if (!timingSafeEqual(body.acl.senderId, senderIdFromKey)) {
      return unauthorized("ACL senderId does not match signing key.");
    }

    const signatureValid = await verifyEd25519Signature(
      body.senderSignPublicKeySpkiB64Url,
      canonicalAclMessage(body.acl),
      body.signatureB64Url,
    );
    if (!signatureValid) {
      return unauthorized("ACL signature verification failed.");
    }

    const doc = await this.readDoc(body.acl.senderId);
    if (
      doc.senderSignPublicKeySpkiB64Url &&
      !timingSafeEqual(doc.senderSignPublicKeySpkiB64Url, body.senderSignPublicKeySpkiB64Url)
    ) {
      return unauthorized("Sender signing key mismatch; key rotation is unsupported in v1.");
    }

    if (doc.aclSeq >= body.acl.aclSeq) {
      return jsonResponse({ ok: false, message: "aclSeq must increase monotonically." }, { status: 409 });
    }

    const uniqueReceivers = normalizeReceiverIds(body.acl.receiverIds).slice(0, 200);
    const nextDoc: SenderStateDocument = {
      ...doc,
      senderSignPublicKeySpkiB64Url: body.senderSignPublicKeySpkiB64Url,
      aclSeq: body.acl.aclSeq,
      receiverIds: uniqueReceivers,
    };
    await this.writeDoc(nextDoc);

    return jsonResponse({ ok: true, appliedAclSeq: nextDoc.aclSeq });
  }

  private async handlePush(request: Request): Promise<Response> {
    const body = await parseJson<PushLocationRequest>(request);
    const envelope = body.push?.envelope;
    if (!envelope || !validateSenderId(envelope.senderId)) {
      return badRequest("Invalid push senderId.");
    }
    if (!validateSeq(envelope.seq)) {
      return badRequest("Invalid push seq.");
    }
    if (!validateSeq(envelope.timestampMs)) {
      return badRequest("Invalid push timestamp.");
    }
    if (typeof envelope.payloadVersion !== "number" || envelope.payloadVersion <= 0) {
      return badRequest("Invalid payloadVersion.");
    }

    if (!Array.isArray(envelope.recipientCiphertexts) || envelope.recipientCiphertexts.length === 0) {
      return badRequest("Push must include at least one recipient ciphertext.");
    }
    if (envelope.recipientCiphertexts.length > 200) {
      return badRequest("Too many recipient ciphertext entries.");
    }

    const senderIdFromKey = await senderIdFromSignPublicKey(body.senderSignPublicKeySpkiB64Url);
    if (!timingSafeEqual(envelope.senderId, senderIdFromKey)) {
      return unauthorized("Push senderId does not match signing key.");
    }

    const signatureValid = await verifyEd25519Signature(
      body.senderSignPublicKeySpkiB64Url,
      canonicalPushMessage(envelope),
      body.push.signatureB64Url,
    );
    if (!signatureValid) {
      return unauthorized("Push signature verification failed.");
    }

    const doc = await this.readDoc(envelope.senderId);
    if (
      doc.senderSignPublicKeySpkiB64Url &&
      !timingSafeEqual(doc.senderSignPublicKeySpkiB64Url, body.senderSignPublicKeySpkiB64Url)
    ) {
      return unauthorized("Sender signing key mismatch.");
    }

    const previousSeq = doc.latestEnvelope?.envelope.seq ?? 0;
    if (envelope.seq <= previousSeq) {
      return jsonResponse({ ok: false, message: "Push seq must increase monotonically." }, { status: 409 });
    }

    const normalizedCiphertexts = normalizeRecipientCiphertexts(envelope.recipientCiphertexts);
    const seenIds = new Set<string>();
    for (const item of normalizedCiphertexts) {
      if (!validateSenderId(item.recipientId)) {
        return badRequest("Invalid recipientId in push ciphertext.");
      }
      if (seenIds.has(item.recipientId)) {
        return badRequest("Duplicate recipientId in push ciphertext list.");
      }
      seenIds.add(item.recipientId);
      if (!doc.receiverIds.includes(item.recipientId)) {
        return unauthorized(`Recipient '${item.recipientId}' is not ACL-authorized.`);
      }
      if (typeof item.ciphertextB64Url !== "string" || item.ciphertextB64Url.length < 8) {
        return badRequest("Invalid ciphertext entry.");
      }
    }

    const storedAtMs = nowMs();
    const nextDoc: SenderStateDocument = {
      ...doc,
      senderSignPublicKeySpkiB64Url: body.senderSignPublicKeySpkiB64Url,
      senderEncPublicKeysetJson: body.senderEncPublicKeysetJson,
      latestEnvelope: {
        ...body.push,
        envelope: {
          ...envelope,
          recipientCiphertexts: normalizedCiphertexts,
        },
      },
      latestStoredAtMs: storedAtMs,
    };
    await this.writeDoc(nextDoc);

    return jsonResponse({
      ok: true,
      appliedSeq: envelope.seq,
      storedAtMs,
    });
  }

  private async handleSelfStatus(request: Request): Promise<Response> {
    const body = await parseJson<SelfStatusRequest>(request);
    if (!validateSenderId(body.senderId)) {
      return badRequest("Invalid senderId.");
    }
    if (!ensureRequestFresh(body.timestampMs, MAX_REQUEST_SKEW_MS)) {
      return badRequest("Request timestamp out of allowed window.");
    }

    const senderIdFromKey = await senderIdFromSignPublicKey(body.senderSignPublicKeySpkiB64Url);
    if (!timingSafeEqual(body.senderId, senderIdFromKey)) {
      return unauthorized("Self-status senderId does not match signing key.");
    }

    const signatureValid = await verifyEd25519Signature(
      body.senderSignPublicKeySpkiB64Url,
      canonicalSelfStatusMessage(body.senderId, body.timestampMs, body.nonceB64Url),
      body.signatureB64Url,
    );
    if (!signatureValid) {
      return unauthorized("Self-status signature verification failed.");
    }

    const doc = await this.readDoc(body.senderId);
    if (
      doc.senderSignPublicKeySpkiB64Url &&
      !timingSafeEqual(doc.senderSignPublicKeySpkiB64Url, body.senderSignPublicKeySpkiB64Url)
    ) {
      return unauthorized("Sender signing key mismatch.");
    }
    if (!this.trackNonce(doc, body.senderId, body.nonceB64Url, body.timestampMs)) {
      return unauthorized("Self-status nonce rejected.");
    }

    await this.writeDoc(doc);

    return jsonResponse({
      ok: true,
      latestSeq: doc.latestEnvelope?.envelope.seq,
      latestTimestampMs: doc.latestEnvelope?.envelope.timestampMs,
      storedAtMs: doc.latestStoredAtMs,
    });
  }

  private async handleClear(request: Request): Promise<Response> {
    const body = await parseJson<ClearLocationRequest>(request);
    if (!validateSenderId(body.senderId)) {
      return badRequest("Invalid senderId.");
    }
    if (!ensureRequestFresh(body.timestampMs, MAX_REQUEST_SKEW_MS)) {
      return badRequest("Request timestamp out of allowed window.");
    }

    const senderIdFromKey = await senderIdFromSignPublicKey(body.senderSignPublicKeySpkiB64Url);
    if (!timingSafeEqual(body.senderId, senderIdFromKey)) {
      return unauthorized("Clear senderId does not match signing key.");
    }

    const signatureValid = await verifyEd25519Signature(
      body.senderSignPublicKeySpkiB64Url,
      canonicalClearMessage(body.senderId, body.timestampMs, body.nonceB64Url),
      body.signatureB64Url,
    );
    if (!signatureValid) {
      return unauthorized("Clear signature verification failed.");
    }

    const doc = await this.readDoc(body.senderId);
    if (
      doc.senderSignPublicKeySpkiB64Url &&
      !timingSafeEqual(doc.senderSignPublicKeySpkiB64Url, body.senderSignPublicKeySpkiB64Url)
    ) {
      return unauthorized("Sender signing key mismatch.");
    }
    if (!this.trackNonce(doc, body.senderId, body.nonceB64Url, body.timestampMs)) {
      return unauthorized("Clear nonce rejected.");
    }

    doc.latestEnvelope = undefined;
    doc.latestStoredAtMs = undefined;
    await this.writeDoc(doc);

    return jsonResponse({ ok: true, cleared: true });
  }

  private async handlePullOne(request: Request): Promise<Response> {
    const body = (await request.json()) as PullBatchRequest & { senderId: string };
    if (!validateSenderId(body.senderId)) {
      return badRequest("Invalid senderId.");
    }
    if (!validateSenderId(body.receiverId)) {
      return badRequest("Invalid receiverId.");
    }
    if (!ensureRequestFresh(body.timestampMs, MAX_REQUEST_SKEW_MS)) {
      return jsonResponse({ senderId: body.senderId, status: "error", message: "Stale pull timestamp." });
    }

    const receiverIdFromKey = await senderIdFromSignPublicKey(body.receiverSignPublicKeySpkiB64Url);
    if (!timingSafeEqual(body.receiverId, receiverIdFromKey)) {
      return jsonResponse({ senderId: body.senderId, status: "unauthorized", message: "Receiver key mismatch." });
    }

    const pullSignatureValid = await verifyEd25519Signature(
      body.receiverSignPublicKeySpkiB64Url,
      canonicalPullMessage(body.receiverId, body.senderIds, body.timestampMs, body.nonceB64Url),
      body.signatureB64Url,
    );
    if (!pullSignatureValid) {
      return jsonResponse({ senderId: body.senderId, status: "unauthorized", message: "Invalid pull signature." });
    }

    const doc = await this.readDoc(body.senderId);
    if (!this.trackNonce(doc, body.receiverId, body.nonceB64Url, body.timestampMs)) {
      return jsonResponse({ senderId: body.senderId, status: "unauthorized", message: "Pull nonce rejected." });
    }

    if (!doc.receiverIds.includes(body.receiverId)) {
      await this.writeDoc(doc);
      return jsonResponse({ senderId: body.senderId, status: "unauthorized", message: "Receiver not authorized." });
    }

    if (!doc.latestEnvelope) {
      await this.writeDoc(doc);
      return jsonResponse({ senderId: body.senderId, status: "no_data", message: "No location stored." });
    }

    const filteredCiphertext = doc.latestEnvelope.envelope.recipientCiphertexts.find(
      (item) => item.recipientId === body.receiverId,
    );
    if (!filteredCiphertext) {
      await this.writeDoc(doc);
      return jsonResponse({ senderId: body.senderId, status: "no_data", message: "No ciphertext for receiver." });
    }

    await this.writeDoc(doc);
    return jsonResponse({
      senderId: body.senderId,
      status: "ok",
      storedAtMs: doc.latestStoredAtMs,
      envelope: {
        ...doc.latestEnvelope,
        envelope: {
          ...doc.latestEnvelope.envelope,
          recipientCiphertexts: [filteredCiphertext],
        },
      },
    });
  }

  private async readDoc(senderId: string): Promise<SenderStateDocument> {
    const existing = await this.state.storage.get<SenderStateDocument>(DOC_KEY);
    if (existing) {
      return existing;
    }
    const created: SenderStateDocument = {
      senderId,
      senderSignPublicKeySpkiB64Url: "",
      aclSeq: 0,
      receiverIds: [],
      noncesByActor: {},
    };
    await this.writeDoc(created);
    return created;
  }

  private async writeDoc(doc: SenderStateDocument): Promise<void> {
    await this.state.storage.put(DOC_KEY, doc);
  }

  private trackNonce(doc: SenderStateDocument, actorId: string, nonce: string, timestampMs: number): boolean {
    if (!nonce || nonce.length > 512) {
      return false;
    }

    const cutoff = nowMs() - NONCE_TTL_MS;
    for (const [key, storedAt] of Object.entries(doc.noncesByActor)) {
      if (storedAt < cutoff) {
        delete doc.noncesByActor[key];
      }
    }

    const nonceKey = `${actorId}|${nonce}`;
    if (doc.noncesByActor[nonceKey] !== undefined) {
      return false;
    }
    doc.noncesByActor[nonceKey] = timestampMs;
    return true;
  }
}

async function senderIdFromSignPublicKey(signPublicKeySpkiB64Url: string): Promise<string> {
  const keyBytes = decodeBase64Url(signPublicKeySpkiB64Url);
  const digest = await crypto.subtle.digest("SHA-256", asArrayBuffer(keyBytes));
  return encodeBase64Url(new Uint8Array(digest));
}

async function verifyEd25519Signature(
  signPublicKeySpkiB64Url: string,
  message: Uint8Array,
  signatureB64Url: string,
): Promise<boolean> {
  return runBoolean(async () => {
    const key = await crypto.subtle.importKey(
      "spki",
      asArrayBuffer(decodeBase64Url(signPublicKeySpkiB64Url)),
      { name: "Ed25519" },
      false,
      ["verify"],
    );
    return await crypto.subtle.verify(
      { name: "Ed25519" },
      key,
      asArrayBuffer(decodeBase64Url(signatureB64Url)),
      asArrayBuffer(message),
    );
  });
}

function asArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const cloned = new Uint8Array(bytes.byteLength);
  cloned.set(bytes);
  return cloned.buffer;
}

function timingSafeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) {
    diff |= left.charCodeAt(i) ^ right.charCodeAt(i);
  }
  return diff === 0;
}

async function runBoolean(block: () => Promise<boolean>): Promise<boolean> {
  try {
    return await block();
  } catch {
    return false;
  }
}
