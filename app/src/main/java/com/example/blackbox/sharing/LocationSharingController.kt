package com.example.blackbox.sharing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.Uri
import android.util.Log
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineMode
import com.example.blackbox.location.LocationSampleEvent
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LocationSharingController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(LocationSharingState())
    val state: StateFlow<LocationSharingState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    private var appContext: Context? = null
    private var settingsStore: SharingSettingsStore? = null
    private var stateStore: SharingStateStore? = null
    private var crypto: SharingCrypto? = null
    private var relayApi: RelayApi? = null

    private var settingsJob: Job? = null
    private var locationEventsJob: Job? = null
    private var pollingJob: Job? = null
    private var relayStatusJob: Job? = null
    private var relayStatusStopJob: Job? = null
    private var retryJob: Job? = null
    private val loopJobLock = Any()
    private val relayStatusStopGraceMs = 1_200L

    private var currentSettings: SharingSettings = SharingSettings()
    private var snapshot: SharingStateSnapshot = SharingStateSnapshot()
    private var pollingVisible: Boolean = false
    private var mainViewVisible: Boolean = false
    private var lastError: String? = null
    private var lastInfo: String = "Sharing subsystem idle."
    private var lastEligibilityDebugReason: String? = null
    private var lastEligibilityDebugAtMs: Long = 0L

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val app = context.applicationContext
            val localSettings = SharingSettingsStore(app)
            val localStore = SharingStateStore(app)
            val localCrypto = SharingCrypto()

            appContext = app
            settingsStore = localSettings
            stateStore = localStore
            crypto = localCrypto

            currentSettings = localSettings.settings.value
            Log.d(
                SHARING_DEBUG_TAG,
                "Initializing sharing controller relayBaseUrl=${currentSettings.relayBaseUrl}"
            )
            val loadedSnapshot = localStore.read()
            var normalizedSnapshot = loadedSnapshot

            val contactsWithSigningKeys = normalizedSnapshot.contacts.filter {
                it.signPublicKeySpkiB64Url.isNotBlank()
            }
            if (contactsWithSigningKeys.size != normalizedSnapshot.contacts.size) {
                val allowedIds = contactsWithSigningKeys.map { it.senderId }.toSet()
                normalizedSnapshot = normalizedSnapshot.copy(
                    contacts = contactsWithSigningKeys,
                    receivedLocations = normalizedSnapshot.receivedLocations.filter { it.senderId in allowedIds },
                    senderPollStatuses = normalizedSnapshot.senderPollStatuses.filter { it.senderId in allowedIds }
                )
                lastInfo = "Legacy contacts without Ed25519 keys were removed. Re-import required."
            }

            val existingIdentity = normalizedSnapshot.identity
            if (existingIdentity == null || !localCrypto.isIdentityValid(existingIdentity)) {
                val generated = localCrypto.generateIdentity(
                    nowMs = System.currentTimeMillis(),
                    requestedSenderName = currentSettings.username
                )
                normalizedSnapshot = normalizedSnapshot.copy(
                    identity = generated,
                    pendingPush = null,
                    sync = SharingSyncState()
                )
                lastInfo = "Sharing identity generated."
            }

            snapshot = localStore.replace(normalizedSnapshot)

            relayApi = OkHttpRelayApi(
                baseUrlProvider = {
                    settingsStore?.settings?.value?.relayBaseUrl ?: DEFAULT_RELAY_BASE_URL
                }
            )

            settingsJob = scope.launch {
                localSettings.settings.collectLatest { settings ->
                    val previousBaseUrl = currentSettings.relayBaseUrl
                    currentSettings = settings
                    if (previousBaseUrl != settings.relayBaseUrl) {
                        Log.d(
                            SHARING_DEBUG_TAG,
                            "Relay base URL updated to ${settings.relayBaseUrl}"
                        )
                    }
                    if (!settings.sharingEnabled && snapshot.pendingPush != null) {
                        snapshot = localStore.update { current ->
                            current.copy(pendingPush = null)
                        }
                        lastInfo = "Sharing disabled; pending push dropped."
                    }
                    publishState()
                }
            }

            locationEventsJob = scope.launch {
                LocationEngine.locationEvents.collectLatest { event ->
                    handleLocationEvent(event)
                }
            }

            startRetryLoopIfNeeded()
            initialized = true
            publishState()
        }
    }

    fun onSharingPageVisible(visible: Boolean) {
        scope.launch {
            var changed = false
            stateMutex.withLock {
                if (pollingVisible == visible) return@withLock
                pollingVisible = visible
                updateSyncState { it.copy(pollingActive = visible) }
                changed = true
            }
            if (!changed) return@launch
            Log.d(SHARING_DEBUG_TAG, "Sharing page visibility changed visible=$visible")
            publishState()
            if (visible) {
                startPollingLoop()
            } else {
                stopPollingLoop()
            }
            updateRelayStatusLoopState()
        }
    }

    fun onMainViewVisible(visible: Boolean) {
        scope.launch {
            var changed = false
            stateMutex.withLock {
                if (mainViewVisible == visible) return@withLock
                mainViewVisible = visible
                changed = true
            }
            if (!changed) return@launch
            updateRelayStatusLoopState()
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        if (enabled && !isValidUsername(normalizeUsername(currentSettings.username))) {
            Log.w(SHARING_DEBUG_TAG, "Rejected enabling sharing: username missing or invalid.")
            setError("Set a valid username before enabling Share My Location.")
            settingsStore?.setSharingEnabled(false)
            return
        }
        Log.d(SHARING_DEBUG_TAG, "setSharingEnabled enabled=$enabled")
        settingsStore?.setSharingEnabled(enabled)
    }

    fun setUsername(username: String) {
        settingsStore?.setUsername(username)
    }

    fun setRelayBaseUrl(baseUrl: String) {
        Log.d(SHARING_DEBUG_TAG, "setRelayBaseUrl requested=$baseUrl")
        settingsStore?.setRelayBaseUrl(baseUrl)
    }

    fun setIntervals(normalMs: Long, fastMs: Long) {
        settingsStore?.setIntervals(normalMs = normalMs, fastMs = fastMs)
    }

    fun addOrUpdateZone(
        zoneId: String?,
        name: String,
        centerLat: Double,
        centerLon: Double,
        radiusM: Int
    ) {
        scope.launch {
            val normalizedName = normalizeZoneName(name)
            if (!isValidZoneName(normalizedName)) {
                setError("Zone name must be 1-40 characters.")
                return@launch
            }
            val boundedRadius = clampZoneRadius(radiusM)
            mutateSnapshot { current ->
                val existing = current.zones.associateBy { it.id }.toMutableMap()
                val resolvedId = zoneId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

                if (!existing.containsKey(resolvedId) && !isValidZoneCount(existing.size + 1)) {
                    throw IllegalStateException("Zone limit reached ($ZONE_LIMIT).")
                }

                val now = System.currentTimeMillis()
                val createdAt = existing[resolvedId]?.createdAtMs ?: now
                existing[resolvedId] = ShareZone(
                    id = resolvedId,
                    name = normalizedName,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    radiusM = boundedRadius,
                    createdAtMs = createdAt
                )

                current.copy(zones = existing.values.sortedBy { it.createdAtMs })
            }
            setInfo("Zone saved.")
        }
    }

    fun removeZone(zoneId: String) {
        scope.launch {
            mutateSnapshot { current ->
                current.copy(zones = current.zones.filterNot { it.id == zoneId })
            }
            setInfo("Zone removed.")
        }
    }

    suspend fun importContactCard(code: String): Result<ContactCardImportResult> {
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        return runCatching {
            val imported = localCrypto.importContactCode(code)
            mutateSnapshot { current ->
                val identitySenderId = current.identity?.senderId
                if (identitySenderId != null && imported.senderId == identitySenderId) {
                    throw IllegalArgumentException("Cannot import your own identity.")
                }

                val byId = current.contacts.associateBy { it.senderId }.toMutableMap()
                if (!byId.containsKey(imported.senderId) && !isValidContactCount(byId.size + 1)) {
                    throw IllegalStateException("Contact limit reached ($CONTACT_LIMIT).")
                }
                val now = System.currentTimeMillis()
                val existing = byId[imported.senderId]
                byId[imported.senderId] = PeerContactState(
                    senderId = imported.senderId,
                    onboardingName = imported.onboardingName,
                    localAlias = existing?.localAlias,
                    signPublicKeySpkiB64Url = imported.signPublicKeySpkiB64Url,
                    encPublicKeysetJson = imported.encPublicKeysetJson,
                    canReceiveFromMe = existing?.canReceiveFromMe ?: false,
                    iFollow = existing?.iFollow ?: true,
                    lastSeenSeq = existing?.lastSeenSeq,
                    safetyFingerprint = imported.safetyFingerprint,
                    addedAtMs = existing?.addedAtMs ?: now,
                    updatedAtMs = now
                )
                current.copy(contacts = byId.values.sortedBy { it.addedAtMs })
            }

            setInfo("Contact imported.")
            ContactCardImportResult(
                senderId = imported.senderId,
                displayName = imported.onboardingName ?: imported.safetyFingerprint,
                safetyFingerprint = imported.safetyFingerprint
            )
        }.onFailure {
            setError(it.message ?: "Contact import failed")
        }
    }

    fun exportMyContactCard(): String? {
        val identity = snapshot.identity ?: return null
        return runCatching {
            val localCrypto = crypto ?: return@runCatching null
            localCrypto.exportContactCode(identity, currentSettings.username.takeIf { it.isNotBlank() })
        }.getOrElse {
            setError(it.message ?: "Failed to export contact code")
            null
        }
    }

    fun setOutboundAuthorization(senderId: String, authorized: Boolean) {
        scope.launch {
            mutateSnapshot { current ->
                current.copy(
                    contacts = current.contacts.map { contact ->
                        if (contact.senderId == senderId) {
                            contact.copy(
                                canReceiveFromMe = authorized,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        } else {
                            contact
                        }
                    }
                )
            }
            setInfo("Authorization updated.")
        }
    }

    fun setFollowing(senderId: String, following: Boolean) {
        scope.launch {
            mutateSnapshot { current ->
                current.copy(
                    contacts = current.contacts.map { contact ->
                        if (contact.senderId == senderId) {
                            contact.copy(
                                iFollow = following,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        } else {
                            contact
                        }
                    }
                )
            }
            setInfo("Follow setting updated.")
        }
    }

    fun setLocalAlias(senderId: String, alias: String?) {
        scope.launch {
            val normalized = alias?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
            mutateSnapshot { current ->
                current.copy(
                    contacts = current.contacts.map { contact ->
                        if (contact.senderId == senderId) {
                            contact.copy(localAlias = normalized, updatedAtMs = System.currentTimeMillis())
                        } else {
                            contact
                        }
                    }
                )
            }
            setInfo("Alias updated.")
        }
    }

    fun removeContact(senderId: String) {
        scope.launch {
            mutateSnapshot { current ->
                current.copy(
                    contacts = current.contacts.filterNot { it.senderId == senderId },
                    receivedLocations = current.receivedLocations.filterNot { it.senderId == senderId },
                    senderPollStatuses = current.senderPollStatuses.filterNot { it.senderId == senderId }
                )
            }
            setInfo("Contact removed.")
        }
    }

    fun manualPollNow() {
        Log.d(SHARING_DEBUG_TAG, "Manual poll requested")
        scope.launch {
            pollNow(trigger = "manual")
        }
    }

    fun clearRelayLocationNow() {
        scope.launch {
            val localRelay = relayApi ?: return@launch
            val identity = snapshot.identity ?: return@launch
            val networkError = networkPreflightError()
            if (networkError != null) {
                Log.w(SHARING_DEBUG_TAG, "Clear relay blocked sender=${shortSharingId(identity.senderId)} reason=$networkError")
                setError(networkError)
                return@launch
            }
            Log.d(SHARING_DEBUG_TAG, "Clear relay requested sender=${shortSharingId(identity.senderId)}")
            val now = System.currentTimeMillis()
            val nonce = randomNonceB64Url()
            val payload = canonicalClearMessage(identity.senderId, now, nonce)
            val signature = crypto?.sign(identity, payload)?.base64UrlEncode().orEmpty()
            val request = ClearLocationRequest(
                senderId = identity.senderId,
                timestampMs = now,
                nonceB64Url = nonce,
                signatureB64Url = signature,
                senderSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url
            )
            localRelay.clearLocation(request)
                .onSuccess {
                    Log.d(
                        SHARING_DEBUG_TAG,
                        "Clear relay success sender=${shortSharingId(identity.senderId)} cleared=${it.cleared}"
                    )
                    setInfo(if (it.cleared) "Relay location cleared." else "Relay clear request accepted.")
                }
                .onFailure {
                    Log.e(
                        SHARING_DEBUG_TAG,
                        "Clear relay failure sender=${shortSharingId(identity.senderId)} error=${it.message}",
                        it
                    )
                    setError(it.message ?: "Relay clear failed")
                }
        }
    }

    suspend fun exportIdentityBundle(passphrase: CharArray, target: Uri): Result<Uri> {
        val context = appContext ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        val identity = snapshot.identity ?: return Result.failure(IllegalStateException("No identity available."))

        return runCatching {
            val bundle = localCrypto.encryptIdentityBundle(identity, passphrase)
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                output.write(bundle.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: error("Failed to open export destination.")
            setInfo("Identity bundle exported.")
            target
        }.onFailure {
            setError(it.message ?: "Identity export failed")
        }
    }

    suspend fun importIdentityBundle(passphrase: CharArray, source: Uri): Result<Unit> {
        val context = appContext ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Sharing not initialized."))

        return runCatching {
            val raw = context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
                ?: error("Failed to open bundle.")
            val imported = localCrypto.decryptIdentityBundle(raw, passphrase)
            mutateSnapshot { current ->
                current.copy(
                    identity = imported,
                    contacts = current.contacts.map { it.copy(canReceiveFromMe = false) },
                    pendingPush = null,
                    sync = SharingSyncState()
                )
            }
            setInfo("Identity bundle imported.")
        }.onFailure {
            setError(it.message ?: "Identity import failed")
        }
    }

    private fun startPollingLoop() {
        synchronized(loopJobLock) {
            if (pollingJob != null) return
            Log.d(SHARING_DEBUG_TAG, "Starting poll loop intervalMs=$POLL_INTERVAL_MS")
            lateinit var createdJob: Job
            createdJob = scope.launch {
                try {
                    pollNow(trigger = "page_open")
                    while (true) {
                        delay(POLL_INTERVAL_MS)
                        if (!pollingVisible) {
                            Log.d(SHARING_DEBUG_TAG, "Poll loop paused: sharing page not visible.")
                            break
                        }
                        pollNow(trigger = "interval")
                    }
                } finally {
                    synchronized(loopJobLock) {
                        if (pollingJob === createdJob) {
                            pollingJob = null
                        }
                    }
                }
            }
            pollingJob = createdJob
        }
    }

    private fun stopPollingLoop() {
        synchronized(loopJobLock) {
            pollingJob?.cancel()
            pollingJob = null
        }
        Log.d(SHARING_DEBUG_TAG, "Stopped poll loop")
    }

    private fun startRelayStatusLoop() {
        synchronized(loopJobLock) {
            relayStatusStopJob?.cancel()
            relayStatusStopJob = null
            if (relayStatusJob != null) return
            Log.d(SHARING_DEBUG_TAG, "Starting relay status loop intervalMs=$RELAY_STATUS_INTERVAL_MS")
            lateinit var createdJob: Job
            createdJob = scope.launch {
                try {
                    refreshRelayStatus(trigger = "page_open")
                    while (true) {
                        delay(RELAY_STATUS_INTERVAL_MS)
                        if (!shouldRunRelayStatusLoop()) {
                            Log.d(SHARING_DEBUG_TAG, "Relay status loop paused: no visible relay status surface.")
                            break
                        }
                        refreshRelayStatus(trigger = "interval")
                    }
                } finally {
                    synchronized(loopJobLock) {
                        if (relayStatusJob === createdJob) {
                            relayStatusJob = null
                        }
                    }
                }
            }
            relayStatusJob = createdJob
        }
    }

    private fun stopRelayStatusLoopNow() {
        synchronized(loopJobLock) {
            relayStatusStopJob?.cancel()
            relayStatusStopJob = null
            relayStatusJob?.cancel()
            relayStatusJob = null
        }
        mutateSyncOnly { it.copy(relayStatusChecking = false) }
        Log.d(SHARING_DEBUG_TAG, "Stopped relay status loop")
    }

    private fun shouldRunRelayStatusLoop(): Boolean {
        return pollingVisible || mainViewVisible
    }

    private fun updateRelayStatusLoopState() {
        if (shouldRunRelayStatusLoop()) {
            startRelayStatusLoop()
        } else {
            scheduleRelayStatusLoopStop()
        }
    }

    private fun scheduleRelayStatusLoopStop() {
        synchronized(loopJobLock) {
            if (relayStatusJob == null) {
                relayStatusStopJob?.cancel()
                relayStatusStopJob = null
                return
            }
            if (relayStatusStopJob != null) return
            relayStatusStopJob = scope.launch {
                delay(relayStatusStopGraceMs)
                if (!shouldRunRelayStatusLoop()) {
                    stopRelayStatusLoopNow()
                }
            }
        }
    }

    private suspend fun refreshRelayStatus(trigger: String) {
        val localRelay = relayApi ?: return
        val now = System.currentTimeMillis()
        mutateSyncOnly {
            it.copy(
                lastRelayStatusCheckAtMs = now,
                relayStatusChecking = true
            )
        }

        val request = RelayStatusRequest(clientTimestampMs = now)
        localRelay.relayStatus(request)
            .onSuccess { response ->
                val successAt = System.currentTimeMillis()
                Log.d(
                    SHARING_DEBUG_TAG,
                    "Relay status success trigger=$trigger status=${response.status} serverTs=${response.serverTimestampMs}"
                )
                mutateSyncOnly {
                    it.copy(
                        relayStatusChecking = false,
                        relayReachable = response.ok,
                        lastRelayStatusOkAtMs = if (response.ok) successAt else it.lastRelayStatusOkAtMs,
                        lastRelayStatusError = if (response.ok) null else (response.message ?: "Relay status unavailable."),
                        lastRelayStatusErrorAtMs = if (response.ok) null else successAt,
                        relayCheckFailureStreak = if (response.ok) 0 else (it.relayCheckFailureStreak + 1)
                    )
                }
            }
            .onFailure { throwable ->
                val failedAt = System.currentTimeMillis()
                val error = throwable.message ?: "Relay status failed."
                Log.w(
                    SHARING_DEBUG_TAG,
                    "Relay status failed trigger=$trigger error=$error",
                    throwable
                )
                mutateSyncOnly {
                    it.copy(
                        relayStatusChecking = false,
                        relayReachable = false,
                        lastRelayStatusError = error,
                        lastRelayStatusErrorAtMs = failedAt,
                        relayCheckFailureStreak = it.relayCheckFailureStreak + 1
                    )
                }
            }
    }

    private suspend fun pollNow(trigger: String) {
        val localRelay = relayApi ?: return
        val localCrypto = crypto ?: return
        val localSnapshot = snapshot
        val identity = localSnapshot.identity ?: return
        val networkError = networkPreflightError()
        if (networkError != null) {
            Log.w(
                SHARING_DEBUG_TAG,
                "Poll blocked trigger=$trigger sender=${shortSharingId(identity.senderId)} reason=$networkError"
            )
            mutateSnapshot {
                it.copy(
                    sync = it.sync.copy(
                        lastPollAttemptAtMs = System.currentTimeMillis(),
                        lastPollError = networkError,
                        lastPollErrorAtMs = System.currentTimeMillis(),
                        pollFailureStreak = it.sync.pollFailureStreak + 1
                    )
                )
            }
            setError(networkError)
            return
        }
        Log.d(
            SHARING_DEBUG_TAG,
            "Poll start trigger=$trigger sender=${shortSharingId(identity.senderId)} following=${localSnapshot.contacts.count { it.iFollow }}"
        )

        val now = System.currentTimeMillis()
        val selfNonce = randomNonceB64Url()
        val selfPayload = canonicalSelfStatusMessage(identity.senderId, now, selfNonce)
        val selfSignature = localCrypto.sign(identity, selfPayload).base64UrlEncode()
        val selfRequest = SelfStatusRequest(
            senderId = identity.senderId,
            timestampMs = now,
            nonceB64Url = selfNonce,
            signatureB64Url = selfSignature,
            senderSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url
        )

        mutateSnapshot {
            it.copy(sync = it.sync.copy(lastPollAttemptAtMs = now))
        }

        val selfStatusResult = localRelay.selfStatus(selfRequest)
        if (selfStatusResult.isFailure) {
            val error = selfStatusResult.exceptionOrNull()?.message ?: "Self-status failed"
            Log.e(
                SHARING_DEBUG_TAG,
                "Poll self-status failed trigger=$trigger sender=${shortSharingId(identity.senderId)} error=$error",
                selfStatusResult.exceptionOrNull()
            )
            mutateSnapshot {
                it.copy(
                    sync = it.sync.copy(
                        lastPollError = error,
                        lastPollErrorAtMs = System.currentTimeMillis(),
                        pollFailureStreak = it.sync.pollFailureStreak + 1
                    )
                )
            }
            setError(error)
            return
        }

        val followingContacts = snapshot.contacts.filter { it.iFollow }
        if (followingContacts.isEmpty()) {
            Log.d(
                SHARING_DEBUG_TAG,
                "Poll completed trigger=$trigger sender=${shortSharingId(identity.senderId)} noFollowedSenders=true"
            )
            mutateSnapshot {
                it.copy(
                    sync = it.sync.copy(
                        lastPollSuccessAtMs = System.currentTimeMillis(),
                        lastPollError = null,
                        lastPollErrorAtMs = null,
                        pollFailureStreak = 0
                    )
                )
            }
            setInfo("Poll complete ($trigger): no followed senders.")
            return
        }

        val pullNow = System.currentTimeMillis()
        val pullNonce = randomNonceB64Url()
        val senderIds = followingContacts.map { it.senderId }.sorted()
        val pullPayload = canonicalPullMessage(
            receiverId = identity.senderId,
            senderIds = senderIds,
            timestampMs = pullNow,
            nonceB64Url = pullNonce
        )
        val pullSignature = localCrypto.sign(identity, pullPayload).base64UrlEncode()
        val pullRequest = PullBatchRequest(
            receiverId = identity.senderId,
            senderIds = senderIds,
            timestampMs = pullNow,
            nonceB64Url = pullNonce,
            signatureB64Url = pullSignature,
            receiverSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url
        )

        localRelay.pullBatch(pullRequest)
            .onSuccess { response ->
                Log.d(
                    SHARING_DEBUG_TAG,
                    "Poll pull success trigger=$trigger sender=${shortSharingId(identity.senderId)} records=${response.records.size}"
                )
                handlePullSuccess(response, identity)
                setInfo("Poll complete ($trigger).")
            }
            .onFailure { throwable ->
                val error = throwable.message ?: "Pull failed"
                Log.e(
                    SHARING_DEBUG_TAG,
                    "Poll pull failed trigger=$trigger sender=${shortSharingId(identity.senderId)} error=$error",
                    throwable
                )
                mutateSnapshot {
                    it.copy(
                        sync = it.sync.copy(
                            lastPollError = error,
                            lastPollErrorAtMs = System.currentTimeMillis(),
                            pollFailureStreak = it.sync.pollFailureStreak + 1
                        )
                    )
                }
                setError(error)
            }
    }

    private suspend fun handlePullSuccess(response: PullBatchResponse, identity: SharingIdentityState) {
        val localCrypto = crypto ?: return
        val responseStatusSummary = response.records.groupingBy { it.status }.eachCount()
        Log.d(
            SHARING_DEBUG_TAG,
            "Handling pull payload sender=${shortSharingId(identity.senderId)} statusSummary=$responseStatusSummary"
        )
        mutateSnapshot { current ->
            val contactById = current.contacts.associateBy { it.senderId }
            val receivedBySender = current.receivedLocations.associateBy { it.senderId }.toMutableMap()
            val pollStatusBySender = current.senderPollStatuses.associateBy { it.senderId }.toMutableMap()
            val contactUpdates = current.contacts.associateBy { it.senderId }.toMutableMap()
            val now = System.currentTimeMillis()

            response.records.forEach { record ->
                val contact = contactById[record.senderId]
                if (contact == null || !contact.iFollow) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Sender is not in follow list."
                    )
                    return@forEach
                }

                val envelope = record.envelope
                if (record.status != "ok" || envelope == null) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = record.message ?: "No data available"
                    )
                    return@forEach
                }

                if (envelope.envelope.payloadVersion != SharingVersions.PAYLOAD_VERSION) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Unsupported payload version ${envelope.envelope.payloadVersion}."
                    )
                    return@forEach
                }

                val unsignedBytes = canonicalPushMessage(envelope.envelope)
                val signatureValid = localCrypto.verify(
                    signPublicKeySpkiB64Url = contact.signPublicKeySpkiB64Url,
                    message = unsignedBytes,
                    signature = envelope.signatureB64Url.base64UrlDecode()
                )
                if (!signatureValid) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Sender signature verification failed."
                    )
                    return@forEach
                }

                val myCiphertext = envelope.envelope.recipientCiphertexts
                    .firstOrNull { it.recipientId == identity.senderId }
                    ?.ciphertextB64Url
                    ?.base64UrlDecode()
                if (myCiphertext == null) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "No ciphertext for current identity."
                    )
                    return@forEach
                }

                val contextInfo = buildPushContext(
                    senderId = envelope.envelope.senderId,
                    recipientId = identity.senderId,
                    seq = envelope.envelope.seq
                )
                val plaintext = runCatching {
                    localCrypto.decryptForIdentity(identity, myCiphertext, contextInfo)
                }.getOrNull()
                if (plaintext == null) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Payload decryption failed."
                    )
                    return@forEach
                }

                val claim = runCatching {
                    json.decodeFromString(LocationClaimV1.serializer(), plaintext.toString(Charsets.UTF_8))
                }.getOrNull()
                if (claim == null || !SharingLogic.isValidClaim(claim)) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Invalid claim payload."
                    )
                    return@forEach
                }

                if (claim.senderId != record.senderId || claim.seq != envelope.envelope.seq) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Sender/sequence mismatch."
                    )
                    return@forEach
                }

                if (claim.version != envelope.envelope.payloadVersion) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Payload version mismatch."
                    )
                    return@forEach
                }

                val previousSeq = contact.lastSeenSeq ?: -1L
                if (claim.seq <= previousSeq) {
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Replay detected: non-increasing sequence."
                    )
                    return@forEach
                }

                receivedBySender[record.senderId] = ReceivedLocationState(
                    senderId = record.senderId,
                    claim = claim,
                    relaySeq = envelope.envelope.seq,
                    relayTimestampMs = envelope.envelope.timestampMs,
                    receivedAtMs = now
                )

                pollStatusBySender[record.senderId] = SenderPollStatus(
                    senderId = record.senderId,
                    lastSuccessAtMs = now,
                    lastErrorAtMs = null,
                    lastError = null
                )

                contactUpdates[record.senderId] = contact.copy(lastSeenSeq = claim.seq, updatedAtMs = now)
            }

            current.copy(
                contacts = contactUpdates.values.sortedBy { it.addedAtMs },
                receivedLocations = receivedBySender.values.sortedByDescending { it.receivedAtMs },
                senderPollStatuses = pollStatusBySender.values.sortedBy { it.senderId },
                sync = current.sync.copy(
                    lastPollSuccessAtMs = now,
                    lastPollError = null,
                    lastPollErrorAtMs = null,
                    pollFailureStreak = 0
                )
            )
        }
    }

    private suspend fun handleLocationEvent(event: LocationSampleEvent) {
        val ineligibilityReason = pushIneligibilityReason(event)
        if (ineligibilityReason != null) {
            maybeLogIneligiblePush(ineligibilityReason)
            return
        }

        val localSnapshot = snapshot
        val identity = localSnapshot.identity ?: return
        val recipients = localSnapshot.contacts.filter { it.canReceiveFromMe }
        if (recipients.isEmpty()) {
            maybeLogIneligiblePush("no_outbound_recipients")
            return
        }

        val seq = localSnapshot.sync.nextSeq
        val claim = buildClaim(event = event, senderId = identity.senderId, seq = seq)
        if (!SharingLogic.isValidClaim(claim)) {
            Log.w(
                SHARING_DEBUG_TAG,
                "Dropping invalid claim sender=${shortSharingId(identity.senderId)} seq=$seq lat=${event.lat} lon=${event.lon}"
            )
            return
        }

        val envelope = buildSignedEnvelope(identity = identity, claim = claim, recipients = recipients)
        if (envelope.envelope.recipientCiphertexts.isEmpty()) {
            Log.w(
                SHARING_DEBUG_TAG,
                "No recipient ciphertexts generated sender=${shortSharingId(identity.senderId)} seq=$seq recipients=${recipients.size}"
            )
            return
        }

        Log.d(
            SHARING_DEBUG_TAG,
            "Push candidate sender=${shortSharingId(identity.senderId)} seq=$seq recipients=${envelope.envelope.recipientCiphertexts.size} speed=${event.speedMps}"
        )
        sendEnvelope(envelope = envelope, claim = claim, identity = identity)
    }

    private fun pushIneligibilityReason(event: LocationSampleEvent): String? {
        if (!currentSettings.sharingEnabled) {
            return "sharing_disabled"
        }

        val username = normalizeUsername(currentSettings.username)
        if (!isValidUsername(username)) {
            return "username_invalid"
        }

        val engineState = LocationEngine.state.value
        if (!engineState.engineEnabled || engineState.engineMode == LocationEngineMode.Off) {
            return "location_engine_inactive"
        }

        val recipientsCount = snapshot.contacts.count { it.canReceiveFromMe }
        if (recipientsCount == 0) {
            return "no_outbound_recipients"
        }

        val thresholdMs = if ((event.speedMps ?: -1f) >= currentSettings.fastSpeedThresholdMps) {
            currentSettings.fastIntervalMs
        } else {
            currentSettings.normalIntervalMs
        }

        val lastSuccess = snapshot.sync.lastPushSuccessAtMs
        if (lastSuccess != null && System.currentTimeMillis() - lastSuccess < thresholdMs) {
            return "threshold_not_elapsed"
        }

        return null
    }

    private fun buildClaim(event: LocationSampleEvent, senderId: String, seq: Long): LocationClaimV1 {
        val matchedZones = SharingLogic.matchingZoneTags(event.lat, event.lon, snapshot.zones)
        val batteryPercent = readBatteryPercent()
        return LocationClaimV1(
            version = SharingVersions.PAYLOAD_VERSION,
            timestampMs = event.fixTimeMs,
            lat = event.lat,
            lon = event.lon,
            speed = event.speedMps,
            accuracy = event.accuracyM.takeIf { it > 0f },
            batteryPercent = batteryPercent,
            zones = matchedZones.takeIf { it.isNotEmpty() },
            username = normalizeUsername(currentSettings.username).takeIf { it.isNotBlank() },
            senderId = senderId,
            seq = seq
        )
    }

    private fun readBatteryPercent(): Int? {
        val context = appContext ?: return null
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level.toFloat() / scale.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    private fun buildSignedEnvelope(
        identity: SharingIdentityState,
        claim: LocationClaimV1,
        recipients: List<PeerContactState>
    ): PushEnvelopeSigned {
        val localCrypto = crypto ?: error("Sharing crypto unavailable")
        val claimJsonBytes = json.encodeToString(LocationClaimV1.serializer(), claim)
            .toByteArray(Charsets.UTF_8)

        val recipientPayloads = recipients.mapNotNull { contact ->
            runCatching {
                val contextInfo = buildPushContext(
                    senderId = identity.senderId,
                    recipientId = contact.senderId,
                    seq = claim.seq
                )
                val ciphertext = localCrypto.encryptForRecipient(
                    recipientEncPublicKeysetJson = contact.encPublicKeysetJson,
                    plaintext = claimJsonBytes,
                    contextInfo = contextInfo
                )
                RecipientCiphertext(
                    recipientId = contact.senderId,
                    ciphertextB64Url = ciphertext.base64UrlEncode()
                )
            }.getOrNull()
        }.sortedBy { it.recipientId }

        val unsigned = PushEnvelopeUnsigned(
            senderId = identity.senderId,
            seq = claim.seq,
            timestampMs = claim.timestampMs,
            payloadVersion = claim.version,
            recipientCiphertexts = recipientPayloads
        )
        val unsignedBytes = canonicalPushMessage(unsigned)
        val signature = localCrypto.sign(identity, unsignedBytes).base64UrlEncode()
        return PushEnvelopeSigned(envelope = unsigned, signatureB64Url = signature)
    }

    private suspend fun sendEnvelope(
        envelope: PushEnvelopeSigned,
        claim: LocationClaimV1,
        identity: SharingIdentityState
    ) {
        val localRelay = relayApi ?: return
        val networkError = networkPreflightError()
        if (networkError != null) {
            Log.w(
                SHARING_DEBUG_TAG,
                "Push blocked sender=${shortSharingId(identity.senderId)} seq=${claim.seq} reason=$networkError"
            )
            queuePendingPush(envelope = envelope, claim = claim, reason = networkError)
            setError(networkError)
            return
        }
        val now = System.currentTimeMillis()
        Log.d(
            SHARING_DEBUG_TAG,
            "Sending push sender=${shortSharingId(identity.senderId)} seq=${claim.seq} recipients=${envelope.envelope.recipientCiphertexts.size}"
        )

        mutateSnapshot {
            it.copy(sync = it.sync.copy(lastPushAttemptAtMs = now))
        }

        val aclUpdated = ensureAclUpToDate(identity)
        if (aclUpdated.isFailure) {
            Log.e(
                SHARING_DEBUG_TAG,
                "ACL sync failed before push sender=${shortSharingId(identity.senderId)} seq=${claim.seq} error=${aclUpdated.exceptionOrNull()?.message}",
                aclUpdated.exceptionOrNull()
            )
            queuePendingPush(envelope = envelope, claim = claim, reason = aclUpdated.exceptionOrNull()?.message)
            return
        }

        val request = PushLocationRequest(
            push = envelope,
            senderSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url,
            senderEncPublicKeysetJson = identity.encPublicKeysetJson
        )

        localRelay.pushLocation(request)
            .onSuccess {
                Log.d(
                    SHARING_DEBUG_TAG,
                    "Push success sender=${shortSharingId(identity.senderId)} seq=${claim.seq} storedAtMs=${it.storedAtMs}"
                )
                mutateSnapshot { current ->
                    current.copy(
                        pendingPush = null,
                        sync = current.sync.copy(
                            lastPushSuccessAtMs = it.storedAtMs,
                            lastPushError = null,
                            lastPushErrorAtMs = null,
                            pushFailureStreak = 0,
                            nextSeq = maxOf(current.sync.nextSeq, it.appliedSeq + 1L)
                        )
                    )
                }
                setInfo("Location pushed successfully.")
            }
            .onFailure { throwable ->
                val failureReason = throwable.message ?: "Push failed"
                if (isPushSeqConflictError(throwable)) {
                    val resolved = reconcilePushSeqConflict(
                        identity = identity,
                        attemptedSeq = claim.seq,
                        source = "push_send"
                    )
                    if (resolved) {
                        return@onFailure
                    }
                    mutateSnapshot { current ->
                        current.copy(
                            pendingPush = current.pendingPush?.takeIf { it.claim.seq > claim.seq },
                            sync = current.sync.copy(
                                nextSeq = maxOf(current.sync.nextSeq, claim.seq + 1L),
                                lastPushError = "Dropped stale push after sequence conflict.",
                                lastPushErrorAtMs = System.currentTimeMillis(),
                                pushFailureStreak = current.sync.pushFailureStreak + 1
                            )
                        )
                    }
                    setError("Dropped stale push after sequence conflict. Next push will use a higher sequence.")
                    return@onFailure
                }
                Log.e(
                    SHARING_DEBUG_TAG,
                    "Push failed sender=${shortSharingId(identity.senderId)} seq=${claim.seq} error=$failureReason",
                    throwable
                )
                queuePendingPush(envelope = envelope, claim = claim, reason = failureReason)
                setError(failureReason)
            }
    }

    private suspend fun ensureAclUpToDate(identity: SharingIdentityState): Result<Unit> {
        val localRelay = relayApi ?: return Result.failure(IllegalStateException("Relay unavailable."))
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Crypto unavailable."))

        val receiverIds = snapshot.contacts.filter { it.canReceiveFromMe }.map { it.senderId }.sorted()
        val digest = digestReceivers(receiverIds)
        if (snapshot.sync.lastAclDigest == digest && snapshot.sync.lastAclSeq > 0L) {
            Log.d(
                SHARING_DEBUG_TAG,
                "ACL up to date sender=${shortSharingId(identity.senderId)} aclSeq=${snapshot.sync.lastAclSeq} receivers=${receiverIds.size}"
            )
            return Result.success(Unit)
        }

        val aclSeq = snapshot.sync.lastAclSeq + 1L
        val acl = AclUnsigned(
            senderId = identity.senderId,
            aclSeq = aclSeq,
            receiverIds = receiverIds,
            timestampMs = System.currentTimeMillis()
        )
        val aclBytes = canonicalAclMessage(acl)
        val signature = localCrypto.sign(identity, aclBytes).base64UrlEncode()
        val request = UpsertAclRequest(
            acl = acl,
            signatureB64Url = signature,
            senderSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url
        )

        Log.d(
            SHARING_DEBUG_TAG,
            "Upserting ACL sender=${shortSharingId(identity.senderId)} aclSeq=$aclSeq receivers=${receiverIds.size}"
        )
        return localRelay.upsertAcl(request)
            .map {
                Log.d(
                    SHARING_DEBUG_TAG,
                    "ACL upsert success sender=${shortSharingId(identity.senderId)} aclSeq=$aclSeq"
                )
                runCatching {
                    mutateSnapshot { current ->
                        current.copy(sync = current.sync.copy(lastAclSeq = aclSeq, lastAclDigest = digest))
                    }
                }
                Unit
            }
            .onFailure { throwable ->
                Log.e(
                    SHARING_DEBUG_TAG,
                    "ACL upsert failed sender=${shortSharingId(identity.senderId)} aclSeq=$aclSeq error=${throwable.message}",
                    throwable
                )
            }
    }

    private suspend fun queuePendingPush(envelope: PushEnvelopeSigned, claim: LocationClaimV1, reason: String?) {
        val now = System.currentTimeMillis()
        Log.w(
            SHARING_DEBUG_TAG,
            "Queueing pending push seq=${claim.seq} reason=${reason ?: "unknown"} recipients=${envelope.envelope.recipientCiphertexts.size}"
        )
        mutateSnapshot { current ->
            val nextAttempt = now + SharingLogic.computeBackoffDelayMs(attemptCount = 1)
            current.copy(
                pendingPush = PendingPushState(
                    envelope = envelope,
                    claim = claim,
                    queuedAtMs = now,
                    attemptCount = 1,
                    nextAttemptAtMs = nextAttempt
                ),
                sync = current.sync.copy(
                    lastPushError = reason ?: "Push failed",
                    lastPushErrorAtMs = now,
                    pushFailureStreak = current.sync.pushFailureStreak + 1
                )
            )
        }
    }

    private fun startRetryLoopIfNeeded() {
        if (retryJob != null) return

        retryJob = scope.launch {
            while (true) {
                delay(5_000L)

                val pending = snapshot.pendingPush ?: continue
                val nextAttempt = pending.nextAttemptAtMs ?: continue
                if (System.currentTimeMillis() < nextAttempt) {
                    continue
                }

                if (!currentSettings.sharingEnabled) {
                    continue
                }
                if (!isValidUsername(currentSettings.username)) {
                    continue
                }
                if (snapshot.contacts.none { it.canReceiveFromMe }) {
                    continue
                }

                val localRelay = relayApi ?: continue
                val identity = snapshot.identity ?: continue
                val networkError = networkPreflightError()
                if (networkError != null) {
                    reschedulePendingWithBackoff(
                        pending = snapshot.pendingPush ?: continue,
                        reason = networkError
                    )
                    continue
                }

                val aclResult = ensureAclUpToDate(identity)
                if (aclResult.isFailure) {
                    reschedulePendingWithBackoff(
                        pending = snapshot.pendingPush ?: continue,
                        reason = aclResult.exceptionOrNull()?.message ?: "ACL retry failed"
                    )
                    continue
                }

                if (pending.claim.seq < snapshot.sync.nextSeq) {
                    Log.w(
                        SHARING_DEBUG_TAG,
                        "Dropping stale pending push sender=${shortSharingId(identity.senderId)} seq=${pending.claim.seq} nextSeq=${snapshot.sync.nextSeq}"
                    )
                    mutateSnapshot { current ->
                        if (current.pendingPush?.claim?.seq == pending.claim.seq) {
                            current.copy(
                                pendingPush = null,
                                sync = current.sync.copy(
                                    lastPushError = null,
                                    lastPushErrorAtMs = null
                                )
                            )
                        } else {
                            current
                        }
                    }
                    continue
                }

                val request = PushLocationRequest(
                    push = pending.envelope,
                    senderSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url,
                    senderEncPublicKeysetJson = identity.encPublicKeysetJson
                )
                Log.d(
                    SHARING_DEBUG_TAG,
                    "Retrying pending push sender=${shortSharingId(identity.senderId)} seq=${pending.claim.seq} attempt=${pending.attemptCount + 1}"
                )
                localRelay.pushLocation(request)
                    .onSuccess {
                        Log.d(
                            SHARING_DEBUG_TAG,
                            "Pending push delivered sender=${shortSharingId(identity.senderId)} seq=${pending.claim.seq}"
                        )
                        mutateSnapshot { current ->
                            current.copy(
                                pendingPush = null,
                                sync = current.sync.copy(
                                    lastPushSuccessAtMs = it.storedAtMs,
                                    lastPushError = null,
                                    lastPushErrorAtMs = null,
                                    pushFailureStreak = 0,
                                    nextSeq = maxOf(current.sync.nextSeq, it.appliedSeq + 1L)
                                )
                            )
                        }
                        setInfo("Pending push delivered.")
                    }
                    .onFailure { throwable ->
                        if (isPushSeqConflictError(throwable)) {
                            val resolved = reconcilePushSeqConflict(
                                identity = identity,
                                attemptedSeq = pending.claim.seq,
                                source = "push_retry"
                            )
                            if (resolved) {
                                return@onFailure
                            }
                            mutateSnapshot { current ->
                                val currentPending = current.pendingPush
                                if (currentPending != null && currentPending.claim.seq <= pending.claim.seq) {
                                    current.copy(
                                        pendingPush = null,
                                        sync = current.sync.copy(
                                            nextSeq = maxOf(current.sync.nextSeq, pending.claim.seq + 1L),
                                            lastPushError = "Dropped stale pending push after seq conflict.",
                                            lastPushErrorAtMs = System.currentTimeMillis(),
                                            pushFailureStreak = current.sync.pushFailureStreak + 1
                                        )
                                    )
                                } else {
                                    current
                                }
                            }
                            setError("Dropped stale pending push after sequence conflict. New pushes will use a higher sequence.")
                            return@onFailure
                        }
                        Log.e(
                            SHARING_DEBUG_TAG,
                            "Pending push retry failed sender=${shortSharingId(identity.senderId)} seq=${pending.claim.seq} error=${throwable.message}",
                            throwable
                        )
                        reschedulePendingWithBackoff(
                            pending = snapshot.pendingPush ?: return@onFailure,
                            reason = throwable.message ?: "Retry push failed"
                        )
                    }
            }
        }
    }

    private suspend fun reschedulePendingWithBackoff(pending: PendingPushState, reason: String) {
        val nextAttemptCount = pending.attemptCount + 1
        val nextDelay = SharingLogic.computeBackoffDelayMs(nextAttemptCount)
        Log.w(
            SHARING_DEBUG_TAG,
            "Rescheduling pending push seq=${pending.claim.seq} nextAttempt=$nextAttemptCount delayMs=$nextDelay reason=$reason"
        )
        mutateSnapshot { current ->
            val refreshed = current.pendingPush
            if (refreshed == null) {
                current
            } else {
                current.copy(
                    pendingPush = refreshed.copy(
                        attemptCount = nextAttemptCount,
                        nextAttemptAtMs = System.currentTimeMillis() + nextDelay
                    ),
                    sync = current.sync.copy(
                        lastPushError = reason,
                        lastPushErrorAtMs = System.currentTimeMillis(),
                        pushFailureStreak = current.sync.pushFailureStreak + 1
                    )
                )
            }
        }
        setError(reason)
    }

    private fun isPushSeqConflictError(throwable: Throwable): Boolean {
        val relayHttp = throwable as? RelayHttpException
        if (relayHttp?.statusCode == 409 &&
            relayHttp.responseBody.contains("Push seq must increase monotonically", ignoreCase = true)
        ) {
            return true
        }
        val message = throwable.message ?: return false
        return message.contains("Push seq must increase monotonically", ignoreCase = true)
    }

    private suspend fun reconcilePushSeqConflict(
        identity: SharingIdentityState,
        attemptedSeq: Long,
        source: String
    ): Boolean {
        val localRelay = relayApi ?: return false
        val localCrypto = crypto ?: return false
        val now = System.currentTimeMillis()
        val nonce = randomNonceB64Url()
        val payload = canonicalSelfStatusMessage(identity.senderId, now, nonce)
        val signature = localCrypto.sign(identity, payload).base64UrlEncode()
        val request = SelfStatusRequest(
            senderId = identity.senderId,
            timestampMs = now,
            nonceB64Url = nonce,
            signatureB64Url = signature,
            senderSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url
        )

        val result = localRelay.selfStatus(request)
        if (result.isFailure) {
            Log.w(
                SHARING_DEBUG_TAG,
                "Seq conflict reconcile failed source=$source sender=${shortSharingId(identity.senderId)} attemptedSeq=$attemptedSeq error=${result.exceptionOrNull()?.message}",
                result.exceptionOrNull()
            )
            return false
        }

        val response = result.getOrNull() ?: return false
        val latestSeq = response.latestSeq ?: return false
        if (latestSeq < attemptedSeq) {
            Log.w(
                SHARING_DEBUG_TAG,
                "Seq conflict reconcile inconclusive source=$source sender=${shortSharingId(identity.senderId)} attemptedSeq=$attemptedSeq relayLatestSeq=$latestSeq"
            )
            return false
        }

        val nextSeq = latestSeq + 1L
        Log.w(
            SHARING_DEBUG_TAG,
            "Resolved stale push seq source=$source sender=${shortSharingId(identity.senderId)} attemptedSeq=$attemptedSeq relayLatestSeq=$latestSeq nextSeq=$nextSeq"
        )
        mutateSnapshot { current ->
            val keepPending = current.pendingPush?.takeIf { it.claim.seq > latestSeq }
            current.copy(
                pendingPush = keepPending,
                sync = current.sync.copy(
                    nextSeq = maxOf(current.sync.nextSeq, nextSeq),
                    lastPushSuccessAtMs = response.storedAtMs ?: current.sync.lastPushSuccessAtMs,
                    lastPushError = if (keepPending == null) null else current.sync.lastPushError,
                    lastPushErrorAtMs = if (keepPending == null) null else current.sync.lastPushErrorAtMs
                )
            )
        }
        if (snapshot.pendingPush == null) {
            setInfo("Recovered from stale push sequence conflict.")
        }
        return true
    }

    private suspend fun mutateSnapshot(transform: (SharingStateSnapshot) -> SharingStateSnapshot) {
        val localStore = stateStore ?: return
        stateMutex.withLock {
            snapshot = localStore.update(transform)
            publishState()
        }
    }

    private fun updateSyncState(transform: (SharingSyncState) -> SharingSyncState) {
        val localStore = stateStore ?: return
        snapshot = localStore.update { current ->
            current.copy(sync = transform(current.sync))
        }
    }

    private fun mutateSyncOnly(transform: (SharingSyncState) -> SharingSyncState) {
        val localStore = stateStore ?: return
        synchronized(this) {
            snapshot = localStore.update { current ->
                current.copy(sync = transform(current.sync))
            }
            publishState()
        }
    }

    private fun setInfo(message: String) {
        Log.d(SHARING_DEBUG_TAG, "INFO $message")
        lastInfo = message
        if (lastError != null) {
            lastError = null
        }
        publishState()
    }

    private fun setError(message: String) {
        Log.w(SHARING_DEBUG_TAG, "ERROR $message")
        lastError = message
        publishState()
    }

    private fun publishState() {
        val localSnapshot = snapshot
        val identity = localSnapshot.identity
        val contactsSorted = localSnapshot.contacts.sortedBy { it.addedAtMs }
        val pollStatusBySender = localSnapshot.senderPollStatuses.associateBy { it.senderId }

        val contacts = contactsSorted.map { contact ->
            ContactView(
                senderId = contact.senderId,
                displayName = contact.localAlias
                    ?: contact.onboardingName
                    ?: contact.safetyFingerprint,
                onboardingName = contact.onboardingName,
                localAlias = contact.localAlias,
                canReceiveFromMe = contact.canReceiveFromMe,
                iFollow = contact.iFollow,
                safetyFingerprint = contact.safetyFingerprint,
                lastSeenSeq = contact.lastSeenSeq
            )
        }

        val receivedCards = localSnapshot.receivedLocations
            .sortedByDescending { it.receivedAtMs }
            .map { location ->
                val contact = contactsSorted.firstOrNull { it.senderId == location.senderId }
                val displayName = contact?.localAlias
                    ?: location.claim.username
                    ?: contact?.onboardingName
                    ?: contact?.safetyFingerprint
                    ?: location.senderId

                ReceivedCardView(
                    senderId = location.senderId,
                    displayName = displayName,
                    claim = location.claim,
                    relaySeq = location.relaySeq,
                    relayTimestampMs = location.relayTimestampMs,
                    receivedAtMs = location.receivedAtMs,
                    pollStatus = pollStatusBySender[location.senderId]
                )
            }

        val safetyFingerprint = identity?.let {
            crypto?.safetyFingerprint(
                senderId = it.senderId,
                signPublicKeySpkiB64Url = it.signPublicKeySpkiB64Url,
                encPublicKeysetJson = it.encPublicKeysetJson
            )
        }

        val myCode = identity?.let {
            runCatching {
                crypto?.exportContactCode(it, currentSettings.username.takeIf { name -> name.isNotBlank() })
            }.getOrNull()
        }

        _state.value = LocationSharingState(
            initialized = initialized,
            settings = currentSettings,
            identitySenderId = identity?.senderId,
            mySafetyFingerprint = safetyFingerprint,
            myContactCode = myCode,
            contacts = contacts,
            zones = localSnapshot.zones.sortedBy { it.createdAtMs },
            receivedCards = receivedCards,
            sync = localSnapshot.sync.copy(pollingActive = pollingVisible),
            outboundRecipientsCount = contacts.count { it.canReceiveFromMe },
            followingCount = contacts.count { it.iFollow },
            pollingVisible = pollingVisible,
            lastError = lastError,
            lastInfo = lastInfo
        )
    }

    private fun maybeLogIneligiblePush(reason: String) {
        val now = System.currentTimeMillis()
        if (reason == lastEligibilityDebugReason && now - lastEligibilityDebugAtMs < 60_000L) {
            return
        }
        lastEligibilityDebugReason = reason
        lastEligibilityDebugAtMs = now
        Log.d(
            SHARING_DEBUG_TAG,
            "Skipping push reason=$reason sharingEnabled=${currentSettings.sharingEnabled} usernameValid=${isValidUsername(normalizeUsername(currentSettings.username))} recipients=${snapshot.contacts.count { it.canReceiveFromMe }}"
        )
    }

    private fun networkPreflightError(): String? {
        val context = appContext ?: return "Sharing is not initialized."
        if (!context.hasSharingNetworkPermissions()) {
            return "Network permission missing (INTERNET/ACCESS_NETWORK_STATE). Update or reinstall the app."
        }
        return null
    }

    private fun buildPushContext(senderId: String, recipientId: String, seq: Long): ByteArray {
        return "${SharingVersions.PUSH_CONTEXT_VERSION}|$senderId|$recipientId|$seq".toByteArray(Charsets.UTF_8)
    }

    private fun digestReceivers(receiverIds: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(receiverIds.joinToString("|").toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun randomNonceB64Url(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.base64UrlEncode()
    }

    private fun ByteArray.base64UrlEncode(): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    }

    private fun String.base64UrlDecode(): ByteArray {
        return Base64.getUrlDecoder().decode(this)
    }

    private val secureRandom = SecureRandom()
}
