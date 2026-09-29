package net.tref.sshtunnel;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.Assert.*;

/** Real Android preferences/JSON, isolated from the installed app's settings. */
@RunWith(AndroidJUnit4.class)
public class SavedProfilesTest {
    private Context context;

    @Before public void setUp() {
        String suffix = UUID.randomUUID().toString();
        context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name + "-profile-test-" + suffix, mode);
            }
        };
    }

    @After public void tearDown() {
        assertTrue(TunnelSettings.prefs(context).edit().clear().commit());
    }

    private TunnelSettings.Values route(boolean jump) {
        return new TunnelSettings.Values(Arrays.asList("first.example", "second.example"), jump ? 1 : 0,
                jump ? "remote" : "direct", jump ? 2222 : 2223, "127.0.0.1", jump ? 1080 : 1081,
                true, jump, "jump.example", "jump-user", 22,
                new HashSet<>(Arrays.asList(jump ? "app.a" : "app.b", "app.shared")));
    }

    @Test public void existingSettingsSurviveUntilUserChoosesAName() {
        SharedPreferences preferences = TunnelSettings.prefs(context);
        preferences.edit().putString("ssh_hosts", "[\"old.example\",\"other.example\"]")
                .putString("active_ssh_host", "other.example").putString("ssh_user", "existing")
                .putInt("ssh_port", 2222).putBoolean("jump_enabled", true)
                .putString("jump_host", "existing-jump").putString("jump_user", "existing-user")
                .putStringSet("allowed_applications", Collections.singleton("app.old")).apply();
        TunnelSettings.Values before = TunnelSettings.loadValues(context);
        assertTrue(SavedProfiles.list(context).isEmpty());
        assertNull(SavedProfiles.active(context));
        assertEquals("other.example", before.sshHost);
        assertTrue(before.jumpEnabled);
        SavedProfiles.Entry entry = SavedProfiles.saveAs(context, "  Мой профиль ✈  ", before);
        assertEquals("Мой профиль ✈", SavedProfiles.active(context).name);
        assertEquals(before, TunnelSettings.loadValues(context));
        assertEquals(before, SavedProfiles.list(context).get(0).values);
        assertEquals(entry.id, SavedProfiles.active(context).id);
    }

    @Test public void switchingRestoresWholeRouteAndAppSelection() {
        SavedProfiles.Entry first = SavedProfiles.saveAs(context, "alpha", route(false));
        SavedProfiles.Entry second = SavedProfiles.saveAs(context, "профиль 2", route(true));
        SavedProfiles.activate(context, first.id);
        assertEquals(route(false), TunnelSettings.loadValues(context));
        assertEquals(route(false).allowedApplications, TunnelSettings.allowedApplications(context));
        SavedProfiles.activate(context, second.id);
        assertEquals(route(true), TunnelSettings.loadValues(context));
        assertEquals(route(true).allowedApplications, TunnelSettings.profiles(context)[0].allowedApplications);
        assertTrue(TunnelSettings.profiles(context)[0].jumpEnabled);
        assertEquals("second.example", TunnelSettings.profiles(context)[0].sshHost);
    }

    @Test public void savingEditsAndApplicationsAffectsOnlyActiveProfile() {
        SavedProfiles.Entry first = SavedProfiles.saveAs(context, "one", route(false));
        SavedProfiles.Entry second = SavedProfiles.saveAs(context, "two", route(true));
        TunnelSettings.saveValues(context, route(false));
        TunnelSettings.saveAllowedApplications(context, Collections.singleton("app.changed"));
        SavedProfiles.activate(context, first.id);
        assertEquals(route(false), TunnelSettings.loadValues(context));
        SavedProfiles.activate(context, second.id);
        assertEquals(route(false).withApplications(Collections.singleton("app.changed")), TunnelSettings.loadValues(context));
    }

    @Test public void namesAreUserSuppliedAndCannotOverwriteAnotherProfile() {
        SavedProfiles.Entry first = SavedProfiles.saveAs(context, "One", route(false));
        SavedProfiles.Entry second = SavedProfiles.saveAs(context, "Two", route(true));
        assertThrows(IllegalArgumentException.class, () -> SavedProfiles.saveAs(context, "  ", route(false)));
        assertThrows(IllegalArgumentException.class, () -> SavedProfiles.saveAs(context, " ONE ", route(false)));
        assertThrows(IllegalArgumentException.class, () -> SavedProfiles.rename(context, second.id, "one"));
        assertThrows(IllegalArgumentException.class, () -> SavedProfiles.saveAs(context,
                String.join("", Collections.nCopies(81, "x")), route(false)));
        SavedProfiles.rename(context, second.id, "  Рабочий  ");
        assertEquals(second.id, SavedProfiles.active(context).id);
        assertEquals("Рабочий", SavedProfiles.active(context).name);
        assertEquals(route(true), TunnelSettings.loadValues(context));
        assertEquals(2, SavedProfiles.list(context).size());
        SavedProfiles.delete(context, first.id);
        assertEquals(second.id, SavedProfiles.active(context).id);
    }

    @Test public void deletingActiveOrResettingDoesNotDestroySavedRoutes() {
        SavedProfiles.Entry first = SavedProfiles.saveAs(context, "one", route(false));
        SavedProfiles.Entry second = SavedProfiles.saveAs(context, "two", route(true));
        SavedProfiles.delete(context, second.id);
        assertNull(SavedProfiles.active(context));
        assertEquals(route(true), TunnelSettings.loadValues(context));
        SavedProfiles.activate(context, first.id);
        SavedProfiles.resetCurrent(context);
        assertNull(SavedProfiles.active(context));
        assertEquals(TunnelSettings.defaultValues().withApplications(route(false).allowedApplications),
                TunnelSettings.loadValues(context));
        SavedProfiles.activate(context, first.id);
        assertEquals(route(false), TunnelSettings.loadValues(context));
    }

    @Test public void brokenSavedEntryDoesNotBreakWorkingSettingsOrOtherProfiles() throws Exception {
        SavedProfiles.saveAs(context, "broken", route(false));
        SavedProfiles.Entry valid = SavedProfiles.saveAs(context, "valid", route(true));
        SharedPreferences preferences = TunnelSettings.prefs(context);
        JSONArray array = new JSONArray(preferences.getString("saved_profiles_v1", "[]"));
        array.getJSONObject(0).getJSONObject("settings").put("sshPort", 0);
        preferences.edit().putString("saved_profiles_v1", array.toString()).apply();
        assertEquals(1, SavedProfiles.list(context).size());
        assertEquals(valid.id, SavedProfiles.active(context).id);
        assertEquals(route(true), TunnelSettings.loadValues(context));
        preferences.edit().putString("saved_profiles_v1", "invalid JSON").apply();
        assertTrue(SavedProfiles.list(context).isEmpty());
        assertEquals(route(true), TunnelSettings.loadValues(context));
    }
}
