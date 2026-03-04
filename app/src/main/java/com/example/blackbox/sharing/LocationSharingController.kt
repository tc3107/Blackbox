package com.example.blackbox.sharing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import com.example.blackbox.logging.AppLog as Log
import androidx.core.net.toUri
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
    private val pollMutex = Mutex()
    private val aclSyncMutex = Mutex()
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
    private val relayStatusTimeoutRetryDelayMs = 1_500L
    private val relayStatusTimeoutUnreachableThreshold = 2
    private val maxAclUpsertAttempts = 4
    private val pollMinGapMs = 3_000L
    private val aclReassertIntervalMs = 15 * 60_000L
    private val forcedAclResyncCooldownMs = 60_000L

    private var currentSettings: SharingSettings = SharingSettings()
    private var snapshot: SharingStateSnapshot = SharingStateSnapshot()
    private var pollingVisible: Boolean = false
    private var mainViewVisible: Boolean = false
    private var lastError: String? = null
    private var lastInfo: String = "Sharing subsystem idle."
    private var lastEligibilityDebugReason: String? = null
    private var lastEligibilityDebugAtMs: Long = 0L
    private var lastForcedAclResyncAtMs: Long = 0L

    private data class PullProcessingSummary(
        val okCount: Int = 0,
        val unauthorizedCount: Int = 0,
        val noDataCount: Int = 0,
        val errorCount: Int = 0,
        val unauthorizedSenderIds: Set<String> = emptySet()
    )

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
            var shouldPoll = false
            stateMutex.withLock {
                if (pollingVisible == visible) return@withLock
                pollingVisible = visible
                shouldPoll = shouldRunPollingLoop()
                updateSyncState { it.copy(pollingActive = shouldPoll) }
                changed = true
            }
            if (!changed) return@launch
            Log.d(SHARING_DEBUG_TAG, "Sharing page visibility changed visible=$visible")
            publishState()
            updatePollingLoopState()
            updateRelayStatusLoopState()
        }
    }

    fun onMainViewVisible(visible: Boolean) {
        scope.launch {
            var changed = false
            var shouldPoll = false
            stateMutex.withLock {
                if (mainViewVisible == visible) return@withLock
                mainViewVisible = visible
                shouldPoll = shouldRunPollingLoop()
                updateSyncState { it.copy(pollingActive = shouldPoll) }
                changed = true
            }
            if (!changed) return@launch
            publishState()
            updatePollingLoopState()
            updateRelayStatusLoopState()
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        if (enabled && !isValidUsername(normalizeUsername(currentSettings.username))) {
            Log.w(SHARING_DEBUG_TAG, "Rejected enabling sharing: username missing or invalid.")
            currentSettings = currentSettings.copy(sharingEnabled = false)
            setError("Set a valid username before enabling Share My Location.")
            settingsStore?.setSharingEnabled(false)
            return
        }
        Log.d(SHARING_DEBUG_TAG, "setSharingEnabled enabled=$enabled")
        currentSettings = currentSettings.copy(sharingEnabled = enabled)
        publishState()
        settingsStore?.setSharingEnabled(enabled)
    }

    fun setUsername(username: String) {
        currentSettings = currentSettings.copy(username = normalizeUsername(username))
        publishState()
        settingsStore?.setUsername(username)
    }

    fun setRelayBaseUrl(baseUrl: String) {
        Log.d(SHARING_DEBUG_TAG, "setRelayBaseUrl requested=$baseUrl")
        val normalizedRelayBaseUrl = normalizeRelayBaseUrl(baseUrl)
        currentSettings = currentSettings.copy(
            relayBaseUrl = normalizedRelayBaseUrl
        )
        publishState()
        settingsStore?.setRelayBaseUrl(normalizedRelayBaseUrl)
    }

    fun setIntervals(normalMs: Long, fastMs: Long) {
        currentSettings = currentSettings.copy(
            normalIntervalMs = normalMs.coerceIn(MIN_NORMAL_INTERVAL_MS, MAX_NORMAL_INTERVAL_MS),
            fastIntervalMs = fastMs.coerceIn(MIN_FAST_INTERVAL_MS, MAX_FAST_INTERVAL_MS)
        )
        publishState()
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
            val identity = snapshot.identity
            if (identity == null) {
                setInfo("Authorization updated.")
                return@launch
            }

            val aclSync = ensureAclUpToDate(identity)
            if (aclSync.isSuccess) {
                setInfo("Authorization updated and synced.")
            } else {
                val reason = aclSync.exceptionOrNull()?.message ?: "unknown error"
                Log.w(
                    SHARING_DEBUG_TAG,
                    "Authorization updated but ACL sync failed sender=${shortSharingId(identity.senderId)} error=$reason",
                    aclSync.exceptionOrNull()
                )
                setInfo("Authorization updated. ACL will sync on next push.")
            }
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
            snapshot.identity?.let { identity ->
                ensureAclUpToDate(identity)
                    .onFailure { throwable ->
                        Log.w(
                            SHARING_DEBUG_TAG,
                            "ACL sync after contact removal failed sender=${shortSharingId(identity.senderId)} error=${throwable.message}",
                            throwable
                        )
                    }
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

    fun manualRelayStatusNow() {
        Log.d(SHARING_DEBUG_TAG, "Manual relay status check requested")
        scope.launch {
            refreshRelayStatus(trigger = "manual")
        }
    }

    fun manualPushNow() {
        Log.d(SHARING_DEBUG_TAG, "Manual push requested")
        scope.launch {
            val event = buildManualLocationSampleEvent()
            if (event == null) {
                setError("No location fix available yet.")
                return@launch
            }
            handleLocationEvent(event = event, forceImmediate = true)
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

    suspend fun fetchContactHistoryLast24h(senderId: String): Result<ContactHistoryRange> {
        val localRelay = relayApi ?: return Result.failure(IllegalStateException("Relay unavailable."))
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Crypto unavailable."))
        val identity = snapshot.identity ?: return Result.failure(IllegalStateException("Identity unavailable."))
        val contact = snapshot.contacts.firstOrNull { it.senderId == senderId }
            ?: return Result.failure(IllegalArgumentException("Unknown contact."))
        if (!contact.iFollow) {
            return Result.failure(IllegalStateException("Contact is not followed."))
        }
        val networkError = networkPreflightError()
        if (networkError != null) {
            return Result.failure(IllegalStateException(networkError))
        }

        val now = System.currentTimeMillis()
        val windowStartMs = (now - CONTACT_HISTORY_WINDOW_MS).coerceAtLeast(0L)
        val nonce = randomNonceB64Url()
        val payload = canonicalPullMessage(
            receiverId = identity.senderId,
            senderIds = listOf(senderId),
            timestampMs = now,
            nonceB64Url = nonce
        )
        val signature = localCrypto.sign(identity, payload).base64UrlEncode()
        val request = PullHistoryRequest(
            senderId = senderId,
            receiverId = identity.senderId,
            timestampMs = now,
            nonceB64Url = nonce,
            signatureB64Url = signature,
            receiverSignPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url
        )

        return localRelay.pullHistory(request).mapCatching { response ->
            when (response.status) {
                "ok" -> Unit
                "no_data" -> {
                    return@mapCatching ContactHistoryRange(
                        senderId = senderId,
                        windowStartMs = windowStartMs,
                        windowEndMs = now,
                        samples = emptyList()
                    )
                }
                "unauthorized" -> error(response.message ?: "Receiver is not authorized for this sender.")
                else -> error(response.message ?: "History pull failed.")
            }

            val bySeq = linkedMapOf<Long, ContactHistorySample>()
            response.records
                .sortedBy { it.storedAtMs }
                .forEach { record ->
                    val claim = decodeAndValidatePulledClaim(
                        recordSenderId = senderId,
                        envelope = record.envelope,
                        contact = contact,
                        identity = identity,
                        localCrypto = localCrypto
                    ).getOrElse { throwable ->
                        error(throwable.message ?: "Invalid history payload.")
                    }
                    if (claim.timestampMs !in windowStartMs..now) {
                        return@forEach
                    }
                    bySeq[claim.seq] = ContactHistorySample(
                        senderId = senderId,
                        seq = claim.seq,
                        timestampMs = claim.timestampMs,
                        latitude = claim.lat,
                        longitude = claim.lon,
                        accuracyMeters = claim.accuracy?.toDouble()?.coerceAtLeast(1.0) ?: 25.0
                    )
                }

            ContactHistoryRange(
                senderId = senderId,
                windowStartMs = windowStartMs,
                windowEndMs = now,
                samples = bySeq.values.sortedBy { it.timestampMs }
            )
        }
    }

    suspend fun exportContactsBundle(passphrase: CharArray, target: Uri): Result<Uri> {
        val context = appContext ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        val identity = snapshot.identity ?: return Result.failure(IllegalStateException("No identity available."))

        return runCatching {
            val bundle = localCrypto.encryptContactsBundle(identity, snapshot.contacts, passphrase)
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                output.write(bundle.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: error("Failed to open export destination.")
            setInfo("Contacts bundle exported.")
            target
        }.onFailure {
            setError(it.message ?: "Contacts export failed")
        }
    }

    suspend fun importContactsBundle(passphrase: CharArray, source: Uri): Result<Unit> {
        val context = appContext ?: return Result.failure(IllegalStateException("Sharing not initialized."))
        val localCrypto = crypto ?: return Result.failure(IllegalStateException("Sharing not initialized."))

        return runCatching {
            val raw = context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
                ?: error("Failed to open bundle.")
            val imported = localCrypto.decryptContactsBundle(raw, passphrase)
            val contactsById = linkedMapOf<String, PeerContactState>()
            imported.contacts.forEach { contact ->
                if (contact.senderId.isBlank()) return@forEach
                if (contact.signPublicKeySpkiB64Url.isBlank()) return@forEach
                if (contact.encPublicKeysetJson.isBlank()) return@forEach
                if (contact.senderId == imported.identity.senderId) return@forEach
                contactsById[contact.senderId] = contact
            }
            require(isValidContactCount(contactsById.size)) { "Contact limit reached ($CONTACT_LIMIT)." }
            val importedContacts = contactsById.values.sortedBy { it.addedAtMs }
            val importedIds = importedContacts.map { it.senderId }.toSet()
            mutateSnapshot { current ->
                val sameSender = current.identity?.senderId == imported.identity.senderId
                val carriedSync = if (sameSender) {
                    // Preserve monotonic counters for same identity to avoid relay sequence conflicts after restore.
                    current.sync
                } else {
                    SharingSyncState()
                }
                current.copy(
                    identity = imported.identity,
                    contacts = importedContacts,
                    receivedLocations = current.receivedLocations.filter { it.senderId in importedIds },
                    senderPollStatuses = current.senderPollStatuses.filter { it.senderId in importedIds },
                    pendingPush = null,
                    sync = carriedSync
                )
            }
            setInfo("Contacts bundle imported.")
        }.onFailure {
            setError(normalizeContactsImportError(it))
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
                        if (!shouldRunPollingLoop()) {
                            Log.d(SHARING_DEBUG_TAG, "Poll loop paused: no visible poll surface.")
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

    private fun shouldRunPollingLoop(): Boolean {
        return pollingVisible || mainViewVisible
    }

    private fun updatePollingLoopState() {
        if (shouldRunPollingLoop()) {
            startPollingLoop()
        } else {
            stopPollingLoop()
        }
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
                    var firstCycle = true
                    var nextCheckAtMs = System.currentTimeMillis()
                    while (true) {
                        val now = System.currentTimeMillis()
                        val waitMs = (nextCheckAtMs - now).coerceAtLeast(0L)
                        if (waitMs > 0L) {
                            delay(waitMs)
                        }
                        if (!shouldRunRelayStatusLoop()) {
                            Log.d(SHARING_DEBUG_TAG, "Relay status loop paused: no visible relay status surface.")
                            break
                        }
                        val startedAtMs = System.currentTimeMillis()
                        val trigger = if (firstCycle) "page_open" else "interval"
                        firstCycle = false
                        refreshRelayStatus(trigger = trigger)
                        nextCheckAtMs = startedAtMs + RELAY_STATUS_INTERVAL_MS
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

        val firstRequest = RelayStatusRequest(clientTimestampMs = now)
        val firstResult = localRelay.relayStatus(firstRequest)
        val timedOut = firstResult.exceptionOrNull() is RelayTimeoutException
        val result = if (timedOut) {
            Log.w(
                SHARING_DEBUG_TAG,
                "Relay status timeout trigger=$trigger retrying_in_ms=$relayStatusTimeoutRetryDelayMs"
            )
            delay(relayStatusTimeoutRetryDelayMs)
            localRelay.relayStatus(
                RelayStatusRequest(clientTimestampMs = System.currentTimeMillis())
            )
        } else {
            firstResult
        }

        result
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
                val timeoutFailure = throwable is RelayTimeoutException
                val error = throwable.message ?: "Relay status failed."
                if (timeoutFailure) {
                    Log.w(
                        SHARING_DEBUG_TAG,
                        "Relay status timeout persisted trigger=$trigger error=$error"
                    )
                } else {
                    Log.w(
                        SHARING_DEBUG_TAG,
                        "Relay status failed trigger=$trigger error=$error",
                        throwable
                    )
                }
                mutateSyncOnly {
                    val nextFailureStreak = it.relayCheckFailureStreak + 1
                    val reachableAfterFailure = if (
                        timeoutFailure && nextFailureStreak < relayStatusTimeoutUnreachableThreshold
                    ) {
                        it.relayReachable
                    } else {
                        false
                    }
                    it.copy(
                        relayStatusChecking = false,
                        relayReachable = reachableAfterFailure,
                        lastRelayStatusError = error,
                        lastRelayStatusErrorAtMs = failedAt,
                        relayCheckFailureStreak = nextFailureStreak
                    )
                }
            }
    }

    private suspend fun pollNow(trigger: String) {
        pollMutex.withLock {
            val localRelay = relayApi ?: return@withLock
            val localCrypto = crypto ?: return@withLock
            val localSnapshot = snapshot
            val identity = localSnapshot.identity ?: return@withLock

            if (trigger == "interval") {
                val lastAttempt = localSnapshot.sync.lastPollAttemptAtMs
                val nowMs = System.currentTimeMillis()
                if (lastAttempt != null && nowMs - lastAttempt < pollMinGapMs) {
                    Log.d(
                        SHARING_DEBUG_TAG,
                        "Poll skipped trigger=$trigger sender=${shortSharingId(identity.senderId)} reason=recent_poll"
                    )
                    return@withLock
                }
            }

            val pollAttemptAt = System.currentTimeMillis()
            mutateSyncOnly {
                it.copy(
                    lastPollAttemptAtMs = pollAttemptAt,
                    pollRequestInFlight = true
                )
            }
            try {
                val networkError = networkPreflightError()
                if (networkError != null) {
                    Log.w(
                        SHARING_DEBUG_TAG,
                        "Poll blocked trigger=$trigger sender=${shortSharingId(identity.senderId)} reason=$networkError"
                    )
                    mutateSnapshot {
                        it.copy(
                            sync = it.sync.copy(
                                lastPollError = networkError,
                                lastPollErrorAtMs = System.currentTimeMillis(),
                                pollFailureStreak = it.sync.pollFailureStreak + 1
                            )
                        )
                    }
                    setError(networkError)
                    return@withLock
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
                    return@withLock
                }

                if (snapshot.contacts.any { it.canReceiveFromMe }) {
                    val aclSync = ensureAclUpToDate(identity)
                    if (aclSync.isFailure) {
                        Log.w(
                            SHARING_DEBUG_TAG,
                            "Poll continuing without fresh ACL sender=${shortSharingId(identity.senderId)} error=${aclSync.exceptionOrNull()?.message}",
                            aclSync.exceptionOrNull()
                        )
                    }
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
                    return@withLock
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
                        val summary = handlePullSuccess(response, identity)
                        if (summary.unauthorizedCount > 0) {
                            maybeForceAclResyncAfterUnauthorized(identity, summary.unauthorizedSenderIds)
                            setError(buildUnauthorizedPollMessage(trigger, summary))
                        } else if (summary.okCount > 0) {
                            setInfo("Poll complete ($trigger): received ${summary.okCount} update(s).")
                        } else if (summary.noDataCount > 0 && summary.errorCount == 0) {
                            setInfo("Poll complete ($trigger): no location updates yet.")
                        } else {
                            setInfo("Poll complete ($trigger).")
                        }
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
            } finally {
                mutateSyncOnly { it.copy(pollRequestInFlight = false) }
            }
        }
    }

    private suspend fun handlePullSuccess(
        response: PullBatchResponse,
        identity: SharingIdentityState
    ): PullProcessingSummary {
        val localCrypto = crypto ?: return PullProcessingSummary(errorCount = response.records.size)
        val responseStatusSummary = response.records.groupingBy { it.status }.eachCount()
        Log.d(
            SHARING_DEBUG_TAG,
            "Handling pull payload sender=${shortSharingId(identity.senderId)} statusSummary=$responseStatusSummary"
        )
        var okCount = 0
        var unauthorizedCount = 0
        var noDataCount = 0
        var errorCount = 0
        val unauthorizedSenderIds = linkedSetOf<String>()
        mutateSnapshot { current ->
            val contactById = current.contacts.associateBy { it.senderId }
            val receivedBySender = current.receivedLocations.associateBy { it.senderId }.toMutableMap()
            val pollStatusBySender = current.senderPollStatuses.associateBy { it.senderId }.toMutableMap()
            val contactUpdates = current.contacts.associateBy { it.senderId }.toMutableMap()
            val now = System.currentTimeMillis()

            response.records.forEach { record ->
                val contact = contactById[record.senderId]
                if (contact == null || !contact.iFollow) {
                    errorCount += 1
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = "Sender is not in follow list."
                    )
                    return@forEach
                }

                val envelope = record.envelope
                if (record.status != "ok" || envelope == null) {
                    val pollError = when (record.status) {
                        "unauthorized" -> {
                            unauthorizedCount += 1
                            unauthorizedSenderIds += record.senderId
                            "Not authorized by sender. Ask them to enable Sharing for your contact and sync ACL."
                        }
                        "no_data" -> {
                            noDataCount += 1
                            record.message ?: "No location published yet."
                        }
                        else -> {
                            errorCount += 1
                            record.message ?: "No data available"
                        }
                    }
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = pollError
                    )
                    return@forEach
                }

                val validatedClaim = decodeAndValidatePulledClaim(
                    recordSenderId = record.senderId,
                    envelope = envelope,
                    contact = contact,
                    identity = identity,
                    localCrypto = localCrypto
                )
                if (validatedClaim.isFailure) {
                    errorCount += 1
                    pollStatusBySender[record.senderId] = SenderPollStatus(
                        senderId = record.senderId,
                        lastErrorAtMs = now,
                        lastError = validatedClaim.exceptionOrNull()?.message ?: "Invalid claim payload."
                    )
                    return@forEach
                }
                val claim = validatedClaim.getOrThrow()

                val previousSeq = contact.lastSeenSeq ?: -1L
                if (claim.seq <= previousSeq) {
                    errorCount += 1
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
                okCount += 1

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
        return PullProcessingSummary(
            okCount = okCount,
            unauthorizedCount = unauthorizedCount,
            noDataCount = noDataCount,
            errorCount = errorCount,
            unauthorizedSenderIds = unauthorizedSenderIds
        )
    }

    private fun buildUnauthorizedPollMessage(
        trigger: String,
        summary: PullProcessingSummary
    ): String {
        val sampleIds = summary.unauthorizedSenderIds
            .take(3)
            .joinToString(", ") { shortSharingId(it) }
        val suffix = if (summary.unauthorizedSenderIds.size > 3) ", ..." else ""
        return "Poll complete ($trigger): ${summary.unauthorizedCount} sender(s) unauthorized ($sampleIds$suffix)."
    }

    private suspend fun maybeForceAclResyncAfterUnauthorized(
        identity: SharingIdentityState,
        unauthorizedSenderIds: Set<String>
    ) {
        if (unauthorizedSenderIds.isEmpty()) return
        if (snapshot.contacts.none { it.canReceiveFromMe }) return

        val now = System.currentTimeMillis()
        if (now - lastForcedAclResyncAtMs < forcedAclResyncCooldownMs) {
            return
        }
        lastForcedAclResyncAtMs = now

        Log.w(
            SHARING_DEBUG_TAG,
            "Unauthorized pull result detected sender=${shortSharingId(identity.senderId)} unauthorizedSenders=${unauthorizedSenderIds.joinToString(",") { shortSharingId(it) }} forcingAclResync=true"
        )
        ensureAclUpToDate(identity, forceRefresh = true)
            .onFailure { throwable ->
                Log.w(
                    SHARING_DEBUG_TAG,
                    "Forced ACL resync after unauthorized pull failed sender=${shortSharingId(identity.senderId)} error=${throwable.message}",
                    throwable
                )
            }
    }

    private fun decodeAndValidatePulledClaim(
        recordSenderId: String,
        envelope: PushEnvelopeSigned,
        contact: PeerContactState,
        identity: SharingIdentityState,
        localCrypto: SharingCrypto
    ): Result<LocationClaimV1> {
        return runCatching {
            if (envelope.envelope.payloadVersion != SharingVersions.PAYLOAD_VERSION) {
                error("Unsupported payload version ${envelope.envelope.payloadVersion}.")
            }

            val unsignedBytes = canonicalPushMessage(envelope.envelope)
            val signatureValid = localCrypto.verify(
                signPublicKeySpkiB64Url = contact.signPublicKeySpkiB64Url,
                message = unsignedBytes,
                signature = envelope.signatureB64Url.base64UrlDecode()
            )
            if (!signatureValid) {
                error("Sender signature verification failed.")
            }

            val myCiphertext = envelope.envelope.recipientCiphertexts
                .firstOrNull { it.recipientId == identity.senderId }
                ?.ciphertextB64Url
                ?.base64UrlDecode()
            if (myCiphertext == null) {
                error("No ciphertext for current identity.")
            }

            val contextInfo = buildPushContext(
                senderId = envelope.envelope.senderId,
                recipientId = identity.senderId,
                seq = envelope.envelope.seq
            )
            val plaintext = runCatching {
                localCrypto.decryptForIdentity(identity, myCiphertext, contextInfo)
            }.getOrNull() ?: error("Payload decryption failed.")

            val claim = runCatching {
                json.decodeFromString(LocationClaimV1.serializer(), plaintext.toString(Charsets.UTF_8))
            }.getOrNull()
            if (claim == null || !SharingLogic.isValidClaim(claim)) {
                error("Invalid claim payload.")
            }

            if (claim.senderId != recordSenderId || claim.seq != envelope.envelope.seq) {
                error("Sender/sequence mismatch.")
            }
            if (claim.version != envelope.envelope.payloadVersion) {
                error("Payload version mismatch.")
            }
            claim
        }
    }

    private suspend fun handleLocationEvent(event: LocationSampleEvent, forceImmediate: Boolean = false) {
        val ineligibilityReason = pushIneligibilityReason(event, forceImmediate = forceImmediate)
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
        if (!forceImmediate && localSnapshot.pendingPush != null) {
            maybeLogIneligiblePush("pending_push_in_flight")
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

    private fun pushIneligibilityReason(event: LocationSampleEvent, forceImmediate: Boolean): String? {
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
        if (!forceImmediate && lastSuccess != null && System.currentTimeMillis() - lastSuccess < thresholdMs) {
            return "threshold_not_elapsed"
        }

        return null
    }

    private fun buildManualLocationSampleEvent(): LocationSampleEvent? {
        val engineState = LocationEngine.state.value
        val fix = engineState.bestPositionFix ?: return null
        val motion = engineState.bestMotionFix
        val location = fix.location
        return LocationSampleEvent(
            receivedAtMs = System.currentTimeMillis(),
            fixTimeMs = fix.fixTimeMillis,
            provider = fix.provider.ifBlank { location.provider ?: "unknown" },
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = fix.accuracyMeters,
            altitudeM = location.takeIf { it.hasAltitude() }?.altitude,
            speedMps = motion?.speedMetersPerSecond ?: location.takeIf { it.hasSpeed() }?.speed,
            bearingDeg = motion?.bearingDegrees ?: location.takeIf { it.hasBearing() }?.bearing,
            speedAccuracyMps = motion?.speedAccuracyMetersPerSecond,
            bearingAccuracyDeg = motion?.bearingAccuracyDegrees,
            engineMode = engineState.engineMode
        )
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
        val pushAttemptAt = System.currentTimeMillis()
        mutateSyncOnly {
            it.copy(
                lastPushAttemptAtMs = pushAttemptAt,
                pushRequestInFlight = true
            )
        }
        try {
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
            Log.d(
                SHARING_DEBUG_TAG,
                "Sending push sender=${shortSharingId(identity.senderId)} seq=${claim.seq} recipients=${envelope.envelope.recipientCiphertexts.size}"
            )

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
        } finally {
            mutateSyncOnly { it.copy(pushRequestInFlight = false) }
        }
    }

    private suspend fun ensureAclUpToDate(
        identity: SharingIdentityState,
        forceRefresh: Boolean = false
    ): Result<Unit> {
        return aclSyncMutex.withLock {
            val localRelay = relayApi ?: return@withLock Result.failure(IllegalStateException("Relay unavailable."))
            val localCrypto = crypto ?: return@withLock Result.failure(IllegalStateException("Crypto unavailable."))

            val receiverIds = snapshot.contacts.filter { it.canReceiveFromMe }.map { it.senderId }.sorted()
            val digest = digestReceivers(receiverIds)
            val now = System.currentTimeMillis()
            val lastSyncedAtMs = snapshot.sync.lastAclSyncedAtMs
            val withinRefreshWindow = lastSyncedAtMs != null && now - lastSyncedAtMs < aclReassertIntervalMs
            if (!forceRefresh &&
                snapshot.sync.lastAclDigest == digest &&
                snapshot.sync.lastAclSeq > 0L &&
                withinRefreshWindow
            ) {
                Log.d(
                    SHARING_DEBUG_TAG,
                    "ACL up to date sender=${shortSharingId(identity.senderId)} aclSeq=${snapshot.sync.lastAclSeq} receivers=${receiverIds.size}"
                )
                return@withLock Result.success(Unit)
            }
            if (!forceRefresh &&
                snapshot.sync.lastAclDigest == digest &&
                snapshot.sync.lastAclSeq > 0L &&
                !withinRefreshWindow
            ) {
                Log.d(
                    SHARING_DEBUG_TAG,
                    "ACL refresh window elapsed sender=${shortSharingId(identity.senderId)} lastSyncedAtMs=$lastSyncedAtMs receivers=${receiverIds.size}"
                )
            }

            var aclSeq = initialAclSeqCandidate()
            var lastFailure: Throwable? = null
            repeat(maxAclUpsertAttempts) { attemptIndex ->
                val attempt = attemptAclUpsert(
                    identity = identity,
                    receiverIds = receiverIds,
                    digest = digest,
                    aclSeq = aclSeq,
                    localRelay = localRelay,
                    localCrypto = localCrypto
                )
                if (attempt.isSuccess) {
                    return@withLock Result.success(Unit)
                }
                val failure = attempt.exceptionOrNull()
                lastFailure = failure
                if (!isAclSeqConflictError(failure)) {
                    return@withLock Result.failure(failure ?: IllegalStateException("ACL upsert failed"))
                }
                val nextAclSeq = nextAclSeqAfterConflict(aclSeq)
                Log.w(
                    SHARING_DEBUG_TAG,
                    "ACL seq conflict sender=${shortSharingId(identity.senderId)} attemptedAclSeq=$aclSeq retry=${attemptIndex + 1}/$maxAclUpsertAttempts nextAclSeq=$nextAclSeq"
                )
                aclSeq = nextAclSeq
            }
            Result.failure(lastFailure ?: IllegalStateException("ACL upsert failed after retries"))
        }
    }

    private suspend fun attemptAclUpsert(
        identity: SharingIdentityState,
        receiverIds: List<String>,
        digest: String,
        aclSeq: Long,
        localRelay: RelayApi,
        localCrypto: SharingCrypto
    ): Result<Unit> {
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
                        current.copy(
                            sync = current.sync.copy(
                                lastAclSeq = aclSeq,
                                lastAclDigest = digest,
                                lastAclSyncedAtMs = System.currentTimeMillis()
                            )
                        )
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
                val retryAttemptAt = System.currentTimeMillis()
                mutateSyncOnly {
                    it.copy(
                        lastPushAttemptAtMs = retryAttemptAt,
                        pushRequestInFlight = true
                    )
                }
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
                mutateSyncOnly { it.copy(pushRequestInFlight = false) }
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

    private fun isAclSeqConflictError(throwable: Throwable?): Boolean {
        val relayHttp = throwable as? RelayHttpException
        if (relayHttp?.statusCode == 409 &&
            relayHttp.responseBody.contains("aclSeq must increase monotonically", ignoreCase = true)
        ) {
            return true
        }
        val message = throwable?.message ?: return false
        return message.contains("aclSeq must increase monotonically", ignoreCase = true)
    }

    private fun initialAclSeqCandidate(): Long {
        val currentSync = snapshot.sync
        val nextFromState = currentSync.lastAclSeq + 1L
        return if (currentSync.lastAclSeq == 0L) {
            maxOf(nextFromState, currentSync.nextSeq, System.currentTimeMillis())
        } else {
            maxOf(nextFromState, currentSync.nextSeq)
        }
    }

    private fun nextAclSeqAfterConflict(currentAttempt: Long): Long {
        val plusOne = if (currentAttempt < Long.MAX_VALUE) currentAttempt + 1L else currentAttempt
        val doubled = if (currentAttempt <= Long.MAX_VALUE / 2L) currentAttempt * 2L else currentAttempt
        return maxOf(plusOne, doubled, System.currentTimeMillis())
    }

    private fun normalizeContactsImportError(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        val looksLikeWrongPassphrase =
            message.contains("BAD_DECRYPT", ignoreCase = true) ||
                message.contains("Tag mismatch", ignoreCase = true) ||
                message.contains("mac check in GCM failed", ignoreCase = true)
        return if (looksLikeWrongPassphrase) {
            "Contacts import failed: incorrect passphrase or corrupted bundle."
        } else {
            message.ifBlank { "Contacts import failed." }
        }
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
        if (message.isBlank()) return
        if (lastInfo == message && lastError == null) {
            return
        }
        Log.d(SHARING_DEBUG_TAG, "INFO $message")
        lastInfo = message
        if (lastError != null) {
            lastError = null
        }
        publishState()
    }

    private fun setError(message: String) {
        if (message.isBlank()) return
        if (lastError == message) {
            return
        }
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
            sync = localSnapshot.sync.copy(pollingActive = shouldRunPollingLoop()),
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

    private fun normalizeRelayBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!trimmed.startsWith("https://")) {
            return DEFAULT_RELAY_BASE_URL
        }
        val parsed = runCatching { trimmed.toUri() }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank()) {
            return DEFAULT_RELAY_BASE_URL
        }
        return trimmed
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
