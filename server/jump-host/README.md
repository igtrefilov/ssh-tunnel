# Jump host

The jump host is an optional transport hop. It does not run the gateway SOCKS5
engine and does not receive access to the final Internet proxy.

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
