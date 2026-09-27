package net.tref.sshtunnel;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Closes in-flight sockets when a probe/connection becomes obsolete. */
final class IoScope implements Closeable {
    private final List<Closeable> resources = new ArrayList<>();
    private boolean closed;

    synchronized <T extends Closeable> T add(T resource) throws IOException {
        if (closed) {
            resource.close();
            throw new IOException("Operation cancelled");
        }
        resources.add(resource);
        return resource;
    }

    synchronized void check() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Operation cancelled");
    }

    synchronized boolean isClosed() { return closed; }

    @Override public void close() {
        List<Closeable> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(resources);
            resources.clear();
        }
        for (Closeable resource : pending) {
            try { resource.close(); } catch (IOException ignored) { }
        }
    }
}
