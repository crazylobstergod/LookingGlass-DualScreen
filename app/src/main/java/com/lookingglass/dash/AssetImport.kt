package com.lookingglass.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream

/** Import work survives Activity recreation; incomplete packs never replace a working pack. */
object AssetImport {
    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set
    @Volatile var failure: String? = null
        private set
    @Synchronized fun start(context: Context, uris: List<Uri>) {
        if (running) return
        running = true; failure = null; status = "Reading your game archives..."
        val app = context.applicationContext
        Thread({
            val work = File(app.cacheDir, "asset-import")
            try {
                work.deleteRecursively(); check(work.mkdirs())
                val raw = File(work, "raw").apply { mkdirs() }
                val pack = File(app.filesDir, "hud-new")
                pack.deleteRecursively(); check(pack.mkdirs())
                var total = 0L
                var count = 0
                // Original archive names establish override order independent of picker order.
                val ordered = uris.sortedBy { uri ->
                    if (uri.scheme == "file") File(requireNotNull(uri.path)).name.lowercase(Locale.ROOT) else app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0).lowercase(Locale.ROOT) else uri.toString()
                    } ?: uri.toString()
                }
                ordered.forEachIndexed { index, uri ->
                    status = "Extracting archive ${index + 1} of ${ordered.size}"
                    ZipInputStream(requireNotNull(app.contentResolver.openInputStream(uri)).buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name.replace('\\', '/').lowercase(Locale.ROOT)
                            require(!name.startsWith('/') && !name.split('/').contains("..")) { "Unsafe archive path" }
                            val wanted = listOf("textures/", "models/ui/", "maps/", "scripts/", "fonts/", "gfx/2d/fonts/").any { name.startsWith(it) } &&
                                listOf(".ftx", ".tga", ".jpg", ".png", ".bsp", ".shader", ".ritualfont").any { name.endsWith(it) }
                            if (wanted && !entry.isDirectory) {
                                require(++count <= 20000) { "Too many asset files" }
                                val dest = File(raw, name)
                                require(dest.canonicalPath.startsWith(raw.canonicalPath + File.separator))
                                dest.parentFile!!.mkdirs()
                                dest.outputStream().use { out ->
                                    val buffer = ByteArray(65536); var size = 0L
                                    while (true) {
                                        val n = zip.read(buffer); if (n < 0) break
                                        size += n; total += n
                                        require(size <= 128L * 1024 * 1024 && total <= 2L * 1024 * 1024 * 1024) { "Archive exceeds import limits" }
                                        out.write(buffer, 0, n)
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                status = "Preparing icons and lettering"
                makeHud(raw, pack)
                val maps = File(raw, "maps").listFiles()?.filter { it.extension == "bsp" }?.sortedBy { it.name } ?: emptyList()
                require(maps.isNotEmpty()) { "No maps found. Select all original game PK3 files together." }
                val baker = MapBaker(raw)
                val manifest = JSONObject()
                maps.forEachIndexed { index, map ->
                    status = "Texturing map ${index + 1} of ${maps.size}: ${MapNames.display(map.nameWithoutExtension)}"
                    manifest.put(map.nameWithoutExtension, baker.bake(map, File(pack, "minimaps/${map.nameWithoutExtension}.png")))
                }
                File(pack, "minimaps.json").writeText(manifest.toString())
                File(pack, "pack-complete.json").writeText(JSONObject().put("format", 1).put("maps", maps.size).toString())
                val current = HudAssets.root(app)
                val old = File(app.filesDir, "hud-old")
                old.deleteRecursively()
                if (current.exists()) check(current.renameTo(old)) { "Could not preserve previous artwork" }
                if (!pack.renameTo(current)) {
                    old.renameTo(current)
                    error("Could not activate prepared artwork")
                }
                old.deleteRecursively()
                status = "Ready: ${maps.size} textured maps"
            } catch (e: Exception) {
                failure = e.message ?: "Unable to read these archives"
                status = "Import failed"
                android.util.Log.e("AliceDash", "Asset import failed", e)
            } finally {
                work.deleteRecursively()
                running = false
            }
        }, "alice-asset-import").start()
    }

    private fun makeHud(raw: File, pack: File) {
        val icons = mapOf("knife" to "knife", "cards" to "cards", "mallet" to "mallet", "jackbomb" to "jackbomb", "icewand" to "icewand", "jacks" to "jacks", "demondice" to "dice1", "eyestaff" to "eyestaff", "blunderbuss" to "blunderbuss", "watch" to "watch", "rage" to "ragebox", "tea" to "tea", "deadtime" to "deadtime")
        for ((slot, name) in icons) {
            val source = File(raw, "models/ui/$name.ftx")
            require(source.isFile) { "Missing $name. Select all original PK3 archives together." }
            val bitmap = AssetImages.decode(source)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var minx = bitmap.width; var miny = bitmap.height; var maxx = -1; var maxy = -1
            pixels.forEachIndexed { i, c -> if (c ushr 24 != 0) {
                val x = i % bitmap.width; val y = i / bitmap.width
                minx = minOf(minx, x); miny = minOf(miny, y); maxx = maxOf(maxx, x); maxy = maxOf(maxy, y)
            } }
            val cropped = if (maxx >= minx) Bitmap.createBitmap(bitmap, minx, miny, maxx-minx+1, maxy-miny+1) else bitmap
            AssetImages.save(cropped, File(pack, "icons/$slot.png"))
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
        }
        for (name in listOf("skin_health02", "skin_mana01")) {
            val source = File(raw, "models/ui").walkTopDown().firstOrNull { it.isFile && it.name == "$name.ftx" }
                ?: error("Missing meter $name")
            val bitmap = AssetImages.decode(source)
            AssetImages.save(bitmap, File(pack, "meters/$name.png")); bitmap.recycle()
        }
        val font = File(raw, "fonts/asrafel.ritualfont").readText().replace(Regex("//[^\\n]*"), "")
        val tokens = Regex("[^\\s{}]+|[{}]").findAll(font).map { it.value }.toList()
        val json = JSONObject().put("height", tokens[tokens.indexOf("height") + 1].toDouble())
        var i = tokens.indexOf("indirections") + 2
        val ind = JSONArray()
        while (tokens[i] != "}") ind.put(tokens[i++].toInt())
        require(ind.length() == 256) { "Unsupported font" }
        json.put("indirections", ind)
        i = tokens.indexOf("locations") + 2
        val glyphs = JSONArray()
        while (i < tokens.size && tokens[i] == "{") {
            glyphs.put(JSONArray((1..4).map { tokens[i + it].toDouble() })); i += 6
        }
        json.put("glyphs", glyphs)
        File(pack, "font").mkdirs(); File(pack, "font/font.json").writeText(json.toString())
        val atlas = AssetImages.decode(File(raw, "gfx/2d/fonts/asrafel.tga"))
        val pixels = IntArray(atlas.width * atlas.height)
        atlas.getPixels(pixels, 0, atlas.width, 0, 0, atlas.width, atlas.height)
        for (j in pixels.indices) pixels[j] = (pixels[j] and -0x1000000) or 0xffffff
        atlas.setPixels(pixels, 0, atlas.width, 0, 0, atlas.width, atlas.height)
        AssetImages.save(atlas, File(pack, "font/atlas.png")); atlas.recycle()
    }
}

internal object AssetImages {
    fun save(bitmap: Bitmap, file: File) { file.parentFile!!.mkdirs(); file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
    fun decode(file: File): Bitmap {
        if (file.extension !in listOf("ftx", "tga")) return requireNotNull(BitmapFactory.decodeFile(file.path)) { "Invalid image ${file.name}" }
        val data = file.readBytes(); val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val w: Int; val h: Int; val pixels: IntArray
        if (file.extension == "ftx") {
            w = b.int; h = b.int; b.int
            require(w in 1..8192 && h in 1..8192 && w.toLong()*h <= 16777216 && data.size.toLong() == 12L+w.toLong()*h*4) { "Invalid FTX image" }
            pixels = IntArray(w*h) { val r=b.get().toInt() and 255;val g=b.get().toInt() and 255;val bl=b.get().toInt() and 255;val a=b.get().toInt() and 255; (a shl 24) or (r shl 16) or (g shl 8) or bl }
        } else {
            require(data.size >= 18)
            val id=data[0].toInt() and 255; val kind=data[2].toInt() and 255
            w=b.getShort(12).toInt() and 65535;h=b.getShort(14).toInt() and 65535
            val bits=data[16].toInt() and 255;val descriptor=data[17].toInt() and 255
            require(data[1].toInt()==0 && kind in listOf(2,10) && bits in listOf(24,32) && w in 1..8192 && h in 1..8192 && w.toLong()*h<=16777216) { "Unsupported TGA" }
            b.position(18+id);pixels=IntArray(w*h);var index=0
            fun pixel():Int {val bl=b.get().toInt() and 255;val g=b.get().toInt() and 255;val r=b.get().toInt() and 255;val a=if(bits==32) b.get().toInt() and 255 else 255;return (a shl 24) or (r shl 16) or (g shl 8) or bl}
            fun put(c:Int) {require(index<pixels.size);val sx=index%w;val sy=index/w;val x=if(descriptor and 16 !=0) w-1-sx else sx;val y=if(descriptor and 32 !=0) sy else h-1-sy;pixels[y*w+x]=c;index++}
            while(index<pixels.size) {
                if(kind==2) put(pixel()) else {
                    val header=b.get().toInt() and 255;val count=(header and 127)+1
                    if(header and 128 !=0) {val c=pixel();repeat(count){put(c)}} else repeat(count){put(pixel())}
                }
            }
        }
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {setPixels(pixels,0,w,0,0,w,h)}
    }
}
