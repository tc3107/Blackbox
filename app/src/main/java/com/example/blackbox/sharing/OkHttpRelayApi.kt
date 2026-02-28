package com.example.blackbox.sharing

import com.example.blackbox.logging.AppLog as Log
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RelayHttpException(
    val statusCode: Int,
    val endpoint: String,
    val responseBody: String
) : IllegalStateException("Relay request failed ($statusCode): $responseBody")

open class RelayNetworkException(
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)

class RelayDnsException(cause: Throwable) : RelayNetworkException(
    message = "Cannot resolve relay host. Check internet, DNS/Private DNS, or relay URL.",
    cause = cause
)

class RelayTimeoutException(cause: Throwable) : RelayNetworkException(
    message = "Relay request timed out. Check connection quality and retry.",
    cause = cause
)

class RelayTlsException(cause: Throwable) : RelayNetworkException(
    message = "Secure relay connection failed. Check device date/time and TLS interception settings.",
    cause = cause
)

class OkHttpRelayApi(
    private val baseUrlProvider: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().build()
) : RelayApi {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    override suspend fun relayStatus(request: RelayStatusRequest): Result<RelayStatusResponse> {
        return postJson(
            path = apiPath("/relay/status"),
            request = request,
            requestSerializer = RelayStatusRequest.serializer(),
            responseSerializer = RelayStatusResponse.serializer()
        )
    }

    override suspend fun upsertAcl(request: UpsertAclRequest): Result<UpsertAclResponse> {
        return postJson(
            path = apiPath("/acl/upsert"),
            request = request,
            requestSerializer = UpsertAclRequest.serializer(),
            responseSerializer = UpsertAclResponse.serializer()
        )
    }

    override suspend fun pushLocation(request: PushLocationRequest): Result<PushLocationResponse> {
        return postJson(
            path = apiPath("/location/push"),
            request = request,
            requestSerializer = PushLocationRequest.serializer(),
            responseSerializer = PushLocationResponse.serializer()
        )
    }

    override suspend fun pullBatch(request: PullBatchRequest): Result<PullBatchResponse> {
        return postJson(
            path = apiPath("/location/pull"),
            request = request,
            requestSerializer = PullBatchRequest.serializer(),
            responseSerializer = PullBatchResponse.serializer()
        )
    }

    override suspend fun pullHistory(request: PullHistoryRequest): Result<PullHistoryResponse> {
        return postJson(
            path = apiPath("/location/pull-history"),
            request = request,
            requestSerializer = PullHistoryRequest.serializer(),
            responseSerializer = PullHistoryResponse.serializer()
        )
    }

    override suspend fun selfStatus(request: SelfStatusRequest): Result<SelfStatusResponse> {
        return postJson(
            path = apiPath("/location/self-status"),
            request = request,
            requestSerializer = SelfStatusRequest.serializer(),
            responseSerializer = SelfStatusResponse.serializer()
        )
    }

    override suspend fun clearLocation(request: ClearLocationRequest): Result<ClearLocationResponse> {
        return postJson(
            path = apiPath("/location/clear"),
            request = request,
            requestSerializer = ClearLocationRequest.serializer(),
            responseSerializer = ClearLocationResponse.serializer()
        )
    }

    private fun apiPath(pathSuffix: String): String {
        val version = SharingVersions.RELAY_API_VERSION.trim('/').ifBlank { "v1" }
        return "/$version$pathSuffix"
    }

    private suspend fun <Req : Any, Res : Any> postJson(
        path: String,
        request: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>
    ): Result<Res> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = baseUrlProvider().trim().trimEnd('/')
                require(baseUrl.startsWith("https://")) {
                    "Relay URL must be absolute and use HTTPS."
                }
                val endpoint = baseUrl + path

                val bodyJson = json.encodeToString(requestSerializer, request)
                Log.d(
                    SHARING_DEBUG_TAG,
                    "Relay request start endpoint=$endpoint payloadBytes=${bodyJson.length}"
                )
                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(
                            SHARING_DEBUG_TAG,
                            "Relay request failed endpoint=$endpoint code=${response.code} body=${summarizeRelayBody(body)}"
                        )
                        throw RelayHttpException(
                            statusCode = response.code,
                            endpoint = endpoint,
                            responseBody = body
                        )
                    }
                    Log.d(
                        SHARING_DEBUG_TAG,
                        "Relay request success endpoint=$endpoint code=${response.code} bodyBytes=${body.length}"
                    )
                    json.decodeFromString(responseSerializer, body)
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { throwable ->
                    val mapped = mapRelayThrowable(throwable)
                    when (mapped) {
                        is RelayHttpException -> {
                            val levelMessage = "Relay request error path=$path code=${mapped.statusCode} error=${mapped.message}"
                            if (mapped.statusCode in 400..499) {
                                Log.w(SHARING_DEBUG_TAG, levelMessage)
                            } else {
                                Log.e(SHARING_DEBUG_TAG, levelMessage, mapped)
                            }
                        }
                        is RelayNetworkException -> {
                            Log.w(
                                SHARING_DEBUG_TAG,
                                "Relay transport transient path=$path error=${mapped.message}"
                            )
                        }
                        else -> {
                            Log.e(
                                SHARING_DEBUG_TAG,
                                "Relay transport exception path=$path error=${mapped.message}",
                                mapped
                            )
                        }
                    }
                    Result.failure(mapped)
                }
            )
        }
    }

    private fun mapRelayThrowable(throwable: Throwable): Throwable {
        return when (throwable) {
            is RelayHttpException -> throwable
            is UnknownHostException -> RelayDnsException(throwable)
            is SocketTimeoutException -> RelayTimeoutException(throwable)
            is SSLException -> RelayTlsException(throwable)
            else -> throwable
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
