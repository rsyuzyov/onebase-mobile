package ru.ontab.onebase

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.ontab.onebase.WrapperTestSupport.eval
import ru.ontab.onebase.WrapperTestSupport.waitForJs

/**
 * Путь пользователя от лаунчера до интерфейса базы.
 *
 * Главный сценарий здесь — открытие базы через `window.open`. На нём обёртка уже
 * ломалась: после установки WebChromeClient вызов начал возвращать null, база
 * запускалась, а интерфейс не появлялся. Поймано случайно, руками, — тест закрывает
 * эту дыру.
 */
@RunWith(AndroidJUnit4::class)
class LauncherFlowTest {

    private lateinit var platform: FakePlatform
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        platform = FakePlatform().also { it.start() }
        // Тот же путь, которым адрес кладёт настоящий сервис.
        // Обе точки: override не даёт сервису поднять платформу и перетереть адрес,
        // launcherUrl — то, что экран читает сразу, не дожидаясь сервиса.
        OneBaseService.launcherOverride = platform.launcherUrl
        OneBaseService.launcherUrl.set(platform.launcherUrl)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        activity = WrapperTestSupport.activityOf(scenario)
        WrapperTestSupport.openLauncher(activity)
    }

    @After
    fun tearDown() {
        WrapperTestSupport.detach(activity)
        scenario.close()
        platform.stop()
        OneBaseService.launcherOverride = null
        OneBaseService.launcherUrl.set(null)
    }

    @Test(timeout = TEST_TIMEOUT)
    fun windowOpenFromLauncherShowsBaseInSameScreen() {
        WrapperTestSupport.tapElement(activity, "start")
        waitForJs(activity, "!!document.getElementById('base')", "интерфейс базы открылся")
    }

    @Test(timeout = TEST_TIMEOUT)
    fun splashGivesWayToLoadedPage() {
        assertEquals(View.VISIBLE, WrapperTestSupport.webOf(activity).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.splash).visibility)
    }

    @Test(timeout = TEST_TIMEOUT)
    fun backNavigatesWithinBase() {
        WrapperTestSupport.tapElement(activity, "start")
        waitForJs(activity, "!!document.getElementById('base')", "интерфейс базы открылся")
        WrapperTestSupport.tapElement(activity, "to-form")
        waitForJs(activity, "!!document.getElementById('form')", "форма открылась")

        val before = WrapperTestSupport.historyOf(activity)
        val canBefore = WrapperTestSupport.canGoBack(activity)
        pressBack()

        runCatching { waitForJs(activity, "!!document.getElementById('base')", "вернулись в интерфейс базы") }
            .onFailure { failure ->
                throw AssertionError(
                    failure.message + " | canGoBack=" + canBefore + " | до: " + before +
                        " | после: " + WrapperTestSupport.historyOf(activity),
                )
            }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    /**
     * Первое нажатие на первой странице не должно закрывать приложение: случайное
     * касание гасило платформу вместе с несохранённой работой.
     */
    @Test(timeout = TEST_TIMEOUT)
    fun firstBackOnHomeDoesNotCloseApp() {
        pressBack()

        WrapperTestSupport.waitFor("приложение осталось открытым") {
            scenario.state == Lifecycle.State.RESUMED
        }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private companion object {
        /** Ни один сценарий обёртки не должен идти дольше: лучше упасть, чем повиснуть. */
        const val TEST_TIMEOUT = 60_000L
    }
}
