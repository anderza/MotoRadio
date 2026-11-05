package com.rockradionet.player

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Название трека из потока (ICY). Звук системный плеер берёт со станции напрямую,
 * а здесь раз в несколько секунд открывается короткое отдельное соединение: читается
 * один блок звука, следом вставка с названием (StreamTitle), и соединение закрывается.
 * Если станция названий не передаёт, опрос прекращается.
 */
class IcyMeta(private val onMeta: (session: Int, title: String, url: String) -> Unit) {
    private val seq = AtomicInteger(0)
    @Volatile private var current = 0
    @Volatile private var conn: HttpURLConnection? = null

    private enum class Got { META, NO_META, FAIL }

    /** Начать опрос станции; прежний опрос прекращается. Возвращает номер сеанса для onMeta. */
    fun start(url: String): Int {
        val n = seq.incrementAndGet()
        current = n
        drop()
        Thread({ loop(n, url) }, "icy-meta").apply { isDaemon = true }.start()
        return n
    }

    fun stop() {
        current = 0
        drop()
    }

    private fun drop() {
        try { conn?.disconnect() } catch (e: Exception) {}
    }

    private fun loop(n: Int, url: String) {
        var fails = 0
        while (n == current) {
            when (fetch(n, url)) {
                Got.META -> fails = 0
                Got.NO_META -> return
                Got.FAIL -> if (++fails >= 5) return
            }
            // пауза до следующего опроса; при смене станции выходим сразу
            val until = System.currentTimeMillis() + if (fails == 0) 10_000L else 15_000L
            while (n == current && System.currentTimeMillis() < until) {
                try { Thread.sleep(250) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun fetch(n: Int, target: String): Got {
        var c: HttpURLConnection? = null
        try {
            var u = URL(target)
            for (i in 0 until 6) {
                val k = u.openConnection() as HttpURLConnection
                k.connectTimeout = 15000
                k.readTimeout = 20000
                k.instanceFollowRedirects = false
                k.setRequestProperty("User-Agent", "RockRadioNet/1.0 (Android)")
                k.setRequestProperty("Icy-MetaData", "1")
                conn = k
                val code = k.responseCode
                if (code in 300..399) {
                    val loc = k.getHeaderField("Location")
                    k.disconnect()
                    if (loc == null) return Got.FAIL
                    u = URL(u, loc)
                    continue
                }
                c = k
                break
            }
            if (c == null || c.responseCode != 200 || n != current) return Got.FAIL
            val metaInt = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull() ?: 0
            if (metaInt <= 0) return Got.NO_META
            val inp = c.inputStream
            // Обычно название приходит в первой же вставке; бывает, что первые пустые
            for (block in 0 until 4) {
                if (n != current || !skip(inp, metaInt.toLong())) return Got.FAIL
                val len = inp.read() * 16
                if (len < 0) return Got.FAIL
                if (len == 0) continue
                val meta = ByteArray(len)
                var got = 0
                while (got < len) {
                    val k = inp.read(meta, got, len - got)
                    if (k < 0) return Got.FAIL
                    got += k
                }
                if (parse(n, meta)) return Got.META
            }
            return Got.META
        } catch (e: Exception) {
            return Got.FAIL
        } finally {
            try { c?.disconnect() } catch (e: Exception) {}
        }
    }

    private fun skip(inp: InputStream, count: Long): Boolean {
        val buf = ByteArray(8192)
        var left = count
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return false
            left -= r
        }
        return true
    }

    private fun parse(n: Int, meta: ByteArray): Boolean {
        var end = meta.size
        while (end > 0 && meta[end - 1].toInt() == 0) end--
        val s = decode(meta, end)
        val title = Regex("StreamTitle='(.*?)';").find(s)?.groupValues?.get(1) ?: return false
        val url = Regex("StreamUrl='(.*?)';").find(s)?.groupValues?.get(1).orEmpty()
        if (n == current) onMeta(n, title, url)
        return true
    }

    /** Название трека чаще всего в UTF-8, у старых станций — в Windows-1251 или Latin-1. */
    private fun decode(b: ByteArray, len: Int): String {
        val utf = String(b, 0, len, Charsets.UTF_8)
        if (!utf.contains('�')) return utf
        return try { String(b, 0, len, charset("windows-1251")) } catch (e: Exception) { String(b, 0, len, Charsets.ISO_8859_1) }
    }
}
