# SSH Tunnel Gateway

The gateway is the only final-server deployment used by both clients. It does
not modify the distribution's normal SSH daemon.

Defaults:

```text
Dedicated SSH: ssh-tun@SERVER:2222
SOCKS5 engine: 127.0.0.1:1080
Configuration: /etc/ssh-tunnel
```

## Install

On a systemd-based Linux server:

```bash
sudo ./deploy.sh --public-key-file /path/to/client.pub
```

`--public-key-file` may be repeated. The script builds the pinned HEV source,
creates the forwarding-only account and dedicated host key, installs both
systemd services, and verifies TCP CONNECT plus Android UDP-in-TCP support.

Add or revoke devices later:

```bash
sudo ssh-tunnel-add-client-key --label android-phone phone.pub
sudo ssh-tunnel-remove-client-key SHA256:FINGERPRINT
```

The dedicated sshd globally permits only local forwarding to
`127.0.0.1:1080`. Shells, commands, TTY, reverse forwarding, arbitrary targets,
SSH TUN and agent forwarding are disabled.

## Operations

```bash
sudo ./verify.sh
sudo systemctl status ssh-tunnel-socks.service ssh-tunnel-sshd.service
sudo journalctl -u ssh-tunnel-socks.service -u ssh-tunnel-sshd.service
```

Only the dedicated SSH port is public. Never expose port 1080 in a firewall.

Remove program files while preserving configuration and keys:

```bash
sudo ./uninstall.sh --yes
```

Add `--purge` only when the host keys and authorized client keys should also be
permanently removed.
