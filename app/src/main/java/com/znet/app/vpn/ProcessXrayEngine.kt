package com.znet.app.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ProcessXrayEngine(
    private val context: Context,
    private val installer: XrayBinaryInstaller = XrayBinaryInstaller(context)
) : XrayEngine {

    private var process: Process? = null
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
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        process?.destroy()
        process?.waitFor()
        process = null
    }
}
