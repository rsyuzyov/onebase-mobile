package ru.ontab.onebase

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор нажатий сканера. Проверяется на настоящем коде маркировки: именно на нём
 * вскрылись обе проблемы — подмена латиницы кириллицей и потеря разделителя групп.
 */
class ScanAssemblerTest {

    private var clock = 1_000L
    private val assembler = ScanAssembler { clock }

    /** Печатает строку так, как её выдаёт сканер: символ за символом. */
    private fun type(text: String): String? {
        var result: String? = null
        for (symbol in text) {
            clock += 3 // между символами одного кода проходят единицы миллисекунд
            result = assembler.accept(keyCodeOf(symbol), symbol.code)
        }
        return result
    }

    private fun press(keyCode: Int): String? {
        clock += 3
        return assembler.accept(keyCode, 0)
    }

    private fun keyCodeOf(symbol: Char): Int = when (symbol) {
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (symbol - '0')
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (symbol - 'a')
        in 'A'..'Z' -> KeyEvent.KEYCODE_A + (symbol - 'A')
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    @Test
    fun `код маркировки собирается целиком по завершающему Enter`() {
        assertNull("код отдан раньше времени", type("0104607042502163215ObkkJ"))

        assertEquals(
            "0104607042502163215ObkkJ",
            press(KeyEvent.KEYCODE_ENTER),
        )
    }

    /** Клавиши для GS не существует, и сканер шлёт вместо него F8. */
    @Test
    fun `разделитель групп восстанавливается из F8`() {
        type("0104607042502163215ObkkJ")
        press(ScanAssembler.GROUP_SEPARATOR_KEY)
        type("93igjJ")

        val code = press(KeyEvent.KEYCODE_ENTER)

        assertEquals("0104607042502163215ObkkJ${ScanAssembler.GROUP_SEPARATOR}93igjJ", code)
        assertEquals("разделитель ровно один", 1, code!!.count { it == ScanAssembler.GROUP_SEPARATOR })
    }

    @Test
    fun `Tab завершает код так же, как Enter`() {
        type("12345")
        assertEquals("12345", press(KeyEvent.KEYCODE_TAB))
    }

    /**
     * Оборванный скан не должен приклеиться к следующему: между кодами проходят
     * секунды, между символами одного кода — миллисекунды.
     */
    @Test
    fun `после долгой паузы начинается новый код`() {
        type("оборванный".take(5))
        clock += ScanAssembler.SCAN_GAP_MS + 1

        type("54321")

        assertEquals("54321", press(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `пустой код не отдаётся`() {
        assertNull(press(KeyEvent.KEYCODE_ENTER))
    }

    /** Кириллицу и иероглифы сканер шлёт без unicodeChar — их дело метода ввода. */
    @Test
    fun `клавиши без символа отдаются методу ввода`() {
        assertFalse(assembler.handles(KeyEvent.KEYCODE_UNKNOWN, 0))
        assertTrue(assembler.handles(KeyEvent.KEYCODE_A, 'a'.code))
        assertTrue("разделитель групп наш", assembler.handles(ScanAssembler.GROUP_SEPARATOR_KEY, 0))
        assertTrue("модификаторы наши", assembler.handles(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
    }
}
