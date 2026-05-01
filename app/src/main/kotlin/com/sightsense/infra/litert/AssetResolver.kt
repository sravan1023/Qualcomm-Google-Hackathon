package com.sightsense.infra.litert

import android.content.Context

object AssetResolver {
    fun firstExisting(context: Context, candidates: List<String>): String? {
        for (path in candidates) {
            if (exists(context, path)) return path
        }
        return null
    }

    fun exists(context: Context, path: String): Boolean {
        return try {
            context.assets.open(path).close()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
