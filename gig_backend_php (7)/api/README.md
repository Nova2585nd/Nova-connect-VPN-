# Gig Backend (نسخه‌ی PHP برای هاست سی‌پنل)

PHP ساده + SQLite؛ بدون نصب هیچ پکیج اضافه.

## ساختار

```
api/
├── .htaccess        # هدایت درخواست‌ها به index.php
├── index.php        # مسیریابی
├── config.php       # تنظیمات: قیمت پیش‌فرض، بایت در هر گیگ، صفحه‌ی تست
├── test_page.html   # صفحه‌ی تست APIها (/test) با توضیح و curl
├── panel.html       # پنل مدیریت (/panel)
├── src/             # کد برنامه (از اینترنت بسته است)
└── data/            # دیتابیس app.db (از اینترنت بسته است)
```

## نصب

۱) cPanel > Select PHP Version: نسخه‌ی ۷.۴ یا بالاتر؛ افزونه‌های `pdo_sqlite` و `sqlite3` تیک داشته باشند.
۲) zip را داخل `public_html` آپلود و Extract کن (پوشه‌ی `api` ساخته می‌شود).
۳) Show Hidden Files را روشن کن و مطمئن شو `.htaccess` داخل `api` هست. اگر نبود، دستی بساز:

```
Options -Indexes
DirectoryIndex index.php

<IfModule mod_rewrite.c>
    RewriteEngine On
    RewriteBase /api/
    RewriteCond %{REQUEST_FILENAME} !-f
    RewriteCond %{REQUEST_FILENAME} !-d
    RewriteRule ^ index.php [L,QSA]
</IfModule>
```

۴) باز کن: `https://دامنه-تو.com/api/test` و `https://دامنه-تو.com/api/panel`

## به‌روزرسانی

۱) از `api/data/app.db` بکاپ بگیر. ۲) `index.php`، `config.php`، `panel.html`، `test_page.html` و پوشه‌ی `src` را جایگزین کن؛ `data` را دست نزن. ساختار دیتابیس قدیمی خودکار سازگار می‌شود (ستون‌های قدیمی `token` و `total_purchase_price` می‌مانند ولی استفاده نمی‌شوند).

## منطق

- کاربر فقط چهار فیلد دارد: `subscription_code`، `total_gig`، `used_gig`، `remaining_gig`. گیگ‌ها اعشاری‌اند.
- `remaining_gig = max(0, total_gig - used_gig)`. مصرف می‌تواند از گیگ کل بیشتر شود.
- `POST /users/` هیچ ورودی نمی‌گیرد: کد ۶ رقمی یکتا می‌سازد و `initial_test_gig` (پیش‌فرض ۲) را می‌دهد.
- هر دعوت `invite_bonus_gig` (پیش‌فرض ۱) به گیگ کل اضافه می‌کند.
- هر گیگ = 1024³ بایت (`bytes_per_gb` در `config.php`).
- اپ مصرف را با `/usage/report` می‌فرستد: `total_bytes` عدد **تجمعی** همان نصب است و سرور فقط اختلاف با گزارش قبلی همان `device_id` را اضافه می‌کند؛ تکرار گزارش مصرف را دو برابر نمی‌کند. اگر عدد کمتر شود، شمارنده ریست‌شده فرض می‌شود.
- نام مخفی کاربر (`label`) فقط در `GET /users/` (فهرست مدیریتی) می‌آید، نه در `GET /users/{code}` یا `/usage/report`؛ پس اپ آن را نمی‌بیند.
- حذف کاربر همه‌ی داده‌های وابسته‌ی او را هم پاک می‌کند و آمار کل خودکار کم می‌شود.
- اگر ثبت‌نام بسته باشد، `POST /users/` برای همه (حتی پنل) خطای ۴۰۳ می‌دهد؛ اپ از این API استفاده نمی‌کند.
- آمارهای فروخته‌شده و هدیه از سابقه‌ی تراکنش‌اند و قابل ریست‌اند؛ بقیه‌ی اعداد (کاربران، گیگ کل، مصرف، مانده) مستقیم از کاربران می‌آیند.
- هیچ مبلغی ثبت یا حساب نمی‌شود. تراکنش‌ها فقط حجم‌اند: `purchase`، `invite`، `free`، `adjust`.

## APIها

آدرس پایه: `https://دامنه-تو.com/api`

| متد | مسیر | بدنه / پارامتر |
|---|---|---|
| POST | `/users/` | بدون ورودی |
| GET | `/users/` | همه‌ی کاربران |
| GET | `/users/{code}` | یک کاربر |
| PUT | `/users/{code}/label` | `{"label": "نام"}` ← نام مخفی (کاربر نمی‌بیند) |
| DELETE | `/users/{code}` | حذف کاربر با دستگاه‌ها و تراکنش‌هایش |
| PUT | `/users/{code}/stats` | `{"total_gig": 15}` |
| PUT | `/users/{code}/usage` | `{"used_gig": 3}` (جایگزین) |
| POST | `/users/{code}/add-usage` | `{"gig": 0.5}` (اضافه به مصرف) |
| POST | `/users/{code}/add-gig` | `{"gig": 1}` |
| POST | `/users/invite/{code}` | بدون ورودی |
| POST | `/usage/report` | `{"code", "device_id", "total_bytes"}` ← کاربر + `exhausted` |
| GET / PUT | `/settings/initial-gig` | `{"gig": 2}` |
| GET / PUT | `/settings/invite-bonus` | `{"gig": 1}` |
| GET / PUT | `/settings/registration` | `{"open": false}` ← بستن ثبت‌نام (POST /users/ می‌شود ۴۰۳) |
| GET / PUT | `/settings/price` | `{"price": 9000}` |
| POST / GET / DELETE | `/announcements/` | `{"text": "..."}` / همه را برمی‌گرداند / همه را حذف می‌کند |
| POST / GET | `/subscriptions/` | `{"link": "...", "name": "اختیاری"}` |
| PUT | `/subscriptions/{id}` | `{"name": "..."}` |
| DELETE | `/subscriptions/?link=...` | |
| POST / GET | `/versions/` | `version_name`, `version_code`, `download_link` (https)، `is_mandatory`, `changelog` |
| GET | `/versions/check?current_code=12` | بررسی آپدیت توسط اپ |
| DELETE | `/versions/{version_code}` | |
| GET | `/stats/summary` | کاربران، گیگ فروخته‌شده و هدیه، مصرف کل |
| POST | `/stats/reset` | `{"stat": "sold_gig"}` یا `gifted_gig` |
| GET | `/transactions/?limit=50` | تراکنش‌های حجم |

## امنیت

APIها **رمز ندارند**، پس هر کسی که آدرس را بداند می‌تواند از آن‌ها استفاده کند (ساخت کاربر، افزودن گیگ، گزارش مصرف برای هر کد و ...). رمز پنل (`0183`) فقط خود صفحه را قفل می‌کند. حداقل کارها: بعد از تست `enable_test_page` را `false` کن، آدرس را منتشر نکن، و از `data/app.db` بکاپ بگیر.

## اگر خطا دیدی

| علامت | راه‌حل |
|---|---|
| `/test` خطای ۴۰۴ | `.htaccess` آپلود نشده یا mod_rewrite فعال نیست |
| «pdo_sqlite فعال نیست» | در Select PHP Version > Extensions تیک بزن |
| «پوشه‌ی data قابل نوشتن نیست» | Permission پوشه‌ی `data` را 755 یا 775 کن |
| ۵۰۰ بدون توضیح | در `config.php` موقتاً `debug` را `true` کن |
| ۴۰۳/۴۰۵ فقط برای PUT و DELETE | از پشتیبانی هاست بخواه این متدها را باز کنند |
