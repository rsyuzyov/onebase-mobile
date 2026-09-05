package ru.ontab.onebase

import android.content.Context
import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/**
 * Без WebChromeClient Android WebView молча гасит JS-диалоги: `confirm()` возвращает
 * false, не показав ничего. Платформа зовёт его на закрытии изменённой формы, удалении
 * вложения и подтверждении действий — то есть без этого класса форму с правками
 * физически нельзя закрыть, а подтверждаемые действия не выполняются вовсе.
 *
 * Он же отвечает за `<input type="file">`: без [onShowFileChooser] выбор файла
 * не открывается, и вложения с картинками номенклатуры недоступны.
 */
class OneBaseChromeClient(
    private val context: Context,
    /** Открывает системный выбор файла; результат уходит в переданный колбэк. */
    private val pickFile: (WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>?>) -> Boolean,
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

    private fun dialog(message: String?) = AlertDialog.Builder(context)
        .setMessage(message.orEmpty())
        .setCancelable(true)
}
