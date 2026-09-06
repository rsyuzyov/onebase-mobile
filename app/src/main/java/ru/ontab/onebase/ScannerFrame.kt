package ru.ontab.onebase

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Корень экрана, который читает штрихкоды до метода ввода.
 *
 * Экранная клавиатура (IME) перехватывает клавиши физического сканера и
 * подставляет символы своей раскладки: латиница в коде маркировки превращается
 * в кириллицу — `5ObkkJ93igjJ` приезжает как `5ЩиллО93шпоО`. До приложения доходят
 * уже подменённые «виртуальные» события, а буквы вовсе не приходят клавишами: IME
 * вставляет их готовым текстом. Настройка раскладки для устройства не спасает —
 * метод ввода применяет свою поверх системной.
 *
 * [dispatchKeyEventPreIme] вызывается на пути к фокусу раньше метода ввода,
 * поэтому здесь виден исходный KeyEvent сканера с настоящим символом. У самого
 * WebView перехватывать бесполезно: ввод обрабатывает его внутренняя вьюха, и
 * onKeyPreIme на нём не вызывается. Цепочка идёт только по вью с фокусом —
 * поэтому [MainActivity] запрашивает фокус для WebView.
 */
class ScannerFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val assembler = ScanAssembler { SystemClock.elapsedRealtime() }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        // Встроенную клавиатуру не трогаем: человек печатает через метод ввода,
        // и кириллица там нужна как есть.
        if (!isScanner(event)) return super.dispatchKeyEventPreIme(event)
        // Клавиши без символа, которые сканеру не принадлежат (кириллица,
        // иероглифы), отдаём методу ввода: он умеет то, чего не умеет раскладка.
        if (!assembler.handles(event.keyCode, event.unicodeChar)) {
            return super.dispatchKeyEventPreIme(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true

        assembler.accept(event.keyCode, event.unicodeChar)?.let(::deliver)
        return true
    }

    /**
     * Пришло ли нажатие с внешнего устройства, а не с экранной клавиатуры.
     *
     * InputDevice.isExternal появился только в Android 10, а приложение живёт
     * с Android 8: на старых телефонах опираемся на признак виртуальной
     * клавиатуры — у неё нулевой идентификатор устройства.
     */
    private fun isScanner(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            device.isExternal
        } else {
            event.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
        }
    }

    /** Отдаёт собранный код странице так, будто его ввели в поле. */
    private fun deliver(code: String) {
        val separators = code.count { it == ScanAssembler.GROUP_SEPARATOR }
        Log.i(TAG, "штрихкод: ${code.length} символов, разделителей групп $separators")

        val web = findViewById<WebView>(R.id.web) ?: return
        // Через JSON, а не склейкой строк: в коде маркировки встречаются кавычки,
        // слэши и управляющие символы, которые иначе поломают выражение.
        val json = JSONObject().put("code", code).toString()
        web.evaluateJavascript(
            """
            (function () {
              var scan = $json.code;
              var el = document.activeElement;
              if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                var start = el.selectionStart == null ? el.value.length : el.selectionStart;
                var end = el.selectionEnd == null ? el.value.length : el.selectionEnd;
                el.value = el.value.slice(0, start) + scan + el.value.slice(end);
                var pos = start + scan.length;
                if (el.setSelectionRange) el.setSelectionRange(pos, pos);
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
              }
              // Событие уходит всегда: страница может слушать сканер и без поля
              // в фокусе — например, чтобы найти позицию по коду.
              document.dispatchEvent(new CustomEvent('ob-scan', {detail: {code: scan}}));
            })()
            """.trimIndent(),
            null,
        )
    }

    private companion object {
        const val TAG = "onebase-scan"
    }
}
