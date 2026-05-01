package com.sightsense.infra.litert

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object ModelLoader {
    fun loadAsByteBuffer(context: Context, assetPath: String): MappedByteBuffer {
        val fd = try {
            context.assets.openFd(assetPath)
        } catch (e: Exception) {
            throw IllegalStateException("Model asset not found: $assetPath", e)
        }
        return fd.use {
            FileInputStream(it.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                it.startOffset,
                it.declaredLength
            )
        }
    }
}