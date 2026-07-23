# Bird Proxy v10 Validation Report

## Passed in this environment

- Java 17 compilation of VPN service, SOCKS5 probe, config builder, JNI wrapper, and bridge against Android API stubs.
- Hev YAML unit test:
  - authenticated SOCKS5 fields
  - quote escaping
  - no native IPv4/IPv6 fields in external-TUN mode
  - MapDNS address/network/netmask
  - task stack and MTU
- Static routing assertions:
  - IPv4 default route
  - IPv6 default route
  - mapped DNS advertised to routed apps
  - All Apps self-exclusion
  - selected-app allow-list
- JNI assertions:
  - direct `TProxyStartService()` result check
  - direct `TProxyIsRunning()` readiness check
  - package/class compile macros
- Old Maven AAR and reflection scan.
- Android XML parse validation.
- Hardcoded proxy IP/port/username/password scan.
- ZIP integrity and source SHA-256 manifest.

## Not performed here

- Android Gradle APK assembly, because this runtime does not contain Android SDK/NDK and cannot clone GitHub from the container network.
- Installation and browsing on the user's physical Android device.
- A live test from the user's mobile network to their private SOCKS5 endpoint.

These omissions mean device operation is not claimed as verified. The code defects identified in v9 are corrected and the project is structured to build the official native core during the first Sync.

## Gradle wrapper note

An offline `gradlew help` attempt could not proceed because the Gradle 8.5 distribution was not cached and this container has no DNS access to `services.gradle.org`. Therefore Gradle/NDK execution was not represented as passed.
