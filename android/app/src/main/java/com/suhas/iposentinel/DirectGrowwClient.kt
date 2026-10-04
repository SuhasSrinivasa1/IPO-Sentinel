package com.suhas.iposentinel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct, device-side Groww authentication.
 *
 * Groww credentials are encrypted with a key held in Android Keystore and the
 * broker base URL is fixed to Groww HTTPS. No custom control-plane configuration
 * is accepted by this client.
 */
class DirectGrowwClient(context: Context) {
    private val appContext = context.applicationContext
    private val store = SecureGrowwStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun saveGrowwSettings(
        totpToken: String,
        totpSecret: String,
        expectedStaticIp: String,
        staticIpConfirmed: Boolean
    ): ApiResult = withContext(Dispatchers.IO) {
        val token = totpToken.trim()
        val secret = totpSecret.replace(" ", "").trim().uppercase()
        val ip = expectedStaticIp.trim()

        if (token.isBlank() || secret.isBlank() || ip.isBlank()) {
            return@withContext ApiResult(false, 400, "", "Token, TOTP secret and static IP are required.")
        }

        try {
            // Validate the secret format before persisting it.
            decodeBase32(secret)
            store.saveCredentials(token, secret)
            prefs.edit()
                .putString(KEY_EXPECTED_IP, ip)
                .putBoolean(KEY_IP_CONFIRMED, staticIpConfirmed)
                .apply()
            ApiResult(true, 200, "{}", null)
        } catch (e: Exception) {
            ApiResult(false, 400, "", "Unable to save Groww credentials: ${safeMessage(e)}")
        }
    }

    suspend fun fetchStatus(): Pair<ApiResult, ConnectionStatus?> = withContext(Dispatchers.IO) {
        try {
            val configured = store.hasCredentials()
            val status = ConnectionStatus(
                secretStoreReady = store.isReady(),
                growwConfigured = configured,
                expectedStaticIp = prefs.getString(KEY_EXPECTED_IP, null)?.takeIf { it.isNotBlank() },
                staticIpConfirmed = prefs.getBoolean(KEY_IP_CONFIRMED, false),
                error = null
            )
            ApiResult(true, 200, "{}", null) to status
        } catch (e: Exception) {
            ApiResult(false, 500, "", safeMessage(e)) to null
        }
    }

    fun lastValidation(): ValidationStatus? {
        val raw = prefs.getString(KEY_LAST_VALIDATION, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ValidationStatus(
                growwAuthOk = json.optBoolean("groww_auth_ok", false),
                detectedEgressIp = json.optString("detected_egress_ip").ifBlank { null },
                expectedStaticIp = json.optString("expected_static_ip").ifBlank { null },
                staticIpMatches = json.optBoolean("static_ip_matches", false),
                staticIpConfirmed = json.optBoolean("static_ip_confirmed", false),
                secretStoreReady = json.optBoolean("secret_store_ready", false),
                calendarReady = json.optBoolean("calendar_ready", false),
                nseIdentitySourceReady = json.optBoolean("nse_identity_source_ready", false),
                liveExecutionReady = json.optBoolean("live_execution_ready", false),
                growwError = json.optString("groww_error").ifBlank { null },
                egressError = json.optString("egress_error").ifBlank { null }
            )
        }.getOrNull()
    }

    suspend fun validate(): Pair<ApiResult, ValidationStatus?> = withContext(Dispatchers.IO) {
        val expectedIp = prefs.getString(KEY_EXPECTED_IP, null)?.trim()?.takeIf { it.isNotEmpty() }
        val confirmed = prefs.getBoolean(KEY_IP_CONFIRMED, false)

        var growwOk = false
        var growwError: String? = null
        var detectedIp: String? = null
        var egressError: String? = null

        try {
            val credentials = store.loadCredentials()
                ?: throw IllegalStateException("Groww TOTP credentials are not saved.")
            val totp = generateTotp(credentials.second)
            val auth = authenticate(credentials.first, totp)
            if (auth.first.ok) {
                growwOk = true
                val accessToken = auth.second
                if (!accessToken.isNullOrBlank()) {
                    store.saveAccessToken(accessToken)
                }
            } else {
                growwError = auth.first.error ?: "Groww authentication failed."
            }
        } catch (e: Exception) {
            growwError = safeMessage(e)
        }

        try {
            detectedIp = detectPublicIp()
        } catch (e: Exception) {
            egressError = safeMessage(e)
        }

        val staticMatches = expectedIp != null && detectedIp != null && expectedIp == detectedIp
        val nseChecks = try {
            probeNse()
        } catch (_: Exception) {
            false to false
        }

        val value = ValidationStatus(
            growwAuthOk = growwOk,
            detectedEgressIp = detectedIp,
            expectedStaticIp = expectedIp,
            staticIpMatches = staticMatches,
            staticIpConfirmed = confirmed,
            secretStoreReady = store.isReady(),
            calendarReady = nseChecks.first,
            nseIdentitySourceReady = nseChecks.second,
            liveExecutionReady = growwOk && staticMatches && confirmed && nseChecks.first && nseChecks.second,
            growwError = growwError,
            egressError = egressError
        )
        persistValidation(value)
        ApiResult(true, 200, "{}", null) to value
    }

    private fun persistValidation(value: ValidationStatus) {
        val json = JSONObject()
            .put("groww_auth_ok", value.growwAuthOk)
            .put("detected_egress_ip", value.detectedEgressIp)
            .put("expected_static_ip", value.expectedStaticIp)
            .put("static_ip_matches", value.staticIpMatches)
            .put("static_ip_confirmed", value.staticIpConfirmed)
            .put("secret_store_ready", value.secretStoreReady)
            .put("calendar_ready", value.calendarReady)
            .put("nse_identity_source_ready", value.nseIdentitySourceReady)
            .put("live_execution_ready", value.liveExecutionReady)
            .put("groww_error", value.growwError)
            .put("egress_error", value.egressError)
        prefs.edit()
            .putString(KEY_LAST_VALIDATION, json.toString())
            .putLong(KEY_LAST_VALIDATION_AT, System.currentTimeMillis())
            .apply()
    }

    suspend fun accessToken(): Pair<ApiResult, String?> = withContext(Dispatchers.IO) {
        try {
            val credentials = store.loadCredentials()
                ?: return@withContext ApiResult(false, 401, "", "Groww TOTP credentials are not saved.") to null
            val totp = generateTotp(credentials.second)
            authenticate(credentials.first, totp)
        } catch (e: Exception) {
            ApiResult(false, 500, "", safeMessage(e)) to null
        }
    }

    private fun authenticate(apiKey: String, totp: String): Pair<ApiResult, String?> {
        val connection = (URL("$GROWW_BASE/v1/token/api/access").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val payload = JSONObject()
            .put("key_type", "totp")
            .put("totp", totp)
            .toString()
            .toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(payload) }

        val code = connection.responseCode
        val body = readBody(connection, code)
        if (code !in 200..299) {
            return ApiResult(false, code, "", brokerError(body, code)) to null
        }

        val json = JSONObject(body)
        val token = json.optString("token").trim()
        if (token.isBlank()) {
            return ApiResult(false, code, "", "Groww authenticated but did not return an access token.") to null
        }
        return ApiResult(true, code, "{}", null) to token
    }

    private fun detectPublicIp(): String {
        val connection = (URL(IP_CHECK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val body = readBody(connection, code)
        if (code !in 200..299) throw IllegalStateException("Public-IP check returned HTTP $code")
        val ip = JSONObject(body).optString("ip").trim()
        if (ip.isBlank()) throw IllegalStateException("Public-IP check returned no address")
        return ip
    }

    /**
     * Probe the two official NSE dependencies used as fail-closed readiness gates.
     * First boolean = cash-market calendar source, second = listing-identity source.
     */
    private fun probeNse(): Pair<Boolean, Boolean> {
        val previous = CookieHandler.getDefault()
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(cookieManager)
        try {
            nseGet("/market-data/all-upcoming-issues-ipo", expectJson = false)
            val calendarReady = runCatching {
                val body = nseGet("/api/holiday-master?type=trading", expectJson = true)
                val json = JSONObject(body)
                json.has("CM")
            }.getOrDefault(false)

            val identityReady = runCatching {
                nseGet("/api/new-listing-today?index=ForthListing", expectJson = true)
                true
            }.getOrDefault(false) || runCatching {
                nseGet("/api/new-listing-today?index=RecentListing", expectJson = true)
                true
            }.getOrDefault(false)

            return calendarReady to identityReady
        } finally {
            CookieHandler.setDefault(previous)
        }
    }

    private fun nseGet(path: String, expectJson: Boolean): String {
        val connection = (URL(NSE_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Accept", if (expectJson) "application/json, text/plain, */*" else "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", NSE_BASE + "/market-data/all-upcoming-issues-ipo")
        }
        val code = connection.responseCode
        val body = readBody(connection, code)
        if (code !in 200..299) throw IllegalStateException("NSE readiness check returned HTTP $code")
        return body
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun brokerError(body: String, code: Int): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val message = parsed?.optString("message")?.takeIf { it.isNotBlank() }
            ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
        return message?.take(240) ?: "Groww authentication returned HTTP $code"
    }

    private fun generateTotp(secret: String, nowMillis: Long = System.currentTimeMillis()): String {
        val key = decodeBase32(secret)
        val counter = nowMillis / 1000L / 30L
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0F
        val binary =
            ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun decodeBase32(value: String): ByteArray {
        val clean = value.uppercase().replace("=", "").filterNot { it.isWhitespace() || it == '-' }
        require(clean.isNotBlank()) { "TOTP secret is empty" }
        val output = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val v = BASE32.indexOf(ch)
            require(v >= 0) { "TOTP secret is not valid Base32" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            while (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        require(output.isNotEmpty()) { "TOTP secret is invalid" }
        return output.toByteArray()
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(240)?.ifBlank { null } ?: error.javaClass.simpleName

    private data class Credentials(val apiKey: String, val secret: String)

    private class SecureGrowwStore(context: Context) {
        private val prefs = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun isReady(): Boolean = runCatching {
            getOrCreateKey()
            true
        }.getOrDefault(false)

        fun hasCredentials(): Boolean =
            prefs.contains(KEY_API_KEY) && prefs.contains(KEY_TOTP_SECRET) &&
                runCatching { loadCredentials() != null }.getOrDefault(false)

        fun saveCredentials(apiKey: String, secret: String) {
            prefs.edit()
                .putString(KEY_API_KEY, encrypt(apiKey))
                .putString(KEY_TOTP_SECRET, encrypt(secret))
                .apply()
        }

        fun loadCredentials(): Pair<String, String>? {
            val apiKeyBlob = prefs.getString(KEY_API_KEY, null) ?: return null
            val secretBlob = prefs.getString(KEY_TOTP_SECRET, null) ?: return null
            return decrypt(apiKeyBlob) to decrypt(secretBlob)
        }

        fun saveAccessToken(token: String) {
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, encrypt(token))
                .putLong(KEY_ACCESS_TOKEN_SAVED_AT, Instant.now().epochSecond)
                .apply()
        }

        private fun getOrCreateKey(): SecretKey {
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            return generator.generateKey()
        }

        private fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(cipher.iv.size + encrypted.size)
            System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
            System.arraycopy(encrypted, 0, packed, cipher.iv.size, encrypted.size)
            return Base64.encodeToString(packed, Base64.NO_WRAP)
        }

        private fun decrypt(blob: String): String {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            require(packed.size > 12) { "Encrypted credential is invalid" }
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }

        companion object {
            private const val SECURE_PREFS = "ipo_sentinel_direct_secure"
            private const val KEY_ALIAS = "ipo_sentinel_groww_direct_v1"
            private const val KEY_API_KEY = "groww_api_key"
            private const val KEY_TOTP_SECRET = "groww_totp_secret"
            private const val KEY_ACCESS_TOKEN = "groww_access_token"
            private const val KEY_ACCESS_TOKEN_SAVED_AT = "groww_access_token_saved_at"
        }
    }

    companion object {
        private const val GROWW_BASE = "https://api.groww.in"
        private const val IP_CHECK_URL = "https://api.ipify.org?format=json"
        private const val NSE_BASE = "https://www.nseindia.com"
        private const val PREFS_NAME = "ipo_sentinel_direct_settings"
        private const val KEY_EXPECTED_IP = "expected_static_ip"
        private const val KEY_IP_CONFIRMED = "static_ip_confirmed"
        private const val KEY_LAST_VALIDATION = "last_validation"
        private const val KEY_LAST_VALIDATION_AT = "last_validation_at"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36"
    }
}
