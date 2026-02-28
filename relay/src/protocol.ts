export interface RecipientCiphertext {
  recipientId: string;
  ciphertextB64Url: string;
}

export interface PushEnvelopeUnsigned {
  senderId: string;
  seq: number;
  timestampMs: number;
  payloadVersion: number;
  recipientCiphertexts: RecipientCiphertext[];
}

export interface PushEnvelopeSigned {
  envelope: PushEnvelopeUnsigned;
  signatureB64Url: string;
}

export interface AclUnsigned {
  senderId: string;
  aclSeq: number;
  receiverIds: string[];
  timestampMs: number;
}

export interface UpsertAclRequest {
  acl: AclUnsigned;
  signatureB64Url: string;
  senderSignPublicKeySpkiB64Url: string;
}

export interface PushLocationRequest {
  push: PushEnvelopeSigned;
  senderSignPublicKeySpkiB64Url: string;
  senderEncPublicKeysetJson: string;
}

export interface PullBatchRequest {
  receiverId: string;
  senderIds: string[];
  timestampMs: number;
  nonceB64Url: string;
  signatureB64Url: string;
  receiverSignPublicKeySpkiB64Url: string;
}

export interface PullHistoryRequest {
  senderId: string;
  receiverId: string;
  timestampMs: number;
  nonceB64Url: string;
  signatureB64Url: string;
  receiverSignPublicKeySpkiB64Url: string;
}

export interface PullRecord {
  senderId: string;
  storedAtMs?: number;
  envelope?: PushEnvelopeSigned;
  status: "ok" | "unauthorized" | "no_data" | "error";
  message?: string;
}

export interface PullHistoryEnvelopeRecord {
  storedAtMs: number;
  envelope: PushEnvelopeSigned;
}

export interface PullHistoryResponse {
  ok: boolean;
  senderId: string;
  status: "ok" | "unauthorized" | "no_data" | "error";
  records: PullHistoryEnvelopeRecord[];
  message?: string;
}

export interface RelayStatusRequest {
  clientTimestampMs?: number;
}

export interface RelayStatusResponse {
  ok: boolean;
  status: "ok";
  serverTimestampMs: number;
  apiVersion: string;
  message?: string;
}

export interface SelfStatusRequest {
  senderId: string;
  timestampMs: number;
  nonceB64Url: string;
  signatureB64Url: string;
  senderSignPublicKeySpkiB64Url: string;
}

export interface ClearLocationRequest {
  senderId: string;
  timestampMs: number;
  nonceB64Url: string;
  signatureB64Url: string;
  senderSignPublicKeySpkiB64Url: string;
}

export interface SenderStateDocument {
  senderId: string;
  senderSignPublicKeySpkiB64Url: string;
  senderEncPublicKeysetJson?: string;
  aclSeq: number;
  receiverIds: string[];
  latestEnvelope?: PushEnvelopeSigned;
  latestStoredAtMs?: number;
  historyEnvelopes: PullHistoryEnvelopeRecord[];
  noncesByActor: Record<string, number>;
}

export async function parseJson<T>(request: Request): Promise<T> {
  return (await request.json()) as T;
}

export function jsonResponse(payload: unknown, init?: ResponseInit): Response {
  return new Response(JSON.stringify(payload), {
    ...init,
    headers: {
      "content-type": "application/json; charset=utf-8",
      ...(init?.headers ?? {}),
    },
  });
}

export function badRequest(message: string): Response {
  return jsonResponse({ ok: false, message }, { status: 400 });
}

export function unauthorized(message: string): Response {
  return jsonResponse({ ok: false, message }, { status: 403 });
}

export function nowMs(): number {
  return Date.now();
}

export function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

export function validateSenderId(senderId: unknown): senderId is string {
  return isNonEmptyString(senderId) && senderId.length <= 128;
}

export function validateReceiverIds(ids: unknown): ids is string[] {
  if (!Array.isArray(ids)) return false;
  if (ids.length > 200) return false;
  return ids.every((id) => validateSenderId(id));
}

export function validateSeq(seq: unknown): seq is number {
  return typeof seq === "number" && Number.isFinite(seq) && seq > 0 && Number.isSafeInteger(seq);
}

export function ensureRequestFresh(timestampMs: number, maxSkewMs: number): boolean {
  const delta = Math.abs(nowMs() - timestampMs);
  return delta <= maxSkewMs;
}

export function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const paddingLength = (4 - (normalized.length % 4 || 4)) % 4;
  const padded = normalized + "=".repeat(paddingLength);
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) {
    bytes[i] = raw.charCodeAt(i);
  }
  return bytes;
}

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function canonicalAclMessage(acl: AclUnsigned): Uint8Array {
  const receivers = normalizeReceiverIds(acl.receiverIds).join(",");
  return textEncode(`acl-v1|${acl.senderId}|${acl.aclSeq}|${acl.timestampMs}|${receivers}`);
}

export function canonicalPushMessage(push: PushEnvelopeUnsigned): Uint8Array {
  const recipients = normalizeRecipientCiphertexts(push.recipientCiphertexts)
    .map((item) => `${item.recipientId}:${item.ciphertextB64Url}`)
    .join(";");
  return textEncode(
    `push-v1|${push.senderId}|${push.seq}|${push.timestampMs}|${push.payloadVersion}|${recipients}`,
  );
}

export function canonicalPullMessage(
  receiverId: string,
  senderIds: string[],
  timestampMs: number,
  nonceB64Url: string,
): Uint8Array {
  const senders = normalizeReceiverIds(senderIds).join(",");
  return textEncode(`pull-v1|${receiverId}|${timestampMs}|${nonceB64Url}|${senders}`);
}

export function canonicalSelfStatusMessage(senderId: string, timestampMs: number, nonceB64Url: string): Uint8Array {
  return textEncode(`self-status-v1|${senderId}|${timestampMs}|${nonceB64Url}`);
}

export function canonicalClearMessage(senderId: string, timestampMs: number, nonceB64Url: string): Uint8Array {
  return textEncode(`clear-v1|${senderId}|${timestampMs}|${nonceB64Url}`);
}

export function normalizeReceiverIds(receiverIds: string[]): string[] {
  return Array.from(new Set(receiverIds)).sort((a, b) => a.localeCompare(b));
}

export function normalizeRecipientCiphertexts(items: RecipientCiphertext[]): RecipientCiphertext[] {
  return [...items].sort((a, b) => a.recipientId.localeCompare(b.recipientId));
}

function textEncode(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}
