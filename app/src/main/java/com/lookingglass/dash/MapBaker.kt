package com.lookingglass.dash

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.*

/** CPU top-down baker for owner-supplied FAKK v42 maps. No native dependency. */
internal class MapBaker(private val raw: File) {
    private data class Tile(val w: Int, val h: Int, val pixels: IntArray)
    private val materials = HashMap<String, List<String>>()
    private val textures = object : LinkedHashMap<String, List<Tile>?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Tile>?>?) = size > 32
    }
    init {
        File(raw, "scripts").walkTopDown().filter { it.isFile && it.extension == "shader" }.sortedBy { it.name }.forEach { file ->
            val tokens = Regex("[^\\s{}]+|[{}]").findAll(file.readText().replace(Regex("//[^\\n]*"), "")).map { it.value }.toList()
            var i = 0
            while (i + 1 < tokens.size) {
                val name = tokens[i++].lowercase(Locale.ROOT)
                if (tokens[i] != "{") continue
                var depth = 1; i++; val body = ArrayList<String>()
                while (i < tokens.size && depth > 0) {
                    val t = tokens[i++]; if(t=="{")depth++;if(t=="}")depth--;body.add(t)
                }
                val candidates = ArrayList<String>()
                for (j in 0 until body.size - 1) {
                    when (body[j].lowercase(Locale.ROOT)) {
                        "map", "clampmap", "qer_editorimage" -> if (!body[j+1].startsWith('$')) candidates.add(body[j+1].lowercase(Locale.ROOT))
                        "animmap" -> if (j+2<body.size) candidates.add(body[j+2].lowercase(Locale.ROOT))
                    }
                }
                materials[name] = candidates
            }
        }
    }
    private fun texture(name: String): List<Tile>? {
        if (textures.containsKey(name)) return textures[name]
        for (candidate in listOf(name) + (materials[name] ?: emptyList())) {
            val stem = candidate.replace(Regex("\\.(ftx|tga|png|jpg)$"), "")
            if (stem.contains("..") || stem.startsWith('/')) continue
            for (ext in listOf("ftx", "tga", "jpg", "png")) {
                val file = File(raw, "$stem.$ext")
                if (!file.isFile) continue
                try {
                    var bitmap = AssetImages.decode(file)
                    if (bitmap.width>256 || bitmap.height>256) {
                        val scaled=Bitmap.createScaledBitmap(bitmap,min(256,bitmap.width),min(256,bitmap.height),true)
                        if (scaled !== bitmap) bitmap.recycle();bitmap=scaled
                    }
                    val levels=ArrayList<Tile>()
                    while (true) {
                        val pixels=IntArray(bitmap.width*bitmap.height)
                        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                        levels.add(Tile(bitmap.width,bitmap.height,pixels))
                        if (min(bitmap.width,bitmap.height)<=1) {bitmap.recycle();break}
                        val next=Bitmap.createScaledBitmap(bitmap,max(1,bitmap.width/2),max(1,bitmap.height/2),true)
                        bitmap.recycle();bitmap=next
                    }
                    textures[name]=levels;return levels
                } catch (_: Exception) { /* unnamed or unsupported material: neutral fallback */ }
            }
        }
        textures[name]=null;return null
    }

    fun bake(file: File, output: File): JSONObject {
        val data=file.readBytes();val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size>=172 && String(data,0,4,Charsets.US_ASCII)=="FAKK" && b.getInt(4)==42) { "Unsupported map ${file.name}" }
        val offsets=IntArray(20);val lengths=IntArray(20)
        for(i in 0..19) { offsets[i]=b.getInt(12+i*8);lengths[i]=b.getInt(16+i*8)
            require(offsets[i]>=0 && lengths[i]>=0 && offsets[i].toLong()+lengths[i]<=data.size) { "Invalid map data" }
        }
        require(lengths[0]%76==0 && lengths[3]%108==0 && lengths[4]%44==0 && lengths[5]%4==0 && lengths[13]>=40)
        val shaders=Array(lengths[0]/76) {i ->
            val at=offsets[0]+i*76;var end=at;while(end<at+64 && data[end].toInt()!=0)end++
            String(data,at,end-at,Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        }
        val verts=Array(lengths[4]/44) { i -> FloatArray(10) { j -> b.getFloat(offsets[4]+i*44+j*4) } }
        val model=offsets[13];val minx=b.getFloat(model);val miny=b.getFloat(model+4)
        val maxx=b.getFloat(model+12);val maxy=b.getFloat(model+16)
        require(maxx>minx && maxy>miny && listOf(minx,miny,maxx,maxy).all {it.isFinite()})
        val w=max(8,(1024*(maxx-minx)/max(maxx-minx,maxy-miny)).roundToInt())
        val h=max(8,(1024*(maxy-miny)/max(maxx-minx,maxy-miny)).roundToInt())
        val pixels=IntArray(w*h) {0xff130c0e.toInt()};val depth=FloatArray(w*h) {Float.NEGATIVE_INFINITY}
        fun triangle(a:FloatArray,c:FloatArray,d:FloatArray,levels:List<Tile>?,lightmap:Int) {
            if ((a[9]+c[9]+d[9])/3<.15f || !a.all {it.isFinite()} || !c.all {it.isFinite()} || !d.all {it.isFinite()}) return
            val ax=(a[0]-minx)/(maxx-minx)*w;val ay=(a[1]-miny)/(maxy-miny)*h
            val bx=(c[0]-minx)/(maxx-minx)*w;val by=(c[1]-miny)/(maxy-miny)*h
            val cx=(d[0]-minx)/(maxx-minx)*w;val cy=(d[1]-miny)/(maxy-miny)*h
            val den=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
            if(abs(den)<.01f)return
            val x0=max(0,floor(min(ax,min(bx,cx))).toInt());val x1=min(w-1,ceil(max(ax,max(bx,cx))).toInt())
            val y0=max(0,floor(min(ay,min(by,cy))).toInt());val y1=min(h-1,ceil(max(ay,max(by,cy))).toInt())
            var tile:Tile?=null
            if(levels!=null) {
                val dx1=bx-ax;val dy1=by-ay;val dx2=cx-ax;val dy2=cy-ay
                val det=dx1*dy2-dx2*dy1
                val dudx=((c[3]-a[3])*dy2-(d[3]-a[3])*dy1)/det
                val dudy=(dx1*(d[3]-a[3])-dx2*(c[3]-a[3]))/det
                val dvdx=((c[4]-a[4])*dy2-(d[4]-a[4])*dy1)/det
                val dvdy=(dx1*(d[4]-a[4])-dx2*(c[4]-a[4]))/det
                val tw=levels[0].w;val th=levels[0].h
                val footprint=max(hypot(dudx*tw,dvdx*th),hypot(dudy*tw,dvdy*th))
                val level=max(0,log2(max(1f,footprint)).toInt()).coerceAtMost(levels.size-1)
                tile=levels[level]
            }
            for(y in y0..y1)for(x in x0..x1) {
                val u=((by-cy)*(x+.5f-cx)+(cx-bx)*(y+.5f-cy))/den
                val v=((cy-ay)*(x+.5f-cx)+(ax-cx)*(y+.5f-cy))/den;val q=1-u-v
                if(u<-.00001f || v<-.00001f || q<-.00001f)continue
                val z=u*a[2]+v*c[2]+q*d[2];val at=y*w+x;if(z<=depth[at])continue
                var p00=0xff756c5b.toInt();var p10=p00;var p01=p00;var p11=p00;var fx=0f;var fy=0f
                if(tile!=null) {
                    val tx=(u*a[3]+v*c[3]+q*d[3])*tile.w-.5f;val ty=(u*a[4]+v*c[4]+q*d[4])*tile.h-.5f
                    val ix=floor(tx).toInt();val iy=floor(ty).toInt();fx=tx-ix;fy=ty-iy
                    val xx=Math.floorMod(ix,tile.w);val yy=Math.floorMod(iy,tile.h)
                    p00=tile.pixels[yy*tile.w+xx];p10=tile.pixels[yy*tile.w+(xx+1)%tile.w]
                    p01=tile.pixels[((yy+1)%tile.h)*tile.w+xx];p11=tile.pixels[((yy+1)%tile.h)*tile.w+(xx+1)%tile.w]
                }
                val f00=(1-fx)*(1-fy);val f10=fx*(1-fy);val f01=(1-fx)*fy;val f11=fx*fy
                val alpha=(p00 ushr 24)*f00+(p10 ushr 24)*f10+(p01 ushr 24)*f01+(p11 ushr 24)*f11
                if(alpha<=80)continue
                var light=-1
                if(lightmap>=0 && (lightmap.toLong()+1)*128*128*3<=lengths[2]) {
                    val lx=((u*a[5]+v*c[5]+q*d[5])*128).toInt().coerceIn(0,127)
                    val ly=((u*a[6]+v*c[6]+q*d[6])*128).toInt().coerceIn(0,127)
                    light=offsets[2]+lightmap*128*128*3+(ly*128+lx)*3
                }
                var color=-0x1000000
                for(channel in 0..2) {
                    val shift=(2-channel)*8
                    var value=((p00 ushr shift)and 255)*f00+((p10 ushr shift)and 255)*f10+((p01 ushr shift)and 255)*f01+((p11 ushr shift)and 255)*f11
                    value*=if(light>=0) .62f+.65f*(data[light+channel].toInt() and 255)/255 else .92f
                    color=color or ((value*1.12f+7).toInt().coerceIn(0,255) shl shift)
                }
                pixels[at]=color;depth[at]=z
            }
        }
        val first=b.getInt(model+24);val count=b.getInt(model+28)
        require(first>=0 && count>=0 && first.toLong()+count<=lengths[3]/108)
        for(si in first until first+count) {
            val s=offsets[3]+si*108;val shader=b.getInt(s);val kind=b.getInt(s+8)
            require(shader in shaders.indices)
            val name=shaders[shader]
            if(listOf("sky","caulk","nodraw","trigger","clip","common/").any {name.contains(it)})continue
            val fv=b.getInt(s+12);val nv=b.getInt(s+16);val fi=b.getInt(s+20);val ni=b.getInt(s+24);val lm=b.getInt(s+28)
            require(fv>=0 && nv>=0 && fv.toLong()+nv<=verts.size && fi>=0 && ni>=0 && fi.toLong()+ni<=lengths[5]/4)
            val tex=texture(name)
            if(kind==1 || kind==3) {
                require(ni%3==0)
                for(i in 0 until ni step 3) {
                    val ids=IntArray(3) { b.getInt(offsets[5]+(fi+i+it)*4) }
                    require(ids.all {it in 0 until nv})
                    triangle(verts[fv+ids[0]],verts[fv+ids[1]],verts[fv+ids[2]],tex,lm)
                }
            } else if(kind==2) {
                val pw=b.getInt(s+96);val ph=b.getInt(s+100)
                require(pw in 3..65 && ph in 3..65 && pw%2==1 && ph%2==1 && pw*ph==nv)
                for(iy in 0 until ph-2 step 2)for(ix in 0 until pw-2 step 2) {
                    val patch=Array(25){FloatArray(10)}
                    for(yy in 0..4)for(xx in 0..4) {
                        val ty=yy/4f;val tx=xx/4f
                        val by=floatArrayOf((1-ty)*(1-ty),2*ty*(1-ty),ty*ty)
                        val bx=floatArrayOf((1-tx)*(1-tx),2*tx*(1-tx),tx*tx)
                        for(j in 0..2)for(k in 0..2)for(component in 0..9)
                            patch[yy*5+xx][component]+=by[j]*bx[k]*verts[fv+(iy+j)*pw+ix+k][component]
                    }
                    for(yy in 0..3)for(xx in 0..3) {
                        triangle(patch[yy*5+xx],patch[(yy+1)*5+xx],patch[yy*5+xx+1],tex,lm)
                        triangle(patch[(yy+1)*5+xx],patch[(yy+1)*5+xx+1],patch[yy*5+xx+1],tex,lm)
                    }
                }
            }
        }
        for(y in 0 until h)for(x in 0 until w) {
            val at=y*w+x;if(!depth[at].isFinite())continue
            val edge=(x>0 && !depth[at-1].isFinite()) || (x<w-1 && !depth[at+1].isFinite()) || (y>0 && !depth[at-w].isFinite()) || (y<h-1 && !depth[at+w].isFinite())
            if(edge) {val old=pixels[at];pixels[at]=-0x1000000 or (((((old ushr 16)and 255)*.65f+70).toInt().coerceAtMost(255)) shl 16) or (((((old ushr 8)and 255)*.65f+61).toInt().coerceAtMost(255)) shl 8) or (((old and 255)*.65f+44).toInt().coerceAtMost(255))}
        }
        val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        AssetImages.save(bitmap,output);bitmap.recycle()
        return JSONObject().put("w",w).put("h",h).put("minx",minx).put("miny",miny).put("maxx",maxx).put("maxy",maxy)
    }
}
