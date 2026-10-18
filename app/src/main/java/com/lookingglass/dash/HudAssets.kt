package com.lookingglass.dash

import android.content.Context
import java.io.File
import java.io.InputStream

/** Locally generated artwork supplied by the game owner, never bundled. */
object HudAssets {
    fun root(context: Context): File = File(context.filesDir, "hud")
    fun open(context: Context, path: String): InputStream {
        require(path.startsWith("hud/") && !path.contains(".."))
        return File(root(context), path.removePrefix("hud/")).inputStream()
    }
    fun ready(context: Context): Boolean = try {
        val root = root(context)
        listOf("pack-complete.json", "font/font.json", "font/atlas.png", "minimaps.json", "icons/knife.png").all { File(root, it).isFile }
    } catch (_: Exception) { false }
}
