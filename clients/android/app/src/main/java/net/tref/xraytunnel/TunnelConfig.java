package net.tref.xraytunnel;

final class TunnelConfig {
    static final String DEFAULT_SSH_HOST = "78.17.105.115";
    static final String DEFAULT_SSH_USER = "ssh-tun";
    static final int DEFAULT_SSH_PORT = 2222;
    static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    static final int DEFAULT_PROXY_PORT = 1080;
    static final int DEFAULT_JUMP_PORT = 22;
    static final String PRIVATE_KEY_ASSET = "ssh_tunnel_key";
    static final String VPN_IPV4_ADDRESS = "198.18.0.1";
    static final int VPN_IPV4_PREFIX = 15;
    static final String VPN_IPV6_ADDRESS = "fc00::1";
    static final int VPN_IPV6_PREFIX = 64;
    static final String VPN_DNS_ADDRESS = "198.18.0.2";
    static final int VPN_MTU = 1500;
    static final int LOCAL_PROXY_PORT = 18080;

    private TunnelConfig() {
    }
}
