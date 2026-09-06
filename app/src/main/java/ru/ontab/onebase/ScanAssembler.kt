package ru.ontab.onebase

import android.view.KeyEvent

/**
 * Собирает штрихкод из нажатий сканера.
 *
 * Вынесено из [ScannerFrame] отдельно от Android-иерархии: подделать в тесте
 * событие «от внешнего устройства» нельзя, а разбор последовательности клавиш
 * проверять надо — именно в нём живут и разделитель групп, и склейка соседних
 * сканов.
 */
class ScanAssembler(private val now: () -> Long) {

    private val buffer = StringBuilder()
    private var lastKeyAt = 0L

    /**
     * Принимает одно нажатие. Возвращает готовый штрихкод, когда сканер сообщил
     * о конце (Enter или Tab), иначе null.
     */
    fun accept(keyCode: Int, unicodeChar: Int): String? {
        val at = now()
        // Пауза длиннее человеческой означает новый штрихкод: остаток прошлого
        // (например, оборванного) в него попасть не должен.
        if (at - lastKeyAt > SCAN_GAP_MS) buffer.setLength(0)
        lastKeyAt = at

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                val code = buffer.toString()
                buffer.setLength(0)
                return code.ifEmpty { null }
            }
            // Разделителя групп (GS, 0x1D) на клавиатуре нет, и сканер шлёт вместо
            // него F8. Без него код маркировки склеивается в одну строку, и разобрать
            // его на группы уже нельзя.
            GROUP_SEPARATOR_KEY -> {
                buffer.append(GROUP_SEPARATOR)
                return null
            }
        }

        if (unicodeChar != 0) buffer.append(unicodeChar.toChar())
        return null
    }

    /** Виден ли смысл забирать событие себе, или его лучше отдать методу ввода. */
    fun handles(keyCode: Int, unicodeChar: Int): Boolean =
        unicodeChar != 0 || keyCode in OWN_KEYS

    /**
     * Клавиши без собственного символа, которые всё равно наши: модификаторы
     * (их влияние уже учтено в unicodeChar следующей клавиши), разделитель групп
     * и завершители кода.
     *
     * Список задан явно, а не через KeyEvent.isModifierKey: тот приходит из
     * android.jar, который в JVM-тестах заглушка и бросает исключение.
     */
    private val OWN_KEYS = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK,
        GROUP_SEPARATOR_KEY,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB,
    )

    companion object {
        /** F8: им сканер заменяет GS, для которого клавиши не существует. */
        const val GROUP_SEPARATOR_KEY = KeyEvent.KEYCODE_F8

        /** Разделитель групп кода маркировки; в исходнике — кодом, он невидим. */
        const val GROUP_SEPARATOR = ''

        /** Между символами одного штрихкода проходят единицы миллисекунд. */
        const val SCAN_GAP_MS = 500L
    }
}
