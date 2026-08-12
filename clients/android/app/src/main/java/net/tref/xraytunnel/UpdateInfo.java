package net.tref.xraytunnel;

/** Metadata for a signed Android release published on GitHub. */
final class UpdateInfo {
    final long versionCode;
    final String versionName;
    final String tagName;
    final String apkUrl;
    final String sha256;
    final String releaseUrl;
    final String releaseNotes;

    UpdateInfo(
            long versionCode,
            String versionName,
            String tagName,
            String apkUrl,
            String sha256,
            String releaseUrl,
            String releaseNotes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.tagName = tagName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.releaseUrl = releaseUrl;
        this.releaseNotes = releaseNotes;
    }
}
