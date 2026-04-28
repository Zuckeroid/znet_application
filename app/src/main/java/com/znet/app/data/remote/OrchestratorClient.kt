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
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun resolveTokenAccess(
        baseUrl: String,
        token: String,
        deviceData: DeviceRegistrationData? = null
    ): Result<TokenAccessResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBody = requestTokenAuthRaw(
                baseUrl = baseUrl,
                token = token,
                deviceData = deviceData
            ).getOrThrow()
            parseTokenAccessResponse(rawBody)
        }
    }

    suspend fun requestTokenAuthRaw(
        baseUrl: String,
        token: String,
        deviceData: DeviceRegistrationData? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedBase = baseUrl.trimEnd('/')
            require(normalizedBase.isNotBlank()) { "API биллинга не настроен" }
            require(token.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            val endpoint = "$normalizedBase$TOKEN_AUTH_PATH".toHttpUrl()
            executeTokenAuthRequest(endpoint, token.trim(), deviceData)
        }
    }

    private fun executeTokenAuthRequest(
        endpoint: okhttp3.HttpUrl,
        token: String,
        deviceData: DeviceRegistrationData?
    ): String {
        val requestPayload = json.encodeToString(
            TokenAuthRequest.serializer(),
            TokenAuthRequest(
                appToken = token,
                deviceName = deviceData?.deviceName,
                platform = deviceData?.platform,
                installId = deviceData?.installId
            )
        ).toRequestBody("application/json".toMediaType())

        val postRequest = Request.Builder()
            .url(endpoint)
            .post(requestPayload)
            .build()

        client.newCall(postRequest).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMessage = extractApiErrorMessage(rawBody)
                if (response.code == 401 || response.code == 403) {
                    throw InvalidTokenException(
                        message = apiMessage ?: INVALID_TOKEN_MESSAGE,
                        statusCode = response.code
                    )
                }

                throw TokenAuthRequestException(
                    message = apiMessage ?: "Не удалось проверить токен: ${response.code}",
                    statusCode = response.code
                )
            }

            return rawBody
        }
    }

    private fun parseTokenAccessResponse(rawBody: String): TokenAccessResponse {
        require(rawBody.isNotBlank()) { "Пустой ответ биллинга" }
        val root = json.parseToJsonElement(rawBody).jsonObject
        throwEnvelopeErrorIfPresent(root)

        val payload = unwrapPayload(root)
        val result = root.objectOrNull("result")
        val app = payload.objectOrNull("app") ?: result?.objectOrNull("app") ?: root.objectOrNull("app")
        val access = payload.objectOrNull("access") ?: result?.objectOrNull("access") ?: root.objectOrNull("access")
        val connection = payload.objectOrNull("connection") ?: result?.objectOrNull("connection") ?: root.objectOrNull("connection")
        val service = payload.objectOrNull("service") ?: result?.objectOrNull("service") ?: root.objectOrNull("service")
        val routingPolicy = connection?.objectOrNull("routing_policy", "routingPolicy")
            ?: payload.objectOrNull("routing_policy", "routingPolicy")
            ?: result?.objectOrNull("routing_policy", "routingPolicy")
            ?: root.objectOrNull("routing_policy", "routingPolicy")
        val automationPolicy = connection?.objectOrNull("automation_policy", "automationPolicy")
            ?: payload.objectOrNull("automation_policy", "automationPolicy")
            ?: result?.objectOrNull("automation_policy", "automationPolicy")
            ?: root.objectOrNull("automation_policy", "automationPolicy")
        val connectionProfiles = connection?.objectOrNull("profiles", "connectionProfiles")
            ?: payload.objectOrNull("profiles", "connectionProfiles")
            ?: result?.objectOrNull("profiles", "connectionProfiles")
            ?: root.objectOrNull("profiles", "connectionProfiles")
        val domainBundle = connection?.objectOrNull("domain_bundle", "domainBundle", "domains")
            ?: payload.objectOrNull("domain_bundle", "domainBundle", "domains")
            ?: result?.objectOrNull("domain_bundle", "domainBundle", "domains")
            ?: root.objectOrNull("domain_bundle", "domainBundle", "domains")

        val device = app?.objectOrNull("device")
        val deviceToken = app?.stringOrNull("token", "device_token", "deviceToken")
            ?: access?.stringOrNull("device_token", "deviceToken")
            ?: device?.stringOrNull("token", "device_token", "deviceToken")
        val hasActiveAccess = access?.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: payload.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: result?.booleanOrNull("has_active_access", "hasActiveAccess")
            ?: root.booleanOrNull("has_active_access", "hasActiveAccess")

        val xrayConfig = connection?.stringOrNull(
            "payload",
            "xray_config",
            "xrayConfig",
            "config"
        ) ?: payload.stringOrNull(
            "payload",
            "xrayConfig",
            "xray_config",
            "config"
        ) ?: result?.stringOrNull(
            "payload",
            "xrayConfig",
            "xray_config",
            "config"
        ) ?: root.stringOrNull(
            "payload",
            "xrayConfig",
            "xray_config",
            "config"
        )

        if (hasActiveAccess == false) {
            throw NoActiveAccessException("No active access for this device")
        }

        return TokenAccessResponse(
            xrayConfig = xrayConfig,
            nodeId = connection?.stringOrNull("node_id", "nodeId")
                ?: payload.stringOrNull("nodeId", "node_id")
                ?: result?.stringOrNull("nodeId", "node_id")
                ?: root.stringOrNull("nodeId", "node_id"),
            nodeName = connection?.stringOrNull("node_label", "nodeName", "node_name", "name")
                ?: payload.stringOrNull("nodeName", "node_name", "name")
                ?: result?.stringOrNull("nodeName", "node_name", "name")
                ?: root.stringOrNull("nodeName", "node_name", "name"),
            country = connection?.stringOrNull("node_country", "country")
                ?: payload.stringOrNull("country")
                ?: result?.stringOrNull("country")
                ?: root.stringOrNull("country"),
            city = connection?.stringOrNull("node_host", "city")
                ?: payload.stringOrNull("city")
                ?: result?.stringOrNull("city")
                ?: root.stringOrNull("city"),
            flagEmoji = payload.stringOrNull("flagEmoji", "flag_emoji")
                ?: result?.stringOrNull("flagEmoji", "flag_emoji")
                ?: root.stringOrNull("flagEmoji", "flag_emoji"),
            deviceToken = deviceToken,
            hasActiveAccess = hasActiveAccess,
            connectionReady = connection?.booleanOrNull("ready"),
            connectionProtocol = connection?.stringOrNull("protocol"),
            routingPolicy = parseRoutingPolicy(routingPolicy),
            automationPolicy = parseAutomationPolicy(automationPolicy),
            profiles = parseConnectionProfiles(connectionProfiles),
            domainBundle = parseDomainBundle(domainBundle),
            serviceTitle = service?.stringOrNull("title", "name"),
            serviceExpiresAt = service?.stringOrNull("expires_at", "expiresAt"),
            serviceDaysRemaining = service?.intOrNull("days_remaining", "daysRemaining")
        )
    }

    private fun extractApiErrorMessage(rawBody: String): String? {
        if (rawBody.isBlank()) {
            return null
        }

        return runCatching {
            val root = json.parseToJsonElement(rawBody).jsonObject
            root.objectOrNull("error")?.stringOrNull("message", "msg", "error")
                ?: root.stringOrNull("message", "msg", "error")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun throwEnvelopeErrorIfPresent(root: JsonObject) {
        val error = root.objectOrNull("error") ?: return
        val message = error.stringOrNull("message", "msg", "error") ?: INVALID_TOKEN_MESSAGE
        val code = error.intOrNull("code", "status", "statusCode")

        if (code == 401 || code == 403) {
            throw InvalidTokenException(
                message = message,
                statusCode = code
            )
        }

        throw TokenAuthRequestException(
            message = message,
            statusCode = code
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

    private fun JsonObject.intOrNull(vararg keys: String): Int? {
        keys.forEach { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@forEach
            primitive.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun parseRoutingPolicy(policy: JsonObject?): AppRoutingPolicy {
        if (policy == null) {
            return AppRoutingPolicy()
        }

        return AppRoutingPolicy(
            mode = policy.stringOrNull("mode") ?: AppRoutingPolicy.MODE_ALL_APPS,
            includedApps = policy.stringSetOrNull(
                "includedApps",
                "included_apps",
                "default_enabled_apps"
            ),
            excludedApps = policy.stringSetOrNull(
                "excludedApps",
                "excluded_apps",
                "default_excluded_apps"
            )
        )
    }

    private fun parseAutomationPolicy(policy: JsonObject?): AppAutomationPolicy {
        if (policy == null) {
            return AppAutomationPolicy()
        }

        return AppAutomationPolicy(
            autoConnectApps = policy.stringSetOrNull(
                "autoConnectApps",
                "auto_connect_apps",
                "auto_enable_apps"
            ),
            autoDisconnectApps = policy.stringSetOrNull(
                "autoDisconnectApps",
                "auto_disconnect_apps",
                "auto_disable_apps"
            ),
            requiresUsageAccess = policy.booleanOrNull("requiresUsageAccess", "requires_usage_access") ?: false
        )
    }

    private fun parseConnectionProfiles(profiles: JsonObject?): Map<String, AccessConnectionProfile> {
        if (profiles == null) {
            return emptyMap()
        }

        return profiles.entries.mapNotNull { (id, element) ->
            val profile = element as? JsonObject ?: return@mapNotNull null
            val config = profile.stringOrNull(
                "runtimePayload",
                "runtime_payload",
                "payload",
                "xray_config",
                "xrayConfig",
                "config"
            )
            val ready = profile.booleanOrNull("ready") ?: !config.isNullOrBlank()
            id to AccessConnectionProfile(
                id = profile.stringOrNull("id") ?: id,
                label = profile.stringOrNull("label", "name"),
                enabled = profile.booleanOrNull("enabled") ?: ready,
                ready = ready,
                xrayConfig = config,
                protocol = profile.stringOrNull("protocol"),
                nodeId = profile.stringOrNull("nodeId", "node_id"),
                nodeName = profile.stringOrNull("nodeLabel", "node_label", "nodeName", "node_name", "name"),
                country = profile.stringOrNull("nodeCountry", "node_country", "country"),
                city = profile.stringOrNull("nodeHost", "node_host", "city"),
                flagEmoji = profile.stringOrNull("flagEmoji", "flag_emoji"),
                routingPolicy = parseRoutingPolicy(profile.objectOrNull("routingPolicy", "routing_policy")),
                automationPolicy = parseAutomationPolicy(profile.objectOrNull("automationPolicy", "automation_policy")),
                error = profile.stringOrNull("error")
            )
        }.toMap()
    }

    private fun parseDomainBundle(bundle: JsonObject?): DomainBundle {
        if (bundle == null) {
            return DomainBundle()
        }

        return DomainBundle(
            version = bundle.intOrNull("version") ?: 1,
            revision = bundle.stringOrNull("revision", "config_revision", "configRevision"),
            generatedAt = bundle.stringOrNull("generated_at", "generatedAt"),
            api = parseDomainEndpoints(
                bundle.arrayOrNull("api", "api_domains", "apiDomains")
            ),
            web = parseDomainEndpoints(
                bundle.arrayOrNull("web", "web_domains", "webDomains")
            )
        )
    }

    private fun parseDomainEndpoints(endpoints: JsonArray?): List<DomainEndpoint> {
        if (endpoints == null) {
            return emptyList()
        }

        return endpoints.mapNotNull { element ->
            val endpoint = element as? JsonObject ?: return@mapNotNull null
            val url = endpoint.stringOrNull("url", "base_url", "baseUrl")
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            DomainEndpoint(
                url = url,
                role = endpoint.stringOrNull("role") ?: "backup",
                priority = endpoint.intOrNull("priority") ?: 100,
                enabled = endpoint.booleanOrNull("enabled", "is_active", "isActive") ?: true,
                label = endpoint.stringOrNull("label", "name")
            )
        }
    }

    private fun JsonObject.stringSetOrNull(vararg keys: String): Set<String> {
        keys.forEach { key ->
            val element = this[key] ?: return@forEach
            val values = when (element) {
                is JsonArray -> element.mapNotNull { item ->
                    (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                }
                is JsonPrimitive -> element.contentOrNull
                    ?.split(',', '\n', '\r')
                    ?.mapNotNull { item -> item.trim().takeIf { it.isNotBlank() } }
                    .orEmpty()
                else -> emptyList()
            }

            if (values.isNotEmpty()) {
                return values.toSet()
            }
        }

        return emptySet()
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key].asObjectOrNull()
    private fun JsonObject.objectOrNull(vararg keys: String): JsonObject? {
        keys.forEach { key ->
            this[key].asObjectOrNull()?.let { return it }
        }
        return null
    }
    private fun JsonObject.arrayOrNull(vararg keys: String): JsonArray? {
        keys.forEach { key ->
            (this[key] as? JsonArray)?.let { return it }
        }
        return null
    }

    private companion object {
        const val TOKEN_AUTH_PATH = "/api/guest/appbridge/token_login"
        const val INVALID_TOKEN_MESSAGE = "Некорректный токен"
    }
}

@Serializable
private data class TokenAuthRequest(
    @SerialName("app_token")
    val appToken: String,
    @SerialName("device_name")
    val deviceName: String? = null,
    @SerialName("platform")
    val platform: String? = null,
    @SerialName("install_id")
    val installId: String? = null
)

data class DeviceRegistrationData(
    val deviceName: String,
    val platform: String,
    val installId: String
)

data class TokenAccessResponse(
    val xrayConfig: String?,
    val nodeId: String?,
    val nodeName: String?,
    val country: String?,
    val city: String?,
    val flagEmoji: String?,
    val deviceToken: String? = null,
    val hasActiveAccess: Boolean? = null,
    val connectionReady: Boolean? = null,
    val connectionProtocol: String? = null,
    val routingPolicy: AppRoutingPolicy = AppRoutingPolicy(),
    val automationPolicy: AppAutomationPolicy = AppAutomationPolicy(),
    val profiles: Map<String, AccessConnectionProfile> = emptyMap(),
    val domainBundle: DomainBundle = DomainBundle(),
    val serviceTitle: String? = null,
    val serviceExpiresAt: String? = null,
    val serviceDaysRemaining: Int? = null
)

@Serializable
data class DomainBundle(
    val version: Int = 1,
    val revision: String? = null,
    val generatedAt: String? = null,
    val api: List<DomainEndpoint> = emptyList(),
    val web: List<DomainEndpoint> = emptyList()
)

@Serializable
data class DomainEndpoint(
    val url: String,
    val role: String = "backup",
    val priority: Int = 100,
    val enabled: Boolean = true,
    val label: String? = null
)

data class AccessConnectionProfile(
    val id: String,
    val label: String? = null,
    val enabled: Boolean = true,
    val ready: Boolean = false,
    val xrayConfig: String? = null,
    val protocol: String? = null,
    val nodeId: String? = null,
    val nodeName: String? = null,
    val country: String? = null,
    val city: String? = null,
    val flagEmoji: String? = null,
    val routingPolicy: AppRoutingPolicy = AppRoutingPolicy(),
    val automationPolicy: AppAutomationPolicy = AppAutomationPolicy(),
    val error: String? = null
)

data class AppRoutingPolicy(
    val mode: String = MODE_ALL_APPS,
    val includedApps: Set<String> = emptySet(),
    val excludedApps: Set<String> = emptySet()
) {
    val normalizedMode: String
        get() = mode.trim().lowercase().ifBlank { MODE_ALL_APPS }

    companion object {
        const val MODE_SELECTED_APPS = "selected_apps"
        const val MODE_ALL_EXCEPT = "all_except"
        const val MODE_ALL_APPS = "all_apps"
    }
}

data class AppAutomationPolicy(
    val autoConnectApps: Set<String> = emptySet(),
    val autoDisconnectApps: Set<String> = emptySet(),
    val requiresUsageAccess: Boolean = false
)

open class TokenAuthException(message: String) : IllegalStateException(message)

class TokenAuthRequestException(
    message: String,
    val statusCode: Int? = null
) : TokenAuthException(message)

class InvalidTokenException(
    message: String,
    val statusCode: Int? = null
) : TokenAuthException(message)

class NoActiveAccessException(message: String) : TokenAuthException(message)

class AccessNotReadyException(message: String) : TokenAuthException(message)
