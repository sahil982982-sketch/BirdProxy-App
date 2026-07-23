# Diagnostics

If v10 does not connect, use Android Studio **Logcat** and filter by `ProxyVpn`.

## Build-time failure

- `Could not fetch official Hev native core`: install Git, then run `FETCH_NATIVE_CORE.bat` or `FETCH_NATIVE_CORE.sh`.
- Missing NDK: install NDK `29.0.14206865` from Android Studio SDK Manager.
- Confirm `app/src/main/jni/hev/Android.mk` exists after the first Sync.

## Runtime messages

- **This endpoint is not a SOCKS5 proxy**: wrong protocol or port.
- **SOCKS5 username or password is incorrect**: authentication rejected.
- **Proxy connection refused/timed out**: server, firewall, IP allow-list, or provider issue.
- **Proxy resets normal HTTPS websites**: login works but the server is not carrying normal web traffic.
- **Native VPN engine rejected the configuration**: capture the complete `ProxyVpn` exception from Logcat.

## Android checks

- Remove the old app and old VPN profile before installing v10.
- Keep Always-on VPN and lockdown off until normal browsing works.
- Only one Android `VpnService` can be active at a time.
- Test All Apps first. In Selected Apps mode, confirm the exact browser package is selected.
