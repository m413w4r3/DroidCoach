package dev.atlas.coach.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.atlas.coach.sync.v4.CoreV4Category
import dev.atlas.coach.sync.v4.CoreV4ConsentSnapshot
import dev.atlas.coach.sync.v4.CoreV4ConsentState
import dev.atlas.coach.sync.v4.CoreV4ProjectionRequest
import dev.atlas.coach.sync.v4.CoreV4Receipt
import dev.atlas.coach.sync.v4.CoreV4ConsentReceipt
import dev.atlas.coach.sync.v4.CoreV4SyncClient
import dev.atlas.coach.sync.v4.CoreV4SyncException
import dev.atlas.coach.sync.v4.CoreV4FailureKind
import java.nio.charset.StandardCharsets
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** App-private token storage configured by the settings integration; values are never logged. */
class SharedPreferencesBearerTokenStore(context: Context) : BearerTokenProvider {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun token(): String? = runCatching {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            .trim()
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    fun configureToken(token: String?) {
        val normalized = token?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length <= MAX_TOKEN_LENGTH) {
            "Bearer token is too long"
        }
        preferences.edit().apply {
            if (normalized == null) {
                remove(TOKEN_KEY)
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
                putString(
                    TOKEN_KEY,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                        Base64.encodeToString(encrypted, Base64.NO_WRAP),
                )
            }
        }.commit()
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "atlas_sync_configuration"
        const val TOKEN_KEY = "backend_bearer_token"
        private const val MAX_TOKEN_LENGTH = 4096
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "atlas_backend_bearer_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

/** Serializes V4 read-modify-write operations inside this process only. */
private val CORE_V4_STATE_LOCK = Any()

object AndroidSyncStorage {
    /** Cached per process; neither this singleton nor its lock coordinates other app processes. */
    @Volatile private var processV4Store: AndroidCoreV4SyncStore? = null

    fun outbox(context: Context): EventOutbox = DurableEventOutbox(
        FileOutboxStorage(File(context.applicationContext.filesDir, "atlas_sync_outbox_v1.json")),
    )

    fun settings(context: Context): AndroidSyncSettingsStore = AndroidSyncSettingsStore(context)

    fun v4(context: Context): AndroidCoreV4SyncStore = processV4Store
        ?: synchronized(CORE_V4_STATE_LOCK) {
            processV4Store ?: AndroidCoreV4SyncStore(context.applicationContext).also { processV4Store = it }
        }
}

data class PersistedSyncSettings(
    val baseUrl: String = "",
    val selectedCategories: Set<SyncDataCategory> = emptySet(),
)

/** Private non-secret backend configuration; bearer credentials live in the Keystore-backed store above. */
class AndroidSyncSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): PersistedSyncSettings = PersistedSyncSettings(
        baseUrl = preferences.getString(BASE_URL_KEY, "").orEmpty(),
        selectedCategories = preferences.getStringSet(CATEGORIES_KEY, emptySet()).orEmpty()
            .mapNotNull { stored -> SyncDataCategory.entries.firstOrNull { it.wireValue == stored } }
            .toSet(),
    )

    fun write(baseUrl: String, selectedCategories: Set<SyncDataCategory>) {
        preferences.edit(commit = true) {
            putString(BASE_URL_KEY, baseUrl.trim())
            putStringSet(CATEGORIES_KEY, selectedCategories.mapTo(mutableSetOf(), SyncDataCategory::wireValue))
        }
    }

    companion object {
        const val PREFERENCES_NAME = "atlas_sync_preferences"
        private const val BASE_URL_KEY = "backend_base_url"
        private const val CATEGORIES_KEY = "consented_categories"
    }
}

data class CoreV4ConsentRecord(
    val snapshot: CoreV4ConsentSnapshot,
    val requestId: String,
    val idempotencyKey: String,
    val sentAt: String,
)

data class CoreV4PendingProjection(
    val sourceKey: String,
    val sourceVersion: Int,
    val sourceFingerprint: String,
    val request: CoreV4ProjectionRequest,
    val body: String,
)

data class CoreV4StoredReceipt(
    val category: CoreV4Category,
    val version: Int,
    val sourceFingerprint: String,
    val resultCode: String,
    val lastSyncedAt: String,
    val observedAt: String,
    val freshness: String,
)

data class CoreV4SyncPresentation(
    val code: String,
    val message: String,
    val updatedAt: String?,
    val pendingCount: Int,
)

internal interface CoreV4SyncPersistence {
    fun read(): String?
    fun write(value: String)
}

private class SharedPreferencesCoreV4SyncPersistence(context: Context) : CoreV4SyncPersistence {
    private val preferences = context.applicationContext
        .getSharedPreferences(AndroidCoreV4SyncStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(CORE_V4_STATE_KEY, null)

    override fun write(value: String) {
        check(preferences.edit().putString(CORE_V4_STATE_KEY, value).commit()) {
            "Unable to persist V4 sync state"
        }
    }
}

/**
 * Versioned V4 state is isolated from the V1 outbox. Exact request bodies stay local
 * for retries. Revoked status requests remain pending with their sequence so they can
 * be replayed idempotently after an explicit regrant; the lock protects this JSON
 * read-modify-write only within the current process.
 */
class AndroidCoreV4SyncStore internal constructor(
    private val persistence: CoreV4SyncPersistence,
) {
    constructor(context: Context) : this(SharedPreferencesCoreV4SyncPersistence(context))

    internal fun <T> withExclusiveState(action: () -> T): T = withCoreV4StateLock(action)

    fun consents(): List<CoreV4ConsentRecord> = withCoreV4StateLock {
        CoreV4Category.entries.map(::consent)
    }

    fun consent(category: CoreV4Category): CoreV4ConsentRecord = withCoreV4StateLock {
        val root = readRoot()
        val rows = root.optJSONObject(KEY_CONSENTS) ?: JSONObject()
        val saved = rows.optJSONObject(category.name)
        if (saved != null) return@withCoreV4StateLock saved.toConsent(category)
        val created = JSONObject()
            .put("version", 1)
            .put("state", CoreV4ConsentState.UNKNOWN.name)
            .put("synced_version", 0)
            .put("request_id", UUID.randomUUID().toString())
            .put("idempotency_key", UUID.randomUUID().toString())
            .put("sent_at", Instant.now().toString())
        rows.put(category.name, created)
        root.put(KEY_CONSENTS, rows)
        writeRoot(root)
        created.toConsent(category)
    }

    /** A local category change is durable before any work is scheduled. */
    fun setConsent(category: CoreV4Category, granted: Boolean): CoreV4ConsentRecord = withCoreV4StateLock {
        val current = consent(category)
        val nextState = if (granted) CoreV4ConsentState.GRANTED else CoreV4ConsentState.REVOKED
        if (current.snapshot.state == nextState) return@withCoreV4StateLock current
        check(current.snapshot.version < Int.MAX_VALUE) { "V4 consent version is exhausted" }
        val root = readRoot()
        val rows = root.optJSONObject(KEY_CONSENTS) ?: JSONObject()
        val next = JSONObject()
            .put("version", current.snapshot.version + 1)
            .put("state", nextState.name)
            .put("synced_version", current.snapshot.syncedVersion)
            .put("request_id", UUID.randomUUID().toString())
            .put("idempotency_key", UUID.randomUUID().toString())
            .put("sent_at", Instant.now().toString())
        rows.put(category.name, next)
        root.put(KEY_CONSENTS, rows)
        if (!granted) {
            val pending = root.optJSONObject(KEY_PENDING) ?: JSONObject()
            if (category == CoreV4Category.ACTIVITY_STATUS) {
                val pendingEventIds = pending.keys().asSequence()
                    .filter { pending.optJSONObject(it)?.optString("category") == category.name }
                    .mapNotNull { it.takeIf { key -> key.startsWith("status:") }?.removePrefix("status:") }
                    .toSet()
                val receipts = root.optJSONObject(KEY_RECEIPTS) ?: JSONObject()
                val sequences = root.optJSONObject(KEY_STATUS_SEQUENCES) ?: JSONObject()
                val acceptedEventIds = receipts.keys().asSequence()
                    .filter { it.startsWith("status:") }
                    .filter { receipts.optJSONObject(it)?.optString("category") == category.name }
                    .map { it.removePrefix("status:") }
                    .toSet()
                sequences.keys().asSequence().toList().forEach { eventId ->
                    if (eventId !in acceptedEventIds && eventId !in pendingEventIds) sequences.remove(eventId)
                }
                root.put(KEY_STATUS_SEQUENCES, sequences)
                val highestAccepted = receipts.keys().asSequence()
                    .mapNotNull { receipts.optJSONObject(it) }
                    .filter { it.optString("category") == category.name }
                    .maxOfOrNull { it.optInt("version").toLong() } ?: 0L
                val highestPending = pendingEventIds.maxOfOrNull { eventId ->
                    pending.optJSONObject("status:$eventId")?.optInt("source_version")?.toLong() ?: 0L
                } ?: 0L
                val highestReserved = sequences.keys().asSequence()
                    .maxOfOrNull { sequences.optLong(it, 0L) } ?: 0L
                root.put(KEY_STATUS_SEQUENCE, maxOf(highestAccepted, highestPending, highestReserved))
            } else {
                pending.keys().asSequence().toList().forEach { key ->
                    if (pending.optJSONObject(key)?.optString("category") == category.name) pending.remove(key)
                }
            }
            root.put(KEY_PENDING, pending)
        }
        writeRoot(root)
        next.toConsent(category)
    }

    fun pendingConsentRecords(): List<CoreV4ConsentRecord> = withCoreV4StateLock {
        consents().filter {
            it.snapshot.state != CoreV4ConsentState.UNKNOWN &&
                it.snapshot.syncedVersion < it.snapshot.version
        }
    }

    fun prepareConsentBody(record: CoreV4ConsentRecord, client: CoreV4SyncClient): String = withCoreV4StateLock {
        val root = readRoot()
        val pending = root.optJSONObject(KEY_PENDING_CONSENTS) ?: JSONObject()
        val existing = pending.optJSONObject(record.snapshot.category.name)
        if (existing != null && existing.optInt("version") == record.snapshot.version) {
            existing.getString("body")
        } else {
            val body = client.consentBody(
                category = record.snapshot.category,
                version = record.snapshot.version,
                state = record.snapshot.state,
                requestId = record.requestId,
                idempotencyKey = record.idempotencyKey,
                sentAt = record.sentAt,
            )
            pending.put(record.snapshot.category.name, JSONObject()
                .put("version", record.snapshot.version)
                .put("body", body))
            root.put(KEY_PENDING_CONSENTS, pending)
            writeRoot(root)
            body
        }
    }

    fun acknowledgeConsent(category: CoreV4Category, receipt: CoreV4ConsentReceipt) = withCoreV4StateLock {
        val root = readRoot()
        val rows = root.optJSONObject(KEY_CONSENTS) ?: JSONObject()
        val current = rows.optJSONObject(category.name) ?: return@withCoreV4StateLock
        if (current.optInt("version") == receipt.version &&
            current.optString("state") == receipt.state.name &&
            receipt.category == category
        ) {
            current.put("synced_version", receipt.version)
            current.put("last_synced_at", receipt.updatedAt)
            current.put("last_result_code", receipt.resultCode)
            current.put("last_replayed", receipt.replayed)
            rows.put(category.name, current)
            root.put(KEY_CONSENTS, rows)
            root.optJSONObject(KEY_PENDING_CONSENTS)?.remove(category.name)
            root.put(KEY_STATUS_CODE, "SYNCED")
            root.put(KEY_STATUS_MESSAGE, "Consentements V4 synchronisés.")
            root.put(KEY_STATUS_AT, receipt.updatedAt)
            writeRoot(root)
        }
    }

    /**
     * Returns a durable request for this source revision. Snapshot retries read the stored
     * body verbatim. Reusing an already used source version with changed content is rejected.
     */
    fun prepareProjection(
        sourceKey: String,
        sourceVersion: Int,
        sourceFingerprint: String,
        category: CoreV4Category,
        consentVersion: Int,
        data: JSONObject,
        client: CoreV4SyncClient,
        nowUtc: String,
    ): CoreV4PendingProjection? = withCoreV4StateLock {
        require(sourceKey.isNotBlank() && sourceKey.length <= 220)
        require(sourceVersion >= 1 && consentVersion >= 1)
        require(sourceFingerprint.matches(HEX_256))
        val root = readRoot()
        val receipts = root.optJSONObject(KEY_RECEIPTS) ?: JSONObject()
        val previousReceipt = receipts.optJSONObject(sourceKey)
        if (previousReceipt != null) {
            val acknowledgedVersion = previousReceipt.optInt("version")
            if (sourceVersion < acknowledgedVersion) return@withCoreV4StateLock null
            if (sourceVersion == acknowledgedVersion) {
                if (previousReceipt.optString("source_fingerprint") != sourceFingerprint) {
                    throw CoreV4SyncException(CoreV4FailureKind.CONFLICT, false, errorCode = "same_version_changed")
                }
                return@withCoreV4StateLock null
            }
        }
        val pending = root.optJSONObject(KEY_PENDING) ?: JSONObject()
        val existing = pending.optJSONObject(sourceKey)
        if (existing != null) {
            val oldVersion = existing.optInt("source_version")
            if (sourceVersion < oldVersion) return@withCoreV4StateLock null
            if (sourceVersion == oldVersion) {
                // Preserve the exact first body if local content changed while this revision
                // was pending; a retry must never pair its idempotency key with new bytes.
                return@withCoreV4StateLock existing.toPending(sourceKey)
            }
            pending.remove(sourceKey)
        }
        val request = CoreV4ProjectionRequest(
            category = category,
            consentVersion = consentVersion,
            requestId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            sentAt = nowUtc,
            data = data,
        )
        val body = client.projectionBody(request)
        val usedKeys = root.optJSONObject(KEY_USED_KEYS) ?: JSONObject()
        val bodyHash = sha256(body)
        val usedHash = usedKeys.optString(request.idempotencyKey, "")
        if (usedHash.isNotEmpty() && usedHash != bodyHash) {
            throw CoreV4SyncException(CoreV4FailureKind.IDEMPOTENCY_CONFLICT, false)
        }
        usedKeys.put(request.idempotencyKey, bodyHash)
        pending.put(sourceKey, JSONObject()
            .put("category", category.name)
            .put("source_version", sourceVersion)
            .put("source_fingerprint", sourceFingerprint)
            .put("consent_version", consentVersion)
            .put("body", body))
        root.put(KEY_USED_KEYS, usedKeys)
        root.put(KEY_PENDING, pending)
        writeRoot(root)
        CoreV4PendingProjection(sourceKey, sourceVersion, sourceFingerprint, request, body)
    }

    fun acknowledgeProjection(
        pendingRequest: CoreV4PendingProjection,
        receipt: CoreV4Receipt,
        observedAt: String,
    ) = withCoreV4StateLock {
        val root = readRoot()
        val requests = root.optJSONObject(KEY_PENDING) ?: JSONObject()
        val current = requests.optJSONObject(pendingRequest.sourceKey)
        if (current?.optString("body") == pendingRequest.body) requests.remove(pendingRequest.sourceKey)
        val sourceFreshness = if (receipt.category == CoreV4Category.TRAINING_PLAN) {
            runCatching {
                JSONObject(pendingRequest.body).getJSONObject("data").getString("sync_state")
            }.getOrDefault("UNKNOWN").takeIf { it in setOf("FRESH", "STALE", "UNKNOWN") } ?: "UNKNOWN"
        } else {
            "FRESH"
        }
        val receipts = root.optJSONObject(KEY_RECEIPTS) ?: JSONObject()
        receipts.put(pendingRequest.sourceKey, JSONObject()
            .put("category", receipt.category.name)
            .put("version", pendingRequest.sourceVersion)
            .put("backend_version", receipt.version)
            .put("source_fingerprint", pendingRequest.sourceFingerprint)
            .put("result_code", receipt.resultCode)
            .put("last_synced_at", receipt.lastSyncedAt)
            .put("observed_at", observedAt)
            .put("freshness", sourceFreshness)
            .put("snapshot_hash", receipt.snapshotHash ?: JSONObject.NULL))
        root.put(KEY_PENDING, requests)
        root.put(KEY_RECEIPTS, receipts)
        val freshnessRows = root.optJSONObject(KEY_FRESHNESS) ?: JSONObject()
        freshnessRows.put(receipt.category.name, JSONObject()
            .put("state", sourceFreshness)
            .put("observed_at", observedAt)
            .put("last_synced_at", receipt.lastSyncedAt))
        root.put(KEY_FRESHNESS, freshnessRows)
        root.put(KEY_STATUS_CODE, "SYNCED")
        root.put(KEY_STATUS_MESSAGE, "Projection V4 synchronisée.")
        root.put(KEY_STATUS_AT, receipt.lastSyncedAt)
        writeRoot(root)
    }

    fun receipt(sourceKey: String): CoreV4StoredReceipt? =
        withCoreV4StateLock {
            readRoot().optJSONObject(KEY_RECEIPTS)?.optJSONObject(sourceKey)?.let { row ->
                CoreV4StoredReceipt(
                    category = CoreV4Category.valueOf(row.getString("category")),
                    version = row.getInt("version"),
                    sourceFingerprint = row.getString("source_fingerprint"),
                    resultCode = row.getString("result_code"),
                    lastSyncedAt = row.getString("last_synced_at"),
                    observedAt = row.getString("observed_at"),
                    freshness = row.optString("freshness", "UNKNOWN"),
                )
            }
        }

    fun pendingProjections(category: CoreV4Category): List<CoreV4PendingProjection> = withCoreV4StateLock {
        val pending = readRoot().optJSONObject(KEY_PENDING) ?: return@withCoreV4StateLock emptyList()
        pending.keys().asSequence()
            .mapNotNull { key ->
                pending.optJSONObject(key)
                    ?.takeIf { it.optString("category") == category.name }
                    ?.let { it.toPending(key) }
            }
            .sortedBy { it.request.data.optLong("sequence", Long.MAX_VALUE) }
            .toList()
    }

    fun hasPendingProjection(sourceKey: String, body: String): Boolean = withCoreV4StateLock {
        readRoot().optJSONObject(KEY_PENDING)?.optJSONObject(sourceKey)?.optString("body") == body
    }

    /**
     * A stale status body may be replaced only after the backend definitively rejected it.
     * The event id, source sequence, fingerprint, and event data remain unchanged.
     */
    fun replaceRejectedStatusProjection(
        rejected: CoreV4PendingProjection,
        consentVersion: Int,
        client: CoreV4SyncClient,
        nowUtc: String,
    ): CoreV4PendingProjection = withCoreV4StateLock {
        require(rejected.request.category == CoreV4Category.ACTIVITY_STATUS)
        require(consentVersion > rejected.request.consentVersion)
        val root = readRoot()
        val pending = root.optJSONObject(KEY_PENDING) ?: JSONObject()
        val current = pending.optJSONObject(rejected.sourceKey)
            ?: throw CoreV4SyncException(CoreV4FailureKind.CONFLICT, false, errorCode = "status_pending_missing")
        check(current.optString("body") == rejected.body) { "Status body changed before definitive rejection." }
        check(current.optInt("source_version") == rejected.sourceVersion)
        val request = CoreV4ProjectionRequest(
            category = CoreV4Category.ACTIVITY_STATUS,
            consentVersion = consentVersion,
            requestId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            sentAt = nowUtc,
            data = JSONObject(rejected.request.data.toString()),
        )
        val body = client.projectionBody(request)
        val usedKeys = root.optJSONObject(KEY_USED_KEYS) ?: JSONObject()
        val bodyHash = sha256(body)
        val usedHash = usedKeys.optString(request.idempotencyKey, "")
        if (usedHash.isNotEmpty() && usedHash != bodyHash) {
            throw CoreV4SyncException(CoreV4FailureKind.IDEMPOTENCY_CONFLICT, false)
        }
        usedKeys.put(request.idempotencyKey, bodyHash)
        pending.put(rejected.sourceKey, JSONObject()
            .put("category", CoreV4Category.ACTIVITY_STATUS.name)
            .put("source_version", rejected.sourceVersion)
            .put("source_fingerprint", rejected.sourceFingerprint)
            .put("consent_version", consentVersion)
            .put("body", body))
        root.put(KEY_USED_KEYS, usedKeys)
        root.put(KEY_PENDING, pending)
        writeRoot(root)
        CoreV4PendingProjection(rejected.sourceKey, rejected.sourceVersion, rejected.sourceFingerprint, request, body)
    }

    /** Discard a status only after a backend response proves that this event was not accepted. */
    fun discardRejectedStatusProjection(
        rejected: CoreV4PendingProjection,
        errorCode: String,
        sequenceConsumedOrUnknown: Boolean = false,
    ) = withCoreV4StateLock {
        require(rejected.request.category == CoreV4Category.ACTIVITY_STATUS)
        require(errorCode.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        val root = readRoot()
        val pending = root.optJSONObject(KEY_PENDING) ?: return@withCoreV4StateLock
        val current = pending.optJSONObject(rejected.sourceKey) ?: return@withCoreV4StateLock
        if (current.optString("body") != rejected.body) return@withCoreV4StateLock
        val receipts = root.optJSONObject(KEY_RECEIPTS) ?: JSONObject()
        if (receipts.has(rejected.sourceKey)) return@withCoreV4StateLock
        pending.remove(rejected.sourceKey)
        root.put(KEY_PENDING, pending)
        val eventId = rejected.sourceKey.removePrefix("status:")
        val rejectedEvents = root.optJSONObject(KEY_REJECTED_STATUSES) ?: JSONObject()
        rejectedEvents.put(eventId, errorCode)
        root.put(KEY_REJECTED_STATUSES, rejectedEvents)
        val sequences = root.optJSONObject(KEY_STATUS_SEQUENCES) ?: JSONObject()
        if (sequenceConsumedOrUnknown) {
            sequences.put(eventId, maxOf(sequences.optLong(eventId, 0L), rejected.sourceVersion.toLong()))
        } else {
            sequences.remove(eventId)
        }
        root.put(KEY_STATUS_SEQUENCES, sequences)
        val highestAccepted = receipts.keys().asSequence()
            .mapNotNull { receipts.optJSONObject(it) }
            .filter { it.optString("category") == CoreV4Category.ACTIVITY_STATUS.name }
            .maxOfOrNull { it.optInt("version").toLong() } ?: 0L
        val highestPending = pending.keys().asSequence()
            .mapNotNull { pending.optJSONObject(it) }
            .filter { it.optString("category") == CoreV4Category.ACTIVITY_STATUS.name }
            .maxOfOrNull { it.optInt("source_version").toLong() } ?: 0L
        val highestReserved = sequences.keys().asSequence().maxOfOrNull { sequences.optLong(it, 0L) } ?: 0L
        root.put(KEY_STATUS_SEQUENCE, maxOf(highestAccepted, highestPending, highestReserved))
        writeRoot(root)
    }

    fun isStatusEventRejected(eventId: String): Boolean = withCoreV4StateLock {
        readRoot().optJSONObject(KEY_REJECTED_STATUSES)?.has(eventId) == true
    }

    fun latestPlanReceiptVersion(planId: String, consentVersion: Int): Int? = withCoreV4StateLock {
        val sourceKey = "plan:$planId:consent:$consentVersion"
        val receipts = readRoot().optJSONObject(KEY_RECEIPTS) ?: return@withCoreV4StateLock null
        receipts.optJSONObject(sourceKey)
            ?.takeIf { it.optString("category") == CoreV4Category.DAY_PLAN.name }
            ?.optInt("version")
            ?.takeIf { it > 0 }
    }

    fun nextSourceVersion(sourceKey: String, fingerprint: String): Int = withCoreV4StateLock {
        require(fingerprint.matches(HEX_256))
        val root = readRoot()
        val versions = root.optJSONObject(KEY_SOURCE_VERSIONS) ?: JSONObject()
        val prior = versions.optJSONObject(sourceKey)
        val version = when {
            prior == null -> 1
            prior.optString("fingerprint") == fingerprint -> prior.optInt("version").coerceAtLeast(1)
            else -> prior.optInt("version").coerceAtLeast(0) + 1
        }
        versions.put(sourceKey, JSONObject().put("fingerprint", fingerprint).put("version", version))
        root.put(KEY_SOURCE_VERSIONS, versions)
        writeRoot(root)
        version
    }

    fun sequenceForStatusEvent(eventId: String): Long = withCoreV4StateLock {
        CoreV4SyncClient.requireUuid(eventId, "event_id")
        val root = readRoot()
        val sequences = root.optJSONObject(KEY_STATUS_SEQUENCES) ?: JSONObject()
        val eventKey = "status:" + eventId
        val receipts = root.optJSONObject(KEY_RECEIPTS) ?: JSONObject()
        val accepted = receipts.optJSONObject(eventKey)
        if (accepted?.optString("category") == CoreV4Category.ACTIVITY_STATUS.name) {
            return@withCoreV4StateLock accepted.optInt("version").toLong()
        }
        val pending = root.optJSONObject(KEY_PENDING) ?: JSONObject()
        val pendingStatus = pending.optJSONObject(eventKey)
        if (pendingStatus?.optString("category") == CoreV4Category.ACTIVITY_STATUS.name) {
            return@withCoreV4StateLock pendingStatus.optInt("source_version").toLong()
        }
        if (root.optJSONObject(KEY_REJECTED_STATUSES)?.has(eventId) == true) {
            return@withCoreV4StateLock 0L
        }
        val reserved = sequences.optLong(eventId, 0L)
        if (reserved > 0L) return@withCoreV4StateLock reserved
        sequences.remove(eventId)
        val highestAccepted = receipts.keys().asSequence()
            .mapNotNull { receipts.optJSONObject(it) }
            .filter { it.optString("category") == CoreV4Category.ACTIVITY_STATUS.name }
            .maxOfOrNull { it.optInt("version").toLong() } ?: 0L
        val highestPending = pending.keys().asSequence()
            .mapNotNull { pending.optJSONObject(it) }
            .filter { it.optString("category") == CoreV4Category.ACTIVITY_STATUS.name }
            .maxOfOrNull { row ->
                runCatching {
                    JSONObject(row.getString("body")).getJSONObject("data").getLong("sequence")
                }.getOrDefault(0L)
            }
            ?: 0L
        val highestReserved = sequences.keys().asSequence().maxOfOrNull { sequences.optLong(it, 0L) } ?: 0L
        val next = maxOf(highestAccepted, highestPending, highestReserved, root.optLong(KEY_STATUS_SEQUENCE, 0L)) + 1L
        if (next > Int.MAX_VALUE.toLong()) {
            throw CoreV4SyncException(CoreV4FailureKind.PROTOCOL, false, errorCode = "sequence_limit")
        }
        sequences.put(eventId, next)
        root.put(KEY_STATUS_SEQUENCES, sequences)
        root.put(KEY_STATUS_SEQUENCE, next)
        writeRoot(root)
        next
    }

    fun purgePendingProjection(category: CoreV4Category) = withCoreV4StateLock {
        require(category != CoreV4Category.ACTIVITY_STATUS) {
            "Status requests reserve a global sequence and cannot be purged locally."
        }
        val root = readRoot()
        val pending = root.optJSONObject(KEY_PENDING) ?: return@withCoreV4StateLock
        pending.keys().asSequence().toList().forEach { key ->
            if (pending.optJSONObject(key)?.optString("category") == category.name) pending.remove(key)
        }
        root.put(KEY_PENDING, pending)
        writeRoot(root)
    }

    fun recordFailure(code: String, message: String) = withCoreV4StateLock {
        val root = readRoot()
        root.put(KEY_STATUS_CODE, code.take(40))
        root.put(KEY_STATUS_MESSAGE, message.take(160))
        root.put(KEY_STATUS_AT, Instant.now().toString())
        writeRoot(root)
    }

    fun recordSourceState(category: CoreV4Category, freshness: String, message: String) = withCoreV4StateLock {
        require(freshness in setOf("STALE", "UNAVAILABLE", "MISMATCH", "UNKNOWN"))
        val root = readRoot()
        root.put(KEY_STATUS_CODE, freshness)
        root.put(KEY_STATUS_MESSAGE, message.take(160))
        root.put(KEY_STATUS_AT, Instant.now().toString())
        val freshnessRows = root.optJSONObject(KEY_FRESHNESS) ?: JSONObject()
        freshnessRows.put(category.name, JSONObject()
            .put("state", freshness)
            .put("observed_at", Instant.now().toString()))
        root.put(KEY_FRESHNESS, freshnessRows)
        writeRoot(root)
    }

    fun presentation(): CoreV4SyncPresentation = withCoreV4StateLock {
        val root = readRoot()
        val pendingCount = root.optJSONObject(KEY_PENDING)?.length().orZero() +
            root.optJSONObject(KEY_PENDING_CONSENTS)?.length().orZero()
        CoreV4SyncPresentation(
            code = root.optString(KEY_STATUS_CODE, "LOCAL_ONLY"),
            message = root.optString(KEY_STATUS_MESSAGE, "Le push Core V4 est désactivé."),
            updatedAt = root.optString(KEY_STATUS_AT).takeIf(String::isNotBlank),
            pendingCount = pendingCount,
        )
    }

    fun freshness(category: CoreV4Category): String = withCoreV4StateLock {
        val root = readRoot()
        val state = root.optJSONObject(KEY_FRESHNESS)?.optJSONObject(category.name)
        if (state != null) {
            val label = state.optString("state", "UNKNOWN")
            val timestamp = state.optString("last_synced_at").takeIf(String::isNotBlank)
                ?: state.optString("observed_at").takeIf(String::isNotBlank)
            return@withCoreV4StateLock if (timestamp == null) label else "$label · $timestamp"
        }
        val receipts = root.optJSONObject(KEY_RECEIPTS) ?: return@withCoreV4StateLock "Aucun reçu"
        val latest = receipts.keys().asSequence()
            .mapNotNull { receipts.optJSONObject(it) }
            .filter { it.optString("category") == category.name }
            .maxByOrNull { it.optString("last_synced_at") }
        latest?.let { it.optString("freshness", "UNKNOWN") + " · " + it.optString("last_synced_at") }
            ?: "Aucun reçu"
    }

    private fun readRoot(): JSONObject {
        val raw = persistence.read() ?: return JSONObject().put("schema_version", SCHEMA_VERSION)
        return runCatching {
            JSONObject(raw).also { require(it.optInt("schema_version") == SCHEMA_VERSION) }
        }.getOrElse { JSONObject().put("schema_version", SCHEMA_VERSION) }
    }

    private fun writeRoot(root: JSONObject) {
        root.put("schema_version", SCHEMA_VERSION)
        persistence.write(root.toString())
    }

    private fun JSONObject.toConsent(category: CoreV4Category) = CoreV4ConsentRecord(
        snapshot = CoreV4ConsentSnapshot(
            category = category,
            version = getInt("version"),
            state = CoreV4ConsentState.valueOf(getString("state")),
            syncedVersion = optInt("synced_version", 0),
        ),
        requestId = getString("request_id"),
        idempotencyKey = getString("idempotency_key"),
        sentAt = getString("sent_at"),
    )

    private fun JSONObject.toPending(sourceKey: String): CoreV4PendingProjection {
        val body = getString("body")
        val envelope = JSONObject(body)
        val category = CoreV4Category.valueOf(getString("category"))
        val data = envelope.getJSONObject("data")
        val request = CoreV4ProjectionRequest(
            category = category,
            consentVersion = envelope.getInt("consent_version"),
            requestId = envelope.getString("request_id"),
            idempotencyKey = envelope.getString("idempotency_key"),
            sentAt = envelope.getString("sent_at"),
            data = data,
        )
        return CoreV4PendingProjection(
            sourceKey,
            getInt("source_version"),
            getString("source_fingerprint"),
            request,
            body,
        )
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFERENCES_NAME = "atlas_core_v4_sync"
        private const val KEY_CONSENTS = "consents"
        private const val KEY_PENDING_CONSENTS = "pending_consents"
        private const val KEY_PENDING = "pending_projections"
        private const val KEY_RECEIPTS = "projection_receipts"
        private const val KEY_FRESHNESS = "source_freshness"
        private const val KEY_USED_KEYS = "idempotency_body_hashes"
        private const val KEY_SOURCE_VERSIONS = "source_versions"
        private const val KEY_STATUS_SEQUENCES = "status_sequences"
        private const val KEY_STATUS_SEQUENCE = "status_sequence"
        private const val KEY_REJECTED_STATUSES = "rejected_status_events"
        private const val KEY_STATUS_CODE = "last_status_code"
        private const val KEY_STATUS_MESSAGE = "last_status_message"
        private const val KEY_STATUS_AT = "last_status_at"
        private const val SCHEMA_VERSION = 1
        private val HEX_256 = Regex("[0-9a-f]{64}")
    }
}

private inline fun <T> withCoreV4StateLock(action: () -> T): T =
    synchronized(CORE_V4_STATE_LOCK) { action() }

private const val CORE_V4_STATE_KEY = "state_v4"
