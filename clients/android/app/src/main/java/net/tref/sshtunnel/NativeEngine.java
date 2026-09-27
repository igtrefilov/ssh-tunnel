package net.tref.sshtunnel;

/** Loaded exclusively in :engine. One native invocation per process lifetime. */
final class NativeEngine {
    static { System.loadLibrary("hev-socks5-tunnel"); }
    static native void run(String config, int descriptor);
    static native long heartbeat();
    static native void simulateStall();
    private NativeEngine() {}
}
