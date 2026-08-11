package net.tref.xraytunnel;

final class TunnelProfile {
    final String sshHost;
    final String sshUser;
    final int sshPort;
    final String proxyHost;
    final int proxyPort;
    final String privateKeyAsset;
    final boolean verifyHostKey;
    final boolean jumpEnabled;
    final String jumpHost;
    final String jumpUser;
    final int jumpPort;
    final String jumpPrivateKeyAsset;
    final java.util.Set<String> allowedApplications;

    TunnelProfile(
            String sshHost,
            String sshUser,
            int sshPort,
            String proxyHost,
            int proxyPort,
            String privateKeyAsset,
            boolean verifyHostKey,
            boolean jumpEnabled,
            String jumpHost,
            String jumpUser,
            int jumpPort,
            String jumpPrivateKeyAsset,
            java.util.Set<String> allowedApplications) {
        this.sshHost = sshHost;
        this.sshUser = sshUser;
        this.sshPort = sshPort;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.privateKeyAsset = privateKeyAsset;
        this.verifyHostKey = verifyHostKey;
        this.jumpEnabled = jumpEnabled;
        this.jumpHost = jumpHost;
        this.jumpUser = jumpUser;
        this.jumpPort = jumpPort;
        this.jumpPrivateKeyAsset = jumpPrivateKeyAsset;
        this.allowedApplications = java.util.Collections.unmodifiableSet(
                new java.util.HashSet<>(allowedApplications));
    }
}
