package com.znet.app.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class XrayBinaryInstaller(
    private val context: Context
) {
    private val binaryFile: File by lazy {
        File(context.applicationInfo.nativeLibraryDir, "libxray.so")
    }

    suspend fun installIfNeeded(): File = withContext(Dispatchers.IO) {
        if (binaryFile.exists()) {
            binaryFile.setReadable(true, false)
            binaryFile.setExecutable(true, false)
            return@withContext binaryFile
        }
        throw IllegalStateException(
            "Xray binary is missing. Put xray core at app/src/main/jniLibs/arm64-v8a/libxray.so"
        )
    }
}
