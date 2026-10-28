<?php
// منطق هر API

// ---------- کاربران ----------
function format_user(array $r)
{
    return [
        'subscription_code' => (int)$r['subscription_code'],
        'total_gig' => (float)$r['total_gig'],
        'used_gig' => (float)$r['used_gig'],
        'remaining_gig' => (float)$r['remaining_gig'],
    ];
}

function find_user($code)
{
    $row = run('SELECT * FROM users WHERE subscription_code = ?', [(int)$code])->fetch();
    return $row ? $row : null;
}

function find_user_or_404($code)
{
    $user = find_user($code);
    if ($user === null) {
        fail(404, 'کاربر پیدا نشد.');
    }
    return $user;
}

function get_settings()
{
    return run('SELECT price_per_gig, initial_test_gig, invite_bonus_gig, registration_open FROM settings WHERE id = 1')->fetch();
}

function log_transaction($code, $type, $gig)
{
    run(
        'INSERT INTO transactions (subscription_code, type, gig, created_at) VALUES (?, ?, ?, ?)',
        [(int)$code, $type, (float)$gig, gmdate('Y-m-d\TH:i:s\Z')]
    );
}

// POST /users  (بدون ورودی؛ گیگ اولیه از تنظیمات initial_test_gig)
function create_user(array $p = [])
{
    $s = get_settings();
    if ((int)$s['registration_open'] !== 1) {
        fail(403, 'ثبت‌نام کاربر جدید فعلاً بسته است.');
    }
    $gig = (float)$s['initial_test_gig'];

    // کد ۶ رقمی تصادفی؛ اگر تکراری بود (خطای یکتایی) دوباره تلاش می‌کنیم
    for ($i = 0; $i < 20; $i++) {
        $code = random_int(100000, 999999);
        try {
            run(
                'INSERT INTO users (subscription_code, total_gig, used_gig, remaining_gig) VALUES (?, ?, 0, ?)',
                [$code, $gig, $gig]
            );
        } catch (PDOException $e) {
            if ((string)$e->getCode() === '23000') {
                continue;
            }
            throw $e;
        }
        if ($gig > 0) {
            log_transaction($code, 'free', $gig);
        }
        json_response(format_user(find_user($code)), 201);
    }
    fail(503, 'ساخت کد اشتراک یکتا ممکن نشد.');
}

// GET /users  (همه‌ی کاربران)
function list_users(array $p = [])
{
    $rows = run('SELECT * FROM users ORDER BY subscription_code')->fetchAll();
    $out = [];
    foreach ($rows as $r) {
        $u = format_user($r);
        $u['label'] = $r['label'] === null ? null : (string)$r['label'];  // نام مخفی؛ فقط در فهرست مدیریتی
        $out[] = $u;
    }
    json_response($out);
}

// GET /users/{code}
function read_user(array $p)
{
    json_response(format_user(find_user_or_404($p[0])));
}

// PUT /users/{code}/stats   بدنه: {"total_gig": 15}
function update_stats(array $p)
{
    $code = (int)$p[0];
    $user = find_user_or_404($code);
    $newTotal = require_number(read_json_body(), 'total_gig');

    $dg = $newTotal - (float)$user['total_gig'];
    run(
        'UPDATE users SET total_gig = ?, remaining_gig = ? WHERE subscription_code = ?',
        [$newTotal, calc_remaining($newTotal, (float)$user['used_gig']), $code]
    );
    if ($dg != 0) {
        log_transaction($code, 'adjust', $dg);
    }
    json_response(format_user(find_user($code)));
}

// PUT /users/{code}/usage   (مصرف را جایگزین می‌کند)
function update_usage(array $p)
{
    $code = (int)$p[0];
    $user = find_user_or_404($code);
    $used = require_number(read_json_body(), 'used_gig');
    $total = (float)$user['total_gig'];

    run(
        'UPDATE users SET used_gig = ?, remaining_gig = ? WHERE subscription_code = ?',
        [$used, calc_remaining($total, $used), $code]
    );
    json_response(format_user(find_user($code)));
}

// POST /users/{code}/add-usage   بدنه: {"gig": 0.5}   (به مصرف قبلی اضافه می‌شود)
function add_usage(array $p)
{
    $code = (int)$p[0];
    $user = find_user_or_404($code);
    $gig = require_number(read_json_body(), 'gig');
    if ($gig <= 0) {
        fail(422, 'مقدار gig باید بزرگ‌تر از صفر باشد.');
    }
    $total = (float)$user['total_gig'];
    $used = round((float)$user['used_gig'] + $gig, 3);

    run(
        'UPDATE users SET used_gig = ?, remaining_gig = ? WHERE subscription_code = ?',
        [$used, calc_remaining($total, $used), $code]
    );
    json_response(format_user(find_user($code)));
}

// POST /users/{code}/add-gig   بدنه: {"gig": 1}
function add_gig(array $p)
{
    $code = (int)$p[0];
    $user = find_user_or_404($code);
    $gig = require_number(read_json_body(), 'gig');
    if ($gig <= 0) {
        fail(422, 'مقدار gig باید بزرگ‌تر از صفر باشد.');
    }
    $total = round((float)$user['total_gig'] + $gig, 3);

    run(
        'UPDATE users SET total_gig = ?, remaining_gig = ? WHERE subscription_code = ?',
        [$total, calc_remaining($total, (float)$user['used_gig']), $code]
    );
    log_transaction($code, 'purchase', $gig);
    json_response(format_user(find_user($code)));
}

// POST /users/invite/{code}   (گیگ هدیه از تنظیمات invite_bonus_gig)
function register_invite(array $p)
{
    $code = (int)$p[0];
    $user = find_user_or_404($code);
    $bonus = (float)get_settings()['invite_bonus_gig'];
    $total = round((float)$user['total_gig'] + $bonus, 3);

    run(
        'UPDATE users SET total_gig = ?, remaining_gig = ? WHERE subscription_code = ?',
        [$total, calc_remaining($total, (float)$user['used_gig']), $code]
    );
    if ($bonus > 0) {
        log_transaction($code, 'invite', $bonus);
    }
    json_response(format_user(find_user($code)));
}

// POST /usage/report   بدنه: {code, device_id, total_bytes}
// total_bytes عدد «تجمعی» این نصب است؛ سرور فقط اختلاف با گزارش قبلی را اضافه می‌کند.
function report_usage(array $p = [])
{
    $body = read_json_body();
    $code = require_number($body, 'code', true);
    $device = require_string($body, 'device_id', 100);
    $bytes = require_number($body, 'total_bytes', true);
    find_user_or_404($code);

    $pdo = db();
    $pdo->exec('BEGIN IMMEDIATE');
    try {
        $row = run(
            'SELECT total_bytes FROM device_usage WHERE subscription_code = ? AND device_id = ?',
            [$code, $device]
        )->fetch();
        $prev = $row ? (int)$row['total_bytes'] : 0;
        // اگر عدد کوچک‌تر شد یعنی شمارنده ریست شده؛ همان عدد جدید اختلاف است
        $delta = $bytes >= $prev ? $bytes - $prev : $bytes;

        run(
            'INSERT OR REPLACE INTO device_usage (subscription_code, device_id, total_bytes, last_seen) VALUES (?, ?, ?, ?)',
            [$code, $device, $bytes, gmdate('Y-m-d\TH:i:s\Z')]
        );
        $fresh = find_user($code);
        $used = round((float)$fresh['used_gig'] + $delta / bytes_per_gb(), 6);
        run(
            'UPDATE users SET used_gig = ?, remaining_gig = ? WHERE subscription_code = ?',
            [$used, calc_remaining((float)$fresh['total_gig'], $used), $code]
        );
        $pdo->exec('COMMIT');
    } catch (Throwable $e) {
        $pdo->exec('ROLLBACK');
        throw $e;
    }

    $out = format_user(find_user($code));
    $out['exhausted'] = $out['used_gig'] >= $out['total_gig'];
    json_response($out);
}

// ---------- تنظیمات ----------
function get_price(array $p = [])
{
    json_response(['price_per_gig' => (int)get_settings()['price_per_gig']]);
}

// PUT /settings/price   بدنه: {"price": 9000}
function update_price(array $p = [])
{
    $price = require_number(read_json_body(), 'price', true);
    if ($price <= 0) {
        fail(422, 'قیمت باید بزرگ‌تر از صفر باشد.');
    }
    run('UPDATE settings SET price_per_gig = ? WHERE id = 1', [$price]);
    json_response(['price_per_gig' => $price]);
}

function get_initial_gig(array $p = [])
{
    json_response(['initial_test_gig' => (float)get_settings()['initial_test_gig']]);
}

// PUT /settings/initial-gig   بدنه: {"gig": 2}  (۰ مجاز است)
function update_initial_gig(array $p = [])
{
    $gig = require_number(read_json_body(), 'gig');
    run('UPDATE settings SET initial_test_gig = ? WHERE id = 1', [$gig]);
    json_response(['initial_test_gig' => $gig]);
}

function get_invite_bonus(array $p = [])
{
    json_response(['invite_bonus_gig' => (float)get_settings()['invite_bonus_gig']]);
}

// PUT /settings/invite-bonus   بدنه: {"gig": 1}  (۰ مجاز است)
function update_invite_bonus(array $p = [])
{
    $gig = require_number(read_json_body(), 'gig');
    run('UPDATE settings SET invite_bonus_gig = ? WHERE id = 1', [$gig]);
    json_response(['invite_bonus_gig' => $gig]);
}

// ---------- اطلاعیه‌ها ----------
function create_announcement(array $p = [])
{
    $text = require_string(read_json_body(), 'text', 20000);
    $createdAt = gmdate('Y-m-d\TH:i:s\Z');

    run('INSERT INTO announcements (text, created_at) VALUES (?, ?)', [$text, $createdAt]);
    json_response([
        'id' => (int)db()->lastInsertId(),
        'text' => $text,
        'created_at' => $createdAt,
    ], 201);
}

// GET /announcements
function list_announcements(array $p = [])
{
    $rows = run('SELECT id, text, created_at FROM announcements ORDER BY id DESC')->fetchAll();
    foreach ($rows as &$r) {
        $r['id'] = (int)$r['id'];
    }
    json_response($rows);
}

// DELETE /announcements  (حذف همه)
function delete_announcements(array $p = [])
{
    $n = run('DELETE FROM announcements')->rowCount();
    json_response(['deleted' => (int)$n]);
}

// ---------- لینک‌های اشتراک ----------
function create_subscription(array $p = [])
{
    $body = read_json_body();
    $link = require_string($body, 'link', 2048);
    $name = get_optional_string($body, 'name', 100);

    try {
        run('INSERT INTO subscription_links (link, name) VALUES (?, ?)', [$link, $name]);
    } catch (PDOException $e) {
        if ((string)$e->getCode() === '23000') {
            fail(409, 'این لینک قبلاً ثبت شده است.');
        }
        throw $e;
    }
    json_response(['id' => (int)db()->lastInsertId(), 'link' => $link, 'name' => $name], 201);
}

// GET /subscriptions
function list_subscriptions(array $p = [])
{
    $rows = run('SELECT id, link, name FROM subscription_links ORDER BY id')->fetchAll();
    foreach ($rows as &$r) {
        $r['id'] = (int)$r['id'];
    }
    json_response($rows);
}

// DELETE /subscriptions?link=...
function delete_subscription(array $p = [])
{
    $link = isset($_GET['link']) && is_string($_GET['link']) ? trim($_GET['link']) : '';
    if ($link === '') {
        fail(422, 'پارامتر link لازم است.');
    }
    $stmt = run('DELETE FROM subscription_links WHERE link = ?', [$link]);
    if ($stmt->rowCount() === 0) {
        fail(404, 'لینک پیدا نشد.');
    }
    no_content();
}

// ---------- نسخه‌های اپ ----------
function format_version(array $r)
{
    return [
        'id' => (int)$r['id'],
        'version_name' => $r['version_name'],
        'version_code' => (int)$r['version_code'],
        'download_link' => $r['download_link'],
        'is_mandatory' => (int)$r['is_mandatory'] === 1,
        'changelog' => $r['changelog'],
        'created_at' => $r['created_at'],
    ];
}

// POST /versions
function create_version(array $p = [])
{
    $body = read_json_body();
    $name = require_string($body, 'version_name', 50);
    $code = require_number($body, 'version_code', true);
    if ($code < 1) {
        fail(422, 'version_code باید ۱ یا بزرگ‌تر باشد.');
    }
    $link = require_string($body, 'download_link', 2048);
    if (!preg_match('#^https://\S+$#i', $link)) {
        fail(422, 'download_link باید با https:// شروع شود.');
    }
    $mandatory = get_bool($body, 'is_mandatory', false);
    $changelog = get_optional_string($body, 'changelog', 5000);

    try {
        run(
            'INSERT INTO app_versions (version_name, version_code, download_link, is_mandatory, changelog, created_at)
             VALUES (?, ?, ?, ?, ?, ?)',
            [$name, $code, $link, $mandatory ? 1 : 0, $changelog, gmdate('Y-m-d\TH:i:s\Z')]
        );
    } catch (PDOException $e) {
        if ((string)$e->getCode() === '23000') {
            fail(409, 'این version_code قبلاً ثبت شده است.');
        }
        throw $e;
    }

    $row = run('SELECT * FROM app_versions WHERE id = ?', [(int)db()->lastInsertId()])->fetch();
    json_response(format_version($row), 201);
}

// GET /versions
function list_versions(array $p = [])
{
    $rows = run('SELECT * FROM app_versions ORDER BY version_code DESC')->fetchAll();
    json_response(array_map('format_version', $rows));
}

// DELETE /versions/{version_code}
function delete_version(array $p)
{
    $stmt = run('DELETE FROM app_versions WHERE version_code = ?', [(int)$p[0]]);
    if ($stmt->rowCount() === 0) {
        fail(404, 'نسخه‌ای با این version_code پیدا نشد.');
    }
    no_content();
}

// GET /versions/check?current_code=12  (اپ این را صدا می‌زند)
function check_version(array $p = [])
{
    $raw = isset($_GET['current_code']) && is_string($_GET['current_code']) ? $_GET['current_code'] : '';
    if ($raw === '' || !ctype_digit($raw)) {
        fail(422, 'پارامتر current_code (عدد صحیح، مثل 12) لازم است.');
    }
    $current = (int)$raw;

    $latest = run('SELECT * FROM app_versions ORDER BY version_code DESC LIMIT 1')->fetch();
    if (!$latest) {
        json_response(['update_available' => false, 'mandatory' => false, 'latest' => null]);
    }

    $updateAvailable = (int)$latest['version_code'] > $current;

    // اگر هر نسخه‌ی جدیدتر از نسخه‌ی کاربر اجباری باشد، آپدیت اجباری است
    $mandatory = false;
    if ($updateAvailable) {
        $row = run(
            'SELECT 1 FROM app_versions WHERE version_code > ? AND is_mandatory = 1 LIMIT 1',
            [$current]
        )->fetch();
        $mandatory = (bool)$row;
    }

    json_response([
        'update_available' => $updateAvailable,
        'mandatory' => $mandatory,
        'latest' => [
            'version_name' => $latest['version_name'],
            'version_code' => (int)$latest['version_code'],
            'download_link' => $latest['download_link'],
            'changelog' => $latest['changelog'],
        ],
    ]);
}

// ---------- آمار و تراکنش‌ها ----------
// GET /stats/summary
function stats_summary(array $p = [])
{
    $sold = run("SELECT COALESCE(SUM(gig), 0) AS g FROM transactions WHERE type = 'purchase'")->fetch();
    $gift = run("SELECT COALESCE(SUM(gig), 0) AS g FROM transactions WHERE type IN ('invite', 'free')")->fetch();
    $u = run('SELECT COUNT(*) AS c, COALESCE(SUM(total_gig), 0) AS t, COALESCE(SUM(used_gig), 0) AS u FROM users')->fetch();

    json_response([
        'users_count' => (int)$u['c'],
        'sold_gig' => round((float)$sold['g'], 3),
        'gifted_gig' => round((float)$gift['g'], 3),
        'total_gig' => round((float)$u['t'], 3),
        'used_gig_total' => round((float)$u['u'], 3),
    ]);
}

// GET /transactions?limit=50
function list_transactions(array $p = [])
{
    $limit = 50;
    if (isset($_GET['limit']) && is_string($_GET['limit']) && ctype_digit($_GET['limit'])) {
        $limit = min(500, max(1, (int)$_GET['limit']));
    }
    $rows = run('SELECT id, subscription_code, type, gig, created_at FROM transactions ORDER BY id DESC LIMIT ' . $limit)->fetchAll();
    $out = [];
    foreach ($rows as $r) {
        $out[] = [
            'id' => (int)$r['id'],
            'subscription_code' => (int)$r['subscription_code'],
            'type' => $r['type'],
            'gig' => (float)$r['gig'],
            'created_at' => $r['created_at'],
        ];
    }
    json_response($out);
}

// ---------- مدیریت: نام کاربر، حذف کاربر، نام لینک، ثبت‌نام، ریست آمار ----------
// PUT /users/{code}/label   بدنه: {"label": "نام"}  (خالی = حذف نام). این نام هرگز در پاسخ‌های اپ نمی‌آید.
function set_label(array $p)
{
    $code = (int)$p[0];
    find_user_or_404($code);
    $label = get_optional_string(read_json_body(), 'label', 100);
    run('UPDATE users SET label = ? WHERE subscription_code = ?', [$label, $code]);
    json_response(['subscription_code' => $code, 'label' => $label]);
}

// DELETE /users/{code}  (کاربر و همه‌ی داده‌های وابسته: دستگاه‌ها و تراکنش‌ها)
function delete_user(array $p)
{
    $code = (int)$p[0];
    find_user_or_404($code);
    $pdo = db();
    $pdo->exec('BEGIN IMMEDIATE');
    try {
        run('DELETE FROM device_usage WHERE subscription_code = ?', [$code]);
        run('DELETE FROM transactions WHERE subscription_code = ?', [$code]);
        run('DELETE FROM users WHERE subscription_code = ?', [$code]);
        $pdo->exec('COMMIT');
    } catch (Throwable $e) {
        $pdo->exec('ROLLBACK');
        throw $e;
    }
    no_content();
}

// PUT /subscriptions/{id}   بدنه: {"name": "نام"}  (خالی = حذف نام)
function update_subscription_name(array $p)
{
    $id = (int)$p[0];
    $name = get_optional_string(read_json_body(), 'name', 100);
    $stmt = run('UPDATE subscription_links SET name = ? WHERE id = ?', [$name, $id]);
    $row = run('SELECT id, link, name FROM subscription_links WHERE id = ?', [$id])->fetch();
    if (!$row) {
        fail(404, 'لینک پیدا نشد.');
    }
    json_response(['id' => (int)$row['id'], 'link' => $row['link'], 'name' => $row['name']]);
}

function get_registration(array $p = [])
{
    json_response(['registration_open' => (int)get_settings()['registration_open'] === 1]);
}

// PUT /settings/registration   بدنه: {"open": true|false}
function update_registration(array $p = [])
{
    $body = read_json_body();
    if (!array_key_exists('open', $body)) {
        fail(422, 'فیلد open لازم است.');
    }
    $open = get_bool($body, 'open', true);
    run('UPDATE settings SET registration_open = ? WHERE id = 1', [$open ? 1 : 0]);
    json_response(['registration_open' => $open]);
}

// POST /stats/reset   بدنه: {"stat": "sold_gig" | "gifted_gig"}
function reset_stats(array $p = [])
{
    $stat = require_string(read_json_body(), 'stat', 30);
    if ($stat === 'sold_gig') {
        $n = run("DELETE FROM transactions WHERE type = 'purchase'")->rowCount();
    } elseif ($stat === 'gifted_gig') {
        $n = run("DELETE FROM transactions WHERE type IN ('invite', 'free')")->rowCount();
    } else {
        fail(422, 'stat باید sold_gig یا gifted_gig باشد.');
    }
    json_response(['reset' => $stat, 'deleted' => (int)$n]);
}
