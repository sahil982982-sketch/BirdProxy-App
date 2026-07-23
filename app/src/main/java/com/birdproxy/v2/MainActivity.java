package com.birdproxy.v2;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.birdproxy.v2.service.ProxyVpn;
import com.google.android.material.button.MaterialButton;

/** Main control screen for BirdProxy. */
public class MainActivity extends AppCompatActivity implements ProxyVpn.VpnStatusListener {
    private static final int VPN_REQUEST_CODE = 100;
    private static final int NOTIFICATION_REQUEST_CODE = 101;

    private ImageView statusIcon;
    private TextView statusText;
    private TextView statusDetail;
    private TextView proxyInfoValue;
    private TextView appCountInfo;
    private TextView speedInfo;
    private MaterialButton vpnToggleButton;
    private MaterialButton settingsButton;
    private MaterialButton appPickButton;

    private int vpnState = ProxyVpn.STATE_DISCONNECTED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusIcon = findViewById(R.id.vpnStatusIcon);
        statusText = findViewById(R.id.vpnStatusText);
        statusDetail = findViewById(R.id.vpnStatusDetail);
        proxyInfoValue = findViewById(R.id.proxyInfoValue);
        appCountInfo = findViewById(R.id.appCountInfo);
        speedInfo = findViewById(R.id.speedInfo);
        vpnToggleButton = findViewById(R.id.vpnToggleButton);
        settingsButton = findViewById(R.id.settingsButton);
        appPickButton = findViewById(R.id.appPickButton);

        vpnToggleButton.setOnClickListener(v -> toggleVpn());
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, Settings.class)));
        appPickButton.setOnClickListener(v -> startActivity(new Intent(this, AppPick.class)));

        updateProxyInfo();
        renderState(ProxyVpn.getCurrentState(), ProxyVpn.getCurrentMessage());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ProxyVpn.setStatusListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateProxyInfo();
    }

    @Override
    protected void onStop() {
        ProxyVpn.setStatusListener(null);
        super.onStop();
    }

    private void toggleVpn() {
        if (vpnState == ProxyVpn.STATE_CONNECTED || vpnState == ProxyVpn.STATE_CONNECTING
                || vpnState == ProxyVpn.STATE_BLOCKED) {
            Intent stop = new Intent(this, ProxyVpn.class).setAction(ProxyVpn.ACTION_STOP);
            startService(stop);
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String server = value(prefs.getString("proxy_server", "")).trim();
        if (server.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No Proxy Configured")
                    .setMessage("Enter your SOCKS5 server, port and login first.")
                    .setPositiveButton("Configure", (dialog, which) ->
                            startActivity(new Intent(this, Settings.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE);
        } else {
            startProxyService();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_REQUEST_CODE) return;
        if (resultCode == RESULT_OK) {
            startProxyService();
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
            renderState(ProxyVpn.STATE_DISCONNECTED, "VPN permission denied");
        }
    }

    private void startProxyService() {
        renderState(ProxyVpn.STATE_CONNECTING, "Starting VPN…");
        Intent start = new Intent(this, ProxyVpn.class).setAction(ProxyVpn.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(start);
        } else {
            startService(start);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST_CODE);
        }
    }

    private void updateProxyInfo() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String server = value(prefs.getString("proxy_server", "")).trim();
        String port = value(prefs.getString("proxy_port", "1080")).trim();
        boolean allApps = prefs.getBoolean("proxy_all_apps", true);

        proxyInfoValue.setText(server.isEmpty() ? "Not configured" : server + ":" + port);
        if (allApps) {
            appCountInfo.setText("Mode: All Apps");
        } else {
            String packages = value(prefs.getString("selected_packages", ""));
            int count = 0;
            for (String packageName : packages.split(",")) {
                if (!packageName.trim().isEmpty()) count++;
            }
            appCountInfo.setText("Mode: " + count + " apps selected");
        }
    }

    private void renderState(int state, String message) {
        vpnState = state;
        runOnUiThread(() -> {
            boolean connected = state == ProxyVpn.STATE_CONNECTED;
            boolean connecting = state == ProxyVpn.STATE_CONNECTING;
            boolean error = state == ProxyVpn.STATE_ERROR;
            boolean blocked = state == ProxyVpn.STATE_BLOCKED;

            if (connected) {
                statusIcon.setImageResource(R.drawable.ic_vpn_enabled);
                statusText.setText(R.string.vpn_status_connected);
                statusText.setTextColor(getColor(R.color.status_connected));
                vpnToggleButton.setText(R.string.stop_vpn);
                vpnToggleButton.setIconResource(android.R.drawable.ic_media_pause);
                settingsButton.setEnabled(false);
                appPickButton.setEnabled(false);
            } else if (connecting) {
                statusIcon.setImageResource(R.drawable.ic_vpn_disabled);
                statusText.setText(R.string.vpn_status_connecting);
                statusText.setTextColor(getColor(R.color.primary));
                vpnToggleButton.setText("Cancel");
                vpnToggleButton.setIconResource(android.R.drawable.ic_menu_close_clear_cancel);
                settingsButton.setEnabled(false);
                appPickButton.setEnabled(false);
            } else if (blocked) {
                statusIcon.setImageResource(R.drawable.ic_vpn_disabled);
                statusText.setText(R.string.vpn_status_blocked);
                statusText.setTextColor(getColor(R.color.status_disconnected));
                vpnToggleButton.setText(R.string.stop_vpn);
                vpnToggleButton.setIconResource(android.R.drawable.ic_media_pause);
                settingsButton.setEnabled(false);
                appPickButton.setEnabled(false);
                speedInfo.setText("Traffic blocked (fail-closed)");
            } else {
                statusIcon.setImageResource(R.drawable.ic_vpn_disabled);
                statusText.setText(error ? "Connection Failed" : getString(R.string.vpn_status_disconnected));
                statusText.setTextColor(getColor(R.color.status_disconnected));
                vpnToggleButton.setText(R.string.start_vpn);
                vpnToggleButton.setIconResource(android.R.drawable.ic_media_play);
                settingsButton.setEnabled(true);
                appPickButton.setEnabled(true);
                speedInfo.setText("");
            }
            statusDetail.setText(message == null || message.isEmpty()
                    ? getString(R.string.vpn_status_disconnected) : message);
        });
    }

    @Override
    public void onVpnStatusChanged(int state, String message) {
        renderState(state, message);
    }

    @Override
    public void onTrafficStats(long bytesSentPerSecond, long bytesReceivedPerSecond) {
        runOnUiThread(() -> speedInfo.setText(
                "↓ " + formatSpeed(bytesReceivedPerSecond) + "  ↑ " + formatSpeed(bytesSentPerSecond)));
    }

    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024L * 1024L) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        }
        return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
