package com.birdproxy.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Settings activity for configuring the SOCKS5 proxy server.
 * Uses TextInputEditText with proper focus and input handling
 * for compatibility with OxygenOS (OnePlus) and other custom ROMs.
 */
public class Settings extends AppCompatActivity {

    // Views
    private TextInputEditText serverInput;
    private TextInputEditText portInput;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private RadioGroup proxyModeGroup;
    private MaterialButton saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        // Initialize views
        serverInput = findViewById(R.id.serverInput);
        portInput = findViewById(R.id.portInput);
        usernameInput = findViewById(R.id.usernameInput);
        passwordInput = findViewById(R.id.passwordInput);
        proxyModeGroup = findViewById(R.id.proxyModeGroup);
        saveButton = findViewById(R.id.saveSettings);

        setupInputFields();
        loadCurrentSettings();
        setupSaveButton();
    }

    /**
     * Configure input fields for maximum compatibility with OxygenOS (OnePlus)
     * and other Android custom ROMs.
     *
     * The key fixes for OxygenOS:
     * - Set explicit focusable/focusableInTouchMode
     * - Use proper TextWatcher for input tracking
     * - Ensure IME options are set correctly
     * - Clear focus properly to prevent ghost cursor issues
     */
    private void setupInputFields() {
        // Server input
        setupEditText(serverInput);
        setupEditText(portInput);
        setupEditText(usernameInput);
        setupEditText(passwordInput);

        // Set IME options for navigation
        serverInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        portInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        usernameInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        passwordInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        // Handle IME action for password field (Done = hide keyboard)
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v);
                v.clearFocus();
                return true;
            }
            return false;
        });
    }

    /**
     * Setup an individual EditText with proper focus handling for OxygenOS.
     * The key issues on OxygenOS are:
     * 1. EditText fields may not properly capture touch events
     * 2. Cursor may not show correctly
     * 3. Keyboard may not appear
     *
     * Fix: Ensure proper focus handling and click listeners.
     */
    private void setupEditText(TextInputEditText editText) {
        // Ensure the EditText is focusable (some OxygenOS versions disable this)
        editText.setFocusable(true);
        editText.setFocusableInTouchMode(true);
        editText.setClickable(true);
        editText.setLongClickable(true);

        // Force cursor visibility
        editText.setCursorVisible(true);

        // Add text change listener to track input
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Ensure click triggers focus properly on OxygenOS
        editText.setOnClickListener(v -> {
            // Force focus on click (needed on some OxygenOS versions)
            if (!v.isFocused()) {
                v.requestFocus();
                showKeyboard(v);
            }
        });

        // Handle focus change for OxygenOS
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // Position cursor at the end of text
                if (editText.getText() != null) {
                    editText.setSelection(editText.getText().length());
                }
            }
        });
    }

    /**
     * Show the soft keyboard for an EditText.
     */
    private void showKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
     * Hide the soft keyboard.
     */
    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Load current settings from SharedPreferences and populate the fields.
     */
    private void loadCurrentSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String server = prefs.getString("proxy_server", "");
        String port = prefs.getString("proxy_port", "1080");
        String username = prefs.getString("proxy_username", "");
        String password = prefs.getString("proxy_password", "");
        boolean allApps = prefs.getBoolean("proxy_all_apps", true);

        serverInput.setText(server);
        portInput.setText(port);
        usernameInput.setText(username);
        passwordInput.setText(password);

        if (allApps) {
            proxyModeGroup.check(R.id.proxyModeAll);
        } else {
            proxyModeGroup.check(R.id.proxyModeSelected);
        }
    }

    /**
     * Setup the save button to persist settings.
     */
    private void setupSaveButton() {
        saveButton.setOnClickListener(v -> {
            // Validate inputs
            String server = serverInput.getText() != null ?
                    serverInput.getText().toString().trim() : "";
            String portStr = portInput.getText() != null ?
                    portInput.getText().toString().trim() : "";

            if (server.isEmpty()) {
                serverInput.setError("Server address is required");
                serverInput.requestFocus();
                return;
            }

            if (portStr.isEmpty()) {
                portInput.setError("Port is required");
                portInput.requestFocus();
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
                if (port < 1 || port > 65535) {
                    portInput.setError("Port must be between 1 and 65535");
                    portInput.requestFocus();
                    return;
                }
            } catch (NumberFormatException e) {
                portInput.setError("Invalid port number");
                portInput.requestFocus();
                return;
            }

            String username = usernameInput.getText() != null ?
                    usernameInput.getText().toString().trim() : "";
            String password = passwordInput.getText() != null ?
                    passwordInput.getText().toString() : "";

            boolean allApps = proxyModeGroup.getCheckedRadioButtonId() == R.id.proxyModeAll;

            // Save settings
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("proxy_server", server);
            editor.putString("proxy_port", portStr);
            editor.putString("proxy_username", username);
            editor.putString("proxy_password", password);
            editor.putBoolean("proxy_all_apps", allApps);
            editor.apply();

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();

            // Hide keyboard and clear focus
            hideKeyboard(v);
            serverInput.clearFocus();
            portInput.clearFocus();
            usernameInput.clearFocus();
            passwordInput.clearFocus();

            finish();
        });
    }
}
