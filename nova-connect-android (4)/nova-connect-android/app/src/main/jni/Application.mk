APP_OPTIM := release
APP_PLATFORM := android-26
APP_ABI := arm64-v8a
APP_CFLAGS := -O3 -DPKGNAME=app/nova/connect -DCLSNAME=TunNative
APP_LDFLAGS := -Wl,--build-id=none
