<?php
// توابع کمکی: پاسخ JSON، خطا، خواندن و اعتبارسنجی ورودی

function json_response($data, $status = 200)
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function no_content()
{
    http_response_code(204);
    exit;
}

function fail($status, $detail)
{
    json_response(['detail' => $detail], $status);
}

function read_json_body($required = true)
{
    $raw = file_get_contents('php://input');
    if ($raw === false || trim($raw) === '') {
        if ($required) {
            fail(422, 'بدنه‌ی درخواست (JSON) خالی است.');
        }
        return [];
    }
    $data = json_decode($raw, true);
    if (!is_array($data)) {
        fail(422, 'بدنه‌ی درخواست باید JSON معتبر باشد.');
    }
    return $data;
}

// عدد اختیاری: اگر نبود null برمی‌گرداند
function get_number(array $body, $key, $integer = false)
{
    if (!array_key_exists($key, $body) || $body[$key] === null) {
        return null;
    }
    $v = $body[$key];
    $ok = $integer ? is_int($v) : (is_int($v) || is_float($v));
    if (!$ok) {
        fail(422, "فیلد {$key} باید " . ($integer ? 'عدد صحیح' : 'عدد') . ' باشد.');
    }
    if ($v < 0) {
        fail(422, "فیلد {$key} نمی‌تواند منفی باشد.");
    }
    return $integer ? (int)$v : (float)$v;
}

// عدد اجباری
function require_number(array $body, $key, $integer = false)
{
    $v = get_number($body, $key, $integer);
    if ($v === null) {
        fail(422, "فیلد {$key} لازم است.");
    }
    return $v;
}

function require_string(array $body, $key, $maxBytes)
{
    if (!isset($body[$key]) || !is_string($body[$key]) || trim($body[$key]) === '') {
        fail(422, "فیلد {$key} لازم است و باید متن غیرخالی باشد.");
    }
    $v = trim($body[$key]);
    if (strlen($v) > $maxBytes) {
        fail(422, "فیلد {$key} بیش از حد طولانی است.");
    }
    return $v;
}

// تبدیل float به رشته‌ی مستقل از زبان سیستم (برای ذخیره در SQLite)
function sql_float($x)
{
    $s = sprintf('%.6F', $x);
    return rtrim(rtrim($s, '0'), '.');
}

// مقدار true/false اختیاری
function get_bool(array $body, $key, $default = false)
{
    if (!array_key_exists($key, $body) || $body[$key] === null) {
        return $default;
    }
    if (!is_bool($body[$key])) {
        fail(422, "فیلد {$key} باید true یا false باشد.");
    }
    return $body[$key];
}

// متن اختیاری: اگر نبود یا خالی بود null
function get_optional_string(array $body, $key, $maxBytes)
{
    if (!array_key_exists($key, $body) || $body[$key] === null) {
        return null;
    }
    if (!is_string($body[$key])) {
        fail(422, "فیلد {$key} باید متن باشد.");
    }
    $v = trim($body[$key]);
    if ($v === '') {
        return null;
    }
    if (strlen($v) > $maxBytes) {
        fail(422, "فیلد {$key} بیش از حد طولانی است.");
    }
    return $v;
}

// مانده هیچ‌وقت منفی نمی‌شود (مصرف می‌تواند از سقف بیشتر شود)
function calc_remaining($total, $used)
{
    return max(0.0, round($total - $used, 3));
}

function bytes_per_gb()
{
    $cfg = config();
    return (float)(isset($cfg['bytes_per_gb']) ? $cfg['bytes_per_gb'] : 1073741824);
}
