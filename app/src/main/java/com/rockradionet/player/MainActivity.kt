package com.rockradionet.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.app.Activity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Окно приложения: тот же интерфейс, что и HTML-версия (assets/www/index.html),
 * а звук, фон и виджет обслуживает RadioService.
 */
class MainActivity : Activity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START = "https://$HOST/assets/www/index.html"
        private const val RQ_FILE = 1
        private const val RQ_SAVE = 2
        private const val RQ_TREE = 3
        private const val RQ_AUDIO = 4
        private const val RQ_LOGIN = 5
        private val PLAYER_HOSTS = listOf(
            "spotify.com", "scdn.co", "yandex.ru", "yandex.net", "yandex.com", "yandex.by", "yandex.kz",
            "youtube.com", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
            "vk.com", "vk.ru"
        )
    }

    private lateinit var web: WebView
    private lateinit var recorder: Recorder
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var lastStations: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingText: String? = null

    private val stateListener: () -> Unit = { main.post { pushState() } }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RQ_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            RQ_SAVE -> {
                val text = pendingText
                pendingText = null
                val uri = if (resultCode == RESULT_OK) data?.data else null
                if (uri != null && text != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        Toast.makeText(this, "Файл сохранён", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // Раздел «Папки»: выбор папки с музыкой (доступ к ней запоминается) и отдельных файлов
            RQ_TREE -> {
                val tree = if (resultCode == RESULT_OK) data?.data else null
                if (tree != null) {
                    keepAccess(tree)
                    Toast.makeText(this, "Ищу музыку в папке…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val json = try { scanTree(tree) } catch (e: Exception) { "[]" }
                        main.post { sendFolders(json) }
                    }.start()
                }
            }
            RQ_AUDIO -> {
                val uris = ArrayList<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                    else data.data?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) {
                    val tracks = JSONArray()
                    for (u in uris) {
                        keepAccess(u)
                        tracks.put(JSONObject().put("n", displayName(u)).put("u", u.toString()))
                    }
                    val folder = JSONObject().put("name", "Выбранные файлы").put("key", "files").put("tracks", tracks)
                    sendFolders(JSONArray().put(folder).toString())
                }
            }
            // после закрытия окна входа страница обновляет встроенный плеер плейлиста
            RQ_LOGIN -> web.evaluateJavascript("window.nativeLoginDone&&window.nativeLoginDone()", null)
        }
    }

    /** Файлы страницы из assets по адресу https://appassets.androidplatform.net/assets/… */
    private fun assetResponse(u: Uri): WebResourceResponse? {
        if (u.host != HOST) return null
        val path = u.path ?: return null
        if (!path.startsWith("/assets/")) return null
        val name = path.removePrefix("/assets/")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "html", "htm" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        return try {
            val enc = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") || mime.endsWith("xml")) "utf-8" else null
            WebResourceResponse(mime, enc, assets.open(name))
        } catch (e: Exception) {
            emptyResponse(404, "Not Found")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.assets = applicationContext.assets
        window.statusBarColor = Color.parseColor("#12141C")
        window.navigationBarColor = Color.parseColor("#22252E")

        recorder = Recorder(applicationContext)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#12141C"))
        // Полосы прокрутки не показываем (прокрутка пальцем работает как раньше)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // http-логотипы станций
            allowFileAccess = false
            allowContentAccess = false
        }
        // вход в аккаунт Яндекс Музыки / YouTube / Spotify внутри встроенного плеера плейлистов
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host == HOST && (u.path ?: "").startsWith("/local/")) return localFile(request)
                if (u.host == HOST && u.path == "/thumb") {
                    // уменьшенное лого станции из кэша (или скачать и уменьшить)
                    val src = u.getQueryParameter("u").orEmpty()
                    if (!src.startsWith("http")) return emptyResponse(404, "Not Found")
                    val b = Thumbs.get(java.io.File(cacheDir, "thumbs"), src) ?: return emptyResponse(404, "Not Found")
                    return WebResourceResponse("image/webp", null, ByteArrayInputStream(b)).apply {
                        responseHeaders = mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*")
                    }
                }
                return assetResponse(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false // переходы внутри встроенного плеера плейлистов
                if (request.url.host == HOST) return false
                openExternal(request.url.toString())
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Защищённое воспроизведение (DRM) для встроенных плееров Spotify / Яндекс Музыки / YouTube
            override fun onPermissionRequest(request: PermissionRequest) {
                val host = request.origin.host.orEmpty()
                val trusted = PLAYER_HOSTS.any { host == it || host.endsWith(".$it") }
                val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (trusted && drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val accept = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }
                val mime = if (accept.any { it.startsWith("image/") }) "image/*" else "*/*"
                val pick = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                return try {
                    startActivityForResult(Intent.createChooser(pick, null), RQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl(START)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        // Сначала закрываем проигрыватель/меню/раздел на странице, иначе сворачиваем приложение
        // (радио продолжает играть).
        // На Android TV двойное «Назад» открывает на странице окно «Закрыть плеер?».
        web.evaluateJavascript("window.rrnBack?rrnBack():((window.goBack&&goBack())?1:0)") { r ->
            if (r != "1") moveTaskToBack(true)
        }
    }

    override fun onStart() {
        super.onStart()
        RadioService.listeners.add(stateListener)
        pushState()
    }

    override fun onStop() {
        RadioService.listeners.remove(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("Android")
        web.destroy()
        super.onDestroy()
    }

    private fun stateJson(): String = RadioService.stateJson.ifEmpty {
        val list = Store.stations(this)
        val url = Store.currentUrl(this)
        JSONObject().apply {
            put("index", list.indexOfFirst { it.url == url })
            put("playing", false)
            put("raw", "")
            put("since", 0)
        }.toString()
    }

    private fun pushState() {
        if (!::web.isInitialized) return
        web.evaluateJavascript("window.nativeState&&window.nativeState(${stateJson()})", null)
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
        }
    }

    /** Команда сервису: напрямую, если он запущен, иначе запуском сервиса. */
    private fun command(action: String, index: Int = -1) {
        main.post {
            val svc = RadioService.instance
            if (svc != null) {
                svc.handle(action, index)
            } else if (action != RadioService.A_PAUSE) {
                try {
                    startService(
                        Intent(this, RadioService::class.java)
                            .setAction(action)
                            .putExtra(RadioService.EXTRA_INDEX, index)
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось запустить воспроизведение", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== Раздел «Папки»: своя музыка на устройстве =====

    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    private fun sendFolders(json: String) {
        if (!::web.isInitialized) return
        web.evaluateJavascript("window.nativeFolders&&window.nativeFolders(" + JSONObject.quote(json) + ")", null)
    }

    private fun displayName(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return (u.lastPathSegment ?: "Файл").substringAfterLast('/')
    }

    private val audioExt = Regex("\\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|webm|mka|aif|aiff|amr|3gp)$", RegexOption.IGNORE_CASE)
    private val coverName = Regex("^(cover|folder|front|albumart[^/]*)\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)

    /** Обходит выбранную папку и вложенные, собирает музыку по папкам: [{name, key, cover, tracks:[{n,u}]}] */
    private fun scanTree(tree: Uri): String {
        val out = JSONArray()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var count = 0
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        fun walk(docId: String, name: String, path: String, depth: Int) {
            if (depth > 8 || count > 5000) return
            val tracks = JSONArray()
            var cover: String? = null
            val dirs = ArrayList<Pair<String, String>>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val n = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs.add(Pair(id, n))
                    } else if (mime.startsWith("audio/") || audioExt.containsMatchIn(n)) {
                        val du = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        tracks.put(JSONObject().put("n", n).put("u", du.toString()))
                        count++
                    } else if (cover == null && coverName.matches(n)) {
                        cover = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    }
                }
            }
            if (tracks.length() > 0) {
                val f = JSONObject().put("name", name).put("key", path).put("tracks", tracks)
                val cv = cover
                if (cv != null) f.put("cover", cv)
                out.put(f)
            }
            for (d in dirs.sortedBy { it.second.lowercase() }) walk(d.first, d.second, path + "/" + d.second, depth + 1)
        }

        val top = rootName(tree, rootId)
        walk(rootId, top, tree.toString(), 0)
        return out.toString()
    }

    private fun rootName(tree: Uri, id: String): String {
        try {
            val u = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Музыка" }
    }

    /** Файл можно отдавать, только если пользователь сам дал к нему (или к его папке) доступ */
    private fun allowed(uri: Uri): Boolean {
        val s = uri.toString()
        return contentResolver.persistedUriPermissions.any { p ->
            val t = p.uri.toString()
            p.isReadPermission && (s == t || s.startsWith("$t/"))
        }
    }

    private fun emptyResponse(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Отдаёт странице свой музыкальный файл по адресу /local/f?u=content://… (с перемоткой — заголовок Range) */
    private fun localFile(req: WebResourceRequest): WebResourceResponse {
        val s = req.url.getQueryParameter("u")
        if (s.isNullOrEmpty()) return emptyResponse(404, "Not Found")
        val uri = Uri.parse(s)
        if (uri.scheme != "content" || !allowed(uri)) return emptyResponse(403, "Forbidden")
        return try {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r") ?: return emptyResponse(404, "Not Found")
            val total = afd.length
            val mime = contentResolver.getType(uri) ?: "audio/mpeg"
            val input: InputStream = afd.createInputStream()
            val range = req.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
            val m = if (range != null && total > 0) Regex("bytes=(\\d*)-(\\d*)").find(range) else null
            if (m != null) {
                var start = m.groupValues[1].toLongOrNull() ?: 0L
                var end = m.groupValues[2].toLongOrNull() ?: (total - 1)
                if (m.groupValues[1].isEmpty()) {
                    start = maxOf(0L, total - end)
                    end = total - 1
                }
                if (end >= total) end = total - 1
                if (start > end) {
                    input.close()
                    return emptyResponse(416, "Range Not Satisfiable")
                }
                skipFully(input, start)
                val len = end - start + 1
                val headers = HashMap<String, String>()
                headers["Content-Range"] = "bytes $start-$end/$total"
                headers["Accept-Ranges"] = "bytes"
                headers["Content-Length"] = len.toString()
                WebResourceResponse(mime, null, 206, "Partial Content", headers, Limited(input, len))
            } else {
                val headers = HashMap<String, String>()
                headers["Accept-Ranges"] = "bytes"
                if (total > 0) headers["Content-Length"] = total.toString()
                WebResourceResponse(mime, null, 200, "OK", headers, input)
            }
        } catch (e: Exception) {
            emptyResponse(404, "Not Found")
        }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k > 0) {
                left -= k
            } else {
                if (input.read() < 0) break
                left--
            }
        }
    }

    /** Поток, который отдаёт не больше len байт (для ответа на Range) */
    private class Limited(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = src.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() = src.close()
    }

    /** Методы, доступные странице как window.Android. Вызываются не в главном потоке. */
    inner class Bridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun isTv(): Boolean =
            (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
                Configuration.UI_MODE_TYPE_TELEVISION

        @JavascriptInterface
        fun getState(): String = stateJson()

        @JavascriptInterface
        fun setStations(json: String) {
            if (json == lastStations) return
            lastStations = json
            Store.saveStations(this@MainActivity, json)
            main.post {
                val svc = RadioService.instance
                if (svc != null) svc.reloadStations() else Widget.update(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun play(index: Int) = command(RadioService.A_PLAY, index)

        @JavascriptInterface
        fun pause() = command(RadioService.A_PAUSE)

        /** «Папки»: системное окно выбора папки с музыкой */
        @JavascriptInterface
        fun pickFolder() {
            main.post {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), RQ_TREE)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "На этом устройстве нет окна выбора папок — выберите файлы", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** «Папки»: системное окно выбора музыкальных файлов (можно несколько) */
        @JavascriptInterface
        fun pickAudio() {
            main.post {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("audio/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                        RQ_AUDIO
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор файлов", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Свернуть приложение (радио продолжает играть). */
        @JavascriptInterface
        fun minimize() {
            main.post { moveTaskToBack(true) }
        }

        /** Окно «Закрыть плеер?» → «Закрыть»: звук останавливается, служба и приложение закрываются. */
        @JavascriptInterface
        fun exitApp() {
            main.post {
                try {
                    recorder.stop()
                } catch (e: Exception) {
                }
                RadioService.instance?.shutdown()
                stopService(Intent(this@MainActivity, RadioService::class.java))
                finishAndRemoveTask()
            }
        }

        @JavascriptInterface
        fun toggle() = command(RadioService.A_TOGGLE)

        @JavascriptInterface
        fun next() = command(RadioService.A_NEXT)

        @JavascriptInterface
        fun prev() = command(RadioService.A_PREV)

        @JavascriptInterface
        fun setVolume(v: Double) {
            val f = v.toFloat().coerceIn(0f, 1f)
            Store.saveVolume(this@MainActivity, f)
            main.post { RadioService.instance?.setVolume(f) }
        }

        /** Ориентация экрана: "auto", "portrait" или "landscape". */
        @JavascriptInterface
        fun setOrientation(mode: String) {
            main.post {
                requestedOrientation = when (mode) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }

        /** Громкость звучащей музыки 0…1 (для стрелки тахометра), или −1, если радио сейчас не звучит. */
        @JavascriptInterface
        fun level(): Double =
            if (SystemClock.elapsedRealtime() - RadioService.levelAt < 400) RadioService.level.toDouble() else -1.0

        /** Приборная панель на весь экран (телефон боком): прячем строку состояния и кнопки навигации. */
        @JavascriptInterface
        fun setImmersive(on: Boolean) {
            main.post {
                if (Build.VERSION.SDK_INT >= 30) {
                    val c = window.insetsController
                    if (c != null) {
                        if (on) {
                            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            c.hide(WindowInsets.Type.systemBars())
                        } else {
                            c.show(WindowInsets.Type.systemBars())
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = if (on)
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    else View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }

        /** Режим «Случайная»: при каждой смене трека плеер сам включает случайную станцию. */
        @JavascriptInterface
        fun setShuffleMode(on: Boolean) {
            Store.saveShuffleMode(this@MainActivity, on)
        }

        @JavascriptInterface
        fun setCoverMode(mode: String) {
            if (mode == Store.coverMode(this@MainActivity)) return
            Store.saveCoverMode(this@MainActivity, mode)
            main.post { RadioService.instance?.refetchCover() }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            main.post { openExternal(url) }
        }

        /** Вход в аккаунт Яндекс Музыки / Spotify в отдельном окне с общими cookie. */
        @JavascriptInterface
        fun openLogin(url: String, title: String) {
            main.post {
                try {
                    startActivityForResult(
                        Intent(this@MainActivity, LoginActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", title),
                        RQ_LOGIN
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть окно входа", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** Запись потока в файл (кнопка «Скачать трек»). */
        @JavascriptInterface
        fun startRecord(url: String, name: String, ext: String) {
            recorder.start(url, name, ext) { msg ->
                main.post { web.evaluateJavascript("window.nativeRec&&window.nativeRec(" + JSONObject.quote(msg) + ")", null) }
            }
        }

        @JavascriptInterface
        fun stopRecord() = recorder.stop()

        @JavascriptInterface
        fun saveText(name: String, text: String) {
            main.post {
                pendingText = text
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TITLE, name),
                        RQ_SAVE
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
