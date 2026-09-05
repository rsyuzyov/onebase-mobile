package ru.ontab.onebase

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Поддельная платформа: отдаёт те же страницы и заголовки, что и настоящая, но не
 * требует бинаря. Так тесты обёртки проверяют именно обёртку и идут на любом
 * эмуляторе, включая x86_64, где сама платформа падает на seccomp-фильтре Android.
 *
 * Страницы намеренно повторяют поведение реальных: лаунчер открывает базу через
 * `window.open` (на этом сценарии обёртка уже ломалась), выгрузка списка отдаётся
 * с Content-Disposition по RFC 6266 с ASCII-фолбэком из подчёркиваний.
 */
class FakePlatform {

    private val server = MockWebServer()

    /** Адрес лаунчера — его подставляют вместо того, что печатает платформа. */
    val launcherUrl: String get() = server.url("/launcher").toString()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Браузер percent-кодирует кириллицу в пути, поэтому сравнивать
                // с исходной строкой нельзя: путь выгрузки узнаём по окончанию.
                val path = request.path?.substringBefore('?').orEmpty()
                return when {
                    path == "/launcher" -> html(LAUNCHER)
                    path == "/ui/app" -> html(BASE)
                    path == "/ui/form" -> html(FORM)
                    path.endsWith("/excel") -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        // Ровно тот заголовок, что шлёт платформа: ASCII-фолбэк плюс RFC 5987.
                        .setHeader(
                            "Content-Disposition",
                            "attachment; filename=\"____________.xlsx\"; " +
                                "filename*=UTF-8''%D0%9D%D0%BE%D0%BC%D0%B5%D0%BD%D0%BA%D0%BB%D0%B0%D1%82%D1%83%D1%80%D0%B0.xlsx",
                        )
                        .setBody(XLSX_BODY)
                    else -> MockResponse().setResponseCode(404).setBody("not found")
                }
            }
        }
        server.start()
    }

    fun stop() = server.shutdown()

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private companion object {
        /**
         * Повторяет startBase() настоящего лаунчера: открывает пустое окно, потом
         * переводит его на адрес базы. Без поддержки нескольких окон window.open
         * возвращает null, база «запускается», а интерфейс не появляется.
         */
        const val LAUNCHER = """
            <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Лаунчер</title></head>
            <body>
              <script>document.addEventListener('click', function (e) { window.__lastClick = (e.target && e.target.id) || ''; }, true);</script>
              <h1 id="launcher">Информационные базы</h1>
              <a href="#" id="start" onclick="return startBase()" style="display:inline-block;padding:24px">Предприятие</a>
              <script>
                function startBase() {
                  window.__clicked = true; // видно в тесте: жест дошёл до страницы
                  var win = window.open('', '_blank');
                  setTimeout(function () {
                    if (win) win.location.href = '/ui/app';
                  }, 50);
                  return false;
                }
              </script>
            </body></html>
        """

        const val BASE = """
            <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>База</title></head>
            <body>
              <script>document.addEventListener('click', function (e) { window.__lastClick = (e.target && e.target.id) || ''; }, true);</script>
              <h1 id="base">Интерфейс базы</h1>
              <a id="excel" href="/ui/catalog/Номенклатура/excel">Выгрузить в Excel</a>
              <a id="to-form" href="/ui/form">Открыть форму</a>
              <input type="file" id="attach">
              <script>
                // Те же вызовы, что делает ui.js платформы.
                window.askClose = function () { return window.confirm('Данные были изменены и не записаны. Закрыть форму?'); };
                window.notify = function () { window.alert('Ошибка загрузки картинки'); return 'ok'; };
              </script>
            </body></html>
        """

        const val FORM = """
            <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Форма</title></head>
            <body><h1 id="form">Создать — Поступление</h1></body></html>
        """

        /** Достаточно сигнатуры ZIP: проверяется путь сохранения, а не сам Excel. */
        const val XLSX_BODY = "PKфиктивная книга"
    }
}
