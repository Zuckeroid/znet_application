package com.znet.app.data.repo

import com.znet.app.data.model.ServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class NodeProbeResult(
    val node: ServerNode,
    val latencyMs: Long,
    val healthy: Boolean
)

class AdaptiveNodeSelector {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun chooseNode(nodes: List<ServerNode>, adaptiveEnabled: Boolean): Pair<ServerNode, Long> {
        require(nodes.isNotEmpty()) { "Node list is empty" }
        if (!adaptiveEnabled) {
            val selected = nodes.minByOrNull { it.priority } ?: nodes.first()
            return selected to -1L
        }

        val probes = probeAll(nodes)
        val best = probes
            .filter { it.healthy }
            .minWithOrNull(compareBy<NodeProbeResult> { it.latencyMs }.thenBy { it.node.priority })
            ?: probes.minByOrNull { it.node.priority }
            ?: NodeProbeResult(nodes.first(), Long.MAX_VALUE, healthy = false)

        return best.node to if (best.latencyMs == Long.MAX_VALUE) -1 else best.latencyMs
    }

    private suspend fun probeAll(nodes: List<ServerNode>): List<NodeProbeResult> = coroutineScope {
        nodes.map { node ->
            async {
                probe(node)
            }
        }.awaitAll()
    }

    private suspend fun probe(node: ServerNode): NodeProbeResult = withContext(Dispatchers.IO) {
        runCatching {
            val latency = when {
                !node.healthUrl.isNullOrBlank() -> probeByHttp(node.healthUrl)
                else -> probeByTcp(node.host, node.port)
            }
            NodeProbeResult(node, latency, healthy = true)
        }.getOrElse {
            NodeProbeResult(node, Long.MAX_VALUE, healthy = false)
        }
    }

    private fun probeByHttp(url: String): Long {
        val start = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .head()
            .build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful)
        }
        return System.currentTimeMillis() - start
    }

    private fun probeByTcp(host: String, port: Int): Long {
        val start = System.currentTimeMillis()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2_000)
        }
        return System.currentTimeMillis() - start
    }
}
