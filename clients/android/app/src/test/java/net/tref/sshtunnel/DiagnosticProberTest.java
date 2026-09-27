package net.tref.sshtunnel;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DiagnosticProberTest {
    @Test public void timedOutRoundCannotAccumulateMoreWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (DiagnosticProber prober = new DiagnosticProber()) {
            DiagnosticProber.RouteProbe blocked = scope -> {
                calls.incrementAndGet();
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return true;
            };
            assertNotNull(prober.probe(null, blocked, true, () -> true));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) assertNull(prober.probe(null, blocked, true, () -> true));
            assertEquals(1, calls.get());
            assertEquals(1, prober.roundsStarted());
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test public void lockingBeforeRoundLaunchPreventsAnyProbe() throws Exception {
        try (DiagnosticProber prober = new DiagnosticProber()) {
            assertNull(prober.probe(null, scope -> { fail("Locked probe"); return false; }, true, () -> false));
            assertEquals(0, prober.roundsStarted());
            assertTrue(prober.probe(null, scope -> true, true, () -> true).route);
        }
    }
}
