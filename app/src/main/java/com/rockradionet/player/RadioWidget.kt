package com.rockradionet.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews

/** Виджет на рабочий стол. Кнопки отправляют сюда же широковещательные команды. */
class RadioWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Net.assets = context.applicationContext.assets
        Widget.update(context)
        // Сервис не запущен — подгружаем логотип сохранённой станции в фоне.
        if (RadioService.instance == null) {
            val st = Widget.savedStation(context) ?: return
            if (st.logo.isBlank()) return
            val pending = goAsync()
            Thread {
                try {
                    val bmp = Net.bitmap(st.logo, 300)
                    if (bmp != null && RadioService.instance == null) {
                        Widget.update(context, Widget.stateFromStore(context, bmp))
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (!action.startsWith("com.rockradionet.player.")) return

        val svc = RadioService.instance
        if (svc != null) {
            svc.handle(action)
            return
        }
        if (action == RadioService.A_PAUSE) return
        if (Store.stations(context).isEmpty()) {
            Widget.openApp(context)
            return
        }
        // Нажатие на виджет разрешает запуск фонового сервиса; все эти команды начинают воспроизведение.
        val start = Intent(context, RadioService::class.java).setAction(
            if (action == RadioService.A_TOGGLE) RadioService.A_PLAY else action
        )
        try {
            context.startForegroundService(start)
        } catch (e: Exception) {
            Widget.openApp(context)
        }
    }
}

object Widget {
    data class State(
        val hasStations: Boolean,
        val hasCurrent: Boolean,
        val name: String,
        val genre: String,
        val artist: String,
        val title: String,
        val playing: Boolean,
        val error: String,
        val image: Bitmap?
    )

    fun savedStation(c: Context): Station? {
        val url = Store.currentUrl(c)
        val list = Store.stations(c)
        return list.firstOrNull { it.url == url } ?: list.firstOrNull()
    }

    fun stateFromStore(c: Context, logo: Bitmap? = null): State {
        val list = Store.stations(c)
        val st = list.firstOrNull { it.url == Store.currentUrl(c) }
        return State(
            hasStations = list.isNotEmpty(),
            hasCurrent = st != null,
            name = st?.name ?: "",
            genre = st?.genre ?: "",
            artist = "",
            title = "",
            playing = false,
            error = "",
            image = logo ?: st?.let { Net.letterBitmap(it.name) }
        )
    }

    /** Обновить виджет по текущему состоянию. */
    fun update(c: Context) {
        val svc = RadioService.instance
        if (svc != null) {
            svc.refresh() // сервис знает трек, обложку и статус
            return
        }
        update(c, stateFromStore(c))
    }

    fun update(c: Context, s: State) {
        val manager = AppWidgetManager.getInstance(c)
        val ids = manager.getAppWidgetIds(ComponentName(c, RadioWidget::class.java))
        if (ids.isEmpty()) return

        val v = RemoteViews(c.packageName, R.layout.widget_player)
        val hasTrack = s.title.isNotEmpty()

        v.setTextViewText(
            R.id.w_artist,
            when {
                !s.hasCurrent -> "MotoRadio"
                hasTrack -> s.artist.ifEmpty { s.name }
                else -> s.name
            }
        )
        v.setTextViewText(
            R.id.w_title,
            when {
                s.error.isNotEmpty() -> s.error
                hasTrack -> s.title
                !s.hasStations -> "Откройте приложение"
                !s.hasCurrent -> "Выберите станцию"
                s.playing -> s.genre.ifEmpty { "В эфире" }
                else -> "Пауза"
            }
        )
        v.setTextViewText(
            R.id.w_status,
            when {
                !s.hasCurrent -> "[—]"
                s.playing -> "[LIVE]"
                else -> "[ПАУЗА]"
            }
        )
        if (s.image != null) v.setImageViewBitmap(R.id.w_cover, s.image)
        else v.setImageViewResource(R.id.w_cover, R.drawable.cover_placeholder)
        v.setImageViewResource(R.id.w_play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)

        val app = openAppIntent(c)
        v.setOnClickPendingIntent(R.id.w_cover, app)
        v.setOnClickPendingIntent(R.id.w_info, app)
        if (s.hasStations) {
            v.setOnClickPendingIntent(R.id.w_play, command(c, RadioService.A_TOGGLE, 1))
            v.setOnClickPendingIntent(R.id.w_prev, command(c, RadioService.A_PREV, 2))
            v.setOnClickPendingIntent(R.id.w_next, command(c, RadioService.A_NEXT, 3))
            v.setOnClickPendingIntent(R.id.w_reconnect, command(c, RadioService.A_RECONNECT, 4))
            v.setOnClickPendingIntent(R.id.w_shuffle, command(c, RadioService.A_SHUFFLE, 5))
        } else {
            for (id in intArrayOf(R.id.w_play, R.id.w_prev, R.id.w_next, R.id.w_reconnect, R.id.w_shuffle)) {
                v.setOnClickPendingIntent(id, app)
            }
        }
        manager.updateAppWidget(ids, v)
    }

    private fun command(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, code,
            Intent(c, RadioWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openAppIntent(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 10,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun openApp(c: Context) {
        try {
            openAppIntent(c).send()
        } catch (e: Exception) {
            // ничего: откроют вручную
        }
    }
}
