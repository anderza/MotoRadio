package com.rockradionet.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фоновое воспроизведение радио. Работает при закрытом приложении и заблокированном экране,
 * показывает уведомление с кнопками, принимает кнопки Bluetooth-гарнитуры (через MediaSession),
 * читает название трека из потока (ICY, через IcyMeta) и обновляет виджет.
 * Звук играет плеер Media3 (ExoPlayer), как в RockRadioNet: он берёт потоки, которые системный MediaPlayer не открывал.
 */
class RadioService : Service() {

    companion object {
        const val A_TOGGLE = "com.rockradionet.player.TOGGLE"
        const val A_PLAY = "com.rockradionet.player.PLAY"
        const val A_PAUSE = "com.rockradionet.player.PAUSE"
        const val A_NEXT = "com.rockradionet.player.NEXT"
        const val A_PREV = "com.rockradionet.player.PREV"
        const val A_RECONNECT = "com.rockradionet.player.RECONNECT"
        const val A_SHUFFLE = "com.rockradionet.player.SHUFFLE"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL = "radio"
        private const val NOTE_ID = 7

        @Volatile
        var instance: RadioService? = null
            private set

        /** Последнее состояние в JSON для веб-интерфейса. Пусто, если сервис не запущен. */
        @Volatile
        var stateJson: String = ""

        /** Подписчики на изменения (вызываются в главном потоке). */
        val listeners = CopyOnWriteArraySet<() -> Unit>()

        /** Громкость звучащей музыки (0…1) для тахометра. Системный плеер её не отдаёт: страница рисует имитацию. */
        @Volatile
        var level = 0f
        @Volatile
        var levelAt = 0L
    }

    private var player: ExoPlayer? = null
    private var prepared = false
    private var wantPlay = false
    private var volume = 1f
    private lateinit var session: MediaSession
    private lateinit var meta: IcyMeta
    private var metaSession = 0
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foreground = false
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(2)

    private var stations: List<Station> = emptyList()
    private var index = -1

    private var raw = ""
    private var artist = ""
    private var title = ""
    private var coverUrl = ""
    private var cover: Bitmap? = null
    @Volatile private var coverTries = 0
    private var logo: Bitmap? = null
    private var logoFor = ""
    private var error = ""
    private var retries = 0
    private var startedAt = 0L

    private val retryRunnable = Runnable { if (index >= 0 && wantPlay) startStream() }
    /** Поток так и не начал играть: считаем это обрывом и переподключаемся. */
    private val prepareTimeout = Runnable { if (wantPlay && !prepared) failed() }
    private val refreshRunnable = Runnable { notifyChanged() }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() // отключили наушники
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Net.assets = applicationContext.assets
        stations = Store.stations(this)
        val savedUrl = Store.currentUrl(this)
        index = stations.indexOfFirst { it.url == savedUrl }
        volume = Store.volume(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        meta = IcyMeta { n, t, u -> main.post { if (n == metaSession) onStreamTitle(t, u) } }

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )

        session = MediaSession(this, "MotoRadio")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onSkipToNext() = step(1)
            override fun onSkipToPrevious() = step(-1)
        })
        session.setSessionActivity(openAppIntent())
        session.isActive = true

        try {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MotoRadio:stream")
                .apply { setReferenceCounted(false) }
        } catch (e: Exception) {
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        notifyChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // запуск через startForegroundService (виджет) требует сразу показать уведомление
        goForeground()
        intent?.action?.let { handle(it, intent.getIntExtra(EXTRA_INDEX, -1)) }
        if (!wantPlay) leaveForeground()
        return START_NOT_STICKY
    }

    /** Команды от приложения и виджета. Вызывать в главном потоке. */
    fun handle(action: String, idx: Int = -1) {
        when (action) {
            A_TOGGLE -> toggle()
            A_PLAY -> if (idx >= 0) playIndex(idx) else resume()
            A_PAUSE -> pause()
            A_NEXT -> step(1)
            A_PREV -> step(-1)
            A_RECONNECT -> playIndex(maxOf(index, 0), keepTrack = true)
            A_SHUFFLE -> shuffle()
        }
    }

    private fun isActive(): Boolean = wantPlay

    private fun isBuffering(): Boolean = wantPlay && !prepared

    private fun toggle() {
        if (isActive()) pause() else resume()
    }

    /** Возобновление: у живого эфира заново подключаемся к потоку, чтобы не играть старый буфер. */
    private fun resume() {
        if (stations.isEmpty()) return
        playIndex(if (index < 0) 0 else index, keepTrack = true)
    }

    private fun pause() {
        wantPlay = false
        main.removeCallbacks(retryRunnable)
        releasePlayer()
        meta.stop()
        abandonFocus()
        notifyChanged()
        leaveForeground()
    }

    private fun step(d: Int) {
        if (stations.isEmpty()) return
        playIndex(if (index < 0) 0 else index + d)
    }

    private fun shuffle() {
        val n = stations.size
        if (n < 2) {
            resume(); return
        }
        var i: Int
        do {
            i = (0 until n).random()
        } while (i == index)
        playIndex(i)
    }

    fun playIndex(i: Int, keepTrack: Boolean = false) {
        val n = stations.size
        if (n == 0) return
        val ni = ((i % n) + n) % n
        val changed = ni != index
        index = ni
        val st = stations[ni]
        Store.saveCurrentUrl(this, st.url)
        main.removeCallbacks(retryRunnable)
        retries = 0
        error = ""
        if (changed || !keepTrack) clearTrack()
        startedAt = System.currentTimeMillis()
        wantPlay = true
        requestFocus()
        goForeground()
        startStream()
        loadLogo(st)
        notifyChanged()
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 13_000) // чтобы страница поняла, что названия трека нет
    }

    private var streamGen = 0
    /** Адреса, которые пробуем по очереди для текущей станции (первый — самый вероятный). */
    private var cands: List<String> = emptyList()
    private var candsFor = ""
    private var candsFull = false
    private var playingUrl = ""

    /**
     * Первая попытка — сразу, без лишних запросов: адрес, который уже играл у этой станции, или адрес из списка.
     * Если не заиграло — в фоне собираем запасные: раскрытый плейлист/переадресация, известные замены,
     * потоки с тем же названием из каталога Radio Browser, другой протокол. Какой заиграл — запоминаем.
     */
    private fun startStream() {
        val st = stations.getOrNull(index) ?: return
        releasePlayer()
        val gen = ++streamGen
        val attempt = retries
        val src = st.url
        if (candsFor != src) {
            candsFor = src
            candsFull = false
            cands = listOfNotNull(Store.goodUrl(this, src), src).distinct()
        }
        main.removeCallbacks(prepareTimeout)
        if (attempt < cands.size) {
            val url = cands[attempt]
            openPlayer(url)
            return
        }
        if (candsFull) { giveUp(); return }
        io.execute {
            val more = StreamResolve.alternatives(src, st.name)
            main.post {
                if (gen != streamGen || !wantPlay || candsFor != src) return@post
                cands = (cands + more).distinct()
                candsFull = true
                if (attempt < cands.size) openPlayer(cands[attempt]) else giveUp()
            }
        }
        main.postDelayed(prepareTimeout, 40_000)
    }

    private fun openPlayer(url: String) {
        releasePlayer()
        playingUrl = url
        meta.stop() // название трека Media3 читает из самого потока (ICY), второе соединение не нужно
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(StreamResolve.UA)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        val mp = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false // фокус звука приложение берёт само (requestFocus)
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = mp
        prepared = false
        mp.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== mp) return
                if (state == Player.STATE_READY && !prepared) {
                    main.removeCallbacks(prepareTimeout)
                    prepared = true
                    retries = 0
                    if (candsFor.isNotEmpty()) Store.saveGoodUrl(this@RadioService, candsFor, playingUrl)
                    error = ""
                    notifyChanged()
                } else if (state == Player.STATE_ENDED && wantPlay) failed()
            }

            override fun onPlayerError(e: PlaybackException) {
                if (player === mp) failed()
            }

            override fun onMetadata(m: Metadata) {
                if (player !== mp) return
                for (i in 0 until m.length()) {
                    val e = m.get(i)
                    if (e is IcyInfo && e.title != null) onStreamTitle(e.title, e.url)
                }
            }
        })
        try {
            mp.volume = volume
            mp.setMediaItem(MediaItem.fromUri(url))
            mp.playWhenReady = wantPlay
            mp.prepare()
            main.removeCallbacks(prepareTimeout)
            main.postDelayed(prepareTimeout, 15_000)
            try { wifiLock?.acquire() } catch (e: Exception) {}
        } catch (e: Exception) {
            failed()
        }
    }

    private fun failed() {
        releasePlayer()
        if (!wantPlay) return
        if (index >= 0 && retries < 10) {
            retries++
            error = "Ищу рабочий поток…"
            main.postDelayed(retryRunnable, 400L)
        } else giveUp()
        notifyChanged()
    }

    private fun giveUp() {
        releasePlayer()
        error = "Поток недоступен"
        wantPlay = false
        meta.stop()
        leaveForeground()
        notifyChanged()
    }

    private fun releasePlayer() {
        main.removeCallbacks(prepareTimeout)
        val p = player ?: return
        player = null
        prepared = false
        try { p.stop() } catch (e: Exception) {}
        try { p.release() } catch (e: Exception) {}
        try { wifiLock?.release() } catch (e: Exception) {}
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.volume = volume * 0.3f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.volume = 0f
            AudioManager.AUDIOFOCUS_GAIN -> player?.volume = volume
        }
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focus = req
        try { audio.requestAudioFocus(req) } catch (e: Exception) {}
    }

    private fun abandonFocus() {
        val f = focus ?: return
        focus = null
        try { audio.abandonAudioFocusRequest(f) } catch (e: Exception) {}
    }

    /** Повторно разослать состояние (виджету и странице). */
    fun refresh() = notifyChanged()

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        player?.volume = volume
    }

    /** Список станций изменили в приложении: сохраняем текущую станцию по ссылке. */
    fun reloadStations() {
        val curUrl = if (index in stations.indices) stations[index].url else Store.currentUrl(this)
        stations = Store.stations(this)
        val ni = stations.indexOfFirst { it.url == curUrl }
        if (ni < 0 && index >= 0) {
            index = -1
            pause()
            clearTrack()
        } else {
            index = ni
            if (ni >= 0) loadLogo(stations[ni])
        }
        notifyChanged()
    }

    private fun clearTrack() {
        raw = ""; artist = ""; title = ""; coverUrl = ""; cover = null
    }

    private fun loadLogo(st: Station) {
        if (st.logo == logoFor && logo != null) return
        logoFor = st.logo
        logo = null
        if (st.logo.isBlank()) return
        val key = st.logo
        io.execute {
            val b = Net.bitmap(key, 300)
            main.post {
                if (logoFor == key) {
                    logo = b; notifyChanged()
                }
            }
        }
    }

    private fun onStreamTitle(t: String?, streamUrl: String? = null) {
        val s = t?.trim().orEmpty()
        // Картинка, которую передаёт сама станция (поле StreamUrl в потоке)
        val streamImg = streamUrl?.trim().orEmpty().takeIf { Net.looksLikeImage(it) }.orEmpty()
        if (s == raw) {
            if (streamImg.isNotEmpty() && streamImg != coverUrl) loadCover(streamImg, s, searchIfFails = false)
            return
        }
        raw = s
        val sep = Regex("\\s[-–—]\\s").find(s)
        if (s.isEmpty()) {
            artist = ""; title = ""
        } else if (sep != null && sep.range.first > 0) {
            artist = s.substring(0, sep.range.first).trim()
            title = s.substring(sep.range.last + 1).trim()
        } else {
            artist = ""; title = s
        }
        // пока ищем новую обложку, оставляем прежнюю (артист/альбом), а не логотип станции
        if (title.isEmpty()) { coverUrl = ""; cover = null }
        notifyChanged()
        coverTries = 0
        when {
            streamImg.isNotEmpty() -> loadCover(streamImg, raw, searchIfFails = true)
            title.isNotEmpty() -> searchCover()
        }
    }

    /** Найти обложку альбома или фото исполнителя (по настройке). */
    private fun searchCover() {
        if (title.isEmpty()) return
        val a = artist
        val tt = title
        val key = raw
        val mode = Store.coverMode(this)
        io.execute {
            val url = Net.findCover(a, tt, mode)
            if (url != null) main.post { if (raw == key) loadCover(url, key) }
            else if (coverTries++ < 2) main.postDelayed({ if (raw == key) searchCover() }, 15000)
        }
    }

    private fun loadCover(url: String, key: String, searchIfFails: Boolean = false) {
        io.execute {
            val bmp = Net.bitmap(url, 400)
            main.post {
                if (raw != key) return@post
                if (bmp != null) {
                    coverUrl = url; cover = bmp; notifyChanged()
                } else if (searchIfFails) {
                    searchCover() // картинка из потока не открылась — ищем сами
                }
            }
        }
    }

    /** Сменили «альбом / исполнитель» в настройках — ищем картинку заново (кроме картинки из потока). */
    fun refetchCover() {
        if (title.isEmpty()) return
        val searched = coverUrl.isEmpty() || coverUrl.contains("mzstatic") || coverUrl.contains("dzcdn")
        if (!searched) return
        coverUrl = ""; cover = null; notifyChanged()
        searchCover()
    }

    // ===== Уведомление и экран блокировки =====

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cmd(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, RadioService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun goForeground() {
        try {
            val n = buildNotificationWithIcons()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTE_ID, n)
            foreground = true
        } catch (e: Exception) {
        }
    }

    /** На паузе уведомление остаётся (можно снова включить), но службу можно закрыть смахиванием. */
    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        try {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } catch (e: Exception) {
        }
        postNotification()
    }

    private fun buildNotificationWithIcons(): Notification {
        val st = stations.getOrNull(index)
        val playing = isActive()
        fun act(icon: Int, label: String, action: String, code: Int) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, cmd(action, code)).build()
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(if (title.isNotEmpty()) title else st?.name ?: "MotoRadio")
            .setContentText(
                when {
                    error.isNotEmpty() -> error
                    title.isNotEmpty() -> artist.ifEmpty { st?.name ?: "" }
                    else -> st?.genre?.ifBlank { "Интернет-радио" } ?: "Интернет-радио"
                }
            )
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(act(R.drawable.ic_w_prev, "Назад", A_PREV, 21))
            .addAction(
                if (playing) act(R.drawable.ic_w_pause, "Пауза", A_TOGGLE, 22)
                else act(R.drawable.ic_w_play, "Играть", A_TOGGLE, 22)
            )
            .addAction(act(R.drawable.ic_w_next, "Вперёд", A_NEXT, 23))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val img = cover ?: logo ?: st?.let { Net.letterBitmap(it.name) }
        if (img != null) b.setLargeIcon(img)
        return b.build()
    }

    private fun postNotification() {
        if (stations.getOrNull(index) == null) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTE_ID, buildNotificationWithIcons())
        } catch (e: Exception) {
        }
    }

    private var sessionMetaKey = ""

    /** Экран блокировки и уведомление: название трека, исполнитель и обложка, как у музыкальных плееров. */
    private fun updateSession() {
        val st = stations.getOrNull(index)
        val state = when {
            isBuffering() -> PlaybackState.STATE_BUFFERING
            isActive() -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        if (st == null) return
        val img = cover ?: logo ?: Net.letterBitmap(st.name)
        val key = "$index|$artist|$title|${System.identityHashCode(img)}"
        if (key == sessionMetaKey) return
        sessionMetaKey = key
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifEmpty { st.name })
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    if (title.isNotEmpty()) artist.ifEmpty { st.name } else st.genre.ifBlank { "Интернет-радио" }
                )
                .putString(MediaMetadata.METADATA_KEY_ALBUM, st.name)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, img)
                .build()
        )
    }

    private fun notifyChanged() {
        val st = stations.getOrNull(index)
        try {
            updateSession()
        } catch (e: Exception) {
        }
        if (foreground || (st != null && startedAt != 0L)) postNotification()
        stateJson = JSONObject().apply {
            put("index", index)
            put("playing", isActive())
            put("buffering", isBuffering())
            put("raw", raw)
            put("artist", artist)
            put("title", title)
            put("cover", coverUrl)
            put("error", error)
            put("since", if (startedAt == 0L) 0L else (System.currentTimeMillis() - startedAt) / 1000L)
        }.toString()
        Widget.update(
            this,
            Widget.State(
                hasStations = stations.isNotEmpty(),
                hasCurrent = st != null,
                name = st?.name ?: "",
                genre = st?.genre ?: "",
                artist = artist,
                title = title,
                playing = isActive(),
                error = error,
                image = cover ?: logo ?: st?.let { Net.letterBitmap(it.name) }
            )
        )
        for (l in listeners) l()
    }

    /** Полная остановка по просьбе пользователя: звук выключается, уведомление убирается, служба завершается. */
    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        wantPlay = false
        releasePlayer()
        meta.stop()
        abandonFocus()
        startedAt = 0L
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Приложение смахнули из списка недавних: если радио не играет — завершаемся.
        if (!isActive()) shutdown()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        wantPlay = false
        releasePlayer()
        abandonFocus()
        try { unregisterReceiver(noisy) } catch (e: Exception) {}
        meta.stop()
        session.isActive = false
        session.release()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
        } catch (e: Exception) {
        }
        instance = null
        stateJson = ""
        Widget.update(this)
        for (l in listeners) l()
        super.onDestroy()
    }
}
