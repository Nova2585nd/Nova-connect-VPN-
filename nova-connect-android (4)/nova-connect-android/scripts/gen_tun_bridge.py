#!/usr/bin/env python3
"""کلاس JNI و پل Kotlin را می‌سازد. hev-socks5-tunnel متدها را با RegisterNatives در JNI_OnLoad ثبت می‌کند
(کلاس از ماکروهای PKGNAME/CLSNAME در Application.mk می‌آید)، پس باید کلاس app.nova.connect.TunNative با هر سه متد وجود داشته باشد."""
import re, subprocess, sys, pathlib
so = sys.argv[1]; out = pathlib.Path(sys.argv[2])
nm = subprocess.run(["nm", "-D", "--defined-only", so], capture_output=True, text=True).stdout
jni = [l for l in nm.splitlines() if re.search(r"JNI|TProxy", l)]
print("نمادهای JNI در کتابخانه:", *(jni or ["(هیچ)"]), sep="\n  ")
pkg, cls = "app.nova.connect", "TunNative"
m = re.search(r"Java_([A-Za-z0-9_]+)_TProxyStartService", nm)
if m:  # حالت قدیمی: نمادهای صادرشده؛ نام از خود نماد خوانده می‌شود
    parts = m.group(1).split("_"); cls = parts[-1]; pkg = ".".join(parts[:-1])
elif "JNI_OnLoad" not in nm:
    sys.exit("نه JNI_OnLoad پیدا شد و نه نماد Java_*؛ فایل hev-jni.c در بیلد نیست. خروجی nm:\n" + nm[:3000])
d = out / pkg.replace(".", "/"); d.mkdir(parents=True, exist_ok=True)
(d / f"{cls}.kt").write_text(f"""package {pkg}

object {cls} {{
    init {{ System.loadLibrary("hev-socks5-tunnel") }}
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
    @JvmStatic external fun TProxyStopService()
    @JvmStatic external fun TProxyGetStats(): LongArray
}}
""")
b = out / "app/nova/connect"; b.mkdir(parents=True, exist_ok=True)
(b / "TunBind.kt").write_text(f"""package app.nova.connect

object TunBind {{
    fun start(cfg: String, fd: Int) {{ {pkg}.{cls}.TProxyStartService(cfg, fd) }}
    fun stop() {{ {pkg}.{cls}.TProxyStopService() }}
}}
""")
print("TunBind آماده شد:", pkg, cls)
