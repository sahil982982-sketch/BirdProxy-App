package com.birdproxy.v2.service;

import com.birdproxy.v2.nativecore.TProxyService;

import java.io.IOException;

/** Strict bridge to the packaged Hev JNI service. No fake running state. */
final class HevTunnelBridge {
    private volatile boolean started;

    void start(String configPath, int tunFd) throws IOException {
        if (configPath == null || configPath.isEmpty() || tunFd < 0) {
            throw new IOException("Invalid native tunnel startup arguments");
        }

        final boolean accepted;
        try {
            accepted = TProxyService.TProxyStartService(configPath, tunFd);
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("Native Hev library/ABI is missing from the APK", error);
        } catch (Throwable error) {
            throw new IOException("Native Hev tunnel startup crashed", error);
        }

        if (!accepted) {
            throw new IOException("Native Hev tunnel rejected its configuration");
        }
        started = true;
    }

    boolean awaitReady(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        do {
            if (isRunning()) return true;
            Thread.sleep(100L);
        } while (started && System.currentTimeMillis() < deadline);
        return false;
    }

    boolean isRunning() {
        if (!started) return false;
        try {
            return TProxyService.TProxyIsRunning();
        } catch (Throwable error) {
            return false;
        }
    }

    long[] getStats() {
        if (!isRunning()) return null;
        try {
            return TProxyService.TProxyGetStats();
        } catch (Throwable ignored) {
            return null;
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            TProxyService.TProxyStopService();
        } catch (Throwable ignored) {
            // ProxyVpn still closes the TUN fd in all cases.
        }
    }
}
