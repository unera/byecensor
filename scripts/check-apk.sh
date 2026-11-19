#!/bin/sh
# Проверка готового APK перед публикацией.
#
# Зачем: 0.8.4 уехала в релиз без ядра. Ядро (libxray.so) в репозитории не лежит, его
# ставит scripts/fetch-core.sh, и сборка, запущенная без него, проходила молча — APK
# выходил 6,3 МБ вместо 22, а приложение на телефоне отвечало «нет ядра». Gradle теперь
# такую сборку не делает, но приложенный к релизу файл никто не проверял — этот скрипт
# и есть та проверка.
#
#   scripts/check-apk.sh [путь к apk]
#
# По умолчанию берёт release-сборку. Выходит с кодом 1, если в APK нет ядра,
# гео-данных или подписи.
set -eu

APK=${1:-app/build/outputs/apk/release/app-release.apk}
[ -f "$APK" ] || { echo "нет файла: $APK" >&2; exit 1; }

fail() { echo "ОШИБКА: $*" >&2; exit 1; }

SIZE=$(wc -c < "$APK")
echo "файл:       $APK"
echo "размер:     $SIZE байт"

# 1. Ядро. Без него приложение не работает вовсе — это главная проверка.
unzip -l "$APK" 2>/dev/null | grep -q 'lib/arm64-v8a/libxray.so' \
  || fail "в APK нет lib/arm64-v8a/libxray.so — собрано без scripts/fetch-core.sh"

# 2. Гео-данные: без них Xray поднимается, но правила direct/proxy не работают.
for asset in geoip.dat geosite.dat; do
  unzip -l "$APK" 2>/dev/null | grep -q "assets/$asset" \
    || fail "в APK нет assets/$asset"
done

# 3. Объём: с ядром APK весит около 22 МБ, без него — около 6. Ловим и случай,
#    когда ядро есть, но пустое (оборванная закачка).
[ "$SIZE" -gt 15000000 ] || fail "APK подозрительно мал: $SIZE байт (с ядром ожидается ~22 МБ)"

# 4. Подпись и название — через build-tools, если они есть.
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
TOOLS=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$TOOLS" ] && [ -x "$TOOLS/aapt2" ]; then
  LABEL=$("$TOOLS/aapt2" dump badging "$APK" 2>/dev/null | sed -n "s/^application-label:'\(.*\)'/\1/p" | head -1)
  VERSION=$("$TOOLS/aapt2" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  echo "название:   ${LABEL:-?}"
  echo "версия:     ${VERSION:-?}"
  if [ -x "$TOOLS/apksigner" ]; then
    "$TOOLS/apksigner" verify "$APK" 2>/dev/null \
      || fail "APK не подписан (или подпись битая) — ставить его нельзя"
    echo "подпись:    проверена"
  fi
else
  echo "название:   (нет aapt2 — пропускаю)"
fi

echo "ок: ядро, гео-данные и объём на месте"
