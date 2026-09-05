package ru.ontab.onebase

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Скачивание файлов, которые платформа отдаёт обычным ответом с Content-Disposition:
 * выгрузка списка в xlsx, вложения, готовые задания экспорта. WebView сам файлы не
 * сохраняет — без DownloadListener нажатие на такую ссылку не делает ничего.
 *
 * Системный DownloadManager здесь не годится: он ходит своим процессом и не понесёт
 * cookie сеанса, а база живёт на loopback этого приложения.
 */
object Downloads {

    // by lazy, а не сразу: иначе Handler создавался бы при загрузке класса, и разбор
    // имени файла нельзя было бы проверить обычным JVM-тестом — android.jar в них
    // заглушка, любой вызов из неё падает.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Качает [url] в папку «Загрузки» и сообщает пользователю результат. */
    fun enqueue(context: Context, url: String, contentDisposition: String?, mimeType: String?) {
        val name = fileName(url, contentDisposition, mimeType)
        val cookie = CookieManager.getInstance().getCookie(url)
        toast(context, context.getString(R.string.download_started, name))
        Thread {
            val result = runCatching { save(context, url, cookie, name, mimeType) }
            main.post {
                result.fold(
                    onSuccess = { toast(context, context.getString(R.string.download_done, it)) },
                    onFailure = { toast(context, context.getString(R.string.download_failed, it.message ?: "")) },
                )
            }
        }.start()
    }

    /** Возвращает путь, по которому файл лёг, — его показываем пользователю. */
    private fun save(context: Context, url: String, cookie: String?, name: String, mimeType: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val type = mimeType?.takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            return conn.inputStream.use { input ->
                openTarget(context, name, type) { out -> input.copyTo(out) }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * С Android 10 запись в общие «Загрузки» идёт через MediaStore и разрешения не
     * требует; на более старых — прямо в каталог, что тоже разрешено без запроса,
     * пока приложение пишет собственный файл.
     */
    private fun openTarget(context: Context, name: String, mime: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("не удалось создать файл в «Загрузках»")
            resolver.openOutputStream(uri)?.use(write) ?: error("не удалось открыть файл на запись")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return name
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use(write)
        return file.absolutePath
    }

    /**
     * Имя файла из Content-Disposition. URLUtil.guessFileName не понимает
     * filename*=UTF-8'' по RFC 6266, а платформа отдаёт русские имена именно там:
     * в обычный filename= уходит ASCII-фолбэк из подчёркиваний. Без разбора любая
     * выгрузка списка оседала в «Загрузках» как excel.xlsx.
     */
    internal fun fileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val header = contentDisposition.orEmpty()
        val extended = EXTENDED.find(header)
        if (extended != null) {
            val charset = extended.groupValues[1].trim().ifEmpty { "UTF-8" }
            val decoded = runCatching { URLDecoder.decode(extended.groupValues[2].trim(), charset) }.getOrNull()
            if (!decoded.isNullOrBlank()) return sanitize(decoded)
        }
        val plain = PLAIN.find(header)?.groupValues?.get(1)?.trim()
        // Фолбэк из одних подчёркиваний — ASCII-заглушка вместо кириллицы; от неё
        // пользы меньше, чем от имени, угаданного по адресу.
        if (!plain.isNullOrBlank() && !plain.substringBeforeLast('.').all { it == '_' }) return sanitize(plain)
        return sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType))
    }

    /** Отсекает разделители пути: имя из заголовка не должно уводить запись из «Загрузок». */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').trim().ifEmpty { "download" }

    private val EXTENDED = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
