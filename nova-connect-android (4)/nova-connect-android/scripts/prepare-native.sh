#!/usr/bin/env bash
# دانلود Xray (با SHA256 رسمی) و ساخت hev-socks5-tunnel برای اندروید
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
XRAY_VERSION="${XRAY_VERSION:-25.1.30}"
mkdir -p app/src/main/jniLibs/arm64-v8a build/tmp
base="https://github.com/XTLS/Xray-core/releases/download/v${XRAY_VERSION}/Xray-android-arm64-v8a.zip"
curl -fsSL "$base" -o build/tmp/xray.zip; curl -fsSL "$base.dgst" -o build/tmp/xray.dgst
exp="$(grep -i -m1 'SHA2-256' build/tmp/xray.dgst | sed -E 's/.*= *//' | tr -d ' \r\n')"; got="$(sha256sum build/tmp/xray.zip | cut -d' ' -f1)"
[ -n "$exp" ] && [ "$exp" = "$got" ] || { echo "SHA256 هسته Xray با مقدار رسمی نمی‌خواند"; exit 1; }
unzip -o -q build/tmp/xray.zip xray -d build/tmp/xr
echo "Xray $XRAY_VERSION تأیید شد: $got"
HEV=app/src/main/jni/hev-socks5-tunnel; rm -rf "$HEV"
git clone --recursive --depth 1 --branch "${HEV_REF:-2.9.0}" https://github.com/heiher/hev-socks5-tunnel.git "$HEV" \
  || git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel.git "$HEV"
echo "hev commit: $(git -C "$HEV" rev-parse HEAD)"
# با NDK جدید (r30) lwip خطای errno می‌دهد؛ include مشکل‌دار را با شکل نقل‌قولی جایگزین می‌کنیم (بی‌خطر برای نسخه‌های دیگر)
grep -rl 'LWIP_ERRNO_INCLUDE[[:space:]]*<errno.h>' "$HEV" 2>/dev/null | while read -r f; do
  sed -i 's|LWIP_ERRNO_INCLUDE[[:space:]]*<errno.h>|LWIP_ERRNO_INCLUDE "errno.h"|' "$f"; echo "patched: $f"
done
# ترجیحاً NDK پایدارتر (ANDROID_NDK_HOME)؛ در نبود آن، آخرین نسخه
NDK="${ANDROID_NDK_HOME:-}"; [ -x "$NDK/ndk-build" ] || NDK="${ANDROID_NDK_LATEST_HOME:-}"
[ -x "$NDK/ndk-build" ] || { echo "NDK پیدا نشد"; exit 1; }
echo "NDK: $NDK"
"$NDK/ndk-build" NDK_PROJECT_PATH=app/src/main APP_BUILD_SCRIPT=app/src/main/jni/Android.mk NDK_APPLICATION_MK=app/src/main/jni/Application.mk \
  NDK_LIBS_OUT=build/ndk-libs NDK_OUT=build/ndk -j"$(nproc)"
python3 scripts/gen_tun_bridge.py build/ndk-libs/arm64-v8a/libhev-socks5-tunnel.so app/src/main/java
# هر دو کتابخانه بعد از ndk-build در jniLibs قرار می‌گیرند
rm -rf app/src/main/jniLibs; mkdir -p app/src/main/jniLibs/arm64-v8a
install -m755 build/ndk-libs/arm64-v8a/libhev-socks5-tunnel.so app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
install -m755 build/tmp/xr/xray app/src/main/jniLibs/arm64-v8a/libxray.so
ls -la app/src/main/jniLibs/arm64-v8a
[ -s app/src/main/jniLibs/arm64-v8a/libxray.so ] && [ -s app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so ] || { echo "کتابخانه‌های بومی کامل نیستند"; exit 1; }
# libxray.so باید «بعد از» ndk-build نصب شود؛ ndk-build کتابخانه‌های ناشناس پوشه jniLibs را پاک می‌کند
install -m755 build/tmp/xr/xray app/src/main/jniLibs/arm64-v8a/libxray.so
ls -la app/src/main/jniLibs/arm64-v8a/
[ -s app/src/main/jniLibs/arm64-v8a/libxray.so ] && [ -s app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so ] || { echo "یکی از کتابخانه‌های native ساخته نشد"; exit 1; }
