#!/bin/bash
# Сборка APK без Gradle и Android SDK: aapt → javac (R) → kotlinc → dalvik-exchange → zipalign → apksigner.
# Нужно: aapt, dalvik-exchange, zipalign, apksigner (apt), Java 17+,
#   ANDROID_JAR — android.jar API 34, KOTLIN_LIB — папка с jar компилятора Kotlin 1.9 и kotlin-stdlib,
#   KEYSTORE — ключ подписи (по умолчанию motoradio.keystore рядом со скриптом).
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?укажите ANDROID_JAR}"
: "${KOTLIN_LIB:?укажите KOTLIN_LIB}"
KEYSTORE=${KEYSTORE:-motoradio.keystore}
KS_PASS=${KS_PASS:-motoradio}
KS_ALIAS=${KS_ALIAS:-motoradio}
VERSION_CODE=${VERSION_CODE:-9}
VERSION_NAME=${VERSION_NAME:-1.8}
OUT=${OUT:-build/MotoRadio.apk}

SRC=app/src/main
B=build/tmp
rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$(dirname "$OUT")"

# Манифест: пакет для класса R, версия и SDK (в Gradle это задаёт app/build.gradle.kts)
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.rockradionet.player" android:versionCode="'"$VERSION_CODE"'" android:versionName="'"$VERSION_NAME"'">\n    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />|' \
    "$SRC/AndroidManifest.xml" > "$B/AndroidManifest.xml"

aapt package -f -m --auto-add-overlay \
    --rename-manifest-package com.motoradio.player \
    -M "$B/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" \
    -I "$ANDROID_JAR" -J "$B/gen" -F "$B/base.apk"

javac --release 8 -nowarn -cp "$ANDROID_JAR" -d "$B/classes" $(find "$B/gen" -name '*.java') 2>&1 | grep -v 'warning' || true

KCP=$(ls "$KOTLIN_LIB"/*.jar | tr '\n' ':')
STDLIB=$(ls "$KOTLIN_LIB"/kotlin-stdlib-1*.jar | head -1)
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -nowarn \
    -classpath "$ANDROID_JAR:$B/classes:$STDLIB" \
    -d "$B/classes" $(find "$SRC/java" -name '*.kt')

# kotlin-stdlib без module-info (его не понимает dalvik-exchange)
mkdir -p "$B/stdlib"
(cd "$B/stdlib" && unzip -q -o "$STDLIB" -x 'META-INF/versions/*' 'module-info.class')
dalvik-exchange --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes" "$B/stdlib"

cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q -j unsigned.apk classes.dex)
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
    --out "$OUT" "$B/aligned.apk"
apksigner verify "$OUT"
echo "Готово: $OUT"
