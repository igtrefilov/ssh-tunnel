package net.tref.sshtunnel;

import android.net.DnsResolver;
import android.net.Network;
import android.os.Build;
import android.os.CancellationSignal;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class BoundedDns {
    // Older Android DNS is not cancellable. Never queue work behind stuck resolvers.
    private static final ThreadPoolExecutor LEGACY = new ThreadPoolExecutor(0, 3, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "tunnel-dns");
                thread.setDaemon(true);
                return thread;
            });

    static InetAddress[] resolve(Network network, String host, long deadline, IoScope scope)
            throws IOException {
        scope.check();
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return new InetAddress[]{InetAddress.getByName(host)};
        }
        Result result = new Result();
        CancellationSignal cancellation = new CancellationSignal();
        scope.add(() -> { cancellation.cancel(); result.cancel(true); });
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY,
                        Runnable::run, cancellation, new DnsResolver.Callback<List<InetAddress>>() {
                            @Override public void onAnswer(List<InetAddress> addresses, int rcode) {
                                if (addresses.isEmpty()) result.completeExceptionally(new IOException("DNS has no answers"));
                                else result.complete(addresses.toArray(new InetAddress[0]));
                            }
                            @Override public void onError(DnsResolver.DnsException error) {
                                result.completeExceptionally(error);
                            }
                        });
            } else {
                LEGACY.execute(() -> {
                    try {
                        if (!result.isCancelled()) result.complete(network == null
                                ? InetAddress.getAllByName(host) : network.getAllByName(host));
                    } catch (Exception e) { result.completeExceptionally(e); }
                });
            }
            return result.get(UnderlyingNetworkSocketFactory.remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("DNS interrupted", e);
        } catch (Exception e) {
            throw new IOException("DNS failed or exceeded deadline", e);
        } finally {
            cancellation.cancel();
            result.cancel(true);
        }
    }

    private static final class Result extends FutureTask<InetAddress[]> {
        Result() { super(() -> null); }
        void complete(InetAddress[] value) { set(value); }
        void completeExceptionally(Exception error) { setException(error); }
    }

    private BoundedDns() {}
}
