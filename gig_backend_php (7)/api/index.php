<?php
// نقطه‌ی ورود: همه‌ی درخواست‌ها از اینجا عبور می‌کنند (به‌کمک .htaccess)

ini_set('display_errors', '0');

require __DIR__ . '/src/helpers.php';
require __DIR__ . '/src/database.php';
require __DIR__ . '/src/handlers.php';

set_exception_handler(function ($e) {
    $cfg = config();
    $msg = !empty($cfg['debug']) ? get_class($e) . ': ' . $e->getMessage() : 'خطای داخلی سرور.';
    json_response(['detail' => $msg], 500);
});

// ---------- محاسبه‌ی مسیر درخواست (حتی اگر پروژه داخل پوشه‌ای مثل /api باشد) ----------
$script = isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '';
$base = substr($script, -9) === 'index.php' ? rtrim(str_replace('\\', '/', dirname($script)), '/') : '';
$path = parse_url(isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '/', PHP_URL_PATH);
$path = $path ? rawurldecode($path) : '/';
if ($base !== '' && strpos($path, $base) === 0) {
    $path = substr($path, strlen($base));
}
$path = '/' . trim($path, '/');
$method = strtoupper(isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET');

$cfg = config();

// ---------- صفحه‌ی تست و صفحه‌ی اصلی ----------
if ($path === '/test' && $method === 'GET') {
    if (empty($cfg['enable_test_page'])) {
        fail(404, 'صفحه‌ی تست غیرفعال است.');
    }
    header('Content-Type: text/html; charset=utf-8');
    readfile(__DIR__ . '/test_page.html');
    exit;
}
if ($path === '/panel' && $method === 'GET') {
    header('Content-Type: text/html; charset=utf-8');
    readfile(__DIR__ . '/panel.html');
    exit;
}
if ($path === '/' && $method === 'GET') {
    json_response(['status' => 'ok', 'message' => 'Gig Backend (PHP) is running.']);
}

// ---------- مسیرها ----------
$routes = [
    ['POST',   '#^/users$#',                     'create_user'],
    ['GET',    '#^/users$#',                     'list_users'],
    ['POST',   '#^/users/invite/(\d+)$#',        'register_invite'],
    ['GET',    '#^/users/(\d+)$#',               'read_user'],
    ['DELETE', '#^/users/(\d+)$#',               'delete_user'],
    ['PUT',    '#^/users/(\d+)/label$#',        'set_label'],
    ['PUT',    '#^/users/(\d+)/stats$#',         'update_stats'],
    ['PUT',    '#^/users/(\d+)/usage$#',         'update_usage'],
    ['GET',    '#^/settings/price$#',            'get_price'],
    ['PUT',    '#^/settings/price$#',            'update_price'],
    ['GET',    '#^/settings/initial-gig$#',      'get_initial_gig'],
    ['PUT',    '#^/settings/initial-gig$#',      'update_initial_gig'],
    ['GET',    '#^/settings/invite-bonus$#',     'get_invite_bonus'],
    ['PUT',    '#^/settings/invite-bonus$#',     'update_invite_bonus'],
    ['GET',    '#^/settings/registration$#',     'get_registration'],
    ['PUT',    '#^/settings/registration$#',     'update_registration'],
    ['POST',   '#^/announcements$#',             'create_announcement'],
    ['POST',   '#^/subscriptions$#',             'create_subscription'],
    ['DELETE', '#^/subscriptions$#',             'delete_subscription'],
    ['PUT',    '#^/subscriptions/(\d+)$#',      'update_subscription_name'],
    ['POST',   '#^/users/(\d+)/add-gig$#',      'add_gig'],
    ['POST',   '#^/users/(\d+)/add-usage$#',    'add_usage'],
    ['GET',    '#^/announcements$#',             'list_announcements'],
    ['DELETE', '#^/announcements$#',             'delete_announcements'],
    ['GET',    '#^/subscriptions$#',             'list_subscriptions'],
    ['POST',   '#^/usage/report$#',              'report_usage'],
    ['GET',    '#^/stats/summary$#',             'stats_summary'],
    ['POST',   '#^/stats/reset$#',               'reset_stats'],
    ['GET',    '#^/transactions$#',              'list_transactions'],
    ['POST',   '#^/versions$#',                  'create_version'],
    ['GET',    '#^/versions$#',                  'list_versions'],
    ['GET',    '#^/versions/check$#',            'check_version'],
    ['DELETE', '#^/versions/(\d+)$#',           'delete_version'],
];

$pathMatched = false;
foreach ($routes as $route) {
    list($m, $pattern, $handler) = $route;
    if (preg_match($pattern, $path, $matches)) {
        $pathMatched = true;
        if ($m === $method) {
            array_shift($matches);
            $handler($matches);
            exit;
        }
    }
}

fail($pathMatched ? 405 : 404, $pathMatched ? 'این متد برای این مسیر مجاز نیست.' : 'مسیر پیدا نشد.');
