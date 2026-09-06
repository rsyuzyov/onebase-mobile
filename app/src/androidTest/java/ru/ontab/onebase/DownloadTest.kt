package ru.ontab.onebase

import android.content.ContentResolver
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.ontab.onebase.WrapperTestSupport.eval
import ru.ontab.onebase.WrapperTestSupport.waitForJs

/**
 * Выгрузка списка. WebView сам файлы не сохраняет: без DownloadListener нажатие на
 * ссылку выгрузки не делает ничего. Отдельно проверяется имя — платформа отдаёт его
 * в filename*=UTF-8'' по RFC 6266, а в обычный filename кладёт ASCII-заглушку из
 * подчёркиваний, и файл легко оседает в «Загрузках» как excel.xlsx.
 */
@RunWith(AndroidJUnit4::class)
class DownloadTest {

    private val resolver: ContentResolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private lateinit var platform: FakePlatform
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        removeDownloaded()
        platform = FakePlatform().also { it.start() }
        // Обе точки: override не даёт сервису поднять платформу и перетереть адрес,
        // launcherUrl — то, что экран читает сразу, не дожидаясь сервиса.
        OneBaseService.launcherOverride = platform.launcherUrl
        OneBaseService.launcherUrl.set(platform.launcherUrl)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        activity = WrapperTestSupport.activityOf(scenario)
        WrapperTestSupport.openLauncher(activity)
        WrapperTestSupport.tapElement(activity, "start")
        waitForJs(activity, "!!document.getElementById('base')", "интерфейс базы открылся")
    }

    @After
    fun tearDown() {
        WrapperTestSupport.detach(activity)
        scenario.close()
        platform.stop()
        OneBaseService.launcherOverride = null
        OneBaseService.launcherUrl.set(null)
        removeDownloaded()
    }

    @Test(timeout = 60_000L)
    fun listExportSavedUnderCyrillicName() {
        WrapperTestSupport.tapElement(activity, "excel")

        // Ждём именно дописанный файл: запись в MediaStore появляется раньше, чем
        // в неё попадает содержимое, и проверка сразу после неё видит нулевой размер.
        WrapperTestSupport.waitFor("файл дописан в «Загрузках»") { (downloadedSize() ?: 0) > 0 }
        assertTrue("файл короче отданного сервером", (downloadedSize() ?: 0) >= 2)
    }

    /** Размер сохранённого файла, либо null — если файла ещё нет. */
    private fun downloadedSize(): Long? = resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads.SIZE),
        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
        arrayOf(FILE_NAME),
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun removeDownloaded() {
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME),
            )
        }
    }

    private companion object {
        const val FILE_NAME = "Номенклатура.xlsx"
    }
}
