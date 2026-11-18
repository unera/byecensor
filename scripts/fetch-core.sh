#!/bin/sh
# Put the Xray kernel and its geo data where the build expects them.
#
# The binary ships as libxray.so on purpose: since Android 10 an app may not execute a file from
# its own data directory, and the native library directory is the one place it may run from.
set -eu

for tool in curl unzip sha256sum; do
  command -v "$tool" > /dev/null || { echo "нужен $tool" >&2; exit 1; }
done

VERSION="${XRAY_VERSION:-v26.11.9}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$HERE/app/src/main/jniLibs/arm64-v8a"
ASSETS="$HERE/app/src/main/assets"

mkdir -p "$TARGET" "$ASSETS"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "downloading Xray $VERSION for android-arm64"
curl -fsSL -o "$WORK/xray.zip" \
  "https://github.com/XTLS/Xray-core/releases/download/$VERSION/Xray-android-arm64-v8a.zip"
# сверяем архив с опубликованной контрольной суммой: это security-инструмент, а не игрушка
curl -fsSL -o "$WORK/xray.dgst" \
  "https://github.com/XTLS/Xray-core/releases/download/$VERSION/Xray-android-arm64-v8a.zip.dgst"
EXPECTED="$(sed -n 's/^SHA2-256= //p' "$WORK/xray.dgst" | tr -d ' \r')"
ACTUAL="$(sha256sum "$WORK/xray.zip" | cut -d' ' -f1)"
[ "$EXPECTED" = "$ACTUAL" ] || { echo "контрольная сумма не совпала: $ACTUAL != $EXPECTED" >&2; exit 1; }

unzip -q -o "$WORK/xray.zip" -d "$WORK"

cp "$WORK/xray" "$TARGET/libxray.so"
chmod +x "$TARGET/libxray.so"
cp "$WORK/geoip.dat" "$WORK/geosite.dat" "$ASSETS/"

echo "ready: $TARGET/libxray.so, $ASSETS/geoip.dat, $ASSETS/geosite.dat"
