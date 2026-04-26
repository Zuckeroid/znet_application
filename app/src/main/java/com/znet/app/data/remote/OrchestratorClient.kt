package com.znet.app.data.remote

import com.znet.app.data.model.ServerNode
import com.znet.app.data.model.TelemetryPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

class OrchestratorClient(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchNodes(baseUrl: String, token: String): Result<List<ServerNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/api/v1/mobile/nodes".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .get()
                .applyAuth(token)
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Nodes request failed: ${response.code}" }
                val raw = response.body?.string().orEmpty()
                val payload = json.decodeFromString<NodesResponse>(raw)
                payload.nodes
            }
        }
    }

    suspend fun resolveTokenAccess(
        baseUrl: String,
        token: String
    ): Result<TokenAccessResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBody = requestTokenAuthRaw(baseUrl = baseUrl, token = token).getOrThrow()
            parseTokenAccessResponse(rawBody)
        }
    }

    suspend fun requestTokenAuthRaw(
        baseUrl: String,
        token: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedBase = baseUrl.trimEnd('/')
            require(normalizedBase.isNotBlank()) { "Orchestrator URL is empty" }
            require(token.isNotBlank()) { "Token is empty" }

            val endpoint = "$normalizedBase$TOKEN_AUTH_PATH".toHttpUrl()
            executeTokenAuthRequest(endpoint, token.trim())
        }
    }

    suspend fun fetchXrayConfig(
        baseUrl: String,
        token: String,
        nodeId: String,
        deviceId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/api/v1/mobile/xray-config"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("nodeId", nodeId)
                .addQueryParameter("deviceId", deviceId)
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .applyAuth(token)
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Config request failed: ${response.code}" }
                val raw = response.body?.string().orEmpty()
                json.decodeFromString<XrayConfigResponse>(raw).config
            }
        }
    }

    suspend fun postTelemetry(
        baseUrl: String,
        token: String,
        payload: TelemetryPayload
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/api/v1/mobile/telemetry".toHttpUrl()
            val body = json.encodeToString(TelemetryPayload.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .applyAuth(token)
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Telemetry request failed: ${response.code}" }
            }
        }
    }

    private fun executeTokenAuthRequest(
        endpoint: okhttp3.HttpUrl,
        token: String
    ): String {
        val requestPayload = json.encodeToString(
            TokenAuthRequest.serializer(),
            TokenAuthRequest(appToken = token)
        ).toRequestBody("application/json".toMediaType())

        val postRequest = Request.Builder()
            .url(endpoint)
            .post(requestPayload)
            .build()

        client.newCall(postRequest).execute().use { response ->
            require(response.isSuccessful) { "Token auth request failed: ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseTokenAccessResponse(rawBody: String): TokenAccessResponse {
        require(rawBody.isNotBlank()) { "Token auth response is empty" }
        val root = json.parseToJsonElement(rawBody).jsonObject
        val payload = unwrapPayload(root)
        val deviceToken = payload.objectOrNull("app")?.stringOrNull("token", "device_token", "deviceToken")
            ?: root.objectOrNull("result")?.objectOrNull("app")?.stringOrNull("token", "device_token", "deviceToken")
            ?: root.objectOrNull("app")?.stringOrNull("token", "device_token", "deviceToken")
        val hasActiveAccess = payload.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: root.objectOrNull("result")?.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: root.booleanOrNull("has_active_access", "hasActiveAccess")
        val activeSubscriptionLinks = (
            payload.extractLinksFromElement("active_subscription_links", "activeSubscriptionLinks") +
                (root.objectOrNull("result")?.extractLinksFromElement("active_subscription_links", "activeSubscriptionLinks")
                    ?: emptyList()) +
                root.extractLinksFromElement("active_subscription_links", "activeSubscriptionLinks")
            )
            .distinct()
        val filteredSubscriptionLinks = (
            payload.filteredSubscriptionLinks() +
                (root.objectOrNull("result")?.filteredSubscriptionLinks() ?: emptyList()) +
                root.filteredSubscriptionLinks()
            )
            .distinct()
        val selectedSubscriptionLink = filteredSubscriptionLinks.firstOrNull() ?: activeSubscriptionLinks.firstOrNull()

        val link = payload.stringOrNull(
            "nodeLink",
            "node_link",
            "link",
            "url",
            "nodeUrl",
            "node_url",
            "connectionUrl",
            "connection_url"
        ) ?: root.stringOrNull(
            "nodeLink",
            "node_link",
            "link",
            "url"
        ) ?: selectedSubscriptionLink

        val xrayConfig = payload.stringOrNull(
            "xrayConfig",
            "xray_config",
            "config"
        ) ?: root.stringOrNull(
            "xrayConfig",
            "xray_config",
            "config"
        )

        require(!link.isNullOrBlank() || !xrayConfig.isNullOrBlank()) {
            "Token auth response has no node link or xray config"
        }

        return TokenAccessResponse(
            nodeLink = link,
            xrayConfig = xrayConfig,
            nodeId = payload.stringOrNull("nodeId", "node_id") ?: root.stringOrNull("nodeId", "node_id"),
            nodeName = payload.stringOrNull("nodeName", "node_name", "name") ?: root.stringOrNull("nodeName", "node_name", "name"),
            country = payload.stringOrNull("country") ?: root.stringOrNull("country"),
            city = payload.stringOrNull("city") ?: root.stringOrNull("city"),
            flagEmoji = payload.stringOrNull("flagEmoji", "flag_emoji") ?: root.stringOrNull("flagEmoji", "flag_emoji"),
            issuedToken = payload.stringOrNull(
                "token",
                "authToken",
                "auth_token",
                "accessToken",
                "access_token"
            ) ?: root.stringOrNull(
                "token",
                "authToken",
                "auth_token",
                "accessToken",
                "access_token"
            ) ?: deviceToken,
            orchestratorBaseUrl = payload.stringOrNull(
                "orchestratorUrl",
                "orchestrator_url",
                "orchestratorBaseUrl",
                "orchestrator_base_url",
                "baseUrl",
                "base_url"
            ) ?: root.stringOrNull(
                "orchestratorUrl",
                "orchestrator_url",
                "orchestratorBaseUrl",
                "orchestrator_base_url",
                "baseUrl",
                "base_url"
            ),
            deviceToken = deviceToken,
            hasActiveAccess = hasActiveAccess,
            activeSubscriptionLinks = activeSubscriptionLinks,
            filteredSubscriptionLinks = filteredSubscriptionLinks,
            selectedSubscriptionLink = selectedSubscriptionLink
        )
    }

    private fun unwrapPayload(root: JsonObject): JsonObject {
        val directCandidates = listOf("data", "payload", "result", "response")
        directCandidates.forEach { key ->
            val nested = root.objectOrNull(key)
            if (nested != null) {
                return nested
            }
        }
        return root
    }

    private fun JsonObject.stringOrNull(vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun JsonObject.booleanOrNull(vararg keys: String): Boolean? {
        keys.forEach { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@forEach
            primitive.booleanOrNull?.let { return it }
            val normalized = primitive.contentOrNull?.trim()?.lowercase() ?: return@forEach
            when (normalized) {
                "true", "1", "yes" -> return true
                "false", "0", "no" -> return false
            }
        }
        return null
    }

    private fun JsonObject.extractLinksFromElement(vararg keys: String): List<String> {
        val links = mutableListOf<String>()
        keys.forEach { key ->
            links += this[key].extractLinkValues()
        }
        return links
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun JsonObject.filteredSubscriptionLinks(): List<String> {
        val subscriptions = mutableListOf<JsonObject>()
        subscriptions += arrayObjectsOrEmpty("subscriptions")
        subscriptions += arrayObjectsOrEmpty("subscrpitions")
        return subscriptions.mapNotNull { item ->
            val orderStatus = item.stringOrNull("order_status", "orderStatus")
            val provisionStatus = item.stringOrNull("provision_status", "provisionStatus")
            val link = item.stringOrNull(
                "subscription_link",
                "subscription_ink",
                "subscriptionLink",
                "link",
                "url",
                "uri",
                "vless"
            )
            if (
                orderStatus.equals("active", ignoreCase = true) &&
                provisionStatus.equals("active", ignoreCase = true) &&
                !link.isNullOrBlank()
            ) {
                link.trim()
            } else {
                null
            }
        }
    }

    private fun JsonObject.arrayObjectsOrEmpty(key: String): List<JsonObject> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }
    }

    private fun JsonElement?.extractLinkValues(): List<String> {
        return when (this) {
            is JsonPrimitive -> listOfNotNull(contentOrNull?.trim())
            is JsonArray -> this.flatMap { it.extractLinkValues() }
            is JsonObject -> {
                val direct = stringOrNull(
                    "subscription_link",
                    "subscription_ink",
                    "subscriptionLink",
                    "link",
                    "url",
                    "uri",
                    "vless"
                )
                if (direct != null) {
                    listOf(direct)
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key].asObjectOrNull()

    private fun Request.Builder.applyAuth(token: String): Request.Builder {
        return if (token.isBlank()) {
            this
        } else {
            header("Authorization", "Bearer $token")
        }
    }

    private companion object {
        const val TOKEN_AUTH_PATH = "/api/guest/appbridge/token_login"
    }
}

@Serializable
private data class NodesResponse(
    val nodes: List<ServerNode>
)

@Serializable
private data class XrayConfigResponse(
    val config: String
)

@Serializable
private data class TokenAuthRequest(
    @SerialName("app_token")
    val appToken: String
)

data class TokenAccessResponse(
    val nodeLink: String?,
    val xrayConfig: String?,
    val nodeId: String?,
    val nodeName: String?,
    val country: String?,
    val city: String?,
    val flagEmoji: String?,
    val issuedToken: String?,
    val orchestratorBaseUrl: String?,
    val deviceToken: String? = null,
    val hasActiveAccess: Boolean? = null,
    val activeSubscriptionLinks: List<String> = emptyList(),
    val filteredSubscriptionLinks: List<String> = emptyList(),
    val selectedSubscriptionLink: String? = null
)
