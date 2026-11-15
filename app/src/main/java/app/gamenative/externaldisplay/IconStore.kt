package app.gamenative.externaldisplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.Inflater

/**
 * wow-mobile: spell icons for the Target page's buffs/debuffs, read from the player's own game
 * files (nothing of the game's artwork ships with the app).
 *
 * The WoWMobileLink addon sends each aura's icon as a hash of its file name (see iconHash in
 * WoWMobileLink.lua). Here the icon lists ("(listfile)") of the game's locale archives, where
 * 3.3.5 keeps Interface\Icons, are hashed the same way; an icon is decoded from its BLP file
 * the first time it's needed and kept as a PNG in the app's files.
 */
object IconStore {
    private const val TAG = "IconStore"

    /** The game's folder (the one with Data in it), set when the game starts. */
    @Volatile private var gameDir: File? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val memory = LruCache<Int, Bitmap>(256)
    private val failed = HashSet<Int>()
    private val pending = HashSet<Int>()
    private var index: Map<Int, Pair<Mpq, String>>? = null
    private var cacheDir: File? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(gameRoot: File, cache: File) {
        if (gameDir == gameRoot) return
        gameDir = gameRoot
        cacheDir = File(cache, "icons").apply { mkdirs() }
        worker.execute { index = null; buildIndex() }
    }

    /** Main thread only: called when an icon becomes available. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** The icon for [hash], or null while it's being loaded (listeners are told) or if it can't be. */
    fun get(hash: Int): Bitmap? {
        memory.get(hash)?.let { return it }
        synchronized(this) {
            if (hash in failed || hash in pending) return null
            pending += hash
        }
        worker.execute {
            val bitmap = load(hash)
            synchronized(this) {
                pending -= hash
                if (bitmap == null) failed += hash
            }
            if (bitmap != null) {
                memory.put(hash, bitmap)
                main.post { listeners.toList().forEach { it() } }
            }
        }
        return null
    }

    /** Same as the addon's iconHash: h = (h * 31 + byte) mod 16777213 over the lower-case file name. */
    fun hash(fileName: String): Int {
        var base = fileName.substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT)
        if (base.endsWith(".blp")) base = base.dropLast(4)
        var h = 0L
        for (c in base.toByteArray(Charsets.ISO_8859_1)) h = (h * 31 + (c.toInt() and 0xFF)) % 16777213
        return h.toInt()
    }

    private fun load(hash: Int): Bitmap? {
        cacheDir?.let { dir ->
            val png = File(dir, "$hash.png")
            if (png.isFile) android.graphics.BitmapFactory.decodeFile(png.path)?.let { return it }
        }
        if (index == null) buildIndex()
        val (mpq, name) = index?.get(hash) ?: return null
        return try {
            val data = mpq.read(name) ?: return null
            val bitmap = Blp.decode(data) ?: return null
            cacheDir?.let { dir -> File(dir, "$hash.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "can't read $name: $e")
            null
        }
    }

    /** Hashes every Interface\Icons file of the locale archives, newest patch first. */
    private fun buildIndex() {
        val data = gameDir?.let { File(it, "Data") } ?: return
        val localeDir = data.listFiles()?.firstOrNull { d ->
            d.isDirectory && d.name.length == 4 && d.listFiles()?.any { it.name.lowercase(Locale.ROOT).startsWith("locale-") } == true
        } ?: return
        val loc = localeDir.name
        val names = listOf("patch-$loc-3.MPQ", "patch-$loc-2.MPQ", "patch-$loc.MPQ", "lichking-locale-$loc.MPQ",
            "expansion-locale-$loc.MPQ", "locale-$loc.MPQ")
        val out = HashMap<Int, Pair<Mpq, String>>()
        for (n in names) {
            val file = localeDir.listFiles()?.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: continue
            try {
                val mpq = Mpq(file)
                val list = mpq.read("(listfile)") ?: continue
                for (line in String(list, Charsets.ISO_8859_1).split('\r', '\n')) {
                    val lower = line.lowercase(Locale.ROOT)
                    if (!lower.startsWith("interface\\icons\\") || !lower.endsWith(".blp")) continue
                    out.putIfAbsent(hash(line), mpq to line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "can't open $file: $e")
            }
        }
        Log.i(TAG, "${out.size} icons in ${localeDir.path}")
        index = out
    }

    /** A read-only MPQ (format 1) archive: zlib-compressed or stored files, not encrypted ones. */
    class Mpq(file: File) {
        private val raf = RandomAccessFile(file, "r")
        private val sectorSize: Int
        private val hashes: IntArray
        private val blocks: IntArray
        private val hashCount: Int

        init {
            val hdr = ByteArray(32)
            raf.readFully(hdr)
            require(hdr[0] == 'M'.code.toByte() && hdr[1] == 'P'.code.toByte() && hdr[2] == 'Q'.code.toByte() && hdr[3] == 0x1A.toByte()) { "not an MPQ" }
            sectorSize = 512 shl u16(hdr, 14)
            val hashPos = u32(hdr, 16)
            val blockPos = u32(hdr, 20)
            hashCount = u32(hdr, 24).toInt()
            val blockCount = u32(hdr, 28).toInt()
            hashes = readTable(hashPos, hashCount * 4, hashString("(hash table)", 3))
            blocks = readTable(blockPos, blockCount * 4, hashString("(block table)", 3))
        }

        private fun readTable(pos: Long, ints: Int, key: Int): IntArray {
            val raw = ByteArray(ints * 4)
            synchronized(raf) { raf.seek(pos); raf.readFully(raw) }
            val words = IntArray(ints) { i -> s32(raw, i * 4) }
            decrypt(words, key)
            return words
        }

        fun read(name: String): ByteArray? {
            val a = hashString(name, 1)
            val b = hashString(name, 2)
            val start = ((hashString(name, 0).toLong() and 0xFFFFFFFFL) % hashCount).toInt()
            var i = start
            var block = -1
            while (true) {
                val blockIndex = hashes[i * 4 + 3]
                if (blockIndex == -1) return null
                if (hashes[i * 4] == a && hashes[i * 4 + 1] == b && blockIndex != -2) { block = blockIndex; break }
                i = (i + 1) % hashCount
                if (i == start) return null
            }
            val offset = blocks[block * 4].toLong() and 0xFFFFFFFFL
            val csize = blocks[block * 4 + 1]
            val fsize = blocks[block * 4 + 2]
            val flags = blocks[block * 4 + 3]
            if (flags and 0x80000000.toInt() == 0 || flags and 0x10000 != 0) return null
            val raw = ByteArray(csize)
            synchronized(raf) { raf.seek(offset); raf.readFully(raw) }
            val compressed = flags and 0x200 != 0
            if (flags and 0x1000000 != 0) return if (compressed && csize < fsize) unpack(raw, 0, csize, fsize) else raw.copyOf(fsize)
            if (!compressed) return raw.copyOf(fsize)
            val sectors = (fsize + sectorSize - 1) / sectorSize
            val out = ByteArray(fsize)
            for (s in 0 until sectors) {
                val from = s32(raw, s * 4)
                val to = s32(raw, (s + 1) * 4)
                val want = minOf(sectorSize, fsize - s * sectorSize)
                val chunk = if (to - from < want) unpack(raw, from, to - from, want) else raw.copyOfRange(from, from + want)
                System.arraycopy(chunk, 0, out, s * sectorSize, want)
            }
            return out
        }

        private fun unpack(raw: ByteArray, from: Int, len: Int, want: Int): ByteArray {
            require(raw[from].toInt() == 0x02) { "compression ${raw[from]} not supported" }
            val inflater = Inflater()
            try {
                inflater.setInput(raw, from + 1, len - 1)
                val out = ByteArray(want)
                var n = 0
                while (n < want && !inflater.finished()) {
                    val got = inflater.inflate(out, n, want - n)
                    if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    n += got
                }
                return out
            } finally {
                inflater.end()
            }
        }

        companion object {
            private val crypt = IntArray(0x500).also { t ->
                var seed = 0x00100001L
                for (i1 in 0 until 0x100) {
                    var i2 = i1
                    repeat(5) {
                        seed = (seed * 125 + 3) % 0x2AAAAB
                        val t1 = (seed and 0xFFFF) shl 0x10
                        seed = (seed * 125 + 3) % 0x2AAAAB
                        val t2 = seed and 0xFFFF
                        t[i2] = (t1 or t2).toInt()
                        i2 += 0x100
                    }
                }
            }

            fun hashString(s: String, kind: Int): Int {
                var seed1 = 0x7FED7FEDL
                var seed2 = 0xEEEEEEEEL
                for (ch in s.uppercase(Locale.ROOT).replace('/', '\\')) {
                    val c = ch.code
                    seed1 = (crypt[(kind shl 8) + c].toLong() and 0xFFFFFFFFL) xor ((seed1 + seed2) and 0xFFFFFFFFL)
                    seed2 = (c + seed1 + seed2 + (seed2 shl 5) + 3) and 0xFFFFFFFFL
                }
                return seed1.toInt()
            }

            fun decrypt(words: IntArray, key0: Int) {
                var key = key0.toLong() and 0xFFFFFFFFL
                var seed = 0xEEEEEEEEL
                for (i in words.indices) {
                    seed = (seed + (crypt[0x400 + (key and 0xFF).toInt()].toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
                    val ch = (words[i].toLong() and 0xFFFFFFFFL) xor ((key + seed) and 0xFFFFFFFFL)
                    key = (((key.inv() shl 0x15) + 0x11111111L) or (key ushr 0x0B)) and 0xFFFFFFFFL
                    seed = (ch + seed + (seed shl 5) + 3) and 0xFFFFFFFFL
                    words[i] = ch.toInt()
                }
            }

            fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun s32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            fun u32(b: ByteArray, o: Int) = s32(b, o).toLong() and 0xFFFFFFFFL
        }
    }

    /** BLP2 decoder (palette, DXT1/3/5), first mipmap only. */
    object Blp {
        fun decode(d: ByteArray): Bitmap? {
            if (d.size < 148 || d[0] != 'B'.code.toByte() || d[1] != 'L'.code.toByte() || d[2] != 'P'.code.toByte() || d[3] != '2'.code.toByte()) return null
            val comp = d[8].toInt() and 0xFF
            val alphaDepth = d[9].toInt() and 0xFF
            val alphaType = d[10].toInt() and 0xFF
            val w = Mpq.s32(d, 12)
            val h = Mpq.s32(d, 16)
            val off = Mpq.s32(d, 20)
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || off <= 0 || off >= d.size) return null
            val px = IntArray(w * h)
            when (comp) {
                1 -> {
                    val n = w * h
                    for (i in 0 until n) {
                        val p = 148 + (d[off + i].toInt() and 0xFF) * 4
                        val b = d[p].toInt() and 0xFF
                        val g = d[p + 1].toInt() and 0xFF
                        val r = d[p + 2].toInt() and 0xFF
                        val a = when (alphaDepth) {
                            8 -> d[off + n + i].toInt() and 0xFF
                            1 -> if ((d[off + n + i / 8].toInt() shr (i % 8)) and 1 != 0) 255 else 0
                            4 -> ((d[off + n + i / 2].toInt() shr (4 * (i % 2))) and 15) * 17
                            else -> 255
                        }
                        px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                2 -> {
                    val dxt = if (alphaDepth <= 1) 1 else if (alphaType == 1) 3 else 5
                    val bsize = if (dxt == 1) 8 else 16
                    var p = off
                    for (by in 0 until h step 4) for (bx in 0 until w step 4) {
                        val alphas = IntArray(16) { 255 }
                        var c = p
                        if (dxt == 3) {
                            for (i in 0 until 16) alphas[i] = ((d[p + i / 2].toInt() shr (4 * (i % 2))) and 15) * 17
                            c = p + 8
                        } else if (dxt == 5) {
                            val a0 = d[p].toInt() and 0xFF
                            val a1 = d[p + 1].toInt() and 0xFF
                            var bits = 0L
                            for (k in 0 until 6) bits = bits or ((d[p + 2 + k].toLong() and 0xFF) shl (8 * k))
                            val lut = IntArray(8)
                            lut[0] = a0; lut[1] = a1
                            if (a0 > a1) for (k in 1..6) lut[k + 1] = ((7 - k) * a0 + k * a1) / 7
                            else { for (k in 1..4) lut[k + 1] = ((5 - k) * a0 + k * a1) / 5; lut[6] = 0; lut[7] = 255 }
                            for (i in 0 until 16) alphas[i] = lut[((bits shr (3 * i)) and 7).toInt()]
                            c = p + 8
                        }
                        val c0 = Mpq.u16(d, c)
                        val c1 = Mpq.u16(d, c + 2)
                        val idx = Mpq.s32(d, c + 4)
                        val cols = IntArray(4)
                        cols[0] = rgb565(c0); cols[1] = rgb565(c1)
                        var transparent3 = false
                        if (c0 > c1 || dxt != 1) {
                            cols[2] = mix(cols[0], cols[1], 2, 1, 3); cols[3] = mix(cols[0], cols[1], 1, 2, 3)
                        } else {
                            cols[2] = mix(cols[0], cols[1], 1, 1, 2); cols[3] = 0; transparent3 = alphaDepth > 0
                        }
                        for (i in 0 until 16) {
                            val x = bx + i % 4
                            val y = by + i / 4
                            if (x >= w || y >= h) continue
                            val ci = (idx ushr (2 * i)) and 3
                            val a = if (dxt != 1) alphas[i] else if (ci == 3 && transparent3) 0 else 255
                            px[y * w + x] = (a shl 24) or (cols[ci] and 0xFFFFFF)
                        }
                        p += bsize
                    }
                }
                else -> return null
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun rgb565(c: Int): Int {
            val r = (c shr 11) and 31
            val g = (c shr 5) and 63
            val b = c and 31
            return ((r shl 3 or (r shr 2)) shl 16) or ((g shl 2 or (g shr 4)) shl 8) or (b shl 3 or (b shr 2))
        }

        private fun mix(a: Int, b: Int, wa: Int, wb: Int, div: Int): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * wa + ((b shr s) and 0xFF) * wb) / div
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
