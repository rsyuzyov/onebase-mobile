package ru.ontab.onebase

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.ontab.onebase.WrapperTestSupport.eval
import ru.ontab.onebase.WrapperTestSupport.waitForJs

/**
 * JS-диалоги платформы. Без WebChromeClient WebView гасит их молча: `confirm()`
 * возвращает false, ничего не показав, — и форму с несохранёнными правками
 * становится невозможно закрыть, а подтверждаемые действия не выполняются.
 *
 * Диалог блокирует поток страницы, поэтому вызов уходит через setTimeout, а
 * результат забирается уже после нажатия кнопки.
 */
@RunWith(AndroidJUnit4::class)
class DialogsTest {

    private lateinit var platform: FakePlatform
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
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
    }

    @Test(timeout = 60_000L)
    fun confirmShowsDialogAndReturnsTrue() {
        eval(activity, "window.__answer = null; setTimeout(function () { window.__answer = window.askClose(); }, 0)")

        onView(withText("Данные были изменены и не записаны. Закрыть форму?"))
            .inRoot(isDialog())
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.isDisplayed(),
            ))
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())

        waitForJs(activity, "window.__answer === true", "confirm вернул согласие")
    }

    @Test(timeout = 60_000L)
    fun confirmReturnsFalseOnCancel() {
        eval(activity, "window.__answer = null; setTimeout(function () { window.__answer = window.askClose(); }, 0)")

        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())

        waitForJs(activity, "window.__answer === false", "confirm вернул отказ")
    }

    @Test(timeout = 60_000L)
    fun alertShowsAndPageContinues() {
        eval(activity, "window.__done = null; setTimeout(function () { window.__done = window.notify(); }, 0)")

        onView(withText("Ошибка загрузки картинки")).inRoot(isDialog())
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.isDisplayed(),
            ))
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())

        waitForJs(activity, "window.__done === 'ok'", "alert не заблокировал страницу")
    }
}
