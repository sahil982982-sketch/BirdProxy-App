package com.birdproxy.v2.service;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** SOCKS5 authentication, remote-DNS, HTTPS and exit-IP preflight. */
final class Socks5Probe {
    private static final int SOCKS_VERSION = 0x05;
    private static final int AUTH_NONE = 0x00;
    private static final int AUTH_USER_PASS = 0x02;
    private static final int AUTH_REJECTED = 0xff;
    private static final int HTTPS_PORT = 443;
    private static final int MAX_HTTP_RESPONSE = 32 * 1024;

    private static final Endpoint[] EXIT_IP_ENDPOINTS = new Endpoint[]{
            new Endpoint("api.ipify.org", "/"),
            new Endpoint("api64.ipify.org", "/"),
            new Endpoint("icanhazip.com", "/")
    };

    private Socks5Probe() {}

    static final class ProbeResult {
        final String exitIp;

        ProbeResult(String exitIp) {
            this.exitIp = exitIp;
        }
    }

    static ProbeResult verifyFullProxy(String host, int port, String username, String password,
                                       int timeoutMs) throws IOException {
        byte[] user = safe(username).getBytes(StandardCharsets.UTF_8);
        byte[] pass = safe(password).getBytes(StandardCharsets.UTF_8);
        if (user.length > 255 || pass.length > 255) {
            throw new IOException("SOCKS username/password must be 255 bytes or less");
        }

        // CONNECT by domain name proves that the SOCKS server, rather than the
        // phone's ISP DNS, resolves the destination.
        verifyGoogleHttps(host, port, user, pass, timeoutMs);

        IOException lastError = null;
        for (Endpoint endpoint : EXIT_IP_ENDPOINTS) {
            try {
                String ip = fetchExitIp(host, port, user, pass, endpoint, timeoutMs);
                return new ProbeResult(ip);
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw new IOException("SOCKS5 web access works, but proxy exit-IP verification failed",
                lastError);
    }

    private static void verifyGoogleHttps(String proxyHost, int proxyPort,
                                          byte[] user, byte[] pass, int timeoutMs)
            throws IOException {
        try (Socket socket = openTunnel(proxyHost, proxyPort, user, pass,
                "www.google.com", HTTPS_PORT, timeoutMs);
             SSLSocket tls = openTls(socket, "www.google.com", timeoutMs)) {

            OutputStream out = tls.getOutputStream();
            out.write(("HEAD /generate_204 HTTP/1.1\r\n"
                    + "Host: www.google.com\r\n"
                    + "User-Agent: BirdProxy/10.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String status = readStatusLine(tls.getInputStream());
            if (!status.startsWith("HTTP/")) {
                throw new IOException("Proxy returned no valid Google HTTPS response");
            }
        } catch (SSLException | SocketException error) {
            throw new IOException("Proxy reset Google HTTPS traffic", error);
        } catch (EOFException error) {
            throw new IOException("Proxy closed during the Google HTTPS test", error);
        }
    }

    private static String fetchExitIp(String proxyHost, int proxyPort,
                                      byte[] user, byte[] pass, Endpoint endpoint,
                                      int timeoutMs) throws IOException {
        try (Socket socket = openTunnel(proxyHost, proxyPort, user, pass,
                endpoint.host, HTTPS_PORT, timeoutMs);
             SSLSocket tls = openTls(socket, endpoint.host, timeoutMs)) {

            OutputStream out = tls.getOutputStream();
            out.write(("GET " + endpoint.path + " HTTP/1.1\r\n"
                    + "Host: " + endpoint.host + "\r\n"
                    + "User-Agent: BirdProxy/10.0\r\n"
                    + "Accept: text/plain\r\n"
                    + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] response = readUpTo(tls.getInputStream(), MAX_HTTP_RESPONSE);
            String text = new String(response, StandardCharsets.US_ASCII);
            int separator = text.indexOf("\r\n\r\n");
            if (separator < 0) throw new IOException("Invalid exit-IP HTTP response");

            String header = text.substring(0, separator);
            String firstLine = header.contains("\r\n")
                    ? header.substring(0, header.indexOf("\r\n")) : header;
            if (!firstLine.matches("HTTP/\\d(?:\\.\\d)? 2\\d\\d.*")) {
                throw new IOException("Exit-IP endpoint returned " + firstLine);
            }

            String body = text.substring(separator + 4).trim();
            if (header.toLowerCase(Locale.US).contains("transfer-encoding: chunked")) {
                body = decodeFirstChunk(body).trim();
            }
            String candidate = body.split("[\\r\\n\\s]", 2)[0].trim();
            return normalizeLiteralIp(candidate);
        }
    }

    private static Socket openTunnel(String proxyHost, int proxyPort,
                                     byte[] user, byte[] pass,
                                     String targetHost, int targetPort,
                                     int timeoutMs) throws IOException {
        Socket socket = new Socket();
        boolean success = false;
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            negotiateAuthentication(in, out, user, pass);
            connectDomain(in, out, targetHost, targetPort);
            success = true;
            return socket;
        } finally {
            if (!success) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static SSLSocket openTls(Socket socket, String host, int timeoutMs)
            throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, HTTPS_PORT, true);
        tls.setUseClientMode(true);
        tls.setSoTimeout(timeoutMs);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        return tls;
    }

    private static void negotiateAuthentication(InputStream in, OutputStream out,
                                                  byte[] user, byte[] pass) throws IOException {
        boolean hasCredentials = user.length > 0 || pass.length > 0;
        if (hasCredentials) {
            // Offer username/password first, while keeping no-auth compatibility.
            out.write(new byte[]{SOCKS_VERSION, 0x02, AUTH_USER_PASS, AUTH_NONE});
        } else {
            out.write(new byte[]{SOCKS_VERSION, 0x01, AUTH_NONE});
        }
        out.flush();

        byte[] greeting = readExact(in, 2);
        if ((greeting[0] & 0xff) != SOCKS_VERSION) {
            throw new IOException("Server is not a SOCKS5 proxy");
        }

        int method = greeting[1] & 0xff;
        if (method == AUTH_REJECTED) {
            throw new IOException("SOCKS5 server rejected all authentication methods");
        }
        if (method == AUTH_USER_PASS) {
            if (!hasCredentials) {
                throw new IOException("SOCKS5 server requires username and password");
            }
            out.write(0x01);
            out.write(user.length);
            out.write(user);
            out.write(pass.length);
            out.write(pass);
            out.flush();

            byte[] auth = readExact(in, 2);
            if ((auth[0] & 0xff) != 0x01 || (auth[1] & 0xff) != 0x00) {
                throw new IOException("SOCKS5 username or password is incorrect");
            }
        } else if (method != AUTH_NONE) {
            throw new IOException("Unsupported SOCKS5 authentication method: " + method);
        }
    }

    private static void connectDomain(InputStream in, OutputStream out,
                                      String targetHost, int targetPort) throws IOException {
        byte[] target = targetHost.getBytes(StandardCharsets.US_ASCII);
        if (target.length == 0 || target.length > 255) {
            throw new IOException("Invalid SOCKS5 target hostname");
        }

        // ATYP=DOMAIN is intentional: destination DNS is resolved at the proxy.
        out.write(new byte[]{SOCKS_VERSION, 0x01, 0x00, 0x03, (byte) target.length});
        out.write(target);
        out.write(new byte[]{(byte) (targetPort >>> 8), (byte) targetPort});
        out.flush();

        byte[] reply = readExact(in, 4);
        if ((reply[0] & 0xff) != SOCKS_VERSION) {
            throw new IOException("Invalid SOCKS5 CONNECT response");
        }
        int result = reply[1] & 0xff;
        if (result != 0x00) {
            throw new IOException("SOCKS5 CONNECT to " + targetHost + " failed: "
                    + replyText(result));
        }
        consumeAddress(in, reply[3] & 0xff);
    }

    private static void consumeAddress(InputStream in, int addressType) throws IOException {
        switch (addressType) {
            case 0x01:
                readExact(in, 4 + 2);
                break;
            case 0x03:
                int length = readExact(in, 1)[0] & 0xff;
                readExact(in, length + 2);
                break;
            case 0x04:
                readExact(in, 16 + 2);
                break;
            default:
                throw new IOException("Unsupported SOCKS5 address type: " + addressType);
        }
    }

    private static String readStatusLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int previous = -1;
        while (line.size() < 1024) {
            int value = in.read();
            if (value < 0) throw new EOFException("HTTPS server closed before status line");
            line.write(value);
            if (previous == '\r' && value == '\n') break;
            previous = value;
        }
        return line.toString(StandardCharsets.US_ASCII.name()).trim();
    }

    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        byte[] buffer = new byte[1024];
        while (output.size() < limit) {
            int max = Math.min(buffer.length, limit - output.size());
            int count = in.read(buffer, 0, max);
            if (count < 0) break;
            output.write(buffer, 0, count);
        }
        if (output.size() == 0) throw new EOFException("HTTPS endpoint returned no data");
        return output.toByteArray();
    }

    private static String decodeFirstChunk(String body) throws IOException {
        int lineEnd = body.indexOf("\r\n");
        if (lineEnd < 1) throw new IOException("Invalid chunked response");
        String sizeText = body.substring(0, lineEnd).split(";", 2)[0].trim();
        int size;
        try {
            size = Integer.parseInt(sizeText, 16);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid chunk size", error);
        }
        int start = lineEnd + 2;
        if (size < 1 || start + size > body.length()) {
            throw new IOException("Incomplete chunked response");
        }
        return body.substring(start, start + size);
    }

    private static String normalizeLiteralIp(String candidate) throws IOException {
        if (candidate.isEmpty() || candidate.length() > 64
                || !candidate.matches("[0-9A-Fa-f:.]+")) {
            throw new IOException("Exit-IP endpoint returned an invalid address");
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address.getHostAddress();
        } catch (Exception error) {
            throw new IOException("Exit-IP endpoint returned an invalid address", error);
        }
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("SOCKS5 server closed the connection");
            offset += count;
        }
        return data;
    }

    private static String replyText(int code) {
        switch (code) {
            case 0x01: return "general server failure";
            case 0x02: return "connection not allowed";
            case 0x03: return "network unreachable";
            case 0x04: return "host unreachable";
            case 0x05: return "connection refused";
            case 0x06: return "TTL expired";
            case 0x07: return "command not supported";
            case 0x08: return "address type not supported";
            default: return "error code " + code;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Endpoint {
        final String host;
        final String path;

        Endpoint(String host, String path) {
            this.host = host;
            this.path = path;
        }
    }
}
