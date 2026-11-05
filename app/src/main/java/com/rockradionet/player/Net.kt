package com.rockradionet.player

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Сетевые помощники: загрузка картинок, поиск обложек, «буквенные» заглушки. Вызывать НЕ из главного потока. */
object Net {
    private val cache = LruCache<String, Bitmap>(24)

    /** Файлы приложения: встроенные обложки станций лежат в assets/www/covers (ссылка вида "covers/…"). */
    @Volatile
    var assets: AssetManager? = null

    fun get(url: String, timeoutMs: Int = 8000): ByteArray? {
        var target = URL(url)
        repeat(6) {
            val conn = try {
                target.openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return null
            }
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "RockRadioNet/1.0 (Android)")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    target = URL(target, location) // переход между http и https
                } else if (code == 200) {
                    return conn.inputStream.use { it.readBytes() }
                } else {
                    return null
                }
            } catch (e: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** Картинка по ссылке (http/https или data:) уменьшенная до maxSide пикселей. */
    fun bitmap(url: String, maxSide: Int): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        val bytes: ByteArray = try {
            if (url.startsWith("data:")) Base64.decode(url.substringAfter(','), Base64.DEFAULT)
            else if (url.startsWith("covers/")) assets?.open("www/$url")?.use { it.readBytes() } ?: return null
            else get(url) ?: return null
        } catch (e: Exception) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // например, SVG
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val big = maxOf(decoded.width, decoded.height)
        val result = if (big > maxSide) {
            val k = maxSide.toFloat() / big
            Bitmap.createScaledBitmap(
                decoded,
                maxOf(1, (decoded.width * k).toInt()),
                maxOf(1, (decoded.height * k).toInt()),
                true
            )
        } else decoded
        cache.put(url, result)
        return result
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Обложка трека из каталога iTunes (как в веб-версии). */
    fun itunesCover(artist: String, title: String): String? {
        val term = "$artist $title".trim()
        if (term.isEmpty()) return null
        val body = get(
            "https://itunes.apple.com/search?media=music&entity=song&limit=5&term=" +
                URLEncoder.encode(term, "UTF-8")
        ) ?: return null
        return try {
            val results = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("results") ?: return null
            val na = norm(artist)
            val nt = norm(title)
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val xa = norm(r.optString("artistName"))
                val xt = norm(r.optString("trackName"))
                val artistOk = na.isEmpty() || xa.contains(na) || na.contains(xa)
                val titleOk = xt.contains(nt) || nt.contains(xt)
                val art = r.optString("artworkUrl100")
                if (artistOk && titleOk && art.isNotEmpty()) return art.replace("100x100bb", "600x600bb")
            }
            // точного совпадения нет — берём обложку любого трека этого исполнителя
            if (na.isNotEmpty()) for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val xa = norm(r.optString("artistName"))
                val art = r.optString("artworkUrl100")
                if ((xa.contains(na) || na.contains(xa)) && art.isNotEmpty()) return art.replace("100x100bb", "600x600bb")
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private class Deezer(val album: String, val artist: String)

    private fun deezerOk(u: String) = u.startsWith("http") && !Regex("/(artist|cover)//").containsMatchIn(u)

    private fun deezerTrack(artist: String, title: String): Deezer {
        val q = if (artist.isNotEmpty()) "artist:\"$artist\" track:\"$title\"" else title
        val body = get("https://api.deezer.com/search?limit=5&q=" + URLEncoder.encode(q, "UTF-8"))
            ?: return Deezer("", "")
        return try {
            val data = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("data") ?: return Deezer("", "")
            val na = norm(artist)
            val nt = norm(title)
            var pick: JSONObject? = null
            for (i in 0 until data.length()) {
                val r = data.optJSONObject(i) ?: continue
                val xa = norm(r.optJSONObject("artist")?.optString("name").orEmpty())
                val xt = norm(r.optString("title"))
                val artistOk = na.isEmpty() || xa.contains(na) || na.contains(xa)
                val titleOk = xt.contains(nt) || nt.contains(xt)
                if (artistOk && titleOk) {
                    pick = r; break
                }
            }
            if (pick == null && artist.isEmpty()) pick = data.optJSONObject(0)
            val album = pick?.optJSONObject("album")?.optString("cover_xl").orEmpty()
            val art = pick?.optJSONObject("artist")?.optString("picture_xl").orEmpty()
            Deezer(if (deezerOk(album)) album else "", if (deezerOk(art)) art else "")
        } catch (e: Exception) {
            Deezer("", "")
        }
    }

    private fun deezerArtist(artist: String): String {
        if (artist.isEmpty()) return ""
        val body = get("https://api.deezer.com/search/artist?limit=3&q=" + URLEncoder.encode(artist, "UTF-8"))
            ?: return ""
        return try {
            val data = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("data") ?: return ""
            val na = norm(artist)
            for (i in 0 until data.length()) {
                val r = data.optJSONObject(i) ?: continue
                val xn = norm(r.optString("name"))
                val pic = r.optString("picture_xl")
                if ((xn.contains(na) || na.contains(xn)) && deezerOk(pic)) return pic
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Картинка трека: mode = "album" — сначала обложка альбома, "artist" — сначала фото исполнителя.
     * Если нужной нет, берётся другая (iTunes и Deezer).
     */
    fun findCover(artist: String, title: String, mode: String): String? {
        var dz: Deezer? = null
        val track = { dz ?: deezerTrack(artist, title).also { dz = it } }
        val steps: List<() -> String?> = if (mode == "artist") listOf(
            { track().artist }, { deezerArtist(artist) }, { itunesCover(artist, title) }, { track().album }
        ) else listOf(
            { itunesCover(artist, title) }, { track().album }, { track().artist }, { deezerArtist(artist) }
        )
        for (step in steps) {
            val u = try {
                step()
            } catch (e: Exception) {
                null
            }
            if (!u.isNullOrBlank()) return u
        }
        return null
    }

    /** Похоже ли на ссылку на картинку (для StreamUrl из потока). */
    fun looksLikeImage(u: String): Boolean {
        if (!u.startsWith("http")) return false
        val path = u.substringAfter("://").substringAfter('/', "").lowercase()
        return Regex("\\.(png|jpe?g|webp|gif)(\\?|#|$)").containsMatchIn(path) ||
            Regex("(cover|artwork|albumart|image|img|thumb)").containsMatchIn(path)
    }

    /** Цвет станции — тот же алгоритм, что hue() в веб-интерфейсе (HSL 55% / 45%). */
    fun stationColor(name: String): Int {
        var h = 0
        for (ch in name) h = (h * 31 + ch.code) % 360
        // HSL(h, 55%, 45%) → HSV(h, 0.7097, 0.6975)
        return Color.HSVToColor(floatArrayOf(h.toFloat(), 0.7097f, 0.6975f))
    }

    /** Квадрат цвета станции с первой буквой названия — заглушка, если логотипа нет. */
    fun letterBitmap(name: String, size: Int = 240): Bitmap {
        val key = "letter:$name"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (name.isBlank()) Color.parseColor("#555555") else stationColor(name))
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        cache.put(key, bmp)
        return bmp
    }
}
