# Nova Connect (اندروید)

کلاینت VPN با هسته Xray؛ رابط Jetpack Compose با تم تاریک. کانفیگ‌ها از لینک‌های `GET /subscriptions/` بک‌اند گرفته و شماره‌گذاری می‌شوند.

## ساخت
1. پروژه را روی GitHub (شاخه main) بگذارید. workflow به‌نام «Build Nova Connect Android» خودکار اجرا می‌شود.
2. خروجی: Artifact با نام `NovaConnect-<نسخه>-code<کد>.apk` (از بخش Actions دانلود کنید).
3. **کلید امضا (برای آپدیت روی نسخه قبلی لازم است):**
   - بعد از یک بیلد موفق، در صفحه همان اجرا بخش Artifacts را باز کنید و `nova-signing-key-SAVE-AS-SECRETS` را دانلود و از حالت zip خارج کنید. داخلش `README.txt` سه مقدار دارد: رمز، نام کلید (alias) و متن Base64 کلید.
   - در مخزن: Settings ← Secrets and variables ← Actions ← New repository secret. سه Secret با همین نام‌ها بسازید: `ANDROID_KEYSTORE_PASSWORD` (رمز)، `ANDROID_KEY_ALIAS` (معمولاً `nova`)، `ANDROID_KEYSTORE_BASE64` (کل متن Base64 در یک خط).
   - از بیلد بعدی، همان کلید همیشه استفاده می‌شود و Artifact کلید ساخته نمی‌شود.
   - مخزن باید Private باشد؛ در مخزن عمومی هر کسی می‌تواند Artifactها را دانلود کند. بعد از ثبت Secrets، Artifact کلید را از Actions پاک کنید و یک نسخه از کلید را جایی امن نگه دارید (اگر گم شود، کاربران باید برنامه را حذف و دوباره نصب کنند).
   - اولین نسخه‌ای که با کلید ثابت ساخته می‌شود با نسخه‌های قبلی (امضای موقت) فرق دارد؛ یک‌بار برنامه قبلی را حذف کنید.

## نسخه‌بندی
فایل `version.properties`: `VERSION_NAME=1.0.0` و `VERSION_CODE=1`. برای هر انتشار، کد را یکی زیاد کنید (1، 2، 3، …) و همان نسخه را در پنل `/versions/` ثبت کنید.

## وضعیت تست
کامپایل Kotlin، ساخت NDK و اجرا روی گوشی انجام نشده است. دور اول ممکن است خطای CI داشته باشد؛ متن لاگ را بفرستید.
