package com.znet.app.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
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

    override suspend fun start(
        configJson: String,
        tunInterface: ParcelFileDescriptor?,
    ) = withContext(Dispatchers.IO) {
        stop()
        val binary = installer.installIfNeeded()
        configFile.parentFile?.mkdirs()
        configFile.writeText(configJson)

        val builder = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            buildLaunchScript(
                binary = binary,
                config = configFile,
                tunInterface = tunInterface
            )
        )
            .directory(context.filesDir)
            .redirectErrorStream(true)

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

    private fun buildLaunchScript(
        binary: File,
        config: File,
        tunInterface: ParcelFileDescriptor?,
    ): String {
        val binaryArg = shellQuote(binary.absolutePath)
        val configArg = shellQuote(config.absolutePath)
        if (tunInterface == null || tunInterface.fd < 0) {
            return "exec $binaryArg run -c $configArg"
        }

        val tunFd = tunInterface.fd
        val hostPid = android.os.Process.myPid()
        return buildString {
            append("exec 3<>")
            append(shellQuote("/proc/$hostPid/fd/$tunFd"))
            append("; export XRAY_TUN_FD=3; exec ")
            append(binaryArg)
            append(" run -c ")
            append(configArg)
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
