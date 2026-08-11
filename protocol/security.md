# Security model

## Gateway

- A dedicated sshd has its own port, host key, account and authorized keys.
- Password, keyboard-interactive and root authentication are disabled.
- Only local TCP forwarding to `127.0.0.1:1080` is permitted.
- Shells, commands, TTY, X11, reverse forwarding, SSH TUN and agent forwarding
  are disabled.
- The SOCKS5 engine is loopback-only and runs as a sandboxed dynamic user.

## Jump host

- Each key entry uses `restrict` and re-enables only port forwarding.
- `permitopen` names exactly one gateway SSH host and port.
- A false forced command prevents shell and command execution.
- The jump host does not run or directly expose the SOCKS5 engine.

## Clients

- Use one key per device so access can be revoked independently.
- Pin or remember the jump and gateway host keys separately.
- Never forward the local SSH agent for the tunnel transport.
- Android APK signing keys and embedded tunnel identities are different
  credentials and must both remain outside Git.

The private Android APK signing key controls application update continuity. An
embedded SSH key controls gateway access and can be extracted from the APK, so
it must remain forwarding-only and should not be reused for normal shell login.
