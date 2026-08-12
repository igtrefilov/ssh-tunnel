package net.tref.xraytunnel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    private static final int REQUEST_BATTERY_OPTIMIZATIONS = 2;
    private static final int REQUEST_VPN_PERMISSION = 3;
    private static final int REQUEST_INSTALL_SOURCES = 4;

    private static final int COLOR_BACKGROUND = 0xFFF4F6F8;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF171A1F;
    private static final int COLOR_MUTED = 0xFF6F7682;
    private static final int COLOR_BORDER = 0xFFE1E5EA;
    private static final int COLOR_PRIMARY = 0xFF171A1F;
    private static final int COLOR_GREEN = 0xFF198754;
    private static final int COLOR_RED = 0xFFDC3545;
    private static final int COLOR_YELLOW = 0xFFFFC107;
    private static final int COLOR_GRAY = 0xFF8A8F98;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences statusPreferences;
    private View statusDot;
    private TextView statusView;
    private TextView statusDetailView;
    private TextView selectedAppsView;
    private Button settingsToggle;
    private LinearLayout settingsPanel;
    private LinearLayout sshHostsInput;
    private final List<ServerRow> serverRows = new ArrayList<>();
    private EditText sshUserInput;
    private EditText sshPortInput;
    private CheckBox jumpEnabledInput;
    private LinearLayout jumpSettingsPanel;
    private EditText jumpHostInput;
    private EditText jumpUserInput;
    private EditText jumpPortInput;
    private EditText proxyHostInput;
    private EditText proxyPortInput;
    private CheckBox verifyHostKeyInput;
    private String pendingNotificationAction;
    private String pendingVpnAction;
    private String pendingBatteryAction;
    private Button updateButton;
    private File pendingUpdateApk;

    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            refreshStatusViews();
            handler.postDelayed(this, 1000);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (preferences, key) -> {
                if (TunnelService.KEY_STATUS.equals(key)
                        || TunnelService.KEY_VPS_REACHABILITY.equals(key)) {
                    handler.post(this::refreshStatusViews);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(18);
        root.setPadding(pad, dp(34), pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, fullWidthWrapContent());

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTextColor(COLOR_TEXT);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1));

        TextView version = new TextView(this);
        version.setText(versionText());
        version.setTextSize(13);
        version.setTextColor(COLOR_MUTED);
        version.setPadding(0, dp(2), 0, 0);
        root.addView(version, fullWidthWrapContent());

        updateButton = styledButton(
                R.string.check_updates,
                Color.TRANSPARENT,
                COLOR_PRIMARY,
                COLOR_BORDER);
        updateButton.setOnClickListener(v -> checkForUpdates());
        LinearLayout.LayoutParams updateParams = fullWidthWrapContent();
        updateParams.setMargins(0, dp(10), 0, 0);
        root.addView(updateButton, updateParams);

        LinearLayout statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusPanel.setBackground(roundRect(COLOR_SURFACE, COLOR_BORDER, 1, 8));
        LinearLayout.LayoutParams statusParams = fullWidthWrapContent();
        statusParams.setMargins(0, dp(16), 0, dp(12));
        root.addView(statusPanel, statusParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusPanel.addView(statusRow, fullWidthWrapContent());

        statusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotParams.setMarginEnd(dp(12));
        statusRow.addView(statusDot, dotParams);

        statusView = new TextView(this);
        statusView.setTextSize(24);
        statusView.setTextColor(COLOR_TEXT);
        statusRow.addView(statusView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        statusDetailView = new TextView(this);
        statusDetailView.setTextSize(13);
        statusDetailView.setTextColor(COLOR_MUTED);
        statusDetailView.setPadding(dp(26), dp(4), 0, 0);
        statusPanel.addView(statusDetailView, fullWidthWrapContent());

        Button selectApps = styledButton(
                R.string.select_apps,
                Color.TRANSPARENT,
                COLOR_PRIMARY,
                COLOR_BORDER);
        selectApps.setOnClickListener(v -> startActivity(new Intent(this, AppSelectionActivity.class)));
        root.addView(selectApps, fullWidthWrapContent());

        selectedAppsView = new TextView(this);
        selectedAppsView.setTextSize(13);
        selectedAppsView.setTextColor(COLOR_MUTED);
        selectedAppsView.setPadding(dp(4), dp(6), dp(4), dp(10));
        root.addView(selectedAppsView, fullWidthWrapContent());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actionRow, fullWidthWrapContent());

        Button start = styledButton(R.string.button_start, COLOR_GREEN, Color.WHITE, COLOR_GREEN);
        start.setOnClickListener(v -> startTunnelService(TunnelService.ACTION_START));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        startParams.setMarginEnd(dp(8));
        actionRow.addView(start, startParams);

        Button stop = styledButton(R.string.button_stop, Color.TRANSPARENT, COLOR_RED, COLOR_RED);
        stop.setOnClickListener(v -> startTunnelService(TunnelService.ACTION_STOP));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        stopParams.setMarginStart(dp(8));
        actionRow.addView(stop, stopParams);

        addSettingsSection(root);
        setContentView(scroll);
        updateSelectedAppsText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusPreferences = getSharedPreferences(TunnelService.PREFS, MODE_PRIVATE);
        statusPreferences.registerOnSharedPreferenceChangeListener(statusListener);
        handler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        if (statusPreferences != null) {
            statusPreferences.unregisterOnSharedPreferenceChangeListener(statusListener);
            statusPreferences = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        updateExecutor.shutdownNow();
        super.onDestroy();
    }

    private void checkForUpdates() {
        if (updateButton == null || updateButton.isEnabled() == false) {
            return;
        }
        updateButton.setEnabled(false);
        updateButton.setText(R.string.checking_updates);
        long currentVersionCode = currentVersionCode();
        updateExecutor.execute(() -> {
            try {
                UpdateInfo update = UpdateChecker.findLatest(currentVersionCode);
                runOnUiThread(() -> {
                    resetUpdateButton();
                    if (update == null) {
                        Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_LONG).show();
                    } else {
                        showUpdateDialog(update);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    resetUpdateButton();
                    Toast.makeText(
                            this,
                            getString(R.string.update_check_failed, errorMessage(error)),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showUpdateDialog(UpdateInfo update) {
        String message = getString(R.string.update_message, update.versionName);
        if (!update.releaseNotes.isEmpty()) {
            message += "\n\n" + getString(R.string.update_notes, update.releaseNotes);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_available)
                .setMessage(message)
                .setNegativeButton(R.string.update_cancel, null)
                .setPositiveButton(R.string.update_install, (dialog, which) -> downloadUpdate(update))
                .show();
    }

    private void downloadUpdate(UpdateInfo update) {
        updateButton.setEnabled(false);
        updateButton.setText(R.string.update_downloading);
        updateExecutor.execute(() -> {
            try {
                File directory = new File(getCacheDir(), "updates");
                File apk = UpdateChecker.downloadAndVerify(update, directory);
                runOnUiThread(() -> {
                    resetUpdateButton();
                    pendingUpdateApk = apk;
                    launchApkInstaller(apk);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    resetUpdateButton();
                    Toast.makeText(
                            this,
                            getString(R.string.update_download_failed, errorMessage(error)),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void launchApkInstaller(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivityForResult(
                        new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())),
                        REQUEST_INSTALL_SOURCES);
                Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show();
            } catch (RuntimeException error) {
                pendingUpdateApk = null;
                Toast.makeText(
                        this,
                        getString(R.string.update_download_failed, errorMessage(error)),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        startApkInstaller(apk);
    }

    private void startApkInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.update_install_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void resetUpdateButton() {
        if (updateButton != null) {
            updateButton.setEnabled(true);
            updateButton.setText(R.string.check_updates);
        }
    }

    private long currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void startTunnelService(String action) {
        if (TunnelService.ACTION_STOP.equals(action)) {
            startService(new Intent(this, TunnelService.class).setAction(action));
            return;
        }
        if (TunnelSettings.allowedApplications(this).isEmpty()) {
            Toast.makeText(this, R.string.select_apps_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (shouldRequestNotifications()) {
            pendingNotificationAction = action;
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
            return;
        }
        requestVpnThenStart(action);
    }

    private void requestVpnThenStart(String action) {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            pendingVpnAction = action;
            startActivityForResult(prepare, REQUEST_VPN_PERMISSION);
            return;
        }
        requestBatteryThenStart(action);
    }

    private void requestBatteryThenStart(String action) {
        if (shouldRequestBatteryOptimization()) {
            pendingBatteryAction = action;
            try {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATIONS);
                return;
            } catch (RuntimeException ignored) {
                pendingBatteryAction = null;
            }
        }
        startTunnelServiceNow(action);
    }

    private void startTunnelServiceNow(String action) {
        Intent intent = new Intent(this, TunnelService.class).setAction(action);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS || pendingNotificationAction == null) {
            return;
        }
        String action = pendingNotificationAction;
        pendingNotificationAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestVpnThenStart(action);
        } else {
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_SOURCES && pendingUpdateApk != null) {
            File apk = pendingUpdateApk;
            pendingUpdateApk = null;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls()) {
                startApkInstaller(apk);
            } else {
                Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_VPN_PERMISSION) {
            String action = pendingVpnAction;
            pendingVpnAction = null;
            if (resultCode == RESULT_OK && action != null) {
                requestBatteryThenStart(action);
            } else {
                Toast.makeText(this, R.string.vpn_permission_required, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_BATTERY_OPTIMIZATIONS && pendingBatteryAction != null) {
            String action = pendingBatteryAction;
            pendingBatteryAction = null;
            startTunnelServiceNow(action);
        }
    }

    private boolean shouldRequestNotifications() {
        return android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldRequestBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return false;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null
                && !powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void addSettingsSection(LinearLayout root) {
        TunnelSettings.Values values = TunnelSettings.loadValues(this);
        settingsToggle = styledButton(
                R.string.settings_title,
                Color.TRANSPARENT,
                COLOR_PRIMARY,
                COLOR_BORDER);
        settingsToggle.setOnClickListener(v -> toggleSettings());
        LinearLayout.LayoutParams toggleParams = fullWidthWrapContent();
        toggleParams.setMargins(0, dp(12), 0, 0);
        root.addView(settingsToggle, toggleParams);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(14), dp(12), dp(14), dp(14));
        settingsPanel.setBackground(roundRect(COLOR_SURFACE, COLOR_BORDER, 1, 8));
        settingsPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams panelParams = fullWidthWrapContent();
        panelParams.setMargins(0, dp(10), 0, 0);
        root.addView(settingsPanel, panelParams);

        TextView serversLabel = new TextView(this);
        serversLabel.setText(R.string.settings_vps_servers);
        serversLabel.setTextSize(12);
        serversLabel.setTextColor(COLOR_MUTED);
        serversLabel.setPadding(0, dp(8), 0, dp(2));
        settingsPanel.addView(serversLabel, fullWidthWrapContent());

        sshHostsInput = new LinearLayout(this);
        sshHostsInput.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.addView(sshHostsInput, fullWidthWrapContent());
        populateServerRows(values);

        Button addServer = styledButton(
                R.string.settings_add_server,
                Color.TRANSPARENT,
                COLOR_PRIMARY,
                COLOR_BORDER);
        addServer.setOnClickListener(v -> addServerRow("", false));
        LinearLayout.LayoutParams addServerParams = fullWidthWrapContent();
        addServerParams.setMargins(0, dp(6), 0, 0);
        settingsPanel.addView(addServer, addServerParams);

        sshUserInput = addTextInput(settingsPanel, R.string.settings_ssh_user, values.sshUser, textInputType());
        sshPortInput = addTextInput(
                settingsPanel,
                R.string.settings_ssh_port,
                String.valueOf(values.sshPort),
                portInputType());

        jumpEnabledInput = new CheckBox(this);
        jumpEnabledInput.setText(R.string.settings_jump_enabled);
        jumpEnabledInput.setTextColor(COLOR_TEXT);
        jumpEnabledInput.setChecked(values.jumpEnabled);
        jumpEnabledInput.setOnCheckedChangeListener(
                (buttonView, isChecked) -> updateJumpFieldsVisibility());
        settingsPanel.addView(jumpEnabledInput, fullWidthWrapContent());

        jumpSettingsPanel = new LinearLayout(this);
        jumpSettingsPanel.setOrientation(LinearLayout.VERTICAL);
        jumpSettingsPanel.setPadding(dp(12), 0, 0, dp(4));
        settingsPanel.addView(jumpSettingsPanel, fullWidthWrapContent());
        jumpHostInput = addTextInput(
                jumpSettingsPanel,
                R.string.settings_jump_host,
                values.jumpHost,
                textInputType());
        jumpUserInput = addTextInput(
                jumpSettingsPanel,
                R.string.settings_jump_user,
                values.jumpUser,
                textInputType());
        jumpPortInput = addTextInput(
                jumpSettingsPanel,
                R.string.settings_jump_port,
                String.valueOf(values.jumpPort),
                portInputType());
        updateJumpFieldsVisibility();

        proxyHostInput = addTextInput(
                settingsPanel,
                R.string.settings_proxy_host,
                values.proxyHost,
                textInputType());
        proxyPortInput = addTextInput(
                settingsPanel,
                R.string.settings_proxy_port,
                String.valueOf(values.proxyPort),
                portInputType());

        verifyHostKeyInput = new CheckBox(this);
        verifyHostKeyInput.setText(R.string.settings_verify_host_key);
        verifyHostKeyInput.setTextColor(COLOR_TEXT);
        verifyHostKeyInput.setChecked(values.verifyHostKey);
        settingsPanel.addView(verifyHostKeyInput, fullWidthWrapContent());

        LinearLayout settingsActions = new LinearLayout(this);
        settingsActions.setOrientation(LinearLayout.HORIZONTAL);
        settingsActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = fullWidthWrapContent();
        actionsParams.setMargins(0, dp(10), 0, 0);
        settingsPanel.addView(settingsActions, actionsParams);

        Button save = styledButton(R.string.settings_save, COLOR_PRIMARY, Color.WHITE, COLOR_PRIMARY);
        save.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        saveParams.setMarginEnd(dp(8));
        settingsActions.addView(save, saveParams);

        Button reset = styledButton(R.string.settings_reset, Color.TRANSPARENT, COLOR_MUTED, COLOR_BORDER);
        reset.setOnClickListener(v -> resetSettings());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        resetParams.setMarginStart(dp(8));
        settingsActions.addView(reset, resetParams);
    }

    private EditText addTextInput(LinearLayout root, int labelResId, String value, int inputType) {
        TextView labelView = new TextView(this);
        labelView.setText(labelResId);
        labelView.setTextSize(12);
        labelView.setTextColor(COLOR_MUTED);
        labelView.setPadding(0, dp(8), 0, dp(2));
        root.addView(labelView, fullWidthWrapContent());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextColor(COLOR_TEXT);
        input.setTextSize(15);
        input.setInputType(inputType);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(roundRect(0xFFF8FAFC, COLOR_BORDER, 1, 6));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        return input;
    }

    private void saveSettings() {
        try {
            TunnelSettings.Values values = readSettingsFromInputs();
            TunnelSettings.saveValues(this, values);
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetSettings() {
        TunnelSettings.Values defaults = TunnelSettings.defaultValues();
        populateSettings(defaults);
        TunnelSettings.saveValues(this, defaults);
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_LONG).show();
    }

    private TunnelSettings.Values readSettingsFromInputs() {
        List<String> hosts = new ArrayList<>();
        Set<String> uniqueHosts = new HashSet<>();
        int activeIndex = -1;
        for (int i = 0; i < serverRows.size(); i++) {
            String host = requiredText(serverRows.get(i).hostInput, "Gateway address");
            if (!uniqueHosts.add(host)) {
                throw new IllegalArgumentException("Gateway addresses must be unique");
            }
            hosts.add(host);
            if (serverRows.get(i).activeInput.isChecked()) {
                activeIndex = i;
            }
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("At least one gateway address required");
        }
        if (activeIndex < 0) {
            throw new IllegalArgumentException("Select an active gateway");
        }
        return new TunnelSettings.Values(
                hosts,
                activeIndex,
                requiredText(sshUserInput, "SSH user"),
                parsePort(sshPortInput, "SSH port"),
                requiredText(proxyHostInput, "Proxy host"),
                parsePort(proxyPortInput, "Proxy port"),
                verifyHostKeyInput.isChecked(),
                jumpEnabledInput.isChecked(),
                jumpEnabledInput.isChecked()
                        ? requiredText(jumpHostInput, "Jump host")
                        : jumpHostInput.getText().toString().trim(),
                jumpEnabledInput.isChecked()
                        ? requiredText(jumpUserInput, "Jump user")
                        : jumpUserInput.getText().toString().trim(),
                jumpEnabledInput.isChecked()
                        ? parsePort(jumpPortInput, "Jump port")
                        : TunnelConfig.DEFAULT_JUMP_PORT,
                TunnelSettings.allowedApplications(this));
    }

    private void populateSettings(TunnelSettings.Values values) {
        populateServerRows(values);
        sshUserInput.setText(values.sshUser);
        sshPortInput.setText(String.valueOf(values.sshPort));
        jumpEnabledInput.setChecked(values.jumpEnabled);
        jumpHostInput.setText(values.jumpHost);
        jumpUserInput.setText(values.jumpUser);
        jumpPortInput.setText(String.valueOf(values.jumpPort));
        updateJumpFieldsVisibility();
        proxyHostInput.setText(values.proxyHost);
        proxyPortInput.setText(String.valueOf(values.proxyPort));
        verifyHostKeyInput.setChecked(values.verifyHostKey);
    }

    private void populateServerRows(TunnelSettings.Values values) {
        if (sshHostsInput == null) {
            return;
        }
        sshHostsInput.removeAllViews();
        serverRows.clear();
        for (int i = 0; i < values.sshHosts.size(); i++) {
            addServerRow(values.sshHosts.get(i), i == values.activeSshIndex);
        }
    }

    private void addServerRow(String host, boolean active) {
        if (sshHostsInput == null) {
            return;
        }

        ServerRow row = new ServerRow();
        row.container = new LinearLayout(this);
        row.container.setOrientation(LinearLayout.HORIZONTAL);
        row.container.setGravity(Gravity.CENTER_VERTICAL);

        row.activeInput = new RadioButton(this);
        row.activeInput.setContentDescription(getString(R.string.settings_active_server));
        row.activeInput.setChecked(active || serverRows.isEmpty());
        row.container.addView(row.activeInput, new LinearLayout.LayoutParams(dp(42), dp(48)));

        row.hostInput = new EditText(this);
        row.hostInput.setSingleLine(true);
        row.hostInput.setText(host);
        row.hostInput.setHint(R.string.settings_vps_ip_hint);
        row.hostInput.setTextColor(COLOR_TEXT);
        row.hostInput.setTextSize(15);
        row.hostInput.setInputType(textInputType());
        row.hostInput.setPadding(dp(10), 0, dp(10), 0);
        row.hostInput.setBackground(roundRect(0xFFF8FAFC, COLOR_BORDER, 1, 6));
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        hostParams.setMarginStart(dp(4));
        hostParams.setMarginEnd(dp(4));
        row.container.addView(row.hostInput, hostParams);

        row.removeButton = styledButton(
                R.string.settings_remove_server,
                Color.TRANSPARENT,
                COLOR_RED,
                COLOR_BORDER);
        row.removeButton.setContentDescription(getString(R.string.settings_remove_server));
        row.removeButton.setPadding(0, 0, 0, 0);
        row.removeButton.setText("−");
        row.container.addView(row.removeButton, new LinearLayout.LayoutParams(dp(42), dp(42)));

        row.activeInput.setOnClickListener(v -> selectServerRow(row));
        row.removeButton.setOnClickListener(v -> removeServerRow(row));
        serverRows.add(row);
        sshHostsInput.addView(row.container, fullWidthWrapContent());
        if (row.activeInput.isChecked()) {
            selectServerRow(row);
        }
    }

    private void selectServerRow(ServerRow selected) {
        for (ServerRow row : serverRows) {
            row.activeInput.setChecked(row == selected);
        }
    }

    private void removeServerRow(ServerRow row) {
        if (serverRows.size() <= 1) {
            Toast.makeText(this, R.string.settings_last_server_required, Toast.LENGTH_LONG).show();
            return;
        }
        serverRows.remove(row);
        sshHostsInput.removeView(row.container);
        // Do not silently switch the selected server.  If the active row was
        // removed, the user must explicitly choose another one before saving.
    }

    private static final class ServerRow {
        LinearLayout container;
        RadioButton activeInput;
        EditText hostInput;
        Button removeButton;
    }

    private void refreshStatusViews() {
        if (statusView == null || statusDot == null) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(TunnelService.PREFS, MODE_PRIVATE);
        String status = prefs.getString(TunnelService.KEY_STATUS, TunnelService.STATUS_STOPPED);
        int reachability = prefs.getInt(
                TunnelService.KEY_VPS_REACHABILITY,
                TunnelService.REACHABILITY_UNKNOWN);
        statusView.setText(status);
        updateStatusDot(status, reachability);
        updateSelectedAppsText();
    }

    private void updateSelectedAppsText() {
        if (selectedAppsView == null) {
            return;
        }
        Set<String> apps = TunnelSettings.allowedApplications(this);
        selectedAppsView.setText(getString(R.string.selected_apps_count, apps.size()));
        statusDetailView.setText(getString(R.string.vpn_mode_selected_apps, apps.size()));
    }

    private String requiredText(EditText input, String label) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " required");
        }
        return value;
    }

    private int parsePort(EditText input, String label) {
        String value = requiredText(input, label);
        try {
            int port = Integer.parseInt(value);
            if (TunnelSettings.isValidPort(port)) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to a single validation message.
        }
        throw new IllegalArgumentException(label + ": 1-65535");
    }

    private int textInputType() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
    }

    private int portInputType() {
        return InputType.TYPE_CLASS_NUMBER;
    }

    private void updateJumpFieldsVisibility() {
        if (jumpSettingsPanel != null && jumpEnabledInput != null) {
            jumpSettingsPanel.setVisibility(
                    jumpEnabledInput.isChecked() ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout.LayoutParams fullWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void toggleSettings() {
        boolean show = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        settingsToggle.setText(show ? R.string.settings_hide : R.string.settings_title);
    }

    private Button styledButton(int textResId, int backgroundColor, int textColor, int strokeColor) {
        Button button = new Button(this);
        button.setText(textResId);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(roundRect(backgroundColor, strokeColor, 1, 8));
        return button;
    }

    private GradientDrawable roundRect(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private void updateStatusDot(String status, int reachability) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(statusDotColor(status, reachability));
        statusDot.setBackground(drawable);
    }

    private int statusDotColor(String status, int reachability) {
        if (TunnelService.STATUS_ONLINE.equals(status)
                || reachability == TunnelService.REACHABILITY_REACHABLE) {
            return COLOR_GREEN;
        }
        if (TunnelService.STATUS_CHEBURNET.equals(status)
                || reachability == TunnelService.REACHABILITY_DEGRADED) {
            return COLOR_YELLOW;
        }
        if (TunnelService.STATUS_OFFLINE.equals(status)) {
            return COLOR_GRAY;
        }
        if (TunnelService.STATUS_VPS_DOWN.equals(status)
                || TunnelService.STATUS_TUNNEL_DOWN.equals(status)
                || reachability == TunnelService.REACHABILITY_UNREACHABLE) {
            return COLOR_RED;
        }
        return COLOR_GRAY;
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : "Version " + info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
