#!/bin/sh
# build.sh — 纯命令行编译 APK（不需要 Gradle / Android Studio）
#
# 依赖（本机已装）：
#   JDK 21            /usr/bin/javac
#   Android SDK 35    /usr/lib/android-sdk/platforms/android-35/android.jar
#   build-tools 29    aapt2 / apksigner / zipalign
#   D8 (r8.jar)       /root/workspace/wx/r8.jar
#
# 用法：sh build.sh
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
BT="$SDK/build-tools/29.0.3"
# 打包用 android-23（build-tools 29 的 aapt2 只认得这个版本）
# 但 javac/D8 编译时用 android-35 的 jar，才能拿到 NotificationChannel / startForegroundService 等新 API
AJ_LINK="$SDK/platforms/android-23/android.jar"
AJ_COMPILE="$SDK/platforms/android-35/android.jar"
AJ="$AJ_COMPILE"
R8="${R8_JAR:-/root/workspace/wx/r8.jar}"
APP_NAME="FlClashGuard"

echo "==> 清理"
rm -rf out
mkdir -p out/gen out/classes out/dex out/assets

echo "==> 1/6 aapt2 compile 资源"
"$BT/aapt2" compile --dir res -o out/res.zip

echo "==> 2/6 aapt2 link（生成 R.java + 基础 apk）"
"$BT/aapt2" link \
  -o out/base.apk \
  -I "$AJ_LINK" \
  --manifest AndroidManifest.xml \
  --java out/gen \
  -R out/res.zip \
  --auto-add-overlay

echo "==> 3/6 javac 编译 Java"
find src out/gen -name "*.java" > out/sources.txt
javac -source 8 -target 8 \
  -bootclasspath "$AJ_COMPILE" \
  -classpath "$AJ_COMPILE:$DIR/libs/shizuku-api.jar:$DIR/libs/shizuku-aidl.jar" \
  -d out/classes \
  -encoding UTF-8 \
  -nowarn \
  @out/sources.txt 2>&1 | grep -v "bootstrap class path" | grep -v "obsolete" | grep -v "^Note:" || true
if [ ! -f out/classes/com/flclashguard/MainActivity.class ]; then
  echo "!! javac 失败"
  exit 1
fi

echo "==> 4/6 D8 转 dex"
# 解开依赖 jar，和我们的 class 一起转 dex
rm -rf out/libs_classes && mkdir -p out/libs_classes
for jar in "$DIR"/libs/*.jar; do
  [ -f "$jar" ] || continue
  (cd out/libs_classes && unzip -o -q "$jar")
done
java -cp "$R8" com.android.tools.r8.D8 \
  --min-api 21 \
  --lib "$AJ_COMPILE" \
  --output out/dex \
  $(find out/classes out/libs_classes -name "*.class" 2>/dev/null | tr '\n' ' ')

echo "==> 5/6 打包 apk"
cd out
cp -f base.apk unsigned.apk
zip -q -j unsigned.apk dex/classes.dex
# 注意：必须保留 assets/ 这一层目录，否则 WebView 的
# file:///android_asset/index.html 会找不到文件（ERR_FILE_NOT_FOUND）
if [ -d "$DIR/assets" ] && [ -n "$(ls -A "$DIR/assets" 2>/dev/null)" ]; then
  # 直接在 out/ 里放到 assets/ 下再整体打包，避免出现 assets/assets
  mkdir -p ./_pack/assets
  cp -r "$DIR/assets/." ./_pack/assets/
  (cd ./_pack && zip -q -r ../unsigned.apk assets)
  rm -rf ./_pack
fi
cd "$DIR"

echo "==> 6/6 签名"
# 固定密钥（首次生成，之后复用，方便覆盖安装升级）
KS="keystore.jks"
if [ ! -f "$KS" ]; then
  echo "    生成签名密钥（只做一次）"
  keytool -genkeypair -v -keystore "$KS" -alias guard \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass guardpass -keypass guardpass \
    -dname "CN=FlClash Guard, OU=Local, O=Local, L=Local, ST=Local, C=CN" >/dev/null 2>&1
fi

"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias guard \
  --ks-pass pass:guardpass --key-pass pass:guardpass \
  --out "out/$APP_NAME.apk" out/aligned.apk
"$BT/apksigner" verify --print-certs "out/$APP_NAME.apk" | head -3

echo ""
echo "✅ 编译完成：$DIR/out/$APP_NAME.apk"
ls -lh "out/$APP_NAME.apk"
