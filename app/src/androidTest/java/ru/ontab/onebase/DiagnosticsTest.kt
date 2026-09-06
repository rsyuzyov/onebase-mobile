package ru.ontab.onebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Диагностика — единственный канал, по которому до нас доедет причина сбоя у
 * кладовщика: ни отладчика, ни доступа к его телефону не будет. Если она молча
 * перестанет писать файлы, узнаем об этом только когда понадобится разбирать жалобу.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = Diagnostics.clearCrashes(context)

    @After
    fun tearDown() = Diagnostics.clearCrashes(context)

    @Test(timeout = 30_000L)
    fun crashReportKeepsWhatIsNeededForDiagnosis() {
        Diagnostics.saveCrash(context, Thread.currentThread(), IllegalStateException("тестовый сбой"))

        val crashes = Diagnostics.pendingCrashes(context)
        assertEquals("отчёт о падении не сохранён", 1, crashes.size)

        val report = Diagnostics.report(context, crashes.first())
        assertTrue("нет причины падения", report.contains("тестовый сбой"))
        assertTrue("нет типа исключения", report.contains("IllegalStateException"))
        assertTrue("нет версии сборки", report.contains(BuildConfig.VERSION_NAME))
        assertTrue("нет модели устройства", report.contains(android.os.Build.MODEL))
        assertTrue("нет журнала приложения", report.contains("журнал приложения"))
    }

    @Test(timeout = 30_000L)
    fun journalKeepsEventsAndSurvivesInReport() {
        Diagnostics.note(context, "проверочная запись о запуске платформы")
        Diagnostics.saveCrash(context, Thread.currentThread(), RuntimeException("падение после события"))

        val report = Diagnostics.report(context, Diagnostics.pendingCrashes(context).first())
        assertTrue("событие до падения потеряно", report.contains("проверочная запись о запуске платформы"))
    }

    /** Разобранные отчёты не должны копиться: телефон склада — не архив. */
    @Test(timeout = 30_000L)
    fun clearingRemovesHandledReports() {
        Diagnostics.saveCrash(context, Thread.currentThread(), RuntimeException("первое"))
        Diagnostics.saveCrash(context, Thread.currentThread(), RuntimeException("второе"))
        assertEquals(2, Diagnostics.pendingCrashes(context).size)

        Diagnostics.clearCrashes(context)

        assertEquals(0, Diagnostics.pendingCrashes(context).size)
    }

    /** Перехватчик ставится приложением, а не экраном: сбои до интерфейса тоже наши. */
    @Test(timeout = 30_000L)
    fun handlerIsInstalledByApplication() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue("перехватчик не установлен", handler != null)
        assertTrue(
            "перехватчик не наш: ${handler?.javaClass?.name}",
            handler!!.javaClass.name.contains("Diagnostics"),
        )
    }
}
