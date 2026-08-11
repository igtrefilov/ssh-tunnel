package net.tref.xraytunnel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TunnelSettings {
    static final String PREFS = "tunnel";
    static final String KEY_STATUS = "status";
    static final String KEY_VPS_REACHABILITY = "vps_reachability";
    static final int REACHABILITY_UNKNOWN = 0;
    static final int REACHABILITY_REACHABLE = 1;
    static final int REACHABILITY_UNREACHABLE = 2;
    static final int REACHABILITY_DEGRADED = 3;

    private static final String KEY_SSH_HOST = "ssh_host";
    private static final String KEY_SSH_HOSTS = "ssh_hosts";
    private static final String KEY_ACTIVE_SSH_HOST = "active_ssh_host";
    private static final String KEY_SSH_USER = "ssh_user";
    private static final String KEY_SSH_PORT = "ssh_port";
    private static final String KEY_PROXY_HOST = "proxy_host";
    private static final String KEY_PROXY_PORT = "proxy_port";
    private static final String KEY_VERIFY_HOST_KEY = "verify_host_key";
    private static final String KEY_JUMP_ENABLED = "jump_enabled";
    private static final String KEY_JUMP_HOST = "jump_host";
    private static final String KEY_JUMP_USER = "jump_user";
    private static final String KEY_JUMP_PORT = "jump_port";
    static final String KEY_ALLOWED_APPLICATIONS = "allowed_applications";

    private TunnelSettings() {
    }

    static Values defaultValues() {
        return new Values(
                Collections.singletonList(TunnelConfig.DEFAULT_SSH_HOST),
                0,
                TunnelConfig.DEFAULT_SSH_USER,
                TunnelConfig.DEFAULT_SSH_PORT,
                TunnelConfig.DEFAULT_PROXY_HOST,
                TunnelConfig.DEFAULT_PROXY_PORT,
                true,
                false,
                "",
                "",
                TunnelConfig.DEFAULT_JUMP_PORT,
                Collections.emptySet());
    }

    static Values loadValues(Context context) {
        SharedPreferences prefs = prefs(context);
        Values defaults = defaultValues();
        List<String> hosts = readHosts(prefs, defaults.sshHosts);
        String activeHost = readString(
                prefs,
                KEY_ACTIVE_SSH_HOST,
                readString(prefs, KEY_SSH_HOST, defaults.sshHost));
        int activeIndex = hosts.indexOf(activeHost);
        if (activeIndex < 0) {
            activeIndex = 0;
        }
        return new Values(
                hosts,
                activeIndex,
                readString(prefs, KEY_SSH_USER, defaults.sshUser),
                readPort(prefs, KEY_SSH_PORT, defaults.sshPort),
                readString(prefs, KEY_PROXY_HOST, defaults.proxyHost),
                readPort(prefs, KEY_PROXY_PORT, defaults.proxyPort),
                prefs.getBoolean(KEY_VERIFY_HOST_KEY, defaults.verifyHostKey),
                prefs.getBoolean(KEY_JUMP_ENABLED, defaults.jumpEnabled),
                readOptionalString(prefs, KEY_JUMP_HOST, defaults.jumpHost),
                readOptionalString(prefs, KEY_JUMP_USER, defaults.jumpUser),
                readPort(prefs, KEY_JUMP_PORT, defaults.jumpPort),
                prefs.getStringSet(KEY_ALLOWED_APPLICATIONS, Collections.emptySet()));
    }

    static void saveValues(Context context, Values values) {
        prefs(context)
                .edit()
                .putString(KEY_SSH_HOSTS, serializeHosts(values.sshHosts))
                // Keep the legacy key in sync for older builds and migrations.
                .putString(KEY_SSH_HOST, values.sshHost)
                .putString(KEY_ACTIVE_SSH_HOST, values.sshHost)
                .putString(KEY_SSH_USER, values.sshUser)
                .putInt(KEY_SSH_PORT, values.sshPort)
                .putString(KEY_PROXY_HOST, values.proxyHost)
                .putInt(KEY_PROXY_PORT, values.proxyPort)
                .putBoolean(KEY_VERIFY_HOST_KEY, values.verifyHostKey)
                .putBoolean(KEY_JUMP_ENABLED, values.jumpEnabled)
                .putString(KEY_JUMP_HOST, values.jumpHost)
                .putString(KEY_JUMP_USER, values.jumpUser)
                .putInt(KEY_JUMP_PORT, values.jumpPort)
                .apply();
    }

    static Set<String> allowedApplications(Context context) {
        return new HashSet<>(prefs(context).getStringSet(
                KEY_ALLOWED_APPLICATIONS,
                Collections.emptySet()));
    }

    static void saveAllowedApplications(Context context, Set<String> applications) {
        prefs(context).edit()
                .putStringSet(KEY_ALLOWED_APPLICATIONS, new HashSet<>(applications))
                .apply();
    }

    static TunnelProfile[] profiles(Context context) {
        return new TunnelProfile[] {loadValues(context).toProfile()};
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> readHosts(
            SharedPreferences prefs,
            List<String> defaultHosts) {
        String encoded = prefs.getString(KEY_SSH_HOSTS, null);
        if (encoded != null) {
            try {
                JSONArray array = new JSONArray(encoded);
                LinkedHashSet<String> uniqueHosts = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    String host = array.optString(i, "").trim();
                    if (!host.isEmpty()) {
                        uniqueHosts.add(host);
                    }
                }
                if (!uniqueHosts.isEmpty()) {
                    return new ArrayList<>(uniqueHosts);
                }
            } catch (JSONException ignored) {
                // Fall back to the legacy single-host setting.
            }
        }

        String legacyHost = readString(prefs, KEY_SSH_HOST, defaultHosts.get(0));
        return Collections.singletonList(legacyHost);
    }

    private static String serializeHosts(List<String> hosts) {
        JSONArray array = new JSONArray();
        for (String host : hosts) {
            array.put(host);
        }
        return array.toString();
    }

    private static String readString(SharedPreferences prefs, String key, String defaultValue) {
        String value = prefs.getString(key, defaultValue);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String readOptionalString(
            SharedPreferences prefs,
            String key,
            String defaultValue) {
        String value = prefs.getString(key, defaultValue);
        return value == null ? defaultValue : value.trim();
    }

    private static int readPort(SharedPreferences prefs, String key, int defaultValue) {
        int value = prefs.getInt(key, defaultValue);
        if (isValidPort(value)) {
            return value;
        }
        return defaultValue;
    }

    static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    static final class Values {
        final List<String> sshHosts;
        final int activeSshIndex;
        final String sshHost;
        final String sshUser;
        final int sshPort;
        final String proxyHost;
        final int proxyPort;
        final boolean verifyHostKey;
        final boolean jumpEnabled;
        final String jumpHost;
        final String jumpUser;
        final int jumpPort;
        final Set<String> allowedApplications;

        Values(
                List<String> sshHosts,
                int activeSshIndex,
                String sshUser,
                int sshPort,
                String proxyHost,
                int proxyPort,
                boolean verifyHostKey,
                boolean jumpEnabled,
                String jumpHost,
                String jumpUser,
                int jumpPort,
                Set<String> allowedApplications) {
            if (sshHosts == null || sshHosts.isEmpty()) {
                throw new IllegalArgumentException("At least one VPS host is required");
            }
            this.sshHosts = Collections.unmodifiableList(new ArrayList<>(sshHosts));
            this.activeSshIndex = Math.max(0, Math.min(activeSshIndex, this.sshHosts.size() - 1));
            this.sshHost = this.sshHosts.get(this.activeSshIndex);
            this.sshUser = sshUser;
            this.sshPort = sshPort;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.verifyHostKey = verifyHostKey;
            this.jumpEnabled = jumpEnabled;
            this.jumpHost = jumpHost == null ? "" : jumpHost.trim();
            this.jumpUser = jumpUser == null ? "" : jumpUser.trim();
            this.jumpPort = jumpPort;
            if (jumpEnabled && (this.jumpHost.isEmpty() || this.jumpUser.isEmpty())) {
                throw new IllegalArgumentException(
                        "Jump host and user are required when jump mode is enabled");
            }
            if (!isValidPort(jumpPort)) {
                throw new IllegalArgumentException("Invalid jump port");
            }
            this.allowedApplications = new HashSet<>(allowedApplications);
        }

        TunnelProfile toProfile() {
            return new TunnelProfile(
                    sshHost,
                    sshUser,
                    sshPort,
                    proxyHost,
                    proxyPort,
                    TunnelConfig.PRIVATE_KEY_ASSET,
                    verifyHostKey,
                    jumpEnabled,
                    jumpHost,
                    jumpUser,
                    jumpPort,
                    TunnelConfig.PRIVATE_KEY_ASSET,
                    allowedApplications);
        }
    }
}
