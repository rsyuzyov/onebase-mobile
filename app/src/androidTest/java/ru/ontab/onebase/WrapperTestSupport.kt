package ru.ontab.onebase

import android.app.Activity
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Общая обвязка инструментальных тестов обёртки.
 *
 * Адрес лаунчера подставляется через [OneBaseService.launcherUrl] до запуска экрана —
 * тот же путь, которым его кладёт настоящий сервис, поэтому продакшн-код не знает
 * о тестах и не содержит для них ни одной ветки.
 */
object WrapperTestSupport {

    // Эмулятор заметно медленнее телефона: первый запуск WebView в процессе плюс
    // холодный старт страницы легко съедают десяток секунд.
    private const val TIMEOUT_MS = 30_000L
    private const val STEP_MS = 100L

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Достаёт экран из сценария: дальше с ним работаем напрямую, без onActivity. */
    fun activityOf(scenario: ActivityScenario<MainActivity>): MainActivity {
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        return requireNotNull(activity) { "экран не запустился" }
    }

    fun webOf(activity: Activity): WebView = activity.findViewById(R.id.web)

    /**
     * Выполняет выражение в странице и возвращает результат как JSON-строку.
     *
     * Через runOnMainSync, а не через ActivityScenario.onActivity: второй ждёт
     * простоя приложения целиком, и любой живой таймер в странице подвешивает
     * вызов на неопределённое время.
     */
    fun eval(activity: MainActivity, js: String): String {
        var result = ""
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webOf(activity).evaluateJavascript(js) { value ->
                result = value.orEmpty()
                done.countDown()
            }
        }
        check(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "страница не ответила на: $js" }
        return result
    }

    /**
     * Ждёт, пока выражение в странице не станет истинным. Ожидание по факту, а не по
     * фиксированной паузе: загрузка страницы в WebView занимает разное время, и
     * sleep на глазок даёт тесты, которые падают через раз.
     */
    fun waitForJs(activity: MainActivity, js: String, what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runCatching { eval(activity, js) }.getOrDefault("")
            if (last == "true") return
            SystemClock.sleep(STEP_MS)
        }
        // Текущий адрес в сообщении: чаще всего страница не та, которую ждали,
        // и без него приходится гадать по логам.
        val where = runCatching {
            eval(activity, "JSON.stringify({url: location.href, history: history.length})")
        }.getOrDefault("?")
        error("не дождались: $what (последнее значение: $last, состояние: $where)")
    }

    /**
     * Список записей истории WebView — «что видит кнопка Назад».
     * JS-свойство history.length показывает только их количество, а разбираться
     * приходится в том, какие именно записи туда легли.
     */
    fun historyOf(activity: MainActivity): String {
        var dump = ""
        instrumentation.runOnMainSync {
            val list = webOf(activity).copyBackForwardList()
            dump = (0 until list.size).joinToString(" | ") { index ->
                val item = list.getItemAtIndex(index)
                val mark = if (index == list.currentIndex) "*" else " "
                "$mark${item.url}"
            }
        }
        return dump
    }

    /**
     * Открывает стартовую страницу и убеждается, что она отрисовалась.
     *
     * Разовый отказ подключения к только что открытому порту — не дефект обёртки,
     * а свойство среды: WebView иногда не достучится до сервера, поднятого
     * мгновение назад, и показывает chrome-error. Приложение на такой случай
     * рисует экран с кнопкой «Повторить» — тест ею и пользуется, вместо того
     * чтобы падать на ровном месте.
     */
    fun openLauncher(activity: MainActivity) {
        repeat(3) { attempt ->
            val loaded = runCatching {
                waitForJs(activity, "!!document.getElementById('launcher')", "лаунчер загрузился")
            }.isSuccess
            if (loaded) return
            if (attempt < 2) {
                instrumentation.runOnMainSync { activity.findViewById<android.widget.Button>(R.id.retry).performClick() }
            }
        }
        error("лаунчер не загрузился за три попытки")
    }

    /**
     * Нажимает по элементу страницы настоящим жестом.
     *
     * Именно жестом, а не element.click() из evaluateJavascript: навигацию,
     * начатую скриптом без участия пользователя, WebView помечает клиентским
     * редиректом и схлопывает с предыдущей записью истории. Тест на JS-кликах
     * показывал «Назад», перепрыгивающий страницу, — поведение, которого на
     * устройстве нет.
     *
     * Масштаб берётся отношением размера самого WebView к innerWidth страницы,
     * а не плотностью экрана: WebView масштабирует страницу по её viewport, и
     * пересчёт по density промахивается. Дерево доступности тут не помощник —
     * содержимое WebView в нём не появляется.
     */
    fun tapElement(activity: MainActivity, elementId: String) {
        val before = eval(activity, "location.href")
        val measured = eval(
            activity,
            """
            (function () {
              var el = document.getElementById('$elementId');
              if (!el) return '';
              var r = el.getBoundingClientRect();
              return [r.left + r.width / 2, r.top + r.height / 2, window.innerWidth, window.innerHeight].join(',');
            })()
            """.trimIndent(),
        )
        val parts = measured.trim('"').split(',').mapNotNull { it.trim().toFloatOrNull() }
        check(parts.size == 4) { "не нашли элемент '$elementId' на странице (ответ: $measured)" }
        val (cssX, cssY, cssWidth, cssHeight) = parts

        var screenX = 0
        var screenY = 0
        instrumentation.runOnMainSync {
            val web = webOf(activity)
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            screenX = location[0] + (cssX * web.width / cssWidth).toInt()
            screenY = location[1] + (cssY * web.height / cssHeight).toInt()
        }
        UiDevice.getInstance(instrumentation).click(screenX, screenY)
        // Промах выглядит как «ничего не произошло», и разбираться пришлось бы по
        // симптомам следующего шага. Засчитываем попадание двумя способами: страница
        // сама отметила касание либо уже сменилась — при переходе по ссылке отметка
        // остаётся на покинутой странице и проверить её негде.
        waitForJs(
            activity,
            "window.__lastClick === '$elementId' || location.href !== $before",
            "нажатие попало по '$elementId' (тап в $screenX, $screenY)",
        )
    }

    /** Что думает сам WebView о возможности шага назад. */
    fun canGoBack(activity: MainActivity): Boolean {
        var can = false
        instrumentation.runOnMainSync { can = webOf(activity).canGoBack() }
        return can
    }

    /** Ждёт произвольного условия на стороне приложения. */
    fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(STEP_MS)
        }
        error("не дождались: $what")
    }

    /**
     * Уводит страницу с тестового сервера перед его остановкой.
     *
     * MockWebServer.shutdown() ждёт закрытия соединений, а WebView держит их
     * keep-alive: без этого шага остановка сервера подвешивала весь прогон —
     * тест уходил в бесконечное ожидание уже после успешных проверок.
     */
    fun detach(activity: MainActivity?) {
        activity ?: return
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webOf(activity).loadUrl("about:blank")
            done.countDown()
        }
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        SystemClock.sleep(300) // соединения закрываются не мгновенно
    }
}
