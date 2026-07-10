package com.hermes.mobile.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class NativeOAuthFlow(
    val authorizationUrl: String,
    val redirectUri: String,
    val clientId: String,
    val codeVerifier: String,
    val state: String,
    val tokenEndpoint: String,
    val callbackServer: ServerSocket,
)

class HermesApi(private val store: ConnectionStore? = null, private val client: OkHttpClient = defaultClient()) {
    private val cookieValuesByOrigin = mutableMapOf<String, LinkedHashMap<String, String>>()

    init {
        CookieManager.getInstance().setAcceptCookie(true)
    }

    /**
     * Re-install cookies from persisted tokens so the app survives process death.
     * Android evicts session cookies (no Max-Age) on process kill; this puts them
     * back so [load] can use them transparently.
     */
    fun restoreSession(baseUrl: String) {
        val root = normalizeBaseUrl(baseUrl)
        val at = store?.accessToken.orEmpty()
        val rt = store?.refreshToken.orEmpty()
        if (at.isNotBlank()) installOAuthCookies(root, at, rt)
    }

    fun clearSession(baseUrl: String) {
        val root = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull() ?: return
        val origin = originOf(root)
        synchronized(cookieValuesByOrigin) { cookieValuesByOrigin.remove(origin) }
        val manager = CookieManager.getInstance()
        val expiry = "; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + if (root.startsWith("https://")) "; Secure" else ""
        listOf("", "__Host-", "__Secure-").forEach { prefix ->
            listOf("hermes_session_at", "hermes_session_rt", "hermes_session_provider").forEach { name ->
                manager.setCookie(root, "$prefix$name=$expiry")
            }
        }
        manager.flush()
    }
    suspend fun probe(baseUrl: String): ConnectionProbe = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        val status = parseStatus(getJson("$root/api/status", includeCookies = false))
        val providers = if (status.authRequired) {
            parseProviders(getJson("$root/api/auth/providers", includeCookies = false))
        } else emptyList()
        ConnectionProbe(status, providers)
    }

    suspend fun load(baseUrl: String, token: String = ""): HermesSnapshot = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        val status = parseStatus(getJson("$root/api/status", token = token, includeCookies = true))
        val sessionsJson = getJson(
            "$root/api/profiles/sessions?limit=100&order=recent&profile=all&exclude_sources=cron",
            token = token,
            includeCookies = true,
        )
        HermesSnapshot(status, parseSessions(sessionsJson))
    }

    suspend fun loadProfiles(baseUrl: String): List<HermesProfile> = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        parseProfiles(getJson("$root/api/profiles", includeCookies = true))
    }

    suspend fun loadModelOptions(baseUrl: String, profile: String): List<ModelOption> = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        val profileQuery = profile.takeIf { it.isNotBlank() && it != "default" }
            ?.let { "&profile=${java.net.URLEncoder.encode(it, "UTF-8")}" }
            .orEmpty()
        parseModelOptions(
            getJson("$root/api/model/options?explicit_only=1$profileQuery", includeCookies = true),
        )
    }

    /** Persists the profile's main provider/model for future sessions only. */
    suspend fun setProfileDefaultModel(
        baseUrl: String,
        profile: String,
        option: ModelOption,
        confirmExpensiveModel: Boolean = false,
    ): ModelDefaultResult = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        val response = postJson(
            "$root/api/model/set",
            JSONObject()
                .put("scope", "main")
                .put("provider", option.provider)
                .put("model", option.model)
                .put("profile", profile.takeIf(String::isNotBlank))
                .put("confirm_expensive_model", confirmExpensiveModel),
        )
        ModelDefaultResult(
            confirmationMessage = response.optString("confirm_message")
                .takeIf { response.optBoolean("confirm_required", false) && it.isNotBlank() },
        )
    }

    suspend fun loadMessages(baseUrl: String, session: HermesSession): List<ChatMessage> =
        loadMessages(baseUrl, session.id, session.profile)

    suspend fun loadMessages(baseUrl: String, sessionId: String, profile: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank() && sessionId != "new") { "Stored session is not available yet" }
        val root = normalizeBaseUrl(baseUrl)
        val profileQuery = if (profile.isNotBlank() && profile != "default") {
            "?profile=${java.net.URLEncoder.encode(profile, "UTF-8")}"
        } else ""
        parseMessages(getJson("$root/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/messages$profileQuery", includeCookies = true))
    }

    suspend fun deleteSession(baseUrl: String, session: HermesSession) = withContext(Dispatchers.IO) {
        require(session.id.isNotBlank() && session.id != "new") { "Stored session is not available yet" }
        val root = normalizeBaseUrl(baseUrl)
        val profileQuery = if (session.profile.isNotBlank() && session.profile != "default") {
            "?profile=${java.net.URLEncoder.encode(session.profile, "UTF-8")}"
        } else ""
        deleteJson("$root/api/sessions/${java.net.URLEncoder.encode(session.id, "UTF-8")}$profileQuery")
    }

    suspend fun mintWsTicket(baseUrl: String): String = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        val builder = Request.Builder()
            .url("$root/api/auth/ws-ticket")
            .post(ByteArray(0).toRequestBody(null))
            .header("Accept", "application/json")
        cookieHeader(root).takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Could not authorize live chat (HTTP ${response.code})")
            saveResponseCookies(root, response.headers("Set-Cookie"))
            JSONObject(body).optString("ticket").ifBlank { throw IOException("Hermes returned no WebSocket ticket") }
        }
    }


    suspend fun prepareNativeOAuth(baseUrl: String, provider: String): NativeOAuthFlow = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val start = Request.Builder()
            .url("$root/auth/login?provider=${java.net.URLEncoder.encode(provider, "UTF-8")}")
            .get()
            .build()
        val portalLocation = noRedirectClient.newCall(start).execute().use { response ->
            if (response.code !in 300..399) throw IOException("Hermes did not start OAuth (HTTP ${response.code})")
            response.header("Location") ?: throw IOException("Hermes OAuth response had no redirect")
        }.toHttpUrl()

        val clientId = portalLocation.queryParameter("client_id")
            ?: throw IOException("Hermes OAuth redirect had no client_id")
        val verifier = randomUrlSafe(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val state = randomUrlSafe(32)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 300_000 }
        val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
        val authorizationUrl = portalLocation.newBuilder()
            .setQueryParameter("redirect_uri", redirectUri)
            .setQueryParameter("state", state)
            .setQueryParameter("code_challenge", challenge)
            .setQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
        val tokenEndpoint = portalLocation.newBuilder()
            .encodedPath("/api/oauth/token")
            .query(null)
            .build()
            .toString()
        NativeOAuthFlow(authorizationUrl, redirectUri, clientId, verifier, state, tokenEndpoint, server)
    }

    suspend fun completeNativeOAuth(baseUrl: String, flow: NativeOAuthFlow): HermesSnapshot = withContext(Dispatchers.IO) {
        val callback = flow.callbackServer.use { server ->
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine().orEmpty()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val target = requestLine.split(' ').getOrNull(1)
                    ?: throw IOException("Invalid OAuth callback")
                val url = "http://127.0.0.1$target".toHttpUrl()
                val response = "HTTP/1.1 302 Found\r\nLocation: hermes-mobile://auth-complete\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                url
            }
        }
        val error = callback.queryParameter("error")
        if (!error.isNullOrBlank()) throw IOException("Nous Portal login failed: $error")
        if (callback.queryParameter("state") != flow.state) throw IOException("OAuth state mismatch")
        val code = callback.queryParameter("code") ?: throw IOException("OAuth callback had no code")

        val tokenRequest = Request.Builder()
            .url(flow.tokenEndpoint)
            .post(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", flow.redirectUri)
                    .add("client_id", flow.clientId)
                    .add("code_verifier", flow.codeVerifier)
                    .build(),
            )
            .header("Accept", "application/json")
            .build()
        val tokenJson = client.newCall(tokenRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Nous Portal token exchange returned HTTP ${response.code}: ${body.take(200)}")
            JSONObject(body)
        }
        val accessToken = tokenJson.optString("access_token")
        val refreshToken = tokenJson.optString("refresh_token")
        if (accessToken.isBlank()) throw IOException("Nous Portal returned no access token")
        installOAuthCookies(normalizeBaseUrl(baseUrl), accessToken, refreshToken)
        load(baseUrl)
    }

    private fun getJson(url: String, token: String = "", includeCookies: Boolean): JSONObject {
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer ${token.trim()}")
        if (includeCookies) {
            cookieHeader(url).takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) {
                    clearSession(url)
                    if (store?.baseUrl?.let { sameOrigin(it, url) } != false) store?.clearTokens()
                    throw HermesAuthenticationRequired()
                }
                throw IOException("Hermes returned HTTP ${response.code}: ${body.take(240)}")
            }
            saveResponseCookies(url, response.headers("Set-Cookie"))
            return JSONObject(body)
        }
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        cookieHeader(url).takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) {
                    clearSession(url)
                    if (store?.baseUrl?.let { sameOrigin(it, url) } != false) store?.clearTokens()
                    throw HermesAuthenticationRequired()
                }
                throw IOException("Hermes returned HTTP ${response.code}: ${responseBody.take(240)}")
            }
            saveResponseCookies(url, response.headers("Set-Cookie"))
            return JSONObject(responseBody)
        }
    }

    private fun deleteJson(url: String): JSONObject {
        val builder = Request.Builder().url(url).delete().header("Accept", "application/json")
        cookieHeader(url).takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) {
                    clearSession(url)
                    if (store?.baseUrl?.let { sameOrigin(it, url) } != false) store?.clearTokens()
                    throw HermesAuthenticationRequired()
                }
                throw IOException("Hermes returned HTTP ${response.code}: ${body.take(240)}")
            }
            saveResponseCookies(url, response.headers("Set-Cookie"))
            return JSONObject(body)
        }
    }

    private fun saveResponseCookies(url: String, cookies: List<String>) {
        if (cookies.isEmpty()) return
        val origin = originOf(url)
        synchronized(cookieValuesByOrigin) {
            val values = cookieValuesByOrigin.getOrPut(origin) { linkedMapOf() }
            cookies.forEach { cookie ->
                val pair = cookie.substringBefore(';')
                val separator = pair.indexOf('=')
                if (separator > 0) {
                    val name = pair.substring(0, separator).trim()
                    val value = pair.substring(separator + 1)
                    if (value.isBlank() || cookie.contains("Max-Age=0", ignoreCase = true)) values.remove(name)
                    else values[name] = value
                    // Persist rotations only for the configured host. Cookies from
                    // another origin must never replace this host's credentials.
                    if (store?.baseUrl?.let { sameOrigin(it, url) } != false && value.isNotBlank()) {
                        if (name.endsWith("hermes_session_at")) store?.accessToken = value
                        if (name.endsWith("hermes_session_rt")) store?.refreshToken = value
                    }
                }
                CookieManager.getInstance().setCookie(url, cookie)
            }
        }
        CookieManager.getInstance().flush()
    }

    private fun cookieHeader(url: String): String = synchronized(cookieValuesByOrigin) {
        val merged = linkedMapOf<String, String>()
        CookieManager.getInstance().getCookie(url).orEmpty().split(';').forEach { raw ->
            val pair = raw.trim()
            val separator = pair.indexOf('=')
            if (separator > 0) merged[pair.substring(0, separator)] = pair.substring(separator + 1)
        }
        merged.putAll(cookieValuesByOrigin[originOf(url)].orEmpty())
        merged.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private fun installOAuthCookies(root: String, accessToken: String, refreshToken: String) {
        val secure = root.startsWith("https://")
        val prefix = if (secure) "__Host-" else ""
        val maxAge = 30 * 24 * 60 * 60
        val attrs = "; Path=/; Max-Age=$maxAge; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else ""
        val origin = originOf(root)
        synchronized(cookieValuesByOrigin) {
            val values = cookieValuesByOrigin.getOrPut(origin) { linkedMapOf() }
            values["${prefix}hermes_session_at"] = accessToken
            values["${prefix}hermes_session_provider"] = "nous"
        }
        CookieManager.getInstance().setCookie(root, "${prefix}hermes_session_at=$accessToken$attrs")
        CookieManager.getInstance().setCookie(root, "${prefix}hermes_session_provider=nous$attrs")
        if (refreshToken.isNotBlank()) {
            synchronized(cookieValuesByOrigin) {
                cookieValuesByOrigin.getOrPut(origin) { linkedMapOf() }["${prefix}hermes_session_rt"] = refreshToken
            }
            CookieManager.getInstance().setCookie(root, "${prefix}hermes_session_rt=$refreshToken$attrs")
        }
        CookieManager.getInstance().flush()
        store?.let {
            it.accessToken = accessToken
            if (refreshToken.isNotBlank()) it.refreshToken = refreshToken
        }
    }

    companion object {

        private fun randomUrlSafe(size: Int): String {
            val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun originOf(value: String): String {
            val url = value.toHttpUrl()
            return "${url.scheme}://${url.host}:${url.port}"
        }

        internal fun sameOrigin(first: String, second: String): Boolean = runCatching {
            originOf(normalizeBaseUrl(first)) == originOf(normalizeBaseUrl(second))
        }.getOrDefault(false)

        private fun isTrustedCleartextHost(host: String): Boolean {
            val normalized = host.lowercase().trim('[', ']')
            if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".local") || !normalized.contains('.')) return true
            val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 100 && octets[1] in 64..127)
        }

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val url = runCatching { trimmed.toHttpUrl() }.getOrElse {
                throw IllegalArgumentException("Use a full http:// or https:// URL")
            }
            require(url.scheme == "https" || isTrustedCleartextHost(url.host)) {
                "Use HTTPS for remote Hermes hosts; HTTP is allowed only for localhost and private networks"
            }
            return trimmed
        }

        fun parseStatus(json: JSONObject) = HermesStatus(
            version = json.optString("version", "unknown"),
            authRequired = json.optBoolean("auth_required", false),
            profiles = json.optJSONArray("profiles")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
            activeSessions = json.optInt("active_sessions", 0),
        )

        fun parseProviders(json: JSONObject): List<AuthProvider> {
            val array = json.optJSONArray("providers") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name")
                if (name.isBlank()) return@mapNotNull null
                AuthProvider(
                    name = name,
                    displayName = item.optString("display_name").ifBlank { name },
                    supportsPassword = item.optBoolean("supports_password", false),
                )
            }
        }

        fun parseProfiles(json: JSONObject): List<HermesProfile> {
            val array = json.optJSONArray("profiles") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                when (val item = array.opt(index)) {
                    is String -> item.takeIf(String::isNotBlank)?.let(::HermesProfile)
                    is JSONObject -> item.optString("name").takeIf(String::isNotBlank)?.let { name ->
                        HermesProfile(name, item.optString("description"))
                    }
                    else -> null
                }
            }.distinctBy { it.name }
        }

        fun parseModelOptions(json: JSONObject): List<ModelOption> {
            val providers = json.optJSONArray("providers") ?: return emptyList()
            val defaultProvider = json.optString("provider")
            val defaultModel = json.optString("model")
            val options = mutableListOf<ModelOption>()
            for (providerIndex in 0 until providers.length()) {
                val provider = providers.optJSONObject(providerIndex) ?: continue
                val slug = provider.optString("slug").ifBlank { provider.optString("name") }
                if (slug.isBlank() || !provider.optBoolean("authenticated", true)) continue
                val providerName = provider.optString("name").ifBlank { slug }
                val unavailableArray = provider.optJSONArray("unavailable_models")
                val unavailable = unavailableArray?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
                }.orEmpty()
                val models = provider.optJSONArray("models") ?: continue
                for (modelIndex in 0 until models.length()) {
                    val model = models.optString(modelIndex)
                    if (model.isNotBlank()) {
                        options += ModelOption(
                            provider = slug,
                            providerName = providerName,
                            model = model,
                            available = model !in unavailable,
                            isProfileDefault = slug == defaultProvider && model == defaultModel,
                        )
                    }
                }
            }
            return options.distinctBy { "${it.provider}/${it.model}" }
        }

        fun parseMessages(json: JSONObject): List<ChatMessage> {
            val array = json.optJSONArray("messages") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                if (role !in setOf("user", "assistant", "system", "tool")) return@mapNotNull null
                val text = coerceMessageText(item.opt("content")).ifBlank {
                    item.optString("text").ifBlank { item.optString("context").ifBlank { item.optString("name") } }
                }
                if (text.isBlank()) return@mapNotNull null
                ChatMessage(
                    id = item.optString("id").ifBlank { "stored-$index" },
                    role = role,
                    text = text,
                    hasToolCalls = item.optJSONArray("tool_calls")?.length()?.let { it > 0 } == true,
                )
            }
        }

        private fun coerceMessageText(value: Any?): String = when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            is org.json.JSONArray -> (0 until value.length()).joinToString("\n") { coerceMessageText(value.opt(it)) }.trim()
            is JSONObject -> value.optString("text").ifBlank { value.optString("content") }
            else -> value.toString()
        }

        fun parseSessions(json: JSONObject): List<HermesSession> {
            val array = json.optJSONArray("sessions") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { item.optString("session_id") }
                if (id.isBlank()) return@mapNotNull null
                HermesSession(
                    id = id,
                    title = item.optString("title").ifBlank { "Untitled session" },
                    preview = item.optString("preview"),
                    model = item.optString("model", "default"),
                    profile = item.optString("profile", "default"),
                    cwd = item.optString("cwd")
                        .takeUnless { it.equals("null", ignoreCase = true) }
                        .orEmpty()
                        .ifBlank {
                            item.optString("workspace")
                                .takeUnless { it.equals("null", ignoreCase = true) }
                                .orEmpty()
                        },
                    inputTokens = item.optLong("input_tokens", 0),
                    outputTokens = item.optLong("output_tokens", 0),
                    messageCount = item.optInt("message_count", 0),
                    lastActive = item.optDouble("last_active", item.optDouble("started_at", 0.0)),
                    isActive = item.optBoolean("is_active", false),
                )
            }
        }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class HermesAuthenticationRequired : IOException("Sign in to this Hermes host")
