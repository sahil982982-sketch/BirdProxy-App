package com.birdproxy.v2;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.Set;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * BirdProxy application class.
 * Handles initialization and global state.
 */
public class BirdApp extends Application {

    private static final String TAG = "BirdApp";
    private static BirdApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "BirdProxy application initialized");
    }

    public static BirdApp getInstance() {
        return instance;
    }

    /**
     * Save proxy settings to SharedPreferences.
     */
    public static void saveSettings(String server, String port, String username,
                                     String password, boolean allApps) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(instance);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("proxy_server", server);
        editor.putString("proxy_port", port);
        editor.putString("proxy_username", username);
        editor.putString("proxy_password", password);
        editor.putBoolean("proxy_all_apps", allApps);
        editor.apply();
        Log.d(TAG, "Settings saved: " + server + ":" + port);
    }

    /**
     * Load proxy settings from SharedPreferences.
     */
    public static String[] loadSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(instance);
        String server = prefs.getString("proxy_server", "");
        String port = prefs.getString("proxy_port", "1080");
        String username = prefs.getString("proxy_username", "");
        String password = prefs.getString("proxy_password", "");
        boolean allApps = prefs.getBoolean("proxy_all_apps", true);
        return new String[]{server, port, username, password, String.valueOf(allApps)};
    }

    /**
     * Save selected packages for per-app VPN.
     */
    public static void saveSelectedPackages(Set<String> packages) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(instance);
        SharedPreferences.Editor editor = prefs.edit();
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (sb.length() > 0) sb.append(",");
            sb.append(pkg);
        }
        editor.putString("selected_packages", sb.toString());
        editor.apply();
        Log.d(TAG, "Saved " + packages.size() + " selected packages");
    }
}
