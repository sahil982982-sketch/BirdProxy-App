# Bird Proxy v10

Android SOCKS5 VPN source project with authenticated SOCKS5, All Apps mode, selected-app mode, remote MapDNS, IPv4/IPv6 capture, and direct official HevSocks5Tunnel JNI integration.

## Important v10 architecture change

v9 used the old `com.zaneschepke:hevtunnel:1.0.1` Maven AAR. v10 removes that dependency completely. On the first Gradle Sync, the project fetches the pinned official `heiher/hev-socks5-tunnel` tag `2.16.0`, including its submodules, and compiles it with Android NDK for Bird Proxy's own JNI class.

The app now checks the native start result and `TProxyIsRunning()` before showing **Connected**. If native startup fails, the TUN is closed immediately so Android is not left with a dead VPN that blocks all Internet.

## Requirements

- Android Studio with Android SDK 34
- JDK 17
- Git available in PATH
- Android NDK `29.0.14206865` (Android Studio can install it)
- Internet access during the first Gradle Sync to fetch the pinned native source and normal Gradle dependencies

## Build

### Android Studio

1. Open this project.
2. Allow Gradle Sync. The first Sync automatically fetches HevSocks5Tunnel `2.16.0` recursively.
3. If Git is not available to Android Studio, run `FETCH_NATIVE_CORE.bat` on Windows or `./FETCH_NATIVE_CORE.sh` on Linux/macOS, then Sync again.
4. Select **Build > Build APK(s)**.
5. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Command line

Linux/macOS:

```bash
./BUILD_LINUX.sh
```

Windows:

```bat
BUILD_WINDOWS.bat
```

## Clean installation

1. Uninstall every earlier Bird Proxy build.
2. Remove its old Android VPN profile.
3. Temporarily disable Always-on VPN and **Block connections without VPN**.
4. Disable other VPN-based apps such as AdGuard, Blokada, firewall VPNs, or another proxy VPN.
5. Reboot the phone.
6. Install the v10 APK and grant VPN permission.
7. Enter server, port, username, and password in separate fields.
8. Test **All Apps** first.

## Routing and DNS behavior

- **All Apps:** Android routes every app except Bird Proxy itself into the TUN. Bird Proxy is excluded only to prevent the native upstream SOCKS socket from looping back into the VPN.
- **Selected Apps:** only selected packages enter the TUN. Non-selected apps intentionally keep their normal network because that is Android's per-app VPN behavior.
- Routed traffic gets IPv4 and IPv6 default routes.
- Routed applications receive only the mapped VPN DNS server `198.18.0.2`.
- MapDNS creates synthetic addresses and preserves hostnames for SOCKS5 remote destination resolution.
- The app does not hardcode any proxy endpoint or credentials.

## Proxy compatibility

This client uses standard SOCKS5 TCP CONNECT and RFC 1928 UDP ASSOCIATE. A server that accepts login but blocks destinations, resets HTTPS, or does not provide usable routing cannot be repaired by Android client code. The preflight checks SOCKS authentication, a domain-name CONNECT request, Google HTTPS, and a proxy exit-IP endpoint before creating the VPN.

## Security note

SOCKS5 itself does not encrypt the connection between the phone and the SOCKS server. HTTPS traffic remains protected by HTTPS, but plain protocols remain plain. Rotate any credentials that were shared publicly or in chat.
