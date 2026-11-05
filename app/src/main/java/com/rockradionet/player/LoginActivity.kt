package com.rockradionet.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity

/**
 * Окно входа в аккаунт сервиса (Яндекс, Spotify, VK) на его собственном сайте.
 * Cookie общие с главным окном приложения, поэтому после входа встроенный плеер плейлистов видит аккаунт.
 * Пароль приложение не получает и нигде не сохраняет — вход идёт напрямую на сайте сервиса.
 */
class LoginActivity : Activity() {

    companion object {
        // Окно открывает только страницы входа этих сервисов (мост доступен и встроенным плеерам).
        private val ALLOWED = listOf(
            "spotify.com", "yandex.ru", "yandex.by", "yandex.kz", "yandex.uz", "yandex.com",
            "vk.com", "vk.ru", "vkontakte.ru"
        )
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val host = Uri.parse(url).host.orEmpty()
        if (!url.startsWith("https://") || ALLOWED.none { host == it || host.endsWith(".$it") }) {
            finish()
            return
        }

        val bg = Color.parseColor("#12141C")
        val panel = Color.parseColor("#22252E")
        window.statusBarColor = bg
        window.navigationBarColor = panel

        val label = TextView(this)
        label.text = title
        label.setTextColor(Color.WHITE)
        label.textSize = 16f
        label.gravity = Gravity.CENTER_VERTICAL

        val done = Button(this)
        done.text = "Готово"
        done.setOnClickListener { finish() }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(panel)
        bar.setPadding(24, 12, 24, 12)
        bar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(
            done,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.loadUrl(url)

    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
