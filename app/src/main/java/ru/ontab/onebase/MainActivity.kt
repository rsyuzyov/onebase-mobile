package ru.ontab.onebase

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Единственный экран: WebView поверх лаунчера информационных баз,
 * который поднимает [OneBaseService] встроенным бинарём платформы.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var splash: View
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var retry: Button
    private lateinit var progress: View

    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false
    private var waitingSince = 0L
    private var lastBackAt = 0L

    /** Колбэк ждёт результата системного выбора файла; пустой — выбор не открыт. */
    private var pendingFiles: ValueCallback<Array<Uri>?>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFiles
        pendingFiles = null
        // Отдать ответ обязательно даже при отмене: пока колбэк не вызван,
        // WebView считает выбор открытым и следующее нажатие игнорирует.
        callback?.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        splash = findViewById(R.id.splash)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        retry = findViewById(R.id.retry)
        progress = findViewById(R.id.progress)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Масштаб щипком ломает вёрстку списков и в рабочем приложении не нужен:
        // страницы платформы уже свёрстаны под ширину экрана.
        web.settings.setSupportZoom(false)
        web.settings.builtInZoomControls = false
        // Лаунчер открывает запущенную базу через window.open — без поддержки
        // нескольких окон этот вызов возвращает null, база стартует, а интерфейс
        // не появляется. Второе окно тут же схлопывается в текущий экран.
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.webViewClient = OneBaseWebViewClient()
        web.webChromeClient = OneBaseChromeClient(this, ::openFileChooser, web::loadUrl)
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            Downloads.enqueue(this, url, contentDisposition, mimeType)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        retry.setOnClickListener { restartWaiting() }
        onBackPressedDispatcher.addCallback(this, BackHandler())

        // Процесс убили в фоне — возвращаем страницу, на которой человек работал,
        // вместо стартовой: иначе после каждого переключения приложений
        // приходится заново идти к нужному документу.
        val restored = savedInstanceState?.let { web.restoreState(it) != null } ?: false
        if (restored) {
            showWeb()
        }

        startForegroundService(Intent(this, OneBaseService::class.java))
        if (!restored) {
            restartWaiting()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (loaded) web.saveState(outState)
    }

    /**
     * Открывает системный выбор файла для `<input type="file">`. Возвращает false,
     * если открыть не удалось, — тогда WebView сам отменит запрос.
     */
    private fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>?>,
    ): Boolean {
        pendingFiles?.onReceiveValue(null) // предыдущий выбор не завершился — снимаем его
        pendingFiles = callback
        return runCatching { filePicker.launch(params.createIntent()) }
            .onFailure { pendingFiles = null }
            .isSuccess
    }

    private fun restartWaiting() {
        loaded = false
        waitingSince = SystemClock.elapsedRealtime()
        showSplash(getString(R.string.starting), hintText = null, canRetry = false)
        handler.removeCallbacksAndMessages(null)
        waitForLauncher()
    }

    /** Лаунчер печатает свой адрес в stdout; сервис кладёт его сюда, как только увидит. */
    private fun waitForLauncher() {
        val url = OneBaseService.launcherUrl.get()
        if (url != null && !loaded) {
            loaded = true
            web.loadUrl(url)
            showWeb()
            return
        }
        val waiting = SystemClock.elapsedRealtime() - waitingSince
        when {
            // Первый запуск разворачивает конфигурацию и мигрирует базу — это долго,
            // и молчащий экран в такой момент выглядит как зависание.
            waiting > STARTUP_HINT_MS && hint.visibility != View.VISIBLE ->
                showSplash(getString(R.string.starting), getString(R.string.starting_slow), canRetry = false)
            waiting > STARTUP_GIVE_UP_MS -> {
                showSplash(getString(R.string.start_failed), getString(R.string.starting_slow), canRetry = true)
                return
            }
        }
        handler.postDelayed({ waitForLauncher() }, POLL_MS)
    }

    private fun showSplash(text: String, hintText: String?, canRetry: Boolean) {
        splash.visibility = View.VISIBLE
        web.visibility = View.GONE
        status.text = text
        hint.text = hintText.orEmpty()
        hint.visibility = if (hintText == null) View.GONE else View.VISIBLE
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        progress.visibility = if (canRetry) View.GONE else View.VISIBLE
    }

    private fun showWeb() {
        loaded = true
        splash.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    /**
     * «Назад» в глубине интерфейса возвращает на шаг назад, а на первой странице —
     * не закрывает приложение сразу: одно случайное нажатие гасило платформу вместе
     * с несохранённой работой. Второе нажатие подряд закрывает.
     */
    private inner class BackHandler : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (web.visibility == View.VISIBLE && web.canGoBack()) {
                web.goBack()
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt < EXIT_CONFIRM_MS) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                return
            }
            lastBackAt = now
            Toast.makeText(this@MainActivity, R.string.back_to_exit, Toast.LENGTH_SHORT).show()
        }
    }

    /** Ошибка загрузки не должна оставлять пустое белое поле без объяснения. */
    private inner class OneBaseWebViewClient : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Подзапросы (иконка, картинка) падать могут, а страница при этом рабочая —
            // экран ошибки показываем только на самой навигации.
            if (!request.isForMainFrame) return
            showSplash(
                getString(R.string.load_failed, error.description ?: ""),
                hintText = null,
                canRetry = true,
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Закрыли приложение — гасим платформу: сценарий «запустил, поработал, закрыл».
        if (isFinishing) {
            stopService(Intent(this, OneBaseService::class.java))
        }
        super.onDestroy()
    }

    private companion object {
        const val POLL_MS = 100L
        const val STARTUP_HINT_MS = 3_000L
        const val STARTUP_GIVE_UP_MS = 90_000L
        const val EXIT_CONFIRM_MS = 2_500L
    }
}
