#!/usr/bin/env bash
# 简笺 · 原生 Kotlin 版构建脚本（无 Gradle：aapt2 + kotlinc + javac + d8 + apksigner）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JDK="${JAVA_HOME:-$ROOT/tools/jdk-17.0.21+9}"
SDK="${ANDROID_SDK_ROOT:-$ROOT/tools/sdk}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KOTLINC="$ROOT/tools/kotlin/kotlinc/bin/kotlinc"
KSTDLIB="$ROOT/tools/kotlin/kotlinc/lib/kotlin-stdlib.jar"
APP="$ROOT/app"
OUT="$ROOT/build-native"
DIST="$ROOT/dist"
VER="${VERSION_NAME:-2.0.15}"
VCODE="${VERSION_CODE:-20}"
NAME="jianjian-${VER}.apk"

export PATH="$JDK/bin:$PATH"
export JAVA_HOME="$JDK"

mkdir -p "$DIST"
rm -rf "$OUT"
mkdir -p "$OUT/jclasses" "$OUT/kclasses" "$OUT/dex" "$OUT/gen"

echo "==> 编译资源 (aapt2)"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VER"

echo "==> 编译 Java（存储 / 加密 / 备份 / WebDAV 内核）"
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$AJAR" -d "$OUT/jclasses" @"$OUT/sources.txt" -Xlint:-options -nowarn

echo "==> 编译 Kotlin（原生 UI）"
find "$APP/src" -name '*.kt' > "$OUT/kt.txt"
"$KOTLINC" -nowarn -classpath "$AJAR:$OUT/jclasses:$KSTDLIB" -jvm-target 1.8 \
  -no-stdlib -no-reflect -d "$OUT/kclasses" @"$OUT/kt.txt"

echo "==> 转换为 DEX (d8)"
"$BT/d8" --release --min-api 26 --lib "$AJAR" --output "$OUT/dex" \
  $(find "$OUT/jclasses" "$OUT/kclasses" -name '*.class') "$KSTDLIB"

echo "==> 合并 APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 签名"
"$BT/apksigner" sign \
  --ks "$ROOT/signing/release.p12" --ks-type PKCS12 --ks-key-alias release \
  --ks-pass "file:$ROOT/signing/ks.pass" --key-pass "file:$ROOT/signing/key.pass" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 26 --out "$DIST/$NAME" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$DIST/$NAME" | head -4
ls -lh "$DIST/$NAME"
echo "==> 完成: $DIST/$NAME"
