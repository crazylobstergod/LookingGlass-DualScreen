package com.lookingglass.dash

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One frame of LookingGlass Alice player state, parsed from the game's
 * newline-delimited JSON export (see `src/state_export.rs` in the game).
 */
data class Snapshot(
    val t: Long,
    val map: String,
    val pos: FloatArray,   // [x, y, z]
    val yaw: Float,        // radians
    val health: Float,     // sanity, 0..=100
    val will: Float,       // 0..=100
    val alive: Boolean,
    val weapon: Int?,      // equipped slot, null when unarmed
    val selected: Int,
    val weaponName: String,
    val owned: IntArray,   // 10 weapon copy counts
    val powers: FloatArray, // [rage, tea, stopped, recharge]
    val inventory: List<String>,
)

/**
 * Reads the game's live state over loopback TCP (127.0.0.1:4781) and keeps the
 * latest parsed [Snapshot] in [latest]. A single background thread owns the
 * socket and reconnects with backoff if the game is (re)started.
 *
 * The HUD view polls [latest] on its own tick loop, so this class only ever
 * writes to a volatile reference — no locks needed.
 */
class StateFeed(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) {
    @Volatile
    var latest: Snapshot? = null
        private set

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    private val pendingEquip = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun equip(slot: Int): Boolean {
        val s = latest ?: return false
        if (!connected || !s.alive || slot !in 0..9 || s.owned.getOrElse(slot) { 0 } <= 0) return false
        pendingEquip.set(JSONObject().put("equip", slot).put("map", s.map).toString() + "\n")
        return true
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "alice-dash-feed").also { it.start() }
    }

    fun stop() {
        running = false
        connected = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var backoffMs = 250L
        while (running) {
            val socket = Socket()
            val reader: BufferedReader? = try {
                socket.soTimeout = 1000
                socket.connect(InetSocketAddress(host, port), 1000)
                connected = true
                backoffMs = 250L
                Log.i(TAG, "connected to $host:$port")
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            } catch (e: Exception) {
                connected = false
                Log.d(TAG, "not connected yet (${e.message}); retry in ${backoffMs}ms")
                try { socket.close() } catch (_: Exception) {}
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
                backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                continue
            }

            try {
                while (running) {
                    pendingEquip.getAndSet(null)?.let {
                        socket.getOutputStream().write(it.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                    }
                    val line = try {
                        reader?.readLine()
                    } catch (e: java.net.SocketTimeoutException) {
                        // No frame within soTimeout (e.g. the game is paused in
                        // a cutscene/menu). The socket is still alive — keep it
                        // and wait for the next frame rather than tearing down
                        // and reconnecting.
                        continue
                    } ?: break
                    if (line.isBlank()) continue
                    parse(line)?.let { latest = it }
                }
            } catch (e: Exception) {
                Log.d(TAG, "read ended: ${e.message}")
            } finally {
                connected = false
                pendingEquip.set(null)
                try { reader?.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun parse(line: String): Snapshot? = try {
        val o = JSONObject(line)

        val posJa = o.optJSONArray("pos")
        val pos = floatArrayOf(
            posJa?.optDouble(0, 0.0)?.toFloat() ?: 0f,
            posJa?.optDouble(1, 0.0)?.toFloat() ?: 0f,
            posJa?.optDouble(2, 0.0)?.toFloat() ?: 0f,
        )

        val ownedJa = o.optJSONArray("owned")
        val owned = IntArray(10)
        for (i in 0 until 10) owned[i] = ownedJa?.optInt(i, 0) ?: 0

        val powersJa = o.optJSONArray("powers")
        val powers = FloatArray(4)
        for (i in 0 until 4) powers[i] = powersJa?.optDouble(i, 0.0)?.toFloat() ?: 0f

        val invJa = o.optJSONArray("inventory")
        val inventory = if (invJa != null) {
            val list = ArrayList<String>(invJa.length())
            for (i in 0 until invJa.length()) list.add(invJa.optString(i, ""))
            list
        } else emptyList()

        Snapshot(
            t = o.optLong("t", 0L),
            map = o.optString("map", ""),
            pos = pos,
            yaw = o.optDouble("yaw", 0.0).toFloat(),
            health = o.optDouble("health", 0.0).toFloat(),
            will = o.optDouble("will", 0.0).toFloat(),
            alive = o.optBoolean("alive", false),
            weapon = if (o.isNull("weapon")) null else o.optInt("weapon"),
            selected = o.optInt("selected", 0),
            weaponName = o.optString("weapon_name", ""),
            owned = owned,
            powers = powers,
            inventory = inventory,
        )
    } catch (e: Exception) {
        Log.w(TAG, "bad snapshot line: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "AliceDash"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 4781
    }
}
