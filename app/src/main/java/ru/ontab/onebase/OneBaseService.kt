package ru.ontab.onebase

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Держит процесс платформы onebase живым, пока приложение открыто.
 *
 * Запускается штатный лаунчер информационных баз (`onebase start --no-gui`):
 * он сам поднимает базы дочерними процессами, поэтому своего механизма
 * управления базами обёртке не нужно. Адрес лаунчера платформа печатает
 * в stdout строкой с фиксированным префиксом — его и ждём.
 */
class OneBaseService : Service() {

    companion object {
        const val TAG = "onebase"
        private const val CHANNEL_ID = "onebase-runtime"
        private const val NOTIFICATION_ID = 1

        /** Префикс — часть контракта платформы, по нему CI апстрима тоже ориентируется. */
        private const val URL_PREFIX = "Лаунчер доступен по адресу: "

        /** Адрес лаунчера; пусто, пока платформа не напечатала строку. */
        val launcherUrl = AtomicReference<String?>(null)

        /**
         * Подставной адрес платформы для тестов обёртки.
         *
         * Когда он задан, сервис не запускает бинарь и берёт адрес отсюда. Без этой
         * ветки тест не изолировать: на устройстве, где платформа поднимается, она
         * перезаписывает [launcherUrl] своим адресом через несколько секунд после
         * старта — подставленный тестом адрес затирался, и проверки зависали в
         * ожидании страницы, которой нет. На x86_64-эмуляторе того же не
         * происходило только потому, что платформа там падает на seccomp.
         */
        @Volatile
        @VisibleForTesting
        var launcherOverride: String? = null
    }

    private var process: Process? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification())

        worker = Thread {
            try {
                prepareAndRun()
            } catch (e: Exception) {
                Log.e(TAG, "платформа не запустилась", e)
            }
        }.also { it.start() }

        return START_STICKY
    }

    private fun prepareAndRun() {
        launcherOverride?.let { url ->
            Log.i(TAG, "платформа не запускается: адрес подставлен ($url)")
            launcherUrl.set(url)
            return
        }
        val binary = File(applicationInfo.nativeLibraryDir, "libonebase.so")
        val home = filesDir
        val project = File(home, "project")
        val db = File(home, "wh.db")

        if (!project.exists()) {
            copyAssetDir("project", project)
            Log.i(TAG, "конфигурация распакована в ${project.absolutePath}")
        }

        if (!db.exists()) {
            runAndWait(binary, home, listOf("migrate", "--project", project.absolutePath, "--sqlite", db.absolutePath))
            runAndWait(
                binary, home, listOf(
                    "ibases", "add",
                    "--name", "Склад",
                    "--source", "file",
                    "--path", project.absolutePath,
                    "--sqlite", db.absolutePath,
                    "--port", "18080",
                )
            )
        }

        val pb = ProcessBuilder(binary.absolutePath, "start", "--no-gui")
            .directory(home)
            .redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath

        val p = pb.start()
        process = p

        BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
            reader.forEachLine { line ->
                Log.i(TAG, line)
                if (line.startsWith(URL_PREFIX)) {
                    val url = line.removePrefix(URL_PREFIX).trim()
                    launcherUrl.set(url)
                    Log.i(TAG, "лаунчер готов: $url")
                }
            }
        }
        Log.w(TAG, "процесс платформы завершился, код ${p.waitFor()}")
    }

    /** Однократный запуск команды платформы (migrate, ibases) с ожиданием результата. */
    private fun runAndWait(binary: File, home: File, args: List<String>) {
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(home)
            .redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        val p = pb.start()
        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            r.forEachLine { Log.i(TAG, "[${args.first()}] $it") }
        }
        Log.i(TAG, "[${args.first()}] завершено с кодом ${p.waitFor()}")
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(target, child))
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Платформа onebase", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Информационная база работает")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "останавливаю платформу")
        process?.destroy()
        process = null
        super.onDestroy()
    }
}
