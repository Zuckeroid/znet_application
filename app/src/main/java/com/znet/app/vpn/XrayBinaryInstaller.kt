package com.znet.app.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class XrayBinaryInstaller(
    private val context: Context
) {
    private val installDir = File(context.filesDir, "xray")
    private val binaryFile = File(installDir, "xray")

    suspend fun installIfNeeded(): File = withContext(Dispatchers.IO) {
        if (binaryFile.exists()) {
            binaryFile.setExecutable(true)
            return@withContext binaryFile
        }

        installDir.mkdirs()
        runCatching {
            context.assets.open("xray/xray").use { input ->
                binaryFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }.getOrElse {
            throw IllegalStateException(
                "Xray binary is missing. Put xray core at app/src/main/assets/xray/xray",
                it
            )
        }

        binaryFile.setExecutable(true)
        binaryFile
    }
}
