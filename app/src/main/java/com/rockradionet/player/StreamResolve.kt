package com.rockradionet.player

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

/**
 * Готовит адрес потока для системного плеера: сам проходит переадресации (в том числе
 * между http и https), раскрывает плейлисты .pls/.m3u/.xspf/.asx до прямой ссылки на звук,
 * а если у станции сломан сертификат https, пробует тот же адрес по http.
 * Сам звук здесь не читается: соединение закрывается сразу после заголовков.
 */
object StreamResolve {
    const val UA = "RockRadioNet/1.0 (Android)" // как в «Колесе» (RockRadioNet): с ним играют все станции

    private val cache = HashMap<String, String>()

    fun cached(url: String): String? = synchronized(cache) { cache[url] }

    fun forget(url: String) = synchronized(cache) { cache.remove(url) }

    /**
     * Поправки к адресу из списка: сквозной CORS-прокси (…herokuapp.com/http://…) нужен только
     * браузеру — приложение идёт к станции напрямую; просроченный ключ token=… у потоков 101.ru
     * (gpmradio) убираем, без него эти потоки тоже открываются.
     */
    fun fix(url: String): String {
        Regex("^https?://[^/]+\\.herokuapp\\.com/(https?://.+)$", RegexOption.IGNORE_CASE).find(url)?.let { return it.groupValues[1] }
        val m = Regex("[?&]token=([\\w-]+)\\.([\\w-]+)\\.[\\w-]+").find(url) ?: return url
        val exp = try {
            val json = String(android.util.Base64.decode(m.groupValues[2], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
        } catch (e: Exception) { 0L }
        if (exp == 0L || exp * 1000 > System.currentTimeMillis()) return url
        return url.removeRange(m.range).replace("?&", "?")
    }

    /** Возвращает адрес, который стоит отдать плееру; при любой ошибке — исходный. */
    fun resolve(url: String): String {
        cached(url)?.let { return it }
        val r = try { walk(url, 0) } catch (e: Exception) { null } ?: url
        synchronized(cache) { cache[url] = r }
        return r
    }

    /** Известные запасные адреса для станций, у которых адрес из списка перестал отвечать. */
    private val ALT = mapOf(
        // Comedy Radio — Женский стендап (101.ru, канал 448): ключ в ссылке истёк, пробуем серверы 101.ru без ключа
        "srv02.gpmradio.ru:8443/stream/pro/aac/64/448" to listOf(
            "https://pub0102.101.ru:8443/stream/pro/aac/64/448",
            "https://srv01.gpmradio.ru/stream/pro/aac/64/448",
            "https://pub0101.101.ru:8443/stream/pro/aac/64/448"
        )
    )

    /** Официальные страницы станций (и API 101.ru), откуда можно взять действующую ссылку на поток. */
    private val PAGES = mapOf(
        "icecast-piterfm.cdnvideo.ru/pfm_ballads" to listOf("https://radiopiterfm.ru/streams/pfm_ballads"),
        "srv02.gpmradio.ru:8443/stream/pro/aac/64/448" to listOf(
            "https://101.ru/api/channel/getServers/448/channel/AAC/64/",
            "https://101.ru/api/channel/getServers/448/channel/MP3/128/",
            "https://101.ru/radio/channel/448"
        )
    )

    private val AUDIO = Regex(
        "https?:(?://|\\\\/\\\\/)[^\\s\"'<>]+?(?:icecast|cdnvideo|hostingradio|gpmradio|101\\.ru(?::\\d+)?(?:/|\\\\/)stream|\\.mp3|\\.aacp?|\\.m3u8|/stream/)[^\\s\"'<>]*",
        RegexOption.IGNORE_CASE
    )

    /** Ссылки на звук, найденные в тексте страницы или ответа API (в JSON слэши бывают экранированы). */
    private fun fromPage(page: String): List<String> {
        val body = try { getText(page) } catch (e: Exception) { null } ?: return emptyList()
        return AUDIO.findAll(body).map { it.value.replace("\\/", "/").replace("&amp;", "&").trimEnd(',', ';', ')', '\\') }
            .filter { !Regex("\\.(js|css|png|jpe?g|svg|webp|html?)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .distinct().take(4).toList()
    }

    private fun getText(page: String): String? {
        var u = URL(page)
        repeat(5) {
            val c = open(u)
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location") ?: return null); return@repeat }
                if (code != 200) return null
                return readText(c, 400_000)
            } finally { try { c.disconnect() } catch (e: Exception) {} }
        }
        return null
    }

    /** Под какими названиями искать станцию в каталоге Radio Browser, если её адрес не отвечает. */
    private val NAMES = mapOf(
        "icecast-piterfm.cdnvideo.ru/pfm_ballads" to listOf("Питер FM Рок баллады", "Piter FM Ballads", "Рок баллады", "rock ballads"),
        "srv02.gpmradio.ru:8443/stream/pro/aac/64/448" to listOf("Женский стендап", "Женский Стендап Comedy Radio", "standup women")
    )

    private fun keyOf(url: String) = url.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").substringBefore('?')

    /** Запасные адреса для станции (вызывать не из главного потока). */
    fun alternatives(src: String, name: String): List<String> {
        val fixed = fix(src)
        val out = ArrayList<String>()
        out += fixed
        out += resolve(fixed)
        // Свежая ссылка прямо со страницы станции: её открывает телефон, поэтому ключ выдаётся на него
        PAGES[keyOf(src)]?.forEach { out += fromPage(it) }
        ALT[keyOf(src)]?.let { out += it }
        val names = NAMES[keyOf(src)] ?: listOf(name)
        for (n in names) {
            out += radioBrowser(n)
            if (out.size >= 8) break
        }
        swapScheme(fixed)?.let { out += it }
        return out.filter { it != src }.distinct().take(10)
    }

    /** Рабочие потоки с таким названием из открытого каталога Radio Browser (проверяются каталогом ежедневно). */
    private fun radioBrowser(name: String): List<String> {
        val q = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        for (host in listOf("all.api.radio-browser.info", "de1.api.radio-browser.info", "nl1.api.radio-browser.info")) {
            val body = Net.get("https://$host/json/stations/byname/$q?hidebroken=true&order=votes&reverse=true&limit=6", 6000) ?: continue
            return try {
                val a = org.json.JSONArray(String(body, Charsets.UTF_8))
                (0 until a.length()).mapNotNull { i ->
                    val o = a.getJSONObject(i)
                    if (o.optInt("lastcheckok", 0) != 1) null
                    else o.optString("url_resolved").ifBlank { o.optString("url") }.takeIf { it.startsWith("http") }
                }.take(3)
            } catch (e: Exception) { emptyList() }
        }
        return emptyList()
    }

    /** Тот же адрес с другим протоколом (запасной вариант при обрыве). */
    fun swapScheme(url: String): String? = when {
        url.startsWith("https://", true) -> "http://" + url.substring(8)
        url.startsWith("http://", true) -> "https://" + url.substring(7)
        else -> null
    }

    private fun walk(start: String, depth: Int): String? {
        if (depth > 3) return start
        var u = URL(start)
        for (hop in 0 until 8) {
            val c = try { open(u) } catch (e: SSLException) {
                val alt = swapScheme(u.toString())?.takeIf { u.protocol == "https" } ?: return null
                u = URL(alt)
                open(u)
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return u.toString()
                    u = URL(u, loc)
                    continue
                }
                if (code !in 200..299) return null
                val ct = (c.contentType ?: "").lowercase()
                val path = u.path.lowercase()
                val listLike = ct.contains("scpls") || ct.contains("mpegurl") || ct.contains("xspf") ||
                    ct.contains("ms-asf") || ct.contains("x-ms-wax") || ct.startsWith("text/") ||
                    Regex("\\.(pls|m3u|m3u8|xspf|asx|wax)$").containsMatchIn(path)
                if (!listLike || ct.contains("html")) return u.toString()
                val text = readText(c, 65536)
                if (text.contains("#EXT-X-")) return u.toString() // HLS плеер разбирает сам
                val next = firstLink(text, u) ?: return u.toString()
                return walk(next, depth + 1) ?: next
            } finally {
                try { c.disconnect() } catch (e: Exception) {}
            }
        }
        return u.toString()
    }

    private fun open(u: URL): HttpURLConnection {
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Icy-MetaData", "0")
        c.connect()
        return c
    }

    private fun readText(c: HttpURLConnection, max: Int): String {
        val inp = c.inputStream
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val k = inp.read(buf, n, max - n)
            if (k < 0) break
            n += k
            if (n >= 4096 && !looksText(buf, n)) break
        }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun looksText(b: ByteArray, n: Int): Boolean {
        var bad = 0
        for (i in 0 until minOf(n, 1024)) {
            val v = b[i].toInt() and 0xff
            if (v < 9 || (v in 14..31)) bad++
        }
        return bad < 8
    }

    /** Первая ссылка на поток из плейлиста: File1= (pls), строка (m3u), <location> (xspf), href (asx). */
    private fun firstLink(text: String, base: URL): String? {
        val t = text.trim()
        val found = Regex("(?im)^\\s*File\\d+\\s*=\\s*(\\S+)").find(t)?.groupValues?.get(1)
            ?: Regex("(?i)<location>\\s*([^<\\s]+)\\s*</location>").find(t)?.groupValues?.get(1)
            ?: Regex("(?i)<ref\\s+href\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
            ?: t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.startsWith("http", true) }
            ?: return null
        val abs = try { URL(base, found.replace("&amp;", "&")).toString() } catch (e: Exception) { return null }
        return if (abs.startsWith("http", true)) abs else null
    }
}
