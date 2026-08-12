# Android client

The Android application routes selected packages through `VpnService`, the
pinned `hev-socks5-tunnel` native engine, SSH local forwarding and the common
gateway SOCKS5 service.

It supports:

- multiple gateway addresses with explicit active selection;
- direct SSH to the gateway;
- nested SSH through an optional jump host;
- TCP and HEV UDP-in-TCP traffic;
- trust-on-first-use host-key persistence with changed-key rejection;
- automatic reconnect when either SSH session or the native tunnel stops.

## Build

Requirements are JDK 17, Android SDK 35 and NDK `29.0.14206865`.

The local APK must contain a deployment SSH identity at:

```text
app/src/main/assets-bundled/ssh_tunnel_key
```

Generate a new deployment identity only for a new server trust domain:

```bash
./scripts/generate-ssh-key.sh
```

Build:

```bash
./gradlew assembleDebug
./scripts/sign-release.sh
```

## Updating an installed application

Release APKs retain application ID `net.tref.xraytunnel`. Version 1.31 uses
`versionCode 32`. Every later update must increase that code and use the exact
same signing identity.

Local secret files are:

```text
keys/ssh-tunnel-release.p12
keys/ssh-tunnel-release.pass
app/src/main/assets-bundled/ssh_tunnel_key
```

They are ignored by Git. `sign-release.sh` also verifies the signed APK against
the committed public certificate fingerprint under `signing/`.

The `android-v*` GitHub workflow requires protected environment secrets:

```text
ANDROID_RELEASE_KEYSTORE_BASE64
ANDROID_RELEASE_KEYSTORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_TUNNEL_PRIVATE_KEY_BASE64
```

Install a signed update without clearing application data:

```bash
adb install -r app/build/outputs/apk/release/ssh-tunnel-*-release-signed.apk
```

The application also has a `Check for updates` button. It reads Android
releases (`android-v*`) from the public GitHub repository, compares the signed
release `versionCode`, verifies the downloaded APK against the SHA-256 value in
`update.json`, and opens the Android package installer. On Android 8 and later,
the user must allow this application to install unknown apps once in system
settings.
