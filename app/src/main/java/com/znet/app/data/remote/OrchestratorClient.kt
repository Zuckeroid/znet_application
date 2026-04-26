package com.znet.app.data.remote

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
            require(normalizedBase.isNotBlank()) { "Billing API URL is empty" }
            require(token.isNotBlank()) { "Token is empty" }

            val endpoint = "$normalizedBase$TOKEN_AUTH_PATH".toHttpUrl()
            executeTokenAuthRequest(endpoint, token.trim())
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
        val result = root.objectOrNull("result")
        val app = payload.objectOrNull("app") ?: result?.objectOrNull("app") ?: root.objectOrNull("app")
        val access = payload.objectOrNull("access") ?: result?.objectOrNull("access") ?: root.objectOrNull("access")
        val connection = payload.objectOrNull("connection") ?: result?.objectOrNull("connection") ?: root.objectOrNull("connection")
        val service = payload.objectOrNull("service") ?: result?.objectOrNull("service") ?: root.objectOrNull("service")

        val deviceToken = app?.stringOrNull("token", "device_token", "deviceToken")
        val hasActiveAccess = access?.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: payload.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: result?.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: root.booleanOrNull("has_active_access", "hasActiveAccess")

        val connectionLinks = buildList {
            addAll(connection?.extractLinksFromElement("links", "active_subscription_links", "activeSubscriptionLinks").orEmpty())
            connection?.stringOrNull("link", "subscription_link", "subscriptionLink", "url")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }

        val activeSubscriptionLinks = (
            connectionLinks +
                payload.extractLinksFromElement("active_subscription_links", "activeSubscriptionLinks") +
                (result?.extractLinksFromElement("active_subscription_links", "activeSubscriptionLinks") ?: emptyList()) +
                root.extractLinksFromElement("active_subscription_links", "activeSubscriptionLinks")
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val filteredSubscriptionLinks = (
            payload.filteredSubscriptionLinks() +
                (result?.filteredSubscriptionLinks() ?: emptyList()) +
                root.filteredSubscriptionLinks()
            )
            .distinct()

        val selectedSubscriptionLink = connection?.stringOrNull(
            "link",
            "subscription_link",
            "subscriptionLink",
            "url"
        ) ?: filteredSubscriptionLinks.firstOrNull() ?: activeSubscriptionLinks.firstOrNull()

        val link = connection?.stringOrNull(
            "link",
            "subscription_link",
            "subscriptionLink",
            "url"
        ) ?: payload.stringOrNull(
            "nodeLink",
            "node_link",
            "link",
            "url",
            "nodeUrl",
            "node_url",
            "connectionUrl",
            "connection_url"
        ) ?: result?.stringOrNull(
            "nodeLink",
            "node_link",
            "link",
            "url"
        ) ?: root.stringOrNull(
            "nodeLink",
            "node_link",
            "link",
            "url"
        ) ?: selectedSubscriptionLink

        val xrayConfig = connection?.stringOrNull(
            "xray_config",
            "xrayConfig",
            "config"
        ) ?: payload.stringOrNull(
            "xrayConfig",
            "xray_config",
            "config"
        ) ?: result?.stringOrNull(
            "xrayConfig",
            "xray_config",
            "config"
        ) ?: root.stringOrNull(
            "xrayConfig",
            "xray_config",
            "config"
        )

        require(hasActiveAccess != false) { "Token has no active VPN access" }
        require(!link.isNullOrBlank() || !xrayConfig.isNullOrBlank()) {
            "Token auth response has no node link or xray config"
        }

        return TokenAccessResponse(
            nodeLink = link,
            xrayConfig = xrayConfig,
            nodeId = payload.stringOrNull("nodeId", "node_id")
                ?: result?.stringOrNull("nodeId", "node_id")
                ?: root.stringOrNull("nodeId", "node_id"),
            nodeName = payload.stringOrNull("nodeName", "node_name", "name")
                ?: result?.stringOrNull("nodeName", "node_name", "name")
                ?: root.stringOrNull("nodeName", "node_name", "name"),
            country = payload.stringOrNull("country")
                ?: result?.stringOrNull("country")
                ?: root.stringOrNull("country"),
            city = payload.stringOrNull("city")
                ?: result?.stringOrNull("city")
                ?: root.stringOrNull("city"),
            flagEmoji = payload.stringOrNull("flagEmoji", "flag_emoji")
                ?: result?.stringOrNull("flagEmoji", "flag_emoji")
                ?: root.stringOrNull("flagEmoji", "flag_emoji"),
            issuedToken = payload.stringOrNull(
                "token",
                "authToken",
                "auth_token",
                "accessToken",
                "access_token"
            ) ?: result?.stringOrNull(
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
            ) ?: result?.stringOrNull(
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
            selectedSubscriptionLink = selectedSubscriptionLink,
            contractVersion = payload.stringOrNull("contract_version", "contractVersion")
                ?: result?.stringOrNull("contract_version", "contractVersion")
                ?: root.stringOrNull("contract_version", "contractVersion"),
            connectionReady = connection?.booleanOrNull("ready"),
            connectionType = connection?.stringOrNull("type"),
            connectionRevision = connection?.stringOrNull("revision"),
            serviceOrderId = service?.stringOrNull("order_id", "orderId"),
            serviceTitle = service?.stringOrNull("title", "name")
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

    private companion object {
        const val TOKEN_AUTH_PATH = "/api/guest/appbridge/token_login"
    }
}

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
    val selectedSubscriptionLink: String? = null,
    val contractVersion: String? = null,
    val connectionReady: Boolean? = null,
    val connectionType: String? = null,
    val connectionRevision: String? = null,
    val serviceOrderId: String? = null,
    val serviceTitle: String? = null
)
