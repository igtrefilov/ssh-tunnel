# Linux client

The Linux client keeps one user-systemd service per profile. It creates an SSH
local forward and exposes the gateway as `socks5h://127.0.0.1:30808`.

## Direct route

```bash
./install.sh --prepare-only --server SERVER
# Add the printed public key to the gateway.
./install.sh --server SERVER --host-key-fingerprint SHA256:...
```

Run one application through the selected profile:

```bash
ssh-tunnel-exec main -- curl https://example.com
```

The wrapper starts a localhost HTTP CONNECT bridge (by default on port 30809)
and exports upper- and lower-case `ALL_PROXY`, `HTTP_PROXY` and `HTTPS_PROXY`
variables with its HTTP URL. The bridge sends hostname requests through the
SOCKS tunnel with remote DNS resolution. This also supports clients such as
Codex whose HTTP library does not include SOCKS proxy support.

Use `--http-bridge-port PORT` when the default local SOCKS port + 1 is already
occupied.

## Jump route

```bash
./install.sh \
  --server GATEWAY \
  --jump-host JUMP_HOST \
  --jump-user jump-user \
  --host-key-fingerprint SHA256:GATEWAY... \
  --jump-host-key-fingerprint SHA256:JUMP...
```

Use `--jump-identity-file` when the jump and gateway use different client
keys. Both identities and both host keys are stored independently.

## Standalone installer

`install-standalone.sh` contains the installer, runner, HTTP bridge and systemd
unit. Rebuild it after changing Linux client sources:

```bash
../../tools/build-linux-standalone.sh
```

Profiles live under `~/.config/ssh-tunnel`, executables under `~/.local`, and
services are named `ssh-tunnel-client@PROFILE.service`.

Remove a profile with:

```bash
./uninstall.sh --profile main --yes
```
