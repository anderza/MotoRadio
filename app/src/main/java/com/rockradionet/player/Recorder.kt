package com.rockradionet.player

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Запись потока радио в файл (кнопка «Скачать трек»). Метод start запускает отдельный поток. */
class Recorder(private val ctx: Context) {
    @Volatile
    var running = false
        private set

    fun start(url: String, name: String, ext: String, done: (String) -> Unit) {
        if (running) return
        running = true
        Thread {
            val tmp = File(ctx.cacheDir, "rec_" + System.currentTimeMillis() + "." + ext)
            var msg = "Не удалось записать поток"
            try {
                download(url, tmp)
            } catch (e: Exception) {
                // обрыв потока — сохраняем то, что успели записать
            }
            try {
                if (tmp.length() > 0) msg = save(tmp, name, ext)
            } catch (e: Exception) {
                msg = "Не удалось сохранить файл"
            } finally {
                running = false
                tmp.delete()
            }
            done(msg)
        }.start()
    }

    fun stop() {
        running = false
    }

    private fun download(url: String, out: File) {
        var target = URL(url)
        for (attempt in 0 until 6) {
            val conn = target.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "RockRadioNet/1.0 (Android)")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return
                    target = URL(target, loc)
                    continue
                }
                if (code != 200) return
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (running) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun save(tmp: File, name: String, ext: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "MotoRadio" }
        val mime = when (ext) {
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val fileName = "$safe.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MotoRadio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "Не удалось сохранить файл"
            resolver.openOutputStream(uri)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Сохранено: Музыка/MotoRadio/$fileName"
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
        tmp.copyTo(File(dir, fileName), true)
        return "Сохранено: " + dir.absolutePath + "/" + fileName
    }
}
