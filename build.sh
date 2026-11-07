#!/usr/bin/env bash
# Сборка APK без Gradle: aapt -> javac -> dx -> zipalign -> apksigner.
# Linux/macOS. Нужны: JDK 17+, aapt, zipalign, apksigner, dx (пакеты Ubuntu:
#   openjdk-17-jdk-headless aapt zipalign apksigner dalvik-exchange)
# и android.jar платформы API 34:  ANDROID_JAR=/путь/к/android.jar ./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
BUILD="$ROOT/build"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android.jar}"
DX="${DX:-$(command -v d8 || command -v dx || echo /home/opencode/bt34/d8)}"
VERSION_CODE="${VERSION_CODE:-20}"
VERSION_NAME="${VERSION_NAME:-2.10}"
OUT="${OUT:-$ROOT/AlarmClock.apk}"

[ -f "$ANDROID_JAR" ] || { echo "Не найден android.jar: $ANDROID_JAR"; exit 1; }

rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes"

echo "1/6 aapt (ресурсы, манифест, assets)..."
aapt package -f \
  -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" \
  -I "$ANDROID_JAR" -J "$BUILD/gen" -F "$BUILD/base.apk" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "2/6 javac..."
find "$SRC/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "3/6 dx (classes.dex)..."
if "$DX" --help 2>/dev/null | grep -q -- '--release'; then
  # d8: принимает .class-файлы по одному
  mapfile -t CLASSFILES < <(find "$BUILD/classes" -name '*.class')
  "$DX" --release --lib "$ANDROID_JAR" --output "$BUILD" "${CLASSFILES[@]}"
else
  "$DX" --dex --min-sdk-version=23 --output="$BUILD/classes.dex" "$BUILD/classes"
fi

echo "4/6 добавляю dex в APK..."
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD" && aapt add unsigned.apk classes.dex >/dev/null )

echo "5/6 zipalign..."
zipalign -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "6/6 подпись..."
KS="$ROOT/release.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass alarmclock -keypass alarmclock \
    -alias alarmclock -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Clean Alarm, O=Clean Alarm, C=RU" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:alarmclock --key-pass pass:alarmclock \
  --out "$OUT" "$BUILD/aligned.apk"
apksigner verify --print-certs "$OUT"
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
