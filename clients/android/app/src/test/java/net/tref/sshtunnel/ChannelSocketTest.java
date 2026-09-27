package net.tref.sshtunnel;

import org.junit.Test;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ChannelSocketTest {
    @Test public void idleChannelTimesOutThenStillReceivesData() throws Exception {
        PipedInputStream source = new PipedInputStream();
        try (PipedOutputStream writer = new PipedOutputStream(source);
             ChannelSocket socket = new ChannelSocket(source, new ByteArrayOutputStream(), 40)) {
            InputStream reader = socket.getInputStream();
            assertThrows(SocketTimeoutException.class, reader::read);
            writer.write(42);
            writer.flush();
            socket.setSoTimeout(2000);
            assertEquals(42, reader.read());
        }
    }

    @Test public void timeoutChangeWakesAnAlreadyBlockedRead() throws Exception {
        PipedInputStream source = new PipedInputStream();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (PipedOutputStream writer = new PipedOutputStream(source);
             ChannelSocket socket = new ChannelSocket(source, new ByteArrayOutputStream(), 60_000)) {
            Future<?> read = executor.submit(() -> {
                assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            });
            socket.setSoTimeout(20);
            read.get(2, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test public void ringBufferPreservesLargeSshStreamAndEof() throws Exception {
        byte[] bytes = new byte[300_000];
        new Random(1234).nextBytes(bytes);
        try (ChannelSocket socket = new ChannelSocket(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), 2000)) {
            assertArrayEquals(bytes, socket.getInputStream().readAllBytes());
        }
    }
}
