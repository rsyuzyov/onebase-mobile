package ru.ontab.onebase

import android.content.Context
import android.net.Uri
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/**
 * Без WebChromeClient Android WebView молча гасит JS-диалоги: `confirm()` возвращает
 * false, не показав ничего. Платформа зовёт его на закрытии изменённой формы, удалении
 * вложения и подтверждении действий — то есть без этого класса форму с правками
 * физически нельзя закрыть, а подтверждаемые действия не выполняются вовсе.
 *
 * Он же отвечает за `<input type="file">` (без [onShowFileChooser] выбор файла не
 * открывается) и за `window.open` — им лаунчер платформы открывает запущенную базу.
 */
class OneBaseChromeClient(
    private val context: Context,
    /** Открывает системный выбор файла; результат уходит в переданный колбэк. */
    private val pickFile: (WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>?>) -> Boolean,
    /** Куда вести адрес, запрошенный через window.open. */
    private val openInPlace: (String) -> Unit = {},
) : WebChromeClient() {

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        dialog(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        dialog(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        val input = EditText(context).apply { setText(defaultValue.orEmpty()) }
        dialog(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    /** Уход со страницы с несохранёнными данными — тот же диалог, что и confirm. */
    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean =
        onJsConfirm(view, url, message, result)

    override fun onShowFileChooser(
        view: WebView?,
        callback: ValueCallback<Array<Uri>?>,
        params: FileChooserParams,
    ): Boolean = pickFile(params, callback)

    /**
     * Лаунчер открывает запущенную базу так: `window.open('', '_blank')`, а когда
     * сервер ответил — `win.location.href = <адрес базы>`. На телефоне второе окно
     * не нужно и некуда его деть, поэтому подставляем разовый WebView-перехватчик:
     * он ловит первый же адрес и передаёт его основному экрану. Без этого база
     * запускалась, но интерфейс не открывался — окно просто пропадало.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        val catcher = WebView(context)
        catcher.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                openInPlace(request.url.toString())
                v.destroy()
                return true
            }

            @Deprecated("нужен для WebView, который зовёт устаревшую перегрузку")
            override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                openInPlace(url)
                v.destroy()
                return true
            }
        }
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = catcher
        resultMsg.sendToTarget()
        return true
    }

    private fun dialog(message: String?) = AlertDialog.Builder(context)
        .setMessage(message.orEmpty())
        .setCancelable(true)
}
