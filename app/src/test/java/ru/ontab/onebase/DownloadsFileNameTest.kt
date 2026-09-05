package ru.ontab.onebase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор имени файла из Content-Disposition. Ветки с заголовком не трогают
 * android.webkit.URLUtil, поэтому проверяются обычным JVM-тестом.
 */
class DownloadsFileNameTest {

    /** То, что реально отдаёт платформа: ASCII-фолбэк из подчёркиваний плюс RFC 5987. */
    @Test
    fun `берёт русское имя из filename со звёздочкой`() {
        val header = "attachment; filename=\"____________.xlsx\"; " +
            "filename*=UTF-8''%D0%9D%D0%BE%D0%BC%D0%B5%D0%BD%D0%BA%D0%BB%D0%B0%D1%82%D1%83%D1%80%D0%B0.xlsx"
        assertEquals(
            "Номенклатура.xlsx",
            Downloads.fileName("http://127.0.0.1:18080/ui/catalog/Номенклатура/excel", header, null),
        )
    }

    @Test
    fun `берёт обычное имя, когда расширенного нет`() {
        assertEquals(
            "report.pdf",
            Downloads.fileName("http://127.0.0.1:18080/ui/x", "attachment; filename=\"report.pdf\"", null),
        )
    }

    @Test
    fun `разделители пути в имени не уводят запись из Загрузок`() {
        assertEquals(
            "evil.xlsx",
            Downloads.fileName("http://127.0.0.1:18080/ui/x", "attachment; filename*=UTF-8''..%2F..%2Fevil.xlsx", null),
        )
    }
}
