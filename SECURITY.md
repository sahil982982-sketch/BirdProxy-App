# Security and Leak Model

- No proxy credentials are hardcoded or logged.
- Included apps receive IPv4 and IPv6 default routes through Android `VpnService`.
- Bird Proxy does not call `allowBypass()`.
- Android is given only the synthetic MapDNS resolver.
- All Apps excludes Bird Proxy's own UID so the native upstream SOCKS socket cannot recursively enter the VPN.
- Selected Apps routes only explicitly saved packages; non-selected apps intentionally keep their normal network.
- If startup or native initialization fails, v8 tears down the TUN immediately and restores the phone's normal network instead of leaving a dead VPN interface.
- Strict system-level fail-closed protection requires Android **Always-on VPN + Block connections without VPN** after the tunnel has been confirmed working.
- A SOCKS5 provider can observe destination metadata and traffic leaving its server. Use a provider you trust.
