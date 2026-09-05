package ru.ontab.onebase

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Единственный экран: WebView поверх лаунчера информационных баз,
 * который поднимает [OneBaseService] встроенным бинарём платформы.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false

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

        status = TextView(this).apply {
            text = getString(R.string.starting)
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }

        web = WebView(this).apply {
            visibility = WebView.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = OneBaseChromeClient(this@MainActivity, ::openFileChooser)
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                Downloads.enqueue(this@MainActivity, url, contentDisposition, mimeType)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val root = android.widget.FrameLayout(this)
        root.addView(web)
        root.addView(status)
        setContentView(root)

        startForegroundService(Intent(this, OneBaseService::class.java))
        waitForLauncher()
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

    /** Лаунчер печатает свой адрес в stdout; сервис кладёт его сюда, как только увидит. */
    private fun waitForLauncher() {
        val url = OneBaseService.launcherUrl.get()
        if (url != null && !loaded) {
            loaded = true
            status.visibility = TextView.GONE
            web.visibility = WebView.VISIBLE
            web.loadUrl(url)
            return
        }
        handler.postDelayed({ waitForLauncher() }, 300)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        // Закрыли приложение — гасим платформу: сценарий «запустил, поработал, закрыл».
        if (isFinishing) {
            stopService(Intent(this, OneBaseService::class.java))
        }
        super.onDestroy()
    }
}
