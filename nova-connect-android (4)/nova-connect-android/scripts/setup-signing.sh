#!/usr/bin/env bash
# اگر کلید ثابت در Secrets بود و درست بود از همان استفاده می‌شود؛ وگرنه کلید موقت ساخته می‌شود (آپدیت روی نسخه قبلی نصب نمی‌شود!)
set -euo pipefail
KS="$RUNNER_TEMP/nova.jks"
clean() { printf '%s' "$1" | tr -d '\r\n' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'; }
st() { [ -n "${1:-}" ] && echo "موجود" || echo "خالی/پیدا نشد"; }
echo "وضعیت Secrets → ANDROID_KEYSTORE_BASE64: $(st "${KS_B64:-}") | ANDROID_KEYSTORE_PASSWORD: $(st "${KS_PASS:-}") | ANDROID_KEY_ALIAS: $(st "${KS_ALIAS:-}")"
use_secret=0; why=""
if [ -n "${KS_B64:-}" ] && [ -n "${KS_PASS:-}" ]; then
  PASS="$(clean "$KS_PASS")"; PASS="${PASS#KEYSTORE_PASSWORD=}"
  ALIAS="$(clean "${KS_ALIAS:-nova}")"; ALIAS="${ALIAS#KEY_ALIAS=}"; [ -n "$ALIAS" ] || ALIAS="nova"
  # اگر کاربر به‌اشتباه متن توضیحی README را هم کپی کرده باشد، فقط بلندترین خط (خود Base64) برداشته می‌شود
  B64="$(printf '%s' "$KS_B64" | tr '\r' '\n' | awk 'length($0)>m{m=length($0);l=$0} END{print l}' | tr -d ' \t')"
  if ! printf '%s' "$B64" | base64 -d > "$KS" 2>/dev/null || [ ! -s "$KS" ]; then
    why="متن ANDROID_KEYSTORE_BASE64 ناقص یا نامعتبر است (باید کل متن بلند Base64 در یک خط باشد)."
  else
    if out="$(keytool -list -keystore "$KS" -storepass "$PASS" 2>&1)"; then
      if echo "$out" | grep -qiE "^${ALIAS},"; then use_secret=1
      else
        found="$(echo "$out" | grep -E 'PrivateKeyEntry' | head -1 | cut -d, -f1 || true)"
        if [ -n "$found" ]; then echo "alias «$ALIAS» پیدا نشد؛ از alias موجود «$found» استفاده می‌شود."; ALIAS="$found"; use_secret=1
        else why="داخل کلید هیچ alias معتبری پیدا نشد."; fi
      fi
    else
      why="keytool نتوانست کلید را باز کند: $(echo "$out" | head -2 | tr '\n' ' ')"
    fi
  fi
else
  why="یکی از Secretهای ANDROID_KEYSTORE_BASE64 یا ANDROID_KEYSTORE_PASSWORD خالی/پیدا نشد (نام‌ها باید دقیقاً همین‌ها و از نوع Repository secret باشند)."
fi
if [ "$use_secret" = "1" ]; then
  echo "کلید ثابت از Secrets خوانده شد (alias: $ALIAS)."; echo "GENERATED_KS=0" >> "$GITHUB_ENV"
else
  echo "::warning::کلید ثابت استفاده نشد. دلیل: $why این بار با کلید موقت ساخته می‌شود."
  PASS="$(openssl rand -hex 12)"; ALIAS="nova"; rm -f "$KS"
  keytool -genkeypair -v -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Nova Connect, O=Nova, C=IR" >/dev/null 2>&1
  mkdir -p keystore-out; cp "$KS" keystore-out/nova.jks
  printf 'KEYSTORE_PASSWORD=%s\nKEY_ALIAS=%s\n' "$PASS" "$ALIAS" > keystore-out/README.txt
  base64 -w0 "$KS" > keystore-out/keystore-base64.txt
  echo "GENERATED_KS=1" >> "$GITHUB_ENV"
fi
echo "::add-mask::$PASS"
{ echo "NOVA_KS_PATH=$KS"; echo "NOVA_KS_PASS=$PASS"; echo "NOVA_KS_ALIAS=$ALIAS"; } >> "$GITHUB_ENV"
