package net.tref.sshtunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public class TunnelPolicyTest {
    @Test public void backgroundBackoffAndNoNetwork() {
        TunnelPolicy policy = new TunnelPolicy();
        policy.setNetworkAvailable(true);
        long now = 1000;
        for (long delay : new long[]{10_000, 30_000, 60_000, 120_000, 300_000, 300_000}) {
            policy.failed(now, policy.revision());
            assertEquals(delay, policy.retryDelay(now));
            assertEquals(1, policy.retryDelay(now + delay - 1));
            now += delay;
            assertEquals(0, policy.retryDelay(now));
        }
        policy.setNetworkAvailable(false);
        assertEquals(Long.MAX_VALUE, policy.retryDelay(now + 1_000_000));
        assertFalse(policy.isInteractive());
        assertEquals(60_000, policy.keepaliveMs());
    }

    @Test public void unlockCancelsLongWaitAndLockSlowsPendingRetry() {
        TunnelPolicy policy = new TunnelPolicy();
        policy.setNetworkAvailable(true);
        for (int i = 0; i < 5; i++) policy.failed(1000, policy.revision());
        policy.setInteractive(true, 2000);
        assertTrue(policy.isInteractive());
        assertEquals(0, policy.retryDelay(2000));
        assertEquals(10_000, policy.keepaliveMs());
        policy.failed(2000, policy.revision());
        assertEquals(1500, policy.retryDelay(2000));
        policy.setInteractive(false, 2001);
        assertEquals(10_000, policy.retryDelay(2001));
    }

    @Test public void returnedNetworkTriggersOneAttemptWhileLocked() {
        TunnelPolicy policy = new TunnelPolicy();
        policy.setNetworkAvailable(true);
        for (int i = 0; i < 5; i++) policy.failed(100, policy.revision());
        policy.retryNow(200);
        assertEquals(0, policy.retryDelay(200));
        policy.failed(201, policy.revision());
        assertEquals(10_000, policy.retryDelay(201));
        // Callback notifications alone do not bypass the deadline.
        policy.setNetworkAvailable(true);
        assertEquals(9999, policy.retryDelay(202));
    }

    @Test public void inFlightFailureCannotSwallowNetworkOrUnlockEvent() {
        TunnelPolicy policy = new TunnelPolicy();
        policy.setNetworkAvailable(true);
        long revision = policy.revision();
        policy.retryNow(100);
        policy.failed(200, revision);
        assertEquals(0, policy.retryDelay(200));
        revision = policy.revision();
        policy.setInteractive(true, 300);
        policy.failed(400, revision);
        assertEquals(0, policy.retryDelay(400));
    }
}
