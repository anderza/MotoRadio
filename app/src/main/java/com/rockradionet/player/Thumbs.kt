package com.rockradionet.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Уменьшенные лого станций для списка. Картинка из интернета скачивается один раз,
 * ужимается до THUMB точек по большей стороне и хранится в кэше приложения, поэтому
 * при прокрутке WebView не раскодирует огромные PNG и не ходит в сеть заново.
 */
object Thumbs {
    private const val THUMB = 320
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Байты WebP для url или null, если картинку получить не удалось. Вызывать не из главного потока. */
    fun get(dir: File, url: String): ByteArray? {
        val f = File(dir, key(url) + ".webp")
        if (f.length() > 0) return try { f.readBytes() } catch (e: Exception) { null }
        val fail = failedAt[url]
        if (fail != null && System.currentTimeMillis() - fail < 10 * 60_000) return null
        val raw = fetch(url)
        val out = raw?.let { shrink(it) }
        if (out == null) {
            failedAt[url] = System.currentTimeMillis()
            return null
        }
        try {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(out)
            tmp.renameTo(f)
        } catch (e: Exception) {}
        return out
    }

    private fun fetch(url: String): ByteArray? {
        Net.get(url)?.let { return it }
        if (url.startsWith("http:")) Net.get("https:" + url.substring(5))?.let { return it }
        val bare = url.replace(Regex("^https?://"), "")
        return Net.get("https://wsrv.nl/?w=512&h=512&fit=inside&url=" + java.net.URLEncoder.encode(bare, "UTF-8"))
    }

    private fun shrink(bytes: ByteArray): ByteArray? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null // не картинка или SVG
        var sample = 1
        while (b.outWidth / (sample * 2) >= THUMB && b.outHeight / (sample * 2) >= THUMB) sample *= 2
        val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val big = maxOf(d.width, d.height)
        val s = if (big > THUMB) {
            val k = THUMB.toFloat() / big
            Bitmap.createScaledBitmap(d, maxOf(1, (d.width * k).toInt()), maxOf(1, (d.height * k).toInt()), true)
        } else d
        val o = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        s.compress(Bitmap.CompressFormat.WEBP, 88, o)
        return o.toByteArray()
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
}
