package com.znet.app.data.repo

import com.znet.app.data.model.ServerNode
import com.znet.app.data.remote.TokenAccessResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

data class ResolvedNodeAccess(
    val node: ServerNode,
    val xrayConfig: String,
    val protocol: String? = null,
    val serviceTitle: String? = null,
    val serviceExpiresAt: String? = null,
    val serviceDaysRemaining: Int? = null
)

object NodeLinkConfigFactory {
    fun fromTokenAccess(access: TokenAccessResponse): ResolvedNodeAccess {
        val runtimeConfig = access.xrayConfig?.trim().orEmpty()
        require(runtimeConfig.isNotBlank()) { "Runtime config is missing" }

        return ResolvedNodeAccess(
            node = buildNode(access, null),
            xrayConfig = normalizeRuntimeConfig(runtimeConfig),
            protocol = access.connectionProtocol,
            serviceTitle = access.serviceTitle,
            serviceExpiresAt = access.serviceExpiresAt,
            serviceDaysRemaining = access.serviceDaysRemaining
        )
    }

    private fun normalizeRuntimeConfig(rawConfig: String): String {
        val parsed = runCatching {
            Json.parseToJsonElement(rawConfig).jsonObject
        }.getOrNull() ?: return rawConfig

        val hasTunInbound = (parsed["inbounds"] as? JsonArray)
            ?.any { inbound ->
                (inbound as? JsonObject)?.get("protocol")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    ?.equals("tun", ignoreCase = true) == true
            } == true

        if (hasTunInbound) {
            return rawConfig
        }

        return buildJsonObject {
            parsed.forEach { (key, value) ->
                if (key != "inbounds") {
                    put(key, value)
                }
            }
            put("inbounds", buildJsonArray {
                add(
                    buildJsonObject {
                        put("tag", JsonPrimitive("tun-in"))
                        put("port", JsonPrimitive(0))
                        put("protocol", JsonPrimitive("tun"))
                        put("settings", buildJsonObject {
                            put("name", JsonPrimitive("xray0"))
                            put("MTU", JsonPrimitive(1500))
                        })
                    }
                )
            })
        }.toString()
    }

    private fun parseLink(rawLink: String): ParsedLink {
        val normalized = rawLink.trim()
        return when {
            normalized.startsWith("vless://", ignoreCase = true) -> parseVless(normalized)
            normalized.startsWith("trojan://", ignoreCase = true) -> parseTrojan(normalized)
            normalized.startsWith("vmess://", ignoreCase = true) -> parseVmess(normalized)
            else -> error("Unsupported node link format: ${normalized.substringBefore("://")}")
        }
    }

    private fun parseEndpoint(rawLink: String): NodeEndpoint? {
        return runCatching {
            when {
                rawLink.startsWith("vmess://", ignoreCase = true) -> parseVmessEndpoint(rawLink)
                rawLink.startsWith("vless://", ignoreCase = true) -> parseUriEndpoint(rawLink, "vless")
                rawLink.startsWith("trojan://", ignoreCase = true) -> parseUriEndpoint(rawLink, "trojan")
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVless(rawLink: String): ParsedLink {
        val uri = URI(rawLink)
        val userId = uri.userInfo?.takeIf { it.isNotBlank() } ?: error("VLESS link has no user id")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("VLESS link has no host")
        val port = if (uri.port > 0) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val network = query.firstValue("type", "net")?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val security = (
            query.firstValue("security")
                ?: if (query.firstValue("tls")?.equals("tls", ignoreCase = true) == true) "tls" else "none"
            ).lowercase().ifBlank { "none" }
        val displayName = decodePart(uri.fragment).ifBlank { "VLESS $host" }

        val outbound = buildJsonObject {
            put("tag", JsonPrimitive("proxy"))
            put("protocol", JsonPrimitive("vless"))
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", JsonPrimitive(host))
                            put("port", JsonPrimitive(port))
                            put("users", buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(userId))
                                        put(
                                            "encryption",
                                            JsonPrimitive(query["encryption"]?.ifBlank { "none" } ?: "none")
                                        )
                                        query["flow"]?.takeIf { it.isNotBlank() }?.let { flow ->
                                            put("flow", JsonPrimitive(flow))
                                        }
                                    }
                                )
                            })
                        }
                    )
                })
            })
            buildStreamSettings(network, security, query)?.let { stream ->
                put("streamSettings", stream)
            }
        }

        return ParsedLink(
            endpoint = NodeEndpoint(
                scheme = "vless",
                host = host,
                port = port,
                displayName = displayName
            ),
            outbound = outbound
        )
    }

    private fun parseTrojan(rawLink: String): ParsedLink {
        val uri = URI(rawLink)
        val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: error("Trojan link has no password")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("Trojan link has no host")
        val port = if (uri.port > 0) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val network = query.firstValue("type", "net")?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val security = (
            query.firstValue("security")
                ?: if (query.firstValue("tls")?.equals("tls", ignoreCase = true) == true) "tls" else "tls"
            ).lowercase().ifBlank { "tls" }
        val displayName = decodePart(uri.fragment).ifBlank { "Trojan $host" }

        val outbound = buildJsonObject {
            put("tag", JsonPrimitive("proxy"))
            put("protocol", JsonPrimitive("trojan"))
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", JsonPrimitive(host))
                            put("port", JsonPrimitive(port))
                            put("password", JsonPrimitive(password))
                        }
                    )
                })
            })
            buildStreamSettings(network, security, query)?.let { stream ->
                put("streamSettings", stream)
            }
        }

        return ParsedLink(
            endpoint = NodeEndpoint(
                scheme = "trojan",
                host = host,
                port = port,
                displayName = displayName
            ),
            outbound = outbound
        )
    }

    private fun parseVmess(rawLink: String): ParsedLink {
        val endpoint = parseVmessEndpoint(rawLink)
        val vmessPayload = parseVmessPayload(rawLink)

        val security = when {
            vmessPayload["security"]?.isNotBlank() == true -> vmessPayload["security"]!!.lowercase()
            vmessPayload["tls"]?.equals("tls", ignoreCase = true) == true -> "tls"
            else -> "none"
        }

        val network = vmessPayload["net"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val queryLike = linkedMapOf<String, String>().apply {
            putAll(vmessPayload)
            put("type", network)
            if (security != "none") {
                put("security", security)
            }
        }

        val outbound = buildJsonObject {
            put("tag", JsonPrimitive("proxy"))
            put("protocol", JsonPrimitive("vmess"))
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", JsonPrimitive(endpoint.host))
                            put("port", JsonPrimitive(endpoint.port))
                            put("users", buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(vmessPayload["id"].orEmpty()))
                                        put("alterId", JsonPrimitive(vmessPayload["aid"]?.toIntOrNull() ?: 0))
                                        put("security", JsonPrimitive(vmessPayload["scy"]?.ifBlank { "auto" } ?: "auto"))
                                    }
                                )
                            })
                        }
                    )
                })
            })
            buildStreamSettings(network, security, queryLike)?.let { stream ->
                put("streamSettings", stream)
            }
        }

        return ParsedLink(endpoint = endpoint, outbound = outbound)
    }

    private fun parseVmessEndpoint(rawLink: String): NodeEndpoint {
        val payload = parseVmessPayload(rawLink)
        val host = payload["add"]?.takeIf { it.isNotBlank() } ?: error("VMESS link has no address")
        val port = payload["port"]?.toIntOrNull() ?: 443
        val display = payload["ps"]?.takeIf { it.isNotBlank() } ?: "VMESS $host"
        return NodeEndpoint(
            scheme = "vmess",
            host = host,
            port = port,
            displayName = display
        )
    }

    private fun parseVmessPayload(rawLink: String): Map<String, String> {
        val encoded = rawLink.removePrefix("vmess://").trim()
        val normalized = encoded.replace('-', '+').replace('_', '/').let { source ->
            val mod = source.length % 4
            if (mod == 0) source else source + "=".repeat(4 - mod)
        }
        val decoded = String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
        val payloadObject = kotlinx.serialization.json.Json.parseToJsonElement(decoded).jsonObject
        return payloadObject.entries
            .mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { content -> key to content }
            }
            .toMap()
    }

    private fun parseUriEndpoint(rawLink: String, scheme: String): NodeEndpoint? {
        val uri = URI(rawLink)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val displayName = decodePart(uri.fragment).ifBlank { "${scheme.uppercase()} $host" }
        return NodeEndpoint(
            scheme = scheme,
            host = host,
            port = port,
            displayName = displayName
        )
    }

    private fun buildXrayConfig(primaryOutbound: JsonObject): String {
        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", JsonPrimitive("info"))
            })
            put("inbounds", buildJsonArray {
                add(
                    buildJsonObject {
                        put("tag", JsonPrimitive("tun-in"))
                        put("port", JsonPrimitive(0))
                        put("protocol", JsonPrimitive("tun"))
                        put("settings", buildJsonObject {
                            put("name", JsonPrimitive("xray0"))
                            put("MTU", JsonPrimitive(1500))
                        })
                    }
                )
            })
            put("outbounds", buildJsonArray {
                add(primaryOutbound)
                add(
                    buildJsonObject {
                        put("tag", JsonPrimitive("direct"))
                        put("protocol", JsonPrimitive("freedom"))
                    }
                )
                add(
                    buildJsonObject {
                        put("tag", JsonPrimitive("block"))
                        put("protocol", JsonPrimitive("blackhole"))
                    }
                )
            })
            put("routing", buildJsonObject {
                put("domainStrategy", JsonPrimitive("IPIfNonMatch"))
            })
        }
        return config.toString()
    }

    private fun buildStreamSettings(
        network: String,
        security: String,
        params: Map<String, String>
    ): JsonObject? {
        val normalizedNetwork = when (network.lowercase()) {
            "raw" -> "tcp"
            else -> network.lowercase()
        }
        return buildJsonObject {
            put("network", JsonPrimitive(normalizedNetwork))
            if (security != "none") {
                put("security", JsonPrimitive(security))
            }

            when (security) {
                "tls", "xtls" -> {
                    val serverName = params.firstValue("sni", "serverName", "servername", "peer", "host")
                    put("tlsSettings", buildJsonObject {
                        serverName?.takeIf { it.isNotBlank() }?.let { sni ->
                            put("serverName", JsonPrimitive(sni.trim()))
                        }
                        params.firstValue("alpn")?.takeIf { it.isNotBlank() }?.let { alpn ->
                            put("alpn", buildJsonArray {
                                alpn.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { token ->
                                    add(JsonPrimitive(token))
                                }
                            })
                        }
                        params.firstValue("allowInsecure", "insecure")?.toBooleanLooseOrNull()?.let { insecure ->
                            put("allowInsecure", JsonPrimitive(insecure))
                        }
                    })
                }

                "reality" -> {
                    val serverName = params.firstValue("sni", "serverName", "servername", "peer", "host")
                    put("realitySettings", buildJsonObject {
                        serverName?.takeIf { it.isNotBlank() }?.let { sni ->
                            put("serverName", JsonPrimitive(sni.trim()))
                        }
                        params.firstValue("pbk", "publicKey")?.takeIf { it.isNotBlank() }?.let { pbk ->
                            put("publicKey", JsonPrimitive(pbk))
                        }
                        params.firstValue("sid", "shortId")?.takeIf { it.isNotBlank() }?.let { sid ->
                            put("shortId", JsonPrimitive(sid))
                        }
                        params.firstValue("fp", "fingerprint")?.takeIf { it.isNotBlank() }?.let { fp ->
                            put("fingerprint", JsonPrimitive(fp))
                        }
                        params.firstValue("spx", "spiderX")?.takeIf { it.isNotBlank() }?.let { spider ->
                            put("spiderX", JsonPrimitive(decodePart(spider)))
                        }
                    })
                }
            }

            when (normalizedNetwork) {
                "ws" -> {
                    put("wsSettings", buildJsonObject {
                        val path = decodePart(params.firstValue("path")).ifBlank { "/" }
                        put("path", JsonPrimitive(path))
                        params.firstValue("host", "authority", "sni")?.takeIf { it.isNotBlank() }?.let { hostHeader ->
                            put("headers", buildJsonObject {
                                put("Host", JsonPrimitive(hostHeader))
                            })
                        }
                    })
                }

                "grpc" -> {
                    put("grpcSettings", buildJsonObject {
                        val serviceName = params.firstValue("serviceName", "service_name")
                            ?.takeIf { it.isNotBlank() }
                            ?: decodePart(params.firstValue("path")).trim('/')
                        if (serviceName.isNotBlank()) {
                            put("serviceName", JsonPrimitive(serviceName))
                        }
                        params.firstValue("authority", "host", "sni")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { authority ->
                                put("authority", JsonPrimitive(authority))
                            }
                        if (params["mode"]?.equals("multi", ignoreCase = true) == true) {
                            put("multiMode", JsonPrimitive(true))
                        }
                    })
                }

                "http", "h2" -> {
                    put("httpSettings", buildJsonObject {
                        params.firstValue("host")?.takeIf { it.isNotBlank() }?.let { host ->
                            put("host", buildJsonArray { add(JsonPrimitive(host)) })
                        }
                        val path = decodePart(params.firstValue("path")).ifBlank { "/" }
                        put("path", JsonPrimitive(path))
                    })
                }

                "tcp" -> {
                    if (params.firstValue("headerType")?.equals("http", ignoreCase = true) == true) {
                        val host = params.firstValue("host")
                        val path = decodePart(params.firstValue("path")).ifBlank { "/" }
                        put("tcpSettings", buildJsonObject {
                            put("header", buildJsonObject {
                                put("type", JsonPrimitive("http"))
                                put("request", buildJsonObject {
                                    put("path", buildJsonArray { add(JsonPrimitive(path)) })
                                    host?.takeIf { it.isNotBlank() }?.let { hostHeader ->
                                        put("headers", buildJsonObject {
                                            put("Host", buildJsonArray { add(JsonPrimitive(hostHeader)) })
                                        })
                                    }
                                })
                            })
                        })
                    }
                }
            }
        }.let { stream ->
            val hasSecurity = stream["security"] != null
            val hasTransport = stream["wsSettings"] != null || stream["grpcSettings"] != null || stream["httpSettings"] != null
            val hasTcpSettings = stream["tcpSettings"] != null
            if (!hasSecurity && !hasTransport && !hasTcpSettings && normalizedNetwork == "tcp") {
                null
            } else {
                stream
            }
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        return rawQuery.split('&')
            .mapNotNull { token ->
                if (token.isBlank()) return@mapNotNull null
                val key = token.substringBefore('=')
                val value = token.substringAfter('=', "")
                decodePart(key) to decodePart(value)
            }
            .toMap()
    }

    private fun decodePart(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            URLDecoder.decode(raw, Charsets.UTF_8.name())
        }.getOrElse { raw }
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? {
        return keys
            .asSequence()
            .mapNotNull { key -> this[key] }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun String.toBooleanLooseOrNull(): Boolean? {
        return when (trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private fun buildNode(
        access: TokenAccessResponse,
        endpoint: NodeEndpoint?
    ): ServerNode {
        val host = endpoint?.host ?: "unknown"
        val port = endpoint?.port ?: 443
        val generatedId = "${endpoint?.scheme ?: "node"}-$host-$port".lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
        return ServerNode(
            id = access.nodeId?.takeIf { it.isNotBlank() } ?: generatedId,
            name = access.nodeName?.takeIf { it.isNotBlank() }
                ?: endpoint?.displayName
                ?: "Znet node",
            country = access.country?.takeIf { it.isNotBlank() } ?: "Auto",
            city = access.city?.takeIf { it.isNotBlank() } ?: host,
            flagEmoji = normalizeFlagEmoji(
                rawFlag = access.flagEmoji,
                fallbackCountry = access.country
            ),
            host = host,
            port = port
        )
    }

    private fun normalizeFlagEmoji(
        rawFlag: String?,
        fallbackCountry: String?
    ): String {
        val normalizedFlag = rawFlag?.trim().orEmpty()
        if (normalizedFlag.isNotBlank() && normalizedFlag.codePoints().count() > 1L && !normalizedFlag.matches(Regex("^[A-Za-z]{2,3}$"))) {
            return normalizedFlag
        }

        val countryCode = when {
            normalizedFlag.matches(Regex("^[A-Za-z]{2}$")) -> normalizedFlag.uppercase()
            !fallbackCountry.isNullOrBlank() && fallbackCountry.trim().matches(Regex("^[A-Za-z]{2}$")) -> fallbackCountry.trim().uppercase()
            else -> null
        }

        if (countryCode == null) {
            return "GL"
        }

        val first = Character.codePointAt(countryCode, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(countryCode, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private data class ParsedLink(
        val endpoint: NodeEndpoint,
        val outbound: JsonObject
    )

    private data class NodeEndpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val displayName: String
    )
}
