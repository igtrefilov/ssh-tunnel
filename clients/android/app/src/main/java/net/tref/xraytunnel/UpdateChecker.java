package net.tref.xraytunnel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class UpdateChecker {
    private static final String REPOSITORY = "igtrefilov/ssh-tunnel";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=20";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final long MAX_APK_BYTES = 100L * 1024L * 1024L;

    private UpdateChecker() {
    }

    static UpdateInfo findLatest(long currentVersionCode) throws IOException, JSONException {
        JSONArray releases = new JSONArray(readUrl(RELEASES_URL, "application/vnd.github+json"));
        UpdateInfo latest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                continue;
            }
            String tagName = release.optString("tag_name", "");
            if (!tagName.startsWith("android-v")) {
                continue;
            }

            JSONObject manifestAsset = findAsset(release, "update.json");
            JSONObject apkAsset = findApkAsset(release);
            if (manifestAsset == null || apkAsset == null) {
                continue;
            }

            JSONObject manifest = new JSONObject(readUrl(
                    requiredUrl(manifestAsset),
                    "application/octet-stream"));
            long versionCode = manifest.optLong("versionCode", -1);
            String versionName = manifest.optString("versionName", "").trim();
            String sha256 = manifest.optString("sha256", "").trim().toLowerCase(Locale.US);
            if (versionCode <= currentVersionCode
                    || versionName.isEmpty()
                    || !isSha256(sha256)) {
                continue;
            }

            UpdateInfo candidate = new UpdateInfo(
                    versionCode,
                    versionName,
                    tagName,
                    requiredUrl(apkAsset),
                    sha256,
                    release.optString("html_url", ""),
                    release.optString("body", "").trim());
            if (latest == null || candidate.versionCode > latest.versionCode) {
                latest = candidate;
            }
        }
        return latest;
    }

    static File downloadAndVerify(UpdateInfo update, File directory)
            throws IOException, NoSuchAlgorithmException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create update directory");
        }
        File temporary = new File(directory, "update-" + update.versionCode + ".apk.part");
        File apk = new File(directory, "update-" + update.versionCode + ".apk");
        deleteIfExists(temporary);
        deleteIfExists(apk);

        try {
            download(update.apkUrl, temporary);
            String actualSha256 = sha256(temporary);
            if (!update.sha256.equalsIgnoreCase(actualSha256)) {
                throw new IOException("Downloaded APK checksum does not match the release");
            }
            if (!temporary.renameTo(apk)) {
                throw new IOException("Unable to finalize downloaded APK");
            }
            return apk;
        } catch (IOException | RuntimeException | NoSuchAlgorithmException error) {
            deleteIfExists(temporary);
            deleteIfExists(apk);
            throw error;
        }
    }

    private static JSONObject findAsset(JSONObject release, String name) throws JSONException {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (name.equals(asset.optString("name", ""))) {
                return asset;
            }
        }
        return null;
    }

    private static JSONObject findApkAsset(JSONObject release) throws JSONException {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "").toLowerCase(Locale.US);
            if (name.endsWith("-release-signed.apk")) {
                return asset;
            }
        }
        return null;
    }

    private static String requiredUrl(JSONObject asset) throws JSONException {
        String url = asset.optString("browser_download_url", "").trim();
        if (!url.startsWith("https://github.com/")) {
            throw new JSONException("Release asset URL is not trusted");
        }
        return url;
    }

    private static String readUrl(String address, String accept) throws IOException {
        HttpURLConnection connection = openConnection(address, accept);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("GitHub returned HTTP " + responseCode);
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                return readText(input, MAX_JSON_BYTES);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void download(String address, File target) throws IOException {
        HttpURLConnection connection = openConnection(address, "application/octet-stream");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("GitHub returned HTTP " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_APK_BYTES) {
                throw new IOException("Release APK is unexpectedly large");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                    OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("Release APK is unexpectedly large");
                    }
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String address, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "SSH-Split-Tunnel-Android");
        return connection;
    }

    private static String readText(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IOException("GitHub response is unexpectedly large");
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < '0' || character > '9')
                    && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static void deleteIfExists(File file) {
        if (file.exists() && !file.delete()) {
            // A later download will report the actual filesystem error.
        }
    }
}
