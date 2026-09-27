package net.tref.sshtunnel;

/** Time-based policy, independent of Android callbacks and diagnostic results. */
final class TunnelPolicy {
    static final long ACTIVE_RETRY_MS = 1500;
    static final int ACTIVE_KEEPALIVE_MS = 10_000;
    static final int BACKGROUND_KEEPALIVE_MS = 60_000;
    private static final long[] BACKGROUND_RETRIES_MS = {10_000, 30_000, 60_000, 120_000, 300_000};

    private boolean interactive;
    private boolean networkAvailable;
    private int failures;
    private long retryAt;
    private long revision;

    synchronized void setInteractive(boolean value, long now) {
        if (interactive == value) return;
        interactive = value;
        if (value) {
            retryAt = now;
            failures = 0;
            revision++;
        } else if (retryAt > now) {
            retryAt = now + BACKGROUND_RETRIES_MS[0];
            failures = 1;
        }
    }

    synchronized boolean isInteractive() { return interactive; }

    synchronized int keepaliveMs() {
        return interactive ? ACTIVE_KEEPALIVE_MS : BACKGROUND_KEEPALIVE_MS;
    }

    synchronized void setNetworkAvailable(boolean value) { networkAvailable = value; }

    synchronized void retryNow(long now) {
        retryAt = now;
        failures = 0;
        revision++;
    }

    synchronized long revision() { return revision; }

    synchronized void failed(long now, long attemptRevision) {
        // A real network change/unlock during an attempt must not lose its wakeup.
        if (revision != attemptRevision) return;
        long delay = interactive ? ACTIVE_RETRY_MS
                : BACKGROUND_RETRIES_MS[Math.min(failures, BACKGROUND_RETRIES_MS.length - 1)];
        failures = Math.min(failures + 1, BACKGROUND_RETRIES_MS.length);
        retryAt = now + delay;
    }

    synchronized void connected() { failures = 0; }

    synchronized long retryDelay(long now) {
        return networkAvailable ? Math.max(0, retryAt - now) : Long.MAX_VALUE;
    }
}
