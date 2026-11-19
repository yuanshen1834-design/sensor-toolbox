#!/bin/bash
# 手工构建 APK：javac -> d8 -> aapt -> zipalign -> apksigner
# 依赖见 README「构建」一节；SDK 目录可用环境变量覆盖。
set -e
SDK=${SDK:-$(cd "$(dirname "$0")" && pwd)/sdk}
SRC=$(cd "$(dirname "$0")" && pwd)
AJ=${ANDROID_JAR:-$SDK/p28/android-9/android.jar}
R8=${R8_JAR:-$SDK/r8.jar}
ZX=${ZXING_JAR:-$SDK/zxing-core.jar}
TF=${TFLITE_JAR:-$SDK/tflite_full.jar}
SO=${TFLITE_SO:-$SDK/tflite_extract/jni/arm64-v8a/libtensorflowlite_jni.so}
KS=${KEYSTORE:-$SRC/key.jks}
KSPASS=${KS_PASS:-sensorkit}

cd "$SRC"
rm -rf build && mkdir -p build/classes build/dex build/stage/lib/arm64-v8a

echo "[1/6] 编译 Java"
javac -nowarn -source 8 -target 8 -classpath "$AJ:$ZX:$TF" -d build/classes $(find src -name '*.java')

echo "[2/6] 生成 dex"
java -cp "$R8" com.android.tools.r8.D8 --lib "$AJ" --min-api 23 \
  --output build/dex $(find build/classes -name '*.class') "$ZX" "$TF"

echo "[3/6] 打包资源"
aapt package -f -M AndroidManifest.xml -A assets -S res -I "$AJ" -F build/base.apk

echo "[4/6] 加入 dex 与原生库"
cp "$SO" build/stage/lib/arm64-v8a/
( cd build/dex && aapt add ../base.apk classes.dex >/dev/null )
( cd build/stage && aapt add ../base.apk lib/arm64-v8a/libtensorflowlite_jni.so >/dev/null )

echo "[5/6] 对齐与签名"
( cd build && zipalign -f 4 base.apk aligned.apk )
apksigner sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --min-sdk-version 23 --out build/sensorkit.apk build/aligned.apk
apksigner verify --min-sdk-version 23 build/sensorkit.apk && echo "签名校验通过"

echo "[6/6] 完成"
ls -lh build/sensorkit.apk
