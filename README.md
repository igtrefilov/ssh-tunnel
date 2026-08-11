# SSH Tunnel

One reproducible SSH tunnel gateway with independent Android and Linux clients.
Both clients reach the same loopback-only SOCKS5 engine, either directly or
through a restricted SSH jump host.

## Architecture

```text
Linux application -> local SOCKS ------------------------------+
                                                               |
Android app -> VpnService -> hev-socks5-tunnel -> local SOCKS -+
                                                               |
                         direct: SSH gateway:2222               |
                         jump:   SSH jump -> SSH gateway:2222   |
                                                               v
                                         127.0.0.1:1080 SOCKS5
                                                               |
                                                               v
                                                           Internet
```

The final gateway runs two services:

- `ssh-tunnel-sshd.service`: a dedicated public-key-only SSH endpoint.
- `ssh-tunnel-socks.service`: the common SOCKS5 engine, currently backed by a
  pinned `hev-socks5-server` build.

The jump host never runs a SOCKS service. It may only open a `direct-tcpip`
channel to the gateway SSH endpoint.

## Repository layout

```text
clients/android/       Android VpnService, UI and release tooling
clients/linux/         OpenSSH/systemd client and standalone installer
server/gateway/        Dedicated sshd and the common SOCKS5 engine
server/jump-host/      Restricted authorized_keys deployment for a bastion
protocol/              Shared profile, transport and security contracts
tests/                 Protocol and deployment smoke tests
```

Clone with pinned native dependencies:

```bash
git clone --recurse-submodules REPOSITORY_URL ssh-tunnel
cd ssh-tunnel
```

## Gateway deployment

The current Android release public key is tracked because it is not secret:

```bash
sudo ./server/gateway/deploy.sh \
  --public-key-file server/gateway/keys/android-client.pub
```

Prepare a Linux profile without connecting, then add its generated public key:

```bash
./clients/linux/install.sh --prepare-only --server SERVER
sudo ssh-tunnel-add-client-key --label linux-main /path/to/main_ed25519.pub
```

The gateway installer prints the dedicated SSH host-key fingerprint. Complete
the direct Linux setup with that fingerprint:

```bash
./clients/linux/install.sh \
  --server SERVER \
  --host-key-fingerprint SHA256:...
```

## Jump-host route

Install the client public key on an existing SSH jump account with one allowed
destination:

```bash
sudo ./server/jump-host/deploy.sh \
  --ssh-user ilya \
  --gateway-host GATEWAY \
  --public-key-file /path/to/client.pub
```

Then configure the Linux client:

```bash
./clients/linux/install.sh \
  --server GATEWAY \
  --jump-host JUMP_HOST \
  --jump-user ilya \
  --host-key-fingerprint SHA256:GATEWAY... \
  --jump-host-key-fingerprint SHA256:JUMP...
```

Android exposes the same optional jump host, user and port in Settings. Its
outer SSH session opens a restricted `direct-tcpip` channel; a second SSH
handshake with independent host-key verification runs inside that channel.

## Android releases

Build locally:

```bash
cd clients/android
./gradlew assembleDebug
./scripts/sign-release.sh
```

Android releases use tags such as `android-v1.26`. They are independent from
Linux and server releases. Updating an installed application requires the same
`applicationId`, a higher `versionCode`, and the original PKCS#12 signing key.
Private signing and tunnel identities are ignored by Git.

See `clients/android/README.md` for the protected CI secrets required by the
release workflow.

## Verification

```bash
./tests/smoke/test-shell.sh
make -C server/gateway/third_party/hev-socks5-server -j2 \
  REV_ID=8b9664d CFLAGS=-Wno-error=unused-result
python3 tests/integration/test_socks_gateway.py \
  server/gateway/third_party/hev-socks5-server/bin/hev-socks5-server
python3 tests/integration/test_ssh_routes.py \
  server/gateway/third_party/hev-socks5-server/bin/hev-socks5-server
```

The integration test checks standard SOCKS5 TCP `CONNECT` for Linux and HEV
UDP-in-TCP command `0x05` for Android, both directly and through a restricted
SSH jump host.
