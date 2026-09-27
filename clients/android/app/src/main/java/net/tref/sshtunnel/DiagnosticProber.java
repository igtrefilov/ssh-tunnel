package net.tref.sshtunnel;

import android.content.Context;

import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** At most one round exists, including cancelled tasks still unwinding in DNS/JSch. */
final class DiagnosticProber implements AutoCloseable {
    interface Permission { boolean allowed(); }
    interface RouteProbe { boolean probe(IoScope scope); }
    static final int DEADLINE_MS = 1200;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 3, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(3));
    private volatile Round current;
    private final java.util.concurrent.atomic.AtomicLong rounds = new java.util.concurrent.atomic.AtomicLong();

    static final class Result {
        final boolean route;
        final boolean ya;
        final boolean google;
        Result(boolean route, boolean ya, boolean google) {
            this.route = route;
            this.ya = ya;
            this.google = google;
        }
    }

    private static final class Round {
        final IoScope scope = new IoScope();
        final CountDownLatch completed;
        final java.util.concurrent.atomic.AtomicIntegerArray answers = new java.util.concurrent.atomic.AtomicIntegerArray(3);
        Round(int count) { completed = new CountDownLatch(count); }
    }

    Result probe(Context context, RouteProbe route, boolean online,
            Permission allowed) throws InterruptedException {
        Round previous = current;
        if (previous != null && previous.completed.getCount() != 0) return null;
        Round round = new Round(online ? 1 : 3);
        current = round;
        // Publish before checking permission: lock/cancel cannot miss a just-starting round.
        if (!allowed.allowed()) { round.scope.close(); current = null; return null; }
        rounds.incrementAndGet();
        submit(round, 0, route);
        if (!online) {
            submit(round, 1, scope -> reachable(context, "ya.ru", 443, scope));
            submit(round, 2, scope -> reachable(context, "google.com", 443, scope));
        }
        try {
            round.completed.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
            return new Result(round.answers.get(0) != 0, round.answers.get(1) != 0, round.answers.get(2) != 0);
        } finally { round.scope.close(); }
    }

    private void submit(Round round, int index, RouteProbe probe) {
        executor.execute(() -> {
            try {
                if (!round.scope.isClosed()) round.answers.set(index, probe.probe(round.scope) ? 1 : 0);
            } finally { round.completed.countDown(); }
        });
    }

    static boolean reachable(Context context, String host, int port, IoScope scope) {
        try (Socket socket = new UnderlyingNetworkSocketFactory(context, DEADLINE_MS, false, scope)
                .createSocket(host, port)) {
            return true;
        } catch (Exception e) { return false; }
    }

    void cancel() {
        Round round = current;
        if (round != null) round.scope.close();
    }

    long roundsStarted() { return rounds.get(); }

    @Override public void close() { cancel(); executor.shutdownNow(); }
}
