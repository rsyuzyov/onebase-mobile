package ru.ontab.onebase

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диагностика для пользователей склада: ни отладчика, ни удалённого доступа к их
 * телефонам у нас не будет.
 *
 * Всё пишется на диск сразу, а не отправляется по требованию: приложение может
 * упасть при открытии или зависнуть, и тогда ни кнопка «сообщить», ни обмен с
 * центральной базой не сработают — отправлять будет уже некому. Файл переживает
 * смерть процесса и дожидается следующего запуска.
 */
object Diagnostics {

    private const val CRASH_DIR = "diagnostics"
    private const val LOG_FILE = "journal.txt"

    /** Хвоста в четверть мегабайта хватает на несколько сеансов работы. */
    private const val LOG_LIMIT_BYTES = 256 * 1024

    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * Ставит перехватчик необработанных исключений. Прежний обработчик вызывается
     * следом: гасить процесс должна система, наше дело — успеть записать причину.
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { saveCrash(context, thread, error) }
            previous?.uncaughtException(thread, error)
        }
        note(context, "приложение запущено, версия ${BuildConfig.VERSION_NAME}")
    }

    /** Строка в журнал приложения — то, что понадобится при разборе жалобы. */
    fun note(context: Context, message: String) {
        runCatching {
            val file = File(dir(context), LOG_FILE)
            trimIfHuge(file)
            file.appendText("$stamp  $message\n")
        }
    }

    /** Отчёты о падениях, которые ещё никто не разобрал, — свежие первыми. */
    fun pendingCrashes(context: Context): List<File> =
        dir(context).listFiles { file -> file.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Текст для отправки: описание падения плюс хвост журнала. */
    fun report(context: Context, crash: File): String = buildString {
        appendLine(crash.readText())
        appendLine("─── журнал приложения ───")
        append(File(dir(context), LOG_FILE).takeIf { it.exists() }?.readText().orEmpty())
    }

    fun clearCrashes(context: Context) {
        pendingCrashes(context).forEach { it.delete() }
    }

    /**
     * Открыто для тестов: вызвать настоящее падение в тесте нельзя — следом
     * отработает системный обработчик и убьёт процесс вместе с прогоном.
     */
    @VisibleForTesting
    fun saveCrash(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val text = """
            Время: $stamp
            Сборка: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})
            Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Архитектура: ${Build.SUPPORTED_ABIS.joinToString()}
            Поток: ${thread.name}

            $trace
        """.trimIndent()
        File(dir(context), "crash-${System.currentTimeMillis()}.txt").writeText(text)
        note(context, "падение: ${error.javaClass.simpleName}: ${error.message}")
    }

    /**
     * Журнал не должен расти без предела на телефоне, где место кончается быстрее
     * всего. Обрезаем с начала, сохраняя свежий хвост, — старое всё равно бесполезно.
     */
    private fun trimIfHuge(file: File) {
        if (!file.exists() || file.length() <= LOG_LIMIT_BYTES) return
        val tail = file.readText().takeLast(LOG_LIMIT_BYTES / 2)
        file.writeText(tail.substringAfter('\n', tail))
    }

    private fun dir(context: Context): File =
        File(context.filesDir, CRASH_DIR).apply { mkdirs() }
}
