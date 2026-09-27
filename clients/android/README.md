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

## Screen state and recovery

Diagnostics run once per second while the screen is interactive and unlocked,
including when another app is in front. Online checks open a short channel over
the existing gateway SSH session to its SOCKS listener, so a stale SSH session
is detected even when a new TCP connection to the host would succeed. Locking or switching the screen off
cancels the current round and stops diagnostic probes entirely. Unlocking
starts a fresh round immediately. Google and ya.ru checks remain part of the
Cheburnet classification; their results never gate SSH connection attempts.

Connection recovery is independent of diagnostics. Active retries use a 1.5 s
pause. Locked retries back off through 10, 30, 60, 120 and 300 s. A newly
available/resumed physical network permits an immediate attempt, without
requiring Android's Internet validation. Signal strength/bandwidth changes do
not reset backoff. When there is no physical network, retries wait for a network
callback. Android suspend/Doze can defer execution; no polling wake lock is held.
SSH keepalives use 10 s while active and 60 s while locked, for both direct and
nested jump-host sessions. Each diagnostic round has a 1.2 s deadline and only
one round can exist, including tasks still cancelling.

HEV runs in a private `:engine` process with a fresh lwIP state on every start.
The VPN service retains its TUN descriptor during reconnection. A heartbeat on
the native cooperative scheduler detects a stalled event loop after about
15–20 seconds of awake time and terminates only the engine process. Deep sleep
does not count toward this timeout. Stopping the VPN never joins a native
thread on the UI thread. This contains the observed `tcp_fasttmr` spin and
avoids reusing native global state; the original list-corruption trigger has
not been conclusively identified.

Private `files/tunnel-events.log` and `files/tunnel-events.previous.log` store
bounded transition/retry logs (about 256 KiB each), without keys or traffic
contents. `adb shell dumpsys activity service
net.tref.xraytunnel/net.tref.sshtunnel.TunnelService` shows screen mode,
diagnostic-round count, connection-attempt count and retry/keepalive timing.

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

Java sources and namespace use `net.tref.sshtunnel`; there is no Xray engine.
Release APKs retain application ID `net.tref.xraytunnel` solely for Android
update compatibility and preservation of existing data. Version 1.33 uses
`versionCode 34`. Every later update must increase that code and use the exact
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

## Tests

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Unit tests cover retry deadlines, lock/unlock and network wakeups, bounded
diagnostic work, socket cancellation and nested SSH stream timeouts.
For the full lifecycle and native-stall test, boot a disposable Android 35
emulator, build both debug APKs, then run from the repository root:

```bash
python3 tests/integration/test_android_tunnel.py --adb /path/to/adb --serial emulator-5554
```

The script refuses physical devices. It installs only on the chosen emulator,
starts temporary SSH/SOCKS fixtures on host loopback, tests direct and jump
routes, lock/unlock, network loss/return, background engine recovery and a deliberately stalled
native scheduler. Native fault injection is accepted only in debug builds.
