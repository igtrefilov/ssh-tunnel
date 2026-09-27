package net.tref.sshtunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/** Supplies real read deadlines to JSch's nested session over a direct-tcpip channel. */
final class ChannelSocket extends Socket {
    private final InputStream source;
    private final OutputStream output;
    private final byte[] buffer = new byte[32 * 1024];
    private int head;
    private int size;
    private int timeout;
    private boolean eof;
    private boolean closed;
    private IOException failure;

    ChannelSocket(InputStream source, OutputStream output, int timeout) {
        this.source = source;
        this.output = output;
        this.timeout = timeout;
        Thread pump = new Thread(this::pump, "ssh-jump-input");
        pump.setDaemon(true);
        pump.start();
    }

    private void pump() {
        byte[] chunk = new byte[8192];
        try {
            int count;
            while ((count = source.read(chunk)) >= 0) {
                int offset = 0;
                synchronized (this) {
                    while (offset < count && !closed) {
                        while (size == buffer.length && !closed) wait();
                        if (closed) return;
                        int tail = (head + size) % buffer.length;
                        int length = Math.min(count - offset, Math.min(buffer.length - size, buffer.length - tail));
                        System.arraycopy(chunk, offset, buffer, tail, length);
                        offset += length;
                        size += length;
                        notifyAll();
                    }
                    if (closed) return;
                }
            }
        } catch (IOException e) {
            synchronized (this) { failure = e; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) { eof = true; notifyAll(); }
        }
    }

    @Override public synchronized void setSoTimeout(int value) throws SocketException {
        if (value < 0) throw new IllegalArgumentException("Negative timeout");
        timeout = value;
        notifyAll();
    }

    @Override public InputStream getInputStream() {
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                long started = System.nanoTime();
                synchronized (ChannelSocket.this) {
                    while (size == 0 && !eof && !closed) {
                        long remaining = timeout == 0 ? 0
                                : timeout - (System.nanoTime() - started) / 1_000_000;
                        if (timeout != 0 && remaining <= 0) throw new SocketTimeoutException("Jump channel read timeout");
                        try { ChannelSocket.this.wait(remaining); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Jump channel interrupted", e);
                        }
                    }
                    if (closed) throw new IOException("Jump channel closed");
                    if (size == 0) {
                        if (failure != null) throw failure;
                        return -1;
                    }
                    int count = Math.min(length, Math.min(size, buffer.length - head));
                    System.arraycopy(buffer, head, bytes, offset, count);
                    head = (head + count) % buffer.length;
                    size -= count;
                    ChannelSocket.this.notifyAll();
                    return count;
                }
            }
        };
    }

    @Override public OutputStream getOutputStream() { return output; }

    @Override public void close() throws IOException {
        synchronized (this) { closed = true; notifyAll(); }
        source.close();
    }
}
