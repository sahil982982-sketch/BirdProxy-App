package com.birdproxy.v2.nativecore;

/** Direct JNI API exported by the pinned official HevSocks5Tunnel build. */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int tunFd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
