# Jump host

The jump role is an optional transport hop. Its restricted SSH key only reaches
the final gateway's SSH endpoint; it does not grant direct SOCKS5 access.

The same VPS may also serve as a standalone Internet gateway. Install the
[gateway backend](../gateway/README.md) on its separate SSH port (default `2222`)
with the client's public key. The normal SSH service and jump key on port `22`
remain independent. Clients can then choose a direct route to this VPS or a
jump route through it to another gateway. Keep the SOCKS listener loopback-only.

Install a restricted key entry for an existing SSH account:

```bash
sudo ./deploy.sh \
  --ssh-user ilya \
  --gateway-host 10.0.0.10 \
  --gateway-port 2222 \
  --public-key-file /path/to/client.pub \
  --label linux-laptop
```

The resulting `authorized_keys` entry combines `restrict`, `port-forwarding`,
`permitopen="10.0.0.10:2222"` and a false forced command. The key therefore
cannot open a shell or forward to another destination.

The hostname passed here must exactly match the gateway hostname sent by the
client through the SSH `direct-tcpip` request.
