# Connection profile contract

Both clients implement the same logical profile even though Linux persists it
as a mode-0600 environment file and Android uses private application storage.

```yaml
name: main
server:
  host: gateway.example.net
  port: 2222
  user: ssh-tun
  identity: server-key
  host-key: SHA256:...
jump: null
socks:
  host: 127.0.0.1
  port: 1080
```

For a jump route:

```yaml
jump:
  host: bastion.example.net
  port: 22
  user: jump-user
  identity: jump-key
  host-key: SHA256:...
```

`server.host` is the address as seen by the jump host. The gateway SSH
handshake and host-key verification are identical in direct and jump modes.
Clients must reconnect the whole chain when either SSH session is lost.

Server and jump identities may refer to the same key for compatibility, but
separate per-device keys are recommended.
