package com.birdproxy.v2.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.birdproxy.v2.MainActivity;
import com.birdproxy.v2.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android full-tunnel/per-app SOCKS5 VPN using HevSocks5Tunnel.
 *
 * v10 follows the upstream Android data path and validates the JNI result:
 * - the real non-blocking TUN descriptor is passed directly to native code;
 * - tunnel addresses/routes are owned by Android; native YAML only receives MTU;
 * - MapDNS is the only DNS server advertised to routed applications;
 * - BirdProxy's UID is excluded only so native SOCKS sockets cannot loop;
 * - startup failure closes the TUN instead of leaving the phone black-holed.
 */
public class ProxyVpn extends VpnService {
    public static final String ACTION_START = "com.birdproxy.v2.action.START";
    public static final String ACTION_STOP = "com.birdproxy.v2.action.STOP";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_ERROR = 3;
    public static final int STATE_BLOCKED = 4; // Kept for UI compatibility; unused in v10.

    private static final String TAG = "ProxyVpn";
    private static final String CHANNEL_ID = "birdproxy_vpn";
    private static final int NOTIFICATION_ID = 1001;

    // HevSocks5Tunnel uses a userspace TCP/IP stack and upstream Android SocksTun
    // uses a jumbo TUN MTU. The outer mobile/Wi-Fi path still negotiates MSS.
    private static final int MTU = 8500;
    private static final String TUN_IPV4 = "198.18.0.1";
    private static final int TUN_IPV4_PREFIX = 32;
    private static final String TUN_IPV6 = "fc00::1";
    private static final int TUN_IPV6_PREFIX = 128;
    private static final String MAPPED_DNS = "198.18.0.2";

    private static volatile VpnStatusListener statusListener;
    private static volatile int currentState = STATE_DISCONNECTED;
    private static volatile String currentMessage = "VPN Disconnected";

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private ExecutorService executor;
    private ParcelFileDescriptor tunInterface;
    private HevTunnelBridge nativeTunnel;
    private File nativeConfigFile;
    private Thread statsThread;

    public interface VpnStatusListener {
        void onVpnStatusChanged(int state, String message);
        void onTrafficStats(long bytesSentPerSecond, long bytesReceivedPerSecond);
    }

    public static void setStatusListener(VpnStatusListener listener) {
        statusListener = listener;
        if (listener != null) listener.onVpnStatusChanged(currentState, currentMessage);
    }

    public static int getCurrentState() {
        return currentState;
    }

    public static String getCurrentMessage() {
        return currentMessage;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "BirdProxy-Start");
            thread.setDaemon(true);
            return thread;
        });
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action) || "stop".equals(action)) {
            stopVpn("VPN Disconnected", false);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting SOCKS5 VPN…"));
        if (currentState == STATE_CONNECTING || currentState == STATE_CONNECTED) {
            notifyStatus(currentState, currentMessage);
            return START_STICKY;
        }

        stopping.set(false);
        notifyStatus(STATE_CONNECTING, "Checking SOCKS5 login and web access…");
        executor.execute(this::startVpnSafely);
        return START_STICKY;
    }

    private void startVpnSafely() {
        try {
            Config config = readConfig();

            // This happens before Android routing changes. The destination hostname
            // is sent in the SOCKS request, proving credentials and remote DNS.
            Socks5Probe.ProbeResult probe = Socks5Probe.verifyFullProxy(
                    config.host, config.port, config.username, config.password, 15_000);
            if (stopping.get()) return;

            notifyStatus(STATE_CONNECTING, "Creating full tunnel and proxy DNS…");
            VpnService.Builder builder = new VpnService.Builder()
                    .setSession("Bird Proxy — " + (config.allApps ? "All Apps" : "Selected Apps"))
                    .setMtu(MTU)
                    .setBlocking(false)
                    .addAddress(TUN_IPV4, TUN_IPV4_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addAddress(TUN_IPV6, TUN_IPV6_PREFIX)
                    .addRoute("::", 0)
                    .addDnsServer(MAPPED_DNS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
            }

            configureApplications(builder, config);
            tunInterface = builder.establish();
            if (tunInterface == null) {
                throw new IOException("Android could not create the VPN interface");
            }
            if (stopping.get()) {
                releaseTunnel();
                return;
            }

            nativeConfigFile = writeNativeConfig(config);
            nativeTunnel = new HevTunnelBridge();
            nativeTunnel.start(nativeConfigFile.getAbsolutePath(), tunInterface.getFd());

            if (!nativeTunnel.awaitReady(8_000L)) {
                throw new IOException("Native tun2socks engine did not enter running state");
            }
            if (stopping.get()) {
                releaseTunnel();
                return;
            }

            String modeText = config.allApps ? "All Apps" : config.packages.size() + " selected apps";
            String status = "Tunnel active — SOCKS precheck IP " + probe.exitIp
                    + " — " + modeText + " — remote MapDNS";
            notifyStatus(STATE_CONNECTED, status);
            updateNotification("Tunnel active • " + modeText + " • remote DNS");
            startStatsLoop();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stopVpn("Connection cancelled", false);
        } catch (Throwable error) {
            Log.e(TAG, "VPN startup failed", error);
            // A failed native startup must never leave a dead TUN behind; v10 tears it down,
            // so a failed proxy never leaves the whole phone without Internet.
            stopVpn(cleanError(error), true);
        }
    }

    private Config readConfig() throws IOException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String host = value(prefs.getString("proxy_server", "")).trim();
        String portText = value(prefs.getString("proxy_port", "1080")).trim();
        String username = value(prefs.getString("proxy_username", ""));
        String password = value(prefs.getString("proxy_password", ""));
        boolean allApps = prefs.getBoolean("proxy_all_apps", true);

        if (host.isEmpty()) throw new IOException("SOCKS5 server address is missing");
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.contains("://")) {
            throw new IOException("Enter only the SOCKS5 host/IP, without socks5://");
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            throw new IOException("SOCKS5 port is invalid");
        }
        if (port < 1 || port > 65535) {
            throw new IOException("SOCKS5 port must be 1–65535");
        }
        if (username.isEmpty() != password.isEmpty()) {
            throw new IOException("Enter both SOCKS5 username and password");
        }

        Set<String> packages = new LinkedHashSet<>();
        String selected = value(prefs.getString("selected_packages", ""));
        for (String packageName : selected.split(",")) {
            packageName = packageName.trim();
            if (!packageName.isEmpty() && !packageName.equals(getPackageName())) {
                packages.add(packageName);
            }
        }
        if (!allApps && packages.isEmpty()) {
            throw new IOException("Select at least one app, or choose All Apps");
        }

        return new Config(host, port, username, password, allApps, packages);
    }

    private void configureApplications(VpnService.Builder builder, Config config)
            throws IOException {
        try {
            if (config.allApps) {
                // The native engine opens the upstream SOCKS socket in BirdProxy's
                // process. Excluding only this UID prevents a recursive VPN loop.
                builder.addDisallowedApplication(getPackageName());
                return;
            }

            int valid = 0;
            for (String packageName : config.packages) {
                try {
                    builder.addAllowedApplication(packageName);
                    valid++;
                } catch (PackageManager.NameNotFoundException error) {
                    Log.w(TAG, "Skipping missing app: " + packageName);
                }
            }
            if (valid == 0) throw new IOException("None of the selected apps are installed");
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("Could not exclude BirdProxy from its own VPN", error);
        }
    }

    private File writeNativeConfig(Config config) throws IOException {
        File file = new File(getCacheDir(), "birdproxy-hev.yml");
        String yaml = HevConfig.build(config.host, config.port,
                config.username, config.password, MTU, MAPPED_DNS);

        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(yaml.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        return file;
    }

    private void startStatsLoop() {
        statsThread = new Thread(() -> {
            long previousSentBytes = 0;
            long previousReceivedBytes = 0;
            int stoppedCount = 0;

            while (!stopping.get() && currentState == STATE_CONNECTED) {
                try {
                    Thread.sleep(1000L);
                    HevTunnelBridge bridge = nativeTunnel;
                    if (bridge == null) break;

                    if (!bridge.isRunning()) {
                        stoppedCount++;
                        if (stoppedCount >= 3) {
                            stopVpn("Native tunnel stopped; VPN disconnected", true);
                            break;
                        }
                        continue;
                    }
                    stoppedCount = 0;

                    long[] stats = bridge.getStats();
                    if (stats == null || stats.length < 4) {
                        // A temporary JNI stats miss is not a reason to kill a
                        // healthy tunnel. isRunning() is the source of truth.
                        continue;
                    }

                    long sentBytes = Math.max(0, stats[1]);
                    long receivedBytes = Math.max(0, stats[3]);
                    long sentRate = Math.max(0, sentBytes - previousSentBytes);
                    long receivedRate = Math.max(0, receivedBytes - previousReceivedBytes);
                    previousSentBytes = sentBytes;
                    previousReceivedBytes = receivedBytes;

                    VpnStatusListener listener = statusListener;
                    if (listener != null) listener.onTrafficStats(sentRate, receivedRate);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable error) {
                    Log.w(TAG, "Stats read failed", error);
                }
            }
        }, "BirdProxy-Stats");
        statsThread.setDaemon(true);
        statsThread.start();
    }

    private synchronized void releaseTunnel() {
        Thread localStats = statsThread;
        statsThread = null;
        if (localStats != null && localStats != Thread.currentThread()) localStats.interrupt();

        HevTunnelBridge bridge = nativeTunnel;
        nativeTunnel = null;
        if (bridge != null) bridge.stop();
        deleteNativeConfig();

        ParcelFileDescriptor tun = tunInterface;
        tunInterface = null;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException error) {
                Log.w(TAG, "Failed to close TUN", error);
            }
        }
    }


    private synchronized void deleteNativeConfig() {
        File file = nativeConfigFile;
        nativeConfigFile = null;
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete temporary native configuration");
        }
    }

    private synchronized void stopVpn(String message, boolean error) {
        stopping.set(true);
        releaseTunnel();
        notifyStatus(error ? STATE_ERROR : STATE_DISCONNECTED,
                error ? message : "VPN Disconnected");
        stopForeground(true);
        stopSelf();
    }

    private void notifyStatus(int state, String message) {
        currentState = state;
        currentMessage = message == null ? "" : message;
        VpnStatusListener listener = statusListener;
        if (listener != null) listener.onVpnStatusChanged(state, currentMessage);
    }

    private String cleanError(Throwable error) {
        StringBuilder chain = new StringBuilder();
        Throwable cursor = error;
        while (cursor != null) {
            String current = cursor.getMessage();
            if (current != null && !current.trim().isEmpty()) {
                if (chain.length() > 0) chain.append(" | ");
                chain.append(current);
            }
            Throwable next = cursor.getCause();
            if (next == null || next == cursor) break;
            cursor = next;
        }

        String lower = chain.toString().toLowerCase();
        if (lower.contains("not a socks5") || lower.contains("invalid socks5 greeting")) {
            return "This endpoint is not a SOCKS5 proxy";
        }
        if (lower.contains("username or password") || lower.contains("authentication failed")) {
            return "SOCKS5 username or password is incorrect";
        }
        if (lower.contains("google https") || lower.contains("connection reset")
                || lower.contains("resets normal web")) {
            return "SOCKS5 login works, but this proxy resets normal HTTPS websites";
        }
        if (lower.contains("wrapper is missing")) {
            return "Native HevTunnel library is missing from the APK";
        }
        if (lower.contains("wrapper api is incompatible")) {
            return "HevTunnel library API is incompatible with this build";
        }
        if (lower.contains("did not start") || lower.contains("running state")
                || lower.contains("rejected its configuration")) {
            return "Native VPN engine rejected the configuration or stopped during startup";
        }
        if (lower.contains("unknown host") || lower.contains("unable to resolve")) {
            return "Proxy host could not be resolved";
        }
        if (lower.contains("timed out")) {
            return "Proxy connection timed out — check server or IP allow-list";
        }
        if (lower.contains("refused")) {
            return "Proxy connection refused — server/port is closed or your IP is not allowed";
        }

        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ProxyVpn.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_enabled)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause,
                        getString(R.string.stop_vpn), stopPending)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Bird Proxy VPN", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Bird Proxy connection status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onRevoke() {
        stopVpn("VPN permission revoked", false);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopping.set(true);
        releaseTunnel();
        ExecutorService localExecutor = executor;
        executor = null;
        if (localExecutor != null) localExecutor.shutdownNow();
        if (currentState != STATE_ERROR && currentState != STATE_DISCONNECTED) {
            notifyStatus(STATE_DISCONNECTED, "VPN Disconnected");
        }
        super.onDestroy();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static final class Config {
        final String host;
        final int port;
        final String username;
        final String password;
        final boolean allApps;
        final Set<String> packages;

        Config(String host, int port, String username, String password,
               boolean allApps, Set<String> packages) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.allApps = allApps;
            this.packages = packages;
        }
    }
}
