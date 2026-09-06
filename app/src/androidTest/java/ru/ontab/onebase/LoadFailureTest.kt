package ru.ontab.onebase

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Недоступный сервер не должен оставлять пользователя перед пустым белым полем:
 * на складе это выглядит как «приложение сломалось», и позвать некого.
 */
@RunWith(AndroidJUnit4::class)
class LoadFailureTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        OneBaseService.launcherOverride = null
        OneBaseService.launcherUrl.set(null)
    }

    @Test(timeout = 60_000L)
    fun unreachableAddressShowsRetryScreen() {
        // Порт закрыт: свободный адрес на loopback без слушателя даёт ту же ошибку
        // навигации, что и не поднявшаяся платформа.
        OneBaseService.launcherOverride = "http://127.0.0.1:49999/launcher"
        OneBaseService.launcherUrl.set("http://127.0.0.1:49999/launcher")
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

        WrapperTestSupport.waitFor("показан экран ошибки") {
            var visible = false
            launched.onActivity { activity ->
                visible = activity.findViewById<View>(R.id.retry).visibility == View.VISIBLE
            }
            visible
        }

        launched.onActivity { activity ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.splash).visibility)
            assertEquals(View.GONE, WrapperTestSupport.webOf(activity).visibility)
            val text = activity.findViewById<TextView>(R.id.status).text.toString()
            assertTrue("текст не объясняет причину: $text", text.startsWith("Не удалось открыть интерфейс"))
        }
    }
}
