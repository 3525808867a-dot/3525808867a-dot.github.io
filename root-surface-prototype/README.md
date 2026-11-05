# KillSuoji Android 16 root permission probe

This is an isolated diagnostic addition on `root-surface-prototype`. It does not replace the production overlay, modify the module, create a window, or change system settings.

## Build

The branch-only workflow `.github/workflows/root-prototype-build.yml` builds a debug APK and uploads it as a GitHub Actions artifact. It does not publish a Release, push to the Pages site, or update `latest.json`.

## Run on the target device

1. Download the workflow artifact and extract `app-debug.apk`.
2. Copy it to the device as `/data/local/tmp/killsuoji-root-probe.apk`.
3. In a root shell, run:

```sh
CLASSPATH=/data/local/tmp/killsuoji-root-probe.apk app_process /system/bin com.killsuoji.app.RootPermissionProbe
```

4. Read the result:

```sh
logcat -d -s KillSuojiPermissionProbe:I '*:S'
```

The probe prints the process PID/UID and asks Android's permission service whether that exact process is granted `ACCESS_SURFACE_FLINGER` and `ACCESS_INPUT_FLINGER`.

## Interpretation

- `ACCESS_SURFACE_FLINGER=DENIED`: a normal SurfaceFlinger transaction carrying input-window commands will be rejected. Do not attempt to bypass this by disabling SELinux or modifying system partitions.
- `ACCESS_INPUT_FLINGER=DENIED`: direct InputFlinger operations may also be blocked.
- `GRANTED` is only a permission check, not proof that a complete native surface and touch channel can be created. SELinux and API compatibility still need separate tests.

This diagnostic is deliberately read-only. It does not attempt a Binder transaction against undocumented transaction codes.
