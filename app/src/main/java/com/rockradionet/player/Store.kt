package com.rockradionet.player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Радиостанция в том же виде, что и в веб-интерфейсе: n — название, u — поток, g — жанр, l — логотип. */
data class Station(val name: String, val url: String, val genre: String, val logo: String)

/** Хранилище станций и состояния, общее для приложения, сервиса и виджета. */
object Store {
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun stations(c: Context): List<Station> {
        val raw = prefs(c).getString("stations", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Station>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("u").trim()
                if (url.isEmpty()) continue
                out.add(
                    Station(
                        name = o.optString("n").ifBlank { url },
                        url = url,
                        genre = o.optString("g"),
                        logo = o.optString("l")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveStations(c: Context, json: String) {
        prefs(c).edit().putString("stations", json).apply()
    }

    fun currentUrl(c: Context): String = prefs(c).getString("current", "") ?: ""

    /** Адрес, на котором станция в последний раз заиграла (если отличается от адреса из списка). */
    fun goodUrl(c: Context, station: String): String? =
        prefs(c).getString("good:" + station, null)?.takeIf { it.isNotBlank() }

    fun saveGoodUrl(c: Context, station: String, url: String) {
        prefs(c).edit().putString("good:" + station, url).apply()
    }

    fun saveCurrentUrl(c: Context, url: String) {
        prefs(c).edit().putString("current", url).apply()
    }

    /** "album" — обложка альбома, "artist" — фото исполнителя. */
    fun coverMode(c: Context): String = prefs(c).getString("coverMode", "album") ?: "album"

    fun saveCoverMode(c: Context, mode: String) {
        prefs(c).edit().putString("coverMode", if (mode == "artist") "artist" else "album").apply()
    }

    /** Режим «Случайная»: при каждой смене трека включается случайная станция. */
    fun shuffleMode(c: Context): Boolean = prefs(c).getBoolean("shuffleMode", false)

    fun saveShuffleMode(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("shuffleMode", on).apply()
    }

    fun volume(c: Context): Float = prefs(c).getFloat("volume", 1f)

    fun saveVolume(c: Context, v: Float) {
        prefs(c).edit().putFloat("volume", v).apply()
    }
}
