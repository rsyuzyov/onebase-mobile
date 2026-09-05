#!/usr/bin/env bash
# Прогон проверок обёртки — то, что должно быть зелёным перед коммитом.
#
# Инструментальные тесты идут против поддельного сервера, а не против бинаря
# платформы, поэтому работают и на x86_64-эмуляторе, где сама платформа падает
# на seccomp-фильтре Android. Устройство нужно любое: эмулятор или телефон.
#
# Прогон проверок самой платформы (ops/scripts/onebase/ci-local.sh в kuzma) сюда
# не входит намеренно: он проверяет апстрим и нужен только перед PR туда.
set -euo pipefail

cd "$(dirname "$0")"

# JDK выбираем сами, а не наследуем из окружения: там может стоять свежая версия
# (у нас — 25), которую Gradle 8.14 не поддерживает и падает с одним лишь номером
# версии в сообщении.
for jdk in "${JAVA17_HOME:-}" "/c/Program Files/BellSoft/LibericaJDK-17" "$HOME/.jdks/LibericaJDK-17"; do
  if [ -n "$jdk" ] && [ -x "$jdk/bin/java" ]; then
    JAVA_HOME="$jdk"
    break
  fi
done
[ -x "${JAVA_HOME:-}/bin/java" ] || { echo "не нашёл JDK 17 — укажите его в JAVA17_HOME"; exit 1; }

: "${ANDROID_HOME:=$LOCALAPPDATA/Android/Sdk}"
export JAVA_HOME ANDROID_HOME

ADB="$ANDROID_HOME/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$ANDROID_HOME/platform-tools/adb"

echo "== модульные тесты =="
./gradlew --quiet testDebugUnitTest

echo "== статический анализ =="
./gradlew --quiet lintDebug

devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$devices" ]; then
  echo "== инструментальные тесты пропущены: нет подключённых устройств =="
  echo "   запустите эмулятор или подключите телефон и повторите"
  exit 1
fi

# Устройство берём одно: прогон на нескольких сразу удваивает время без пользы.
# Эмулятор предпочтительнее телефона — установка на телефон требует ручного
# подтверждения на его экране, и прогон встаёт до нажатия.
target=$(echo "$devices" | grep '^emulator-' | head -1 || true)
[ -n "$target" ] || target=$(echo "$devices" | head -1)

echo "== инструментальные тесты на $target =="
ANDROID_SERIAL="$target" ./gradlew connectedDebugAndroidTest

echo "== всё зелёное =="
