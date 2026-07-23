package com.birdproxy.v2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Activity for selecting which apps to route through the proxy.
 * Works on Android 11+ with QUERY_ALL_PACKAGES permission.
 * Shows both system and user-installed apps.
 */
public class AppPick extends AppCompatActivity {

    private RecyclerView appList;
    private AppListAdapter adapter;
    private TabLayout tabLayout;
    private Set<String> selectedPackages;
    private List<AppInfo> allApps;
    private List<AppInfo> systemApps;
    private List<AppInfo> userApps;
    private int currentTab = 1; // 1 = user apps, 2 = system apps

    private static class AppInfo {
        String packageName;
        String appName;
        Drawable icon;
        boolean isSystem;
        boolean isSelected;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_pick);

        // Load selected packages
        selectedPackages = loadSelectedPackages();

        // Initialize views
        appList = findViewById(R.id.appList);
        tabLayout = findViewById(R.id.tabLayout);

        // Setup tabs
        tabLayout.addTab(tabLayout.newTab().setText("User Apps"));
        tabLayout.addTab(tabLayout.newTab().setText("System Apps"));

        // Setup RecyclerView
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setHasFixedSize(true);

        // Search input
        com.google.android.material.textfield.TextInputEditText searchInput =
                findViewById(R.id.searchInput);
        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (adapter != null) {
                        adapter.getFilter().filter(s.toString());
                    }
                }
            });
        }

        // Save button
        findViewById(R.id.saveAppsButton).setOnClickListener(v -> saveSelection());

        // Tab selection
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition() + 1;
                updateAppList();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Load apps in background
        loadApps();
    }

    /**
     * Load all installed apps using PackageManager.
     * This requires QUERY_ALL_PACKAGES permission on Android 11+.
     */
    private void loadApps() {
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> installedApps;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    installedApps = pm.getInstalledApplications(
                            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
                } else {
                    installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                }

            allApps = new ArrayList<>();
            List<AppInfo> userList = new ArrayList<>();
            List<AppInfo> systemList = new ArrayList<>();

            for (ApplicationInfo app : installedApps) {
                // Skip this app
                if (app.packageName.equals(getPackageName())) continue;

                AppInfo info = new AppInfo();
                info.packageName = app.packageName;
                info.appName = pm.getApplicationLabel(app).toString();
                info.icon = pm.getApplicationIcon(app);
                info.isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                info.isSelected = selectedPackages.contains(app.packageName);
                allApps.add(info);

                if (info.isSystem) {
                    systemList.add(info);
                } else {
                    userList.add(info);
                }
            }

            // Sort alphabetically
            Comparator<AppInfo> byName = (a, b) -> a.appName.compareToIgnoreCase(b.appName);
            Collections.sort(userList, byName);
            Collections.sort(systemList, byName);

            this.userApps = userList;
            this.systemApps = systemList;

            runOnUiThread(() -> {
                updateAppList();
                tabLayout.getTabAt(0).select();
            });
            } catch (Exception e) {
                Log.e("AppPick", "Failed to load apps", e);
            }
        }).start();
    }

    /**
     * Update the displayed app list based on current tab.
     */
    private void updateAppList() {
        List<AppInfo> apps;
        if (currentTab == 1) {
            apps = userApps != null ? userApps : new ArrayList<>();
        } else {
            apps = systemApps != null ? systemApps : new ArrayList<>();
        }

        adapter = new AppListAdapter(apps);
        appList.setAdapter(adapter);
    }

    /**
     * Save the selected packages to SharedPreferences.
     */
    private void saveSelection() {
        Set<String> selected = new HashSet<>();
        // allApps is the canonical list, so selections survive tab switches and
        // search filtering.
        if (allApps != null) {
            for (AppInfo app : allApps) {
                if (app.isSelected) {
                    selected.add(app.packageName);
                }
            }
        }

        // Save to preferences
        BirdApp.saveSelectedPackages(selected);

        Toast.makeText(this, selected.size() + " apps selected", Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * Load previously selected packages.
     */
    private Set<String> loadSelectedPackages() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String packagesStr = prefs.getString("selected_packages", "");
        Set<String> packages = new HashSet<>();
        if (packagesStr != null && !packagesStr.isEmpty()) {
            for (String pkg : packagesStr.split(",")) {
                packages.add(pkg.trim());
            }
        }
        return packages;
    }

    /**
     * RecyclerView adapter for the app list.
     */
    private static class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder>
            implements Filterable {

        private List<AppInfo> originalList;
        private List<AppInfo> filteredList;
        private AppFilter filter;

        AppListAdapter(List<AppInfo> apps) {
            this.originalList = new ArrayList<>(apps);
            this.filteredList = new ArrayList<>(apps);
        }

        List<AppInfo> getCurrentList() {
            return filteredList;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_multiple_choice, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo app = filteredList.get(position);
            holder.textView.setText(app.appName);
            holder.textView.setChecked(app.isSelected);

            // Set icon
            if (app.icon != null) {
                app.icon.setBounds(0, 0, 48, 48);
                holder.textView.setCompoundDrawables(app.icon, null, null, null);
                holder.textView.setCompoundDrawablePadding(12);
            }

            holder.itemView.setOnClickListener(v -> {
                app.isSelected = !app.isSelected;
                holder.textView.setChecked(app.isSelected);
                notifyItemChanged(position);
            });
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }

        @Override
        public Filter getFilter() {
            if (filter == null) {
                filter = new AppFilter();
            }
            return filter;
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            CheckedTextView textView;
            ViewHolder(View itemView) {
                super(itemView);
                textView = itemView.findViewById(android.R.id.text1);
            }
        }

        private class AppFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                List<AppInfo> filtered = new ArrayList<>();

                if (constraint == null || constraint.length() == 0) {
                    filtered.addAll(originalList);
                } else {
                    String query = constraint.toString().toLowerCase();
                    for (AppInfo app : originalList) {
                        if (app.appName.toLowerCase().contains(query) ||
                                app.packageName.toLowerCase().contains(query)) {
                            filtered.add(app);
                        }
                    }
                }

                results.values = filtered;
                results.count = filtered.size();
                return results;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredList = (List<AppInfo>) results.values;
                notifyDataSetChanged();
            }
        }
    }
}
