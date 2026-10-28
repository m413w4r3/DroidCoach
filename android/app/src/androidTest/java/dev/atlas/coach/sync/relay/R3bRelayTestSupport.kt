package dev.atlas.coach.sync.relay

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.RelaySettingsController
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class R3bRelayArguments(
    val relayUrl: String,
    val serverId: String,
    val serverPublicKey: String,
    val deviceTokenFile: String? = null,
) {
    val serverPublicKeyBytes: ByteArray
        get() = Base64.getDecoder().decode(serverPublicKey)
}

internal data class R3bDeviceToken(
    val deviceId: String,
    val token: String,
)

internal data class R3bA3Marker(
    val deviceId: String,
    val serverPublicKeySha256: String,
    val deviceTokenSha256: String,
    val createdAtUtcMillis: Long,
)

internal fun requireR3bRelayArguments(
    includeDeviceTokenFile: Boolean = false,
): R3bRelayArguments {
    val keys = buildList {
        add("relay_url")
        add("server_id")
        add("server_public_key")
        if (includeDeviceTokenFile) add("device_token_file")
    }
    val args = InstrumentationRegistry.getArguments()
    val missing = keys.filter { args.getString(it).isNullOrBlank() }
    Assume.assumeTrue(
        "Missing non-secret instrumentation arguments: ${missing.joinToString()}",
        missing.isEmpty(),
    )
    return R3bRelayArguments(
        relayUrl = requireNotNull(args.getString("relay_url")),
        serverId = requireNotNull(args.getString("server_id")),
        serverPublicKey = requireNotNull(args.getString("server_public_key")),
        deviceTokenFile = if (includeDeviceTokenFile) requireNotNull(args.getString("device_token_file")) else null,
    )
}

internal fun readR3bDeviceToken(context: Context, fileName: String): R3bDeviceToken {
    require(fileName == File(fileName).name && !File(fileName).isAbsolute) {
        "device_token_file must be a name inside filesDir"
    }
    val file = File(context.filesDir, fileName)
    check(file.isFile && file.canRead()) { "The private device token file is missing or unreadable" }
    val mode = Os.stat(file.absolutePath).st_mode
    val groupOrOther = OsConstants.S_IRGRP or OsConstants.S_IWGRP or OsConstants.S_IXGRP or
        OsConstants.S_IROTH or OsConstants.S_IWOTH or OsConstants.S_IXOTH
    val ownerReadWrite = OsConstants.S_IRUSR or OsConstants.S_IWUSR
    check((mode and groupOrOther) == 0 && (mode and ownerReadWrite) == ownerReadWrite) {
        "The device token file must have mode 0600"
    }

    val json = JSONObject(file.readText(Charsets.UTF_8))
    val deviceId = json.optString("device_id").trim()
    val token = json.optString("token").trim()
    check(runCatching { UUID.fromString(deviceId) }.isSuccess && token.isNotEmpty()) {
        "The private device token file does not contain a valid device identity"
    }
    return R3bDeviceToken(deviceId = deviceId, token = token)
}

internal fun deleteR3bDeviceTokenFile(context: Context, fileName: String) {
    val file = File(context.filesDir, fileName)
    check(!file.exists() || file.delete()) { "The consumed device token file could not be removed" }
}

internal fun r3bCredentialStore(context: Context): KeystoreRelayCredentialStore =
    KeystoreRelayCredentialStore(context, AndroidRelaySettingsStore(context))

internal fun awaitRelayState(
    relay: RelaySettingsController,
    expected: RelayConnectionState,
    timeoutMillis: Long,
): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var observedState: RelayConnectionState? = null
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { observedState = relay.state }
        if (observedState == expected) return true
        SystemClock.sleep(100)
    }
    instrumentation.waitForIdleSync()
    instrumentation.runOnMainSync { observedState = relay.state }
    return observedState == expected
}

internal fun connectR3bRelay(relay: RelaySettingsController, timeoutMillis: Long = 25_000L) {
    if (relay.mode != RelayTransportMode.RELAY) relay.selectMode(RelayTransportMode.RELAY)
    relay.connect()
    assertTrue(
        "The app did not reach RELAY_CONNECTED within ${timeoutMillis}ms (state=${relay.state})",
        awaitRelayState(relay, RelayConnectionState.RELAY_CONNECTED, timeoutMillis),
    )
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

internal fun writeR3bA3Marker(context: Context, marker: R3bA3Marker) {
    val file = r3bA3MarkerFile(context)
    val temp = File(context.filesDir, "${file.name}.tmp")
    temp.writeText(
        JSONObject()
            .put("deviceId", marker.deviceId)
            .put("serverPublicKeySha256", marker.serverPublicKeySha256)
            .put("deviceTokenSha256", marker.deviceTokenSha256)
            .put("createdAtUtcMillis", marker.createdAtUtcMillis)
            .toString(),
        Charsets.UTF_8,
    )
    check(temp.renameTo(file)) { "The public A3 phase marker could not be committed" }
}

internal fun readR3bA3Marker(context: Context): R3bA3Marker {
    val file = r3bA3MarkerFile(context)
    check(file.isFile) { "The A3 pairing marker is missing" }
    val json = JSONObject(file.readText(Charsets.UTF_8))
    return R3bA3Marker(
        deviceId = json.getString("deviceId"),
        serverPublicKeySha256 = json.getString("serverPublicKeySha256"),
        deviceTokenSha256 = json.getString("deviceTokenSha256"),
        createdAtUtcMillis = json.getLong("createdAtUtcMillis"),
    )
}

internal fun r3bA3MarkerFile(context: Context): File = File(context.filesDir, "r3b-a3-marker.json")

internal fun r3bA4ClearMarkerFile(context: Context): File = File(context.filesDir, "r3b-a4-cleared-marker.json")

internal fun writeR3bA4ClearMarker(context: Context, deviceId: String) {
    r3bA4ClearMarkerFile(context).writeText(
        JSONObject()
            .put("deviceId", deviceId)
            .put("clearedAtUtcMillis", System.currentTimeMillis())
            .toString(),
        Charsets.UTF_8,
    )
}

internal fun readR3bA4ClearedDeviceId(context: Context): String? {
    val file = r3bA4ClearMarkerFile(context)
    if (!file.isFile) return null
    return JSONObject(file.readText(Charsets.UTF_8)).optString("deviceId").takeIf(String::isNotBlank)
}

internal fun assertR3bTokenNotPlaintext(context: Context, token: String) {
    val preferences = context.getSharedPreferences(
        AndroidRelaySettingsStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    assertFalse(
        "The relay settings preferences contain the device token in cleartext",
        preferences.all.values.any { value -> value?.toString()?.contains(token) == true },
    )
    val encryptedToken = preferences.getString(ENCRYPTED_TOKEN_KEY, null)
    assertNotNull("The Keystore credential ciphertext was not stored", encryptedToken)
    assertFalse("The Keystore credential value equals the clear token", encryptedToken == token)
}

internal fun r3bRelayKeystoreAliasPresent(): Boolean = runCatching {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("atlas_relay_device_v1")
}.getOrDefault(false)

internal fun assertR3bCredentialRemoved(context: Context) {
    val preferences = context.getSharedPreferences(
        AndroidRelaySettingsStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    assertFalse("The encrypted relay token preference remains after clear", preferences.contains(ENCRYPTED_TOKEN_KEY))
    assertTrue("The Keystore no longer returns a relay credential", r3bCredentialStore(context).credentials() == null)
}

private const val ENCRYPTED_TOKEN_KEY = "relay_device_token"
