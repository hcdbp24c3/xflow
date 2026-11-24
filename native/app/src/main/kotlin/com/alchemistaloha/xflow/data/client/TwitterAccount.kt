package com.alchemistaloha.xflow.data.client

import com.alchemistaloha.xflow.core.client.TwitterLogFormat
import com.alchemistaloha.xflow.core.client.FetchDiagnostics
import com.alchemistaloha.xflow.core.client.XUri
import com.alchemistaloha.xflow.core.client.X_BEARER_TOKEN
import com.alchemistaloha.xflow.core.client.X_MOBILE_USER_AGENT
import com.alchemistaloha.xflow.core.model.Account
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.db.Repository
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** What [TwitterAccount.fetch] returns (Dart: `http.Response`). Header names are lower case. */
data class HttpResult(val statusCode: Int, val body: String, val headers: Map<String, String>)

/** The logged-in account and every request made in its name. */
object TwitterAccount {
    @Volatile
    private var _currentAccount: Account? = null
    private val cache = HttpCache()

    /** One client for every request; the whole call may take 15 seconds, as in the Flutter app. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The same client with the 2 second limit of the transaction id fallback. */
    private val generatorClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    val currentAccount: Account?
        get() = _currentAccount

    suspend fun init() {
        val accounts = Repository.getAccounts()
        if (accounts.isNotEmpty()) {
            val account = accounts.first()
            _currentAccount = account
            AccountStore.onAccountLoaded(account)
        }
    }

    fun hasAccountAvailable(): Boolean {
        return _currentAccount != null
    }

    private fun getCacheKey(uri: XUri): String {
        val digest = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun compactForLog(value: Any?, maxLength: Int? = null): String =
        TwitterLogFormat.compactForLog(value, maxLength)

    fun formatUriForLog(uri: XUri): String = TwitterLogFormat.formatUriForLog(uri)

    fun formatTransactionIdForLog(transactionId: String?): String =
        TwitterLogFormat.formatTransactionIdForLog(transactionId)

    /**
     * Sends a request to X as the current account. [cacheDurationMillis]
     * caches a successful GET for that long. Throws when the network fails
     * or the request takes longer than 15 seconds.
     */
    suspend fun fetch(
        uri: XUri,
        method: String = "GET",
        body: Any? = null,
        headers: Map<String, String>? = null,
        cacheDurationMillis: Long? = null,
        transactionIdMode: TransactionIdMode = TransactionIdMode.generated,
    ): HttpResult {
        // Short on purpose: the operation and its variables. The feature flags are
        // the same on every call and a POST body can hold the user's own text.
        val requestSummary = "$method ${uri.path.substringAfterLast('/')}" +
            (uri.queryParameters["variables"]?.let { " vars=${TwitterLogFormat.compactForLog(it, 300)}" } ?: "")
        val cacheKey = getCacheKey(uri)
        if (method == "GET" && cacheDurationMillis != null) {
            val cachedBody = cache.getString(cacheKey)
            if (cachedBody != null) {
                AppLogger.log("HTTP cache hit: $requestSummary bytes=${cachedBody.length}")
                return HttpResult(
                    200, cachedBody,
                    mapOf("content-type" to "application/json; charset=utf-8"),
                )
            }
            AppLogger.log("HTTP cache miss: $requestSummary ttl=${cacheDurationMillis / 60_000}m")
        }

        if (_currentAccount == null) {
            init()
        }

        val combinedHeaders = linkedMapOf(
            "accept" to "*/*",
            "accept-language" to "en-US,en;q=0.9",
            "authorization" to X_BEARER_TOKEN,
            "cache-control" to "no-cache",
            "content-type" to "application/json",
            "pragma" to "no-cache",
            "referer" to "https://x.com",
            "origin" to "https://x.com",
            "user-agent" to X_MOBILE_USER_AGENT,
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "en",
            "x-twitter-auth-type" to "OAuth2Session",
        )
        if (headers != null) combinedHeaders.putAll(headers)

        val account = _currentAccount
        if (account != null) {
            // Dart: Map<String, String>.from(json.decode(authHeader)); a value that is not text fails the request.
            val authHeaders = Json.parseToJsonElement(account.authHeader) as JsonObject
            for ((name, value) in authHeaders) {
                if (value !is JsonPrimitive || value is JsonNull || !value.isString) {
                    throw IllegalStateException("auth header '$name' is not a string")
                }
                combinedHeaders[name] = value.content
            }
        }

        // x-client-transaction-id: X's own generator in the hidden page, then
        // the Kotlin generator, then the third-party service.
        var transactionId: String? = null
        var txIdStatus = "missing"
        var madeInKotlin = false
        try {
            when (transactionIdMode) {
                TransactionIdMode.placeholder -> {
                    combinedHeaders["x-client-transaction-id"] = TransactionIdMode.PLACEHOLDER_VALUE
                    txIdStatus = "placeholder"
                }
                TransactionIdMode.none -> txIdStatus = "none:requested"
                TransactionIdMode.generated -> {
                    transactionId = TransactionIdService.generateForRequest(uri.path, method = method)
                    if (transactionId != null) {
                        txIdStatus = "webview-module:len=${transactionId.length}"
                    } else {
                        transactionId = LocalTransactionId.generate(uri.path, method)
                        if (transactionId != null) {
                            madeInKotlin = true
                            txIdStatus = "kotlin:len=${transactionId.length}"
                        } else {
                            transactionId = thirdPartyTransactionId(uri.path)
                            txIdStatus = if (transactionId != null) {
                                "third-party:len=${transactionId.length}"
                            } else {
                                "missing:$thirdPartyState"
                            }
                        }
                    }
                    if (transactionId != null) combinedHeaders["x-client-transaction-id"] = transactionId
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            txIdStatus = "missing:error:${e::class.simpleName}"
            AppLogger.log("TXID could not be made: ${e::class.simpleName}: ${e.message}")
        }

        AppLogger.log(
            "HTTP request start: $requestSummary account=${_currentAccount?.screenName ?: "none"} txId=$txIdStatus",
        )
        val startedAt = System.nanoTime()
        val requestBuilder = Request.Builder().url(uri.toString())
        if (method == "POST") {
            val bodyBytes = (body?.toString() ?: "").toByteArray(Charsets.UTF_8)
            // The Dart http package sends a JSON content type as it is (no charset added);
            // checked by running the Flutter client against a recording HTTP client.
            requestBuilder.post(bodyBytes.toRequestBody(null))
        } else {
            requestBuilder.get()
        }
        val headerBuilder = Headers.Builder()
        for ((name, value) in combinedHeaders) headerBuilder.addUnsafeNonAscii(name, value)
        requestBuilder.headers(headerBuilder.build())

        val response = execute(httpClient, requestBuilder.build())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        AppLogger.log(
            "HTTP request end: $requestSummary status=${response.statusCode} elapsedMs=$elapsedMs bytes=${response.bodyBytes.size}",
        )

        if (response.statusCode == 404 && madeInKotlin) LocalTransactionId.onRefused()

        if (response.statusCode == 200) {
            // Force UTF-8 decoding for the body string to avoid mangling and caching issues
            val decodedBody = String(response.bodyBytes, Charsets.UTF_8)
            if (method == "GET" && cacheDurationMillis != null) {
                cache.setStringWithTimeout(cacheKey, decodedBody, cacheDurationMillis)
            }
            val resultHeaders = LinkedHashMap(response.headers)
            resultHeaders["content-type"] = "application/json; charset=utf-8"
            return HttpResult(200, decodedBody, resultHeaders)
        }
        AppLogger.log(
            "HTTP error body: $requestSummary status=${response.statusCode} " +
                (FetchDiagnostics.shorten(response.text(), 300) ?: "(empty)"),
        )
        return HttpResult(response.statusCode, response.text(), response.headers)
    }

    // --- the third-party transaction id service -------------------------
    // Kept as the last resort, as in the Flutter app. Its host stopped
    // resolving in November 2026, so a failure pauses it instead of adding
    // a failed lookup to every request.

    private const val THIRD_PARTY_PAUSE_MS = 30 * 60_000L

    @Volatile private var thirdPartyPausedUntil = 0L
    @Volatile private var thirdPartyState = "third-party-not-tried"

    private suspend fun thirdPartyTransactionId(path: String): String? {
        val now = System.currentTimeMillis()
        if (now < thirdPartyPausedUntil) return null
        try {
            val transactionUri = XUri.http(
                "x-client-transaction-id-generator.xyz",
                "/generate-x-client-transaction-id", mapOf("path" to path),
            )
            val response = execute(generatorClient, Request.Builder().url(transactionUri.toString()).get().build())
            if (response.statusCode == 200) {
                val field = (Json.parseToJsonElement(response.text()) as? JsonObject)?.get("x-client-transaction-id")
                if (field is JsonPrimitive && field.isString && field.content.isNotEmpty()) return field.content
                thirdPartyState = "third-party-empty-answer"
            } else {
                thirdPartyState = "third-party-status-${response.statusCode}"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            thirdPartyState = "third-party-${e::class.simpleName}"
        }
        thirdPartyPausedUntil = now + THIRD_PARTY_PAUSE_MS
        AppLogger.log(
            "TXID third-party generator unusable ($thirdPartyState); not asked again for ${THIRD_PARTY_PAUSE_MS / 60_000} minutes",
        )
        thirdPartyState += ":paused"
        return null
    }

    // --- pages and scripts of x.com --------------------------------------

    private val documentHeaders = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to X_MOBILE_USER_AGENT,
    )

    /** A public page or script, fetched without the session. Null when it is not a 200. */
    suspend fun fetchPublicText(url: String): String? {
        val builder = Request.Builder().url(url).get()
        for ((name, value) in documentHeaders) builder.header(name, value)
        val response = execute(httpClient, builder.build())
        return if (response.statusCode == 200) String(response.bodyBytes, Charsets.UTF_8) else null
    }

    /**
     * A page of x.com as the logged-in account sees it (the session cookies
     * are sent, nothing else). Null when logged out or when it is not a 200.
     */
    suspend fun fetchSessionDocument(uri: XUri): String? {
        if (uri.host != "x.com") throw IllegalArgumentException("not a page of x.com")
        val account = _currentAccount ?: return null
        val cookie = ((Json.parseToJsonElement(account.authHeader) as? JsonObject)?.get("Cookie") as? JsonPrimitive)?.content
            ?: return null
        val builder = Request.Builder().url(uri.toString()).get()
        val headers = Headers.Builder()
        for ((name, value) in documentHeaders) headers.add(name, value)
        headers.addUnsafeNonAscii("cookie", cookie)
        builder.headers(headers.build())
        val startedAt = System.nanoTime()
        val response = execute(httpClient, builder.build())
        AppLogger.log(
            "HTTP page: ${uri.path} status=${response.statusCode} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} bytes=${response.bodyBytes.size}",
        )
        return if (response.statusCode == 200) String(response.bodyBytes, Charsets.UTF_8) else null
    }

    fun setCurrentAccount(account: Account) {
        _currentAccount = account
    }

    suspend fun logout() {
        Repository.deleteAllAccounts()
        _currentAccount = null
    }

    // --- OkHttp plumbing ---

    private class RawResponse(
        val statusCode: Int,
        val bodyBytes: ByteArray,
        val headers: Map<String, String>,
        private val charset: java.nio.charset.Charset?,
    ) {
        /** The body as text: the charset of the content type, else Latin-1 as the Dart http package does. */
        fun text(): String = String(bodyBytes, charset ?: Charsets.ISO_8859_1)
    }

    /** Runs [request] off the main thread; cancelling the coroutine cancels the call. */
    private suspend fun execute(client: OkHttpClient, request: Request): RawResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val raw = response.use {
                            val responseBody = it.body
                            val headerMap = LinkedHashMap<String, String>()
                            for (name in it.headers.names()) {
                                headerMap[name.lowercase()] = it.headers.values(name).joinToString(",") { v -> v.trim() }
                            }
                            RawResponse(
                                it.code,
                                responseBody?.bytes() ?: ByteArray(0),
                                headerMap,
                                responseBody?.contentType()?.charset(),
                            )
                        }
                        if (continuation.isActive) continuation.resume(raw)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
}

/** What a request sends as `x-client-transaction-id`. */
enum class TransactionIdMode {
    /** A real id: X's generator in the hidden x.com page, else the Kotlin generator, else the third-party service. */
    generated,

    /** The fixed text the Flutter client ended up sending (its WebView call returned an unresolved promise). */
    placeholder,

    /** No header at all. */
    none;

    companion object {
        const val PLACEHOLDER_VALUE = "{}"
    }
}
