package com.lookingglass.dash

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

/** One Activity owns both displays. The native launcher's imported base/ is the only input. */
class DualScreenController(private val activity: Activity, private val base: File) {
    private val handler = Handler(Looper.getMainLooper())
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private val prefs = activity.getSharedPreferences("dual_screen", 0)
    private var active = false
    private var presentation: Presentation? = null
    private var message: TextView? = null
    private var feed: StateFeed? = null
    private var showingHud = false
    @Volatile var importing = false
    private var attempted = ""
    private var bakingSignature: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            try { update() } catch (e: Exception) {
                android.util.Log.e("AliceDash", "Secondary display unavailable", e)
                dismiss()
            }
            handler.postDelayed(this, 500)
        }
    }

    fun resume() {
        if (active) return
        active = true
        handler.post(tick)
    }

    fun pause() {
        active = false
        handler.removeCallbacks(tick)
        dismiss()
    }

    private fun dismiss() {
        presentation?.dismiss(); presentation = null
        feed?.stop(); feed = null
        message = null; showingHud = false
    }

    private fun update() {
        val display = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (display == null) { dismiss(); return }
        if (presentation?.display?.displayId != display.displayId || presentation?.isShowing != true) {
            dismiss()
            presentation = Presentation(activity, display).also {
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.show()
            }
        }
        val archives = base.listFiles()?.filter { it.isFile && it.extension.equals("pk3", true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val signature = archives.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        val pending = bakingSignature
        if (pending != null && !AssetImport.running) {
            if (AssetImport.failure == null && HudAssets.ready(activity)) {
                prefs.edit().putString("archives", pending).apply()
            }
            bakingSignature = null
        }
        if (!importing && archives.isNotEmpty() && !AssetImport.running &&
            signature != attempted && (signature != prefs.getString("archives", "") || !HudAssets.ready(activity))) {
            attempted = signature
            bakingSignature = signature
            showMessage("Preparing your second screen…")
            AssetImport.start(activity, archives.map { Uri.fromFile(it) })
        }
        when {
            importing -> showMessage("Importing your game…\nBoth screens will use the same files.")
            AssetImport.running -> showMessage("Preparing your second screen\n\n${AssetImport.status}\n\nYou can continue on the main screen.")
            HudAssets.ready(activity) -> {
                if (!showingHud) {
                    feed = StateFeed().also { it.start() }
                    presentation?.setContentView(HudView(presentation!!.context, feed!!))
                    showingHud = true; message = null
                    android.util.Log.i("AliceDash", "Integrated HUD ready on display ${display.displayId}")
                }
            }
            AssetImport.failure != null -> {
                showMessage("Second-screen setup needs another try\n\n${AssetImport.failure}\n\nTap here to retry, or select your complete game folder on the main screen.")
                message?.setOnClickListener { attempted = "" }
            }
            else -> showMessage("Looking Glass\n\nChoose your Alice game folder on the main screen.\nYour map and inventory will appear here automatically.")
        }
    }

    private fun showMessage(text: String) {
        if (message == null) {
            feed?.stop(); feed = null; showingHud = false
            message = TextView(presentation!!.context).apply {
                setBackgroundColor(Color.rgb(26, 5, 5))
                setTextColor(Color.rgb(240, 227, 200))
                textSize = 22f; gravity = Gravity.CENTER
                setPadding(48, 32, 48, 32)
            }
            presentation?.setContentView(requireNotNull(message))
        }
        message?.text = text
    }
}

