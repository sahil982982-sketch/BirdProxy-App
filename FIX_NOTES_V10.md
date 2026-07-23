# Bird Proxy v10 Fix Notes

## Confirmed v9 defects removed

1. **Old third-party AAR removed**
   - Removed `com.zaneschepke:hevtunnel:1.0.1` and Kotlin runtime dependency.
   - v10 builds pinned official HevSocks5Tunnel `2.16.0` source with its exact JNI package/class macros.

2. **Native startup result is mandatory**
   - `TProxyStartService()` boolean return is checked.
   - `TProxyIsRunning()` must become true before the UI can report Connected.
   - A failed engine closes the Android TUN instead of black-holing the phone.

3. **External-TUN YAML corrected**
   - Android owns the interface addresses and routes.
   - Native YAML contains MTU, ICMP policy, SOCKS5/auth, UDP mode, and MapDNS only.
   - Removed native `tunnel.ipv4` and `tunnel.ipv6` fields that were inappropriate when passing an Android-created TUN file descriptor.

4. **Native config lifetime corrected**
   - The YAML remains available for the running tunnel and is deleted during teardown.

5. **Current MapDNS synthetic range**
   - Uses `100.64.0.0/10` (`255.192.0.0`) for mapped destination addresses.
   - Android advertises `198.18.0.2` only as the local VPN DNS endpoint.

6. **Direct compile-time JNI API**
   - No reflection, guessed class names, or fake Java running state.
   - JNI symbols are generated for `com.birdproxy.v2.nativecore.TProxyService`.

7. **Pinned reproducible native source**
   - Tag: `2.16.0`
   - Clone includes all required submodules.
   - Four ABIs: arm64-v8a, armeabi-v7a, x86_64, x86.
