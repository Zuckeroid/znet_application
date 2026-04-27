package com.znet.app.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ProcessXrayEngine(
    private val context: Context,
    private val installer: XrayBinaryInstaller = XrayBinaryInstaller(context)
) : XrayEngine {

    private var process: Process? = null
    private var logThread: Thread? = null
    private val configFile: File by lazy {
        File(context.filesDir, "xray/config.json")
    }

    override suspend fun ensureReady() {
        installer.installIfNeeded()
    }

    override suspend fun start(configJson: String, tunFd: Int?) = withContext(Dispatchers.IO) {
        stop()
        val binary = installer.installIfNeeded()
        configFile.parentFile?.mkdirs()
        configFile.writeText(configJson)

        val builder = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .directory(context.filesDir)
            .redirectErrorStream(true)

        if (tunFd != null && tunFd >= 0) {
            builder.environment()["XRAY_TUN_FD"] = tunFd.toString()
        }

        process = builder.start()
        val startedProcess = process ?: error("Unable to start xray process")
        val recentOutput = ArrayDeque<String>()
        logThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(startedProcess.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(recentOutput) {
                            if (recentOutput.size >= 20) {
                                recentOutput.removeFirst()
                            }
                            recentOutput.addLast(line)
                        }
                        Log.d(TAG, line)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to read xray output", error)
            }
        }.apply {
            name = "xray-log-reader"
            isDaemon = true
            start()
        }

        delay(400)
        if (!startedProcess.isAlive) {
            val tail = synchronized(recentOutput) {
                recentOutput.joinToString(separator = " | ")
            }.ifBlank { "no xray output" }
            throw IllegalStateException("Xray exited early: $tail")
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        process?.destroy()
        process?.waitFor()
        process = null
        logThread?.interrupt()
        logThread = null
    }

    private companion object {
        const val TAG = "ProcessXrayEngine"
    }
}
