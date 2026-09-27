package net.tref.sshtunnel;

import org.junit.Test;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class IoScopeTest {
    @Test public void cancellationInterruptsBlockedSocketAndRejectsLateRegistration() throws Exception {
        IoScope scope = new IoScope();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0);
             Socket client = scope.add(new Socket("127.0.0.1", server.getLocalPort()));
             Socket peer = server.accept()) {
            Future<?> read = executor.submit(() -> assertThrows(IOException.class, () -> client.getInputStream().read()));
            scope.close();
            read.get(2, TimeUnit.SECONDS);
            Socket late = new Socket();
            assertThrows(IOException.class, () -> scope.add(late));
            assertTrue(late.isClosed());
            scope.close();
        } finally { executor.shutdownNow(); }
    }
}
