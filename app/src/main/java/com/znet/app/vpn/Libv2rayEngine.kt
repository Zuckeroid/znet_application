package com.znet.app.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.util.Log
import go.Seq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class Libv2rayEngine(
    private val context: Context
) : XrayEngine {

    private val appContext = context.applicationContext
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var controller: CoreController? = null

    override suspend fun ensureReady() = withContext(Dispatchers.IO) {
        initCoreEnvIfNeeded()
    }

    override suspend fun start(
        configJson: String,
        tunInterface: ParcelFileDescriptor?,
    ) = withContext(Dispatchers.IO) {
        stop()
        initCoreEnvIfNeeded()

        val nextController = CoreController(
            object : CoreCallbackHandler {
                override fun onEmitStatus(code: Long, message: String?): Long {
                    Log.d(TAG, "core status[$code]: ${message.orEmpty()}")
                    return 0
                }

                override fun shutdown(): Long {
                    Log.i(TAG, "core requested shutdown")
                    return 0
                }

                override fun startup(): Long {
                    Log.i(TAG, "core startup callback")
                    return 0
                }
            }
        )

        nextController.startLoop(configJson, tunInterface?.fd ?: 0)
        if (!nextController.isRunning) {
            throw IllegalStateException("Embedded Xray core failed to start")
        }

        controller = nextController
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val activeController = controller
        controller = null
        if (activeController != null) {
            runCatching {
                activeController.stopLoop()
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop embedded Xray core", error)
            }
        }
    }

    private fun initCoreEnvIfNeeded() {
        if (initialized.compareAndSet(false, true)) {
            Seq.setContext(appContext)
            val assetDir = resolveAssetDirectory()
            Libv2ray.initCoreEnv(assetDir.absolutePath, buildXudpBaseKey())
            Log.i(TAG, "Embedded Xray initialized: ${Libv2ray.checkVersionX()}")
        }
    }

    private fun resolveAssetDirectory(): File {
        val externalDir = appContext.getExternalFilesDir("xray-assets")
        val directory = externalDir ?: File(appContext.filesDir, "xray-assets")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    private fun buildXudpBaseKey(): String {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val keyBytes = androidId.toByteArray(Charsets.UTF_8).copyOf(32)
        return Base64.encodeToString(
            keyBytes,
            Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP
        )
    }

    private companion object {
        const val TAG = "Libv2rayEngine"
    }
}
