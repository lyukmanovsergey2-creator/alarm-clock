#!/usr/bin/env bash
# Сборка APK без Gradle: aapt -> javac -> d8 -> zipalign -> apksigner.
# Linux/macOS. Нужны:
#   - JDK 17+ (java и javac в PATH)
#   - Android build-tools 34.0.0+: aapt, zipalign, apksigner, d8
#   - android.jar платформы API 34 (из platforms/android-34)
# Пример: ANDROID_JAR=/opt/android-sdk/platforms/android-34/android.jar \
#         BT=/opt/android-sdk/build-tools/34.0.0 ./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
BUILD="$ROOT/build"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android.jar}"
BT="${BT:-$(dirname "$(command -v aapt || echo /usr/lib/android-sdk/build-tools/30/aapt)")}"
VERSION_CODE="${VERSION_CODE:-9}"
VERSION_NAME="${VERSION_NAME:-2.7}"
OUT="${OUT:-$ROOT/AlarmClock.apk}"
KS="${KS:-$ROOT/release.keystore}"
KS_PASS="${KS_PASS:-alarmclock}"

[ -f "$ANDROID_JAR" ] || { echo "Не найден android.jar: $ANDROID_JAR"; exit 1; }
for tool in aapt javac zipalign apksigner; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Нет утилиты: $tool"; exit 1; }
done

# d8 (современный) или dx (старый) — whichever есть
if [ -x "$BT/d8" ]; then DEX="$BT/d8"; DEX_KIND=d8
elif command -v d8 >/dev/null 2>&1; then DEX="$(command -v d8)"; DEX_KIND=d8
elif [ -x "$BT/dx" ]; then DEX="$BT/dx"; DEX_KIND=dx
else DEX="/usr/lib/android-sdk/build-tools/debian/dx"; DEX_KIND=dx
fi
echo "Invoke-dexer: $DEX_KIND -> $DEX"

rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes"

echo "1/6 aapt (ресурсы, манифест, assets)..."
"$BT/aapt" package -f \
  -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" \
  -I "$ANDROID_JAR" -J "$BUILD/gen" -F "$BUILD/base.apk" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "2/6 javac..."
find "$SRC/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "3/6 $DEX_KIND (classes.dex)..."
if [ "$DEX_KIND" = d8 ]; then
  ( cd "$BUILD/classes" && jar cf "$BUILD/app.jar" . )
  "$DEX" --release --output "$BUILD/" "$BUILD/app.jar" --lib "$ANDROID_JAR" --min-api 23
else
  "$DEX" --dex --min-sdk-version=23 --output="$BUILD/classes.dex" "$BUILD/classes"
fi

echo "4/6 добавляю dex в APK..."
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD" && "$BT/aapt" add unsigned.apk classes.dex >/dev/null )

echo "5/6 zipalign..."
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "6/6 подпись..."
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT" "$BUILD/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT" | head -2

echo "Готово: $OUT"
