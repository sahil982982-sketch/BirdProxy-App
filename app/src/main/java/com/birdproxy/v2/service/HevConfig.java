package com.birdproxy.v2.service;

/** Builds the HevSocks5Tunnel YAML used with an Android-supplied TUN fd. */
final class HevConfig {
    private HevConfig() {}

    static String build(String host, int port, String username, String password,
                        int mtu, String mappedDns) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("SOCKS5 host is empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SOCKS5 port is invalid");
        }

        StringBuilder yaml = new StringBuilder(512);
        yaml.append("misc:\n")
                .append("  task-stack-size: 81920\n")
                .append("tunnel:\n")
                .append("  mtu: ").append(mtu).append('\n')
                .append("  icmp: 'reply'\n")
                .append("socks5:\n")
                .append("  address: '").append(quote(host.trim())).append("'\n")
                .append("  port: ").append(port).append('\n')
                // Standard RFC 1928 UDP ASSOCIATE. TCP CONNECT is always enabled.
                .append("  udp: 'udp'\n");

        if (username != null && password != null
                && !username.isEmpty() && !password.isEmpty()) {
            yaml.append("  username: '").append(quote(username)).append("'\n")
                    .append("  password: '").append(quote(password)).append("'\n");
        }

        // Android advertises mappedDns as the VPN DNS server. Hev answers locally
        // with synthetic addresses and preserves the original domain for SOCKS5.
        yaml.append("mapdns:\n")
                .append("  address: ").append(mappedDns).append('\n')
                .append("  port: 53\n")
                .append("  network: 100.64.0.0\n")
                .append("  netmask: 255.192.0.0\n")
                .append("  cache-size: 10000\n");
        return yaml.toString();
    }

    static String quote(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
