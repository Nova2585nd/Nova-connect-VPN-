<?php
// اتصال به SQLite با PDO و ساخت خودکار جدول‌ها

function config()
{
    static $cfg = null;
    if ($cfg === null) {
        $cfg = require __DIR__ . '/../config.php';
    }
    return $cfg;
}

function db()
{
    static $pdo = null;
    if ($pdo !== null) {
        return $pdo;
    }

    if (!extension_loaded('pdo_sqlite')) {
        fail(500, 'افزونه‌ی pdo_sqlite روی این هاست فعال نیست. در cPanel بخش Select PHP Version > Extensions آن را تیک بزن یا از پشتیبانی هاست بخواه.');
    }

    $cfg = config();
    $dir = dirname($cfg['db_path']);
    if (!is_dir($dir) && !@mkdir($dir, 0755, true)) {
        fail(500, 'پوشه‌ی data ساخته نشد. آن را دستی بساز.');
    }
    if (!is_writable($dir)) {
        fail(500, 'پوشه‌ی data قابل نوشتن نیست. Permission آن را روی 755 (یا 775) بگذار.');
    }

    $pdo = new PDO('sqlite:' . $cfg['db_path']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    $pdo->exec('PRAGMA busy_timeout = 5000');

    init_schema($pdo, (int)$cfg['default_price']);
    return $pdo;
}

function init_schema(PDO $pdo, $defaultPrice)
{
    // ستون‌های قدیمی (token, total_purchase_price) در دیتابیس‌های قبلی می‌مانند ولی دیگر استفاده نمی‌شوند.
    $pdo->exec('CREATE TABLE IF NOT EXISTS users (
        subscription_code INTEGER PRIMARY KEY,
        total_gig REAL NOT NULL DEFAULT 0,
        used_gig REAL NOT NULL DEFAULT 0,
        remaining_gig REAL NOT NULL DEFAULT 0,
        label TEXT
    )');
    $pdo->exec('CREATE TABLE IF NOT EXISTS settings (
        id INTEGER PRIMARY KEY,
        price_per_gig INTEGER NOT NULL,
        initial_test_gig REAL NOT NULL DEFAULT 2,
        invite_bonus_gig REAL NOT NULL DEFAULT 1,
        registration_open INTEGER NOT NULL DEFAULT 1
    )');

    // مهاجرت: افزودن دو تنظیم جدید به دیتابیس قدیمی
    $cols = array_column($pdo->query('PRAGMA table_info(settings)')->fetchAll(), 'name');
    if (!in_array('initial_test_gig', $cols, true)) {
        $pdo->exec('ALTER TABLE settings ADD COLUMN initial_test_gig REAL NOT NULL DEFAULT 2');
    }
    if (!in_array('invite_bonus_gig', $cols, true)) {
        $pdo->exec('ALTER TABLE settings ADD COLUMN invite_bonus_gig REAL NOT NULL DEFAULT 1');
    }

    $pdo->exec('CREATE TABLE IF NOT EXISTS announcements (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        text TEXT NOT NULL,
        created_at TEXT NOT NULL
    )');
    $pdo->exec('CREATE TABLE IF NOT EXISTS subscription_links (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        link TEXT NOT NULL UNIQUE,
        name TEXT
    )');
    $pdo->exec('CREATE TABLE IF NOT EXISTS app_versions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        version_name TEXT NOT NULL,
        version_code INTEGER NOT NULL UNIQUE,
        download_link TEXT NOT NULL,
        is_mandatory INTEGER NOT NULL DEFAULT 0,
        changelog TEXT,
        created_at TEXT NOT NULL
    )');
    $pdo->exec('CREATE TABLE IF NOT EXISTS device_usage (
        subscription_code INTEGER NOT NULL,
        device_id TEXT NOT NULL,
        total_bytes INTEGER NOT NULL DEFAULT 0,
        last_seen TEXT NOT NULL,
        PRIMARY KEY (subscription_code, device_id)
    )');
    $pdo->exec('CREATE TABLE IF NOT EXISTS transactions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        subscription_code INTEGER NOT NULL,
        type TEXT NOT NULL,
        gig REAL NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL
    )');

    // مهاجرت دیتابیس قدیمی: ستون‌های جدید
    $newCols = [
        ['settings', 'registration_open', 'INTEGER NOT NULL DEFAULT 1'],
        ['users', 'label', 'TEXT'],
        ['subscription_links', 'name', 'TEXT'],
    ];
    foreach ($newCols as $m) {
        $have = array_column($pdo->query('PRAGMA table_info(' . $m[0] . ')')->fetchAll(), 'name');
        if (!in_array($m[1], $have, true)) {
            $pdo->exec('ALTER TABLE ' . $m[0] . ' ADD COLUMN ' . $m[1] . ' ' . $m[2]);
        }
    }

    $stmt = $pdo->prepare('INSERT OR IGNORE INTO settings (id, price_per_gig) VALUES (1, ?)');
    $stmt->bindValue(1, $defaultPrice, PDO::PARAM_INT);
    $stmt->execute();
}

// اجرای کوئری با پارامتر؛ int و float درست بایند می‌شوند
function run($sql, array $params = [])
{
    $stmt = db()->prepare($sql);
    $i = 1;
    foreach ($params as $v) {
        if (is_int($v)) {
            $stmt->bindValue($i, $v, PDO::PARAM_INT);
        } elseif (is_float($v)) {
            $stmt->bindValue($i, sql_float($v), PDO::PARAM_STR);
        } elseif ($v === null) {
            $stmt->bindValue($i, null, PDO::PARAM_NULL);
        } else {
            $stmt->bindValue($i, (string)$v, PDO::PARAM_STR);
        }
        $i++;
    }
    $stmt->execute();
    return $stmt;
}
