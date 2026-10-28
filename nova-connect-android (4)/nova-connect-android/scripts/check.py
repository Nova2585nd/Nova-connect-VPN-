#!/usr/bin/env python3
"""بررسی ساده‌ی ساختار پروژه (بدون کامپایل): فایل‌ها، نسخه، لوگو، پرانتزها و پیکربندی CI."""
import re, glob, pathlib, sys
R = pathlib.Path(__file__).resolve().parent.parent; bad = []
def need(p): 
    if not (R / p).exists(): bad.append("فایل گم‌شده: " + p)
for p in ["version.properties", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/src/main/res/drawable/ic_logo.xml", "app/src/main/res/drawable/ic_launcher_fg.xml",
          "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "scripts/prepare-native.sh", "scripts/gen_tun_bridge.py", ".github/workflows/android.yml", "nova-logo.svg"]: need(p)
v = dict(l.strip().split("=", 1) for l in (R / "version.properties").read_text().splitlines() if "=" in l)
if not re.fullmatch(r"\d+\.\d+\.\d+", v.get("VERSION_NAME", "")) or not v.get("VERSION_CODE", "").isdigit(): bad.append("version.properties نامعتبر")
code = "".join(open(f, encoding="utf8").read() for f in glob.glob(str(R / "app/src/main/java/app/nova/connect/*.kt")))
for need_s in ["R.drawable.ic_logo", "R.drawable.ic_stat", "/versions/check", "/usage/report", "/subscriptions/", "t.me/Nova_2585", "addDisallowedApplication", "fun register(", "syncOnLaunch", "/users/invite/", "invite-bonus", "probeWarm", "openTelegram", "SpeedChart", "onAccountDeleted", "QuotaSheet"]:
    if need_s not in code: bad.append("در کد پیدا نشد: " + need_s)
for f in glob.glob(str(R / "app/src/main/java/app/nova/connect/*.kt")):
    s = open(f, encoding="utf8").read(); s = re.sub(r'"(?:\\.|[^"\\])*"', '""', s); s = re.sub(r"'(?:\\.|[^'\\])'", "''", s)
    for a, b in ["()", "{}", "[]"]:
        if s.count(a) != s.count(b): bad.append(f"{pathlib.Path(f).name}: پرانتز نامتعادل {a}{b}")
mf = (R / "app/src/main/AndroidManifest.xml").read_text()
for t in ["BIND_VPN_SERVICE", "FOREGROUND_SERVICE", "usesCleartextTraffic"]: 
    if t not in mf: bad.append("مانیفست: " + t)
print("\n".join(bad) if bad else "بررسی ساختار پروژه موفق بود (کامپایل Kotlin انجام نشده است).")
sys.exit(1 if bad else 0)
