package net.tref.sshtunnel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Named snapshots; the existing flat preferences remain the current connection settings.
 * Keeping both in one editor makes switching atomic and preserves pre-profile installations.
 */
final class SavedProfiles {
    private static final String KEY_PROFILES = "saved_profiles_v1";
    private static final String KEY_ACTIVE = "active_profile_id";
    static final int MAX_NAME_LENGTH = 80;

    private SavedProfiles() { }

    static final class Entry {
        final String id;
        final String name;
        final TunnelSettings.Values values;

        Entry(String id, String name, TunnelSettings.Values values) {
            this.id = id;
            this.name = name;
            this.values = values;
        }
    }

    static List<Entry> list(Context context) {
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONArray array = new JSONArray(TunnelSettings.prefs(context).getString(KEY_PROFILES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONObject object = array.getJSONObject(i);
                    String id = object.getString("id");
                    String name = object.getString("name").trim();
                    TunnelSettings.Values values = decodeValues(object.getJSONObject("settings"));
                    if (!id.isEmpty() && !name.isEmpty() && ids.add(id)) {
                        entries.add(new Entry(id, name, values));
                    }
                } catch (JSONException | IllegalArgumentException error) {
                    Log.w("SavedProfiles", "Skipping an unreadable saved profile");
                }
            }
        } catch (JSONException error) {
            Log.w("SavedProfiles", "Cannot read saved profiles; current settings are preserved");
        }
        return entries;
    }

    static Entry active(Context context) {
        String id = TunnelSettings.prefs(context).getString(KEY_ACTIVE, "");
        for (Entry entry : list(context)) if (entry.id.equals(id)) return entry;
        return null;
    }

    static synchronized Entry saveAs(Context context, String name, TunnelSettings.Values values) {
        List<Entry> entries = list(context);
        String checked = checkedName(context, entries, name, null);
        Entry entry = new Entry(UUID.randomUUID().toString(), checked, values);
        entries.add(entry);
        TunnelSettings.writeValues(TunnelSettings.prefs(context).edit(), values)
                .putString(KEY_PROFILES, encode(entries))
                .putString(KEY_ACTIVE, entry.id).apply();
        return entry;
    }

    static synchronized void saveCurrent(Context context, TunnelSettings.Values values) {
        SharedPreferences prefs = TunnelSettings.prefs(context);
        SharedPreferences.Editor editor = TunnelSettings.writeValues(prefs.edit(), values);
        String id = prefs.getString(KEY_ACTIVE, "");
        List<Entry> entries = list(context);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.id.equals(id)) {
                entries.set(i, new Entry(id, entry.name, values));
                editor.putString(KEY_PROFILES, encode(entries));
                break;
            }
        }
        editor.apply();
    }

    static synchronized void activate(Context context, String id) {
        Entry entry = requireEntry(context, list(context), id);
        TunnelSettings.writeValues(TunnelSettings.prefs(context).edit(), entry.values)
                .putString(KEY_ACTIVE, entry.id).apply();
    }

    static synchronized void rename(Context context, String id, String name) {
        List<Entry> entries = list(context);
        Entry entry = requireEntry(context, entries, id);
        String checked = checkedName(context, entries, name, id);
        entries.set(entries.indexOf(entry), new Entry(id, checked, entry.values));
        TunnelSettings.prefs(context).edit().putString(KEY_PROFILES, encode(entries)).apply();
    }

    static synchronized void delete(Context context, String id) {
        List<Entry> entries = list(context);
        entries.remove(requireEntry(context, entries, id));
        SharedPreferences prefs = TunnelSettings.prefs(context);
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_PROFILES, encode(entries));
        if (id.equals(prefs.getString(KEY_ACTIVE, ""))) editor.remove(KEY_ACTIVE);
        editor.apply();
    }

    static synchronized void resetCurrent(Context context) {
        // Reset only the working settings. Saved profiles and the app selection survive.
        TunnelSettings.Values values = TunnelSettings.defaultValues()
                .withApplications(TunnelSettings.allowedApplications(context));
        TunnelSettings.writeValues(TunnelSettings.prefs(context).edit(), values)
                .remove(KEY_ACTIVE).apply();
    }

    private static Entry requireEntry(Context context, List<Entry> entries, String id) {
        for (Entry entry : entries) if (entry.id.equals(id)) return entry;
        throw new IllegalArgumentException(context.getString(R.string.profile_missing));
    }

    private static String checkedName(Context context, List<Entry> entries, String name, String id) {
        String checked = name == null ? "" : name.trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(context.getString(R.string.profile_name_required));
        if (checked.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(context.getString(R.string.profile_name_too_long, MAX_NAME_LENGTH));
        }
        for (Entry entry : entries) {
            if (!entry.id.equals(id) && entry.name.equalsIgnoreCase(checked)) {
                throw new IllegalArgumentException(context.getString(R.string.profile_name_exists));
            }
        }
        return checked;
    }

    private static String encode(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(new JSONObject().put("id", entry.id).put("name", entry.name)
                        .put("settings", encodeValues(entry.values)));
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Cannot encode connection profiles", error);
        }
        return array.toString();
    }

    private static JSONObject encodeValues(TunnelSettings.Values values) throws JSONException {
        return new JSONObject()
                .put("hosts", new JSONArray(values.sshHosts))
                .put("activeIndex", values.activeSshIndex)
                .put("sshUser", values.sshUser).put("sshPort", values.sshPort)
                .put("proxyHost", values.proxyHost).put("proxyPort", values.proxyPort)
                .put("verifyHostKey", values.verifyHostKey)
                .put("jumpEnabled", values.jumpEnabled).put("jumpHost", values.jumpHost)
                .put("jumpUser", values.jumpUser).put("jumpPort", values.jumpPort)
                .put("applications", new JSONArray(values.allowedApplications));
    }

    private static TunnelSettings.Values decodeValues(JSONObject object) throws JSONException {
        List<String> hosts = strings(object.getJSONArray("hosts"));
        int activeIndex = object.getInt("activeIndex");
        if (hosts.isEmpty() || activeIndex < 0 || activeIndex >= hosts.size()
                || new HashSet<>(hosts).size() != hosts.size()) {
            throw new JSONException("Invalid gateway selection");
        }
        return new TunnelSettings.Values(hosts, activeIndex,
                requiredString(object, "sshUser"), port(object, "sshPort"),
                requiredString(object, "proxyHost"), port(object, "proxyPort"),
                object.getBoolean("verifyHostKey"), object.getBoolean("jumpEnabled"),
                object.getString("jumpHost"), object.getString("jumpUser"), port(object, "jumpPort"),
                new HashSet<>(strings(object.getJSONArray("applications"))));
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.getString(i).trim();
            if (value.isEmpty()) throw new JSONException("Empty value");
            values.add(value);
        }
        return values;
    }

    private static String requiredString(JSONObject object, String key) throws JSONException {
        String value = object.getString(key).trim();
        if (value.isEmpty()) throw new JSONException("Missing " + key);
        return value;
    }

    private static int port(JSONObject object, String key) throws JSONException {
        int port = object.getInt(key);
        if (!TunnelSettings.isValidPort(port)) throw new JSONException("Invalid " + key);
        return port;
    }
}
