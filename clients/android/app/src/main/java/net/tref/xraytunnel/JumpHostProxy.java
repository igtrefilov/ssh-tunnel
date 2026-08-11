package net.tref.xraytunnel;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Exposes a direct-tcpip channel from an already authenticated jump session as
 * a JSch proxy. The gateway session then performs its own SSH handshake,
 * authentication and host-key verification inside that channel.
 */
final class JumpHostProxy implements Proxy {
    private final Session jumpSession;
    private ChannelDirectTCPIP channel;
    private InputStream input;
    private OutputStream output;

    JumpHostProxy(Session jumpSession) {
        this.jumpSession = jumpSession;
    }

    @Override
    public synchronized void connect(
            SocketFactory socketFactory,
            String host,
            int port,
            int timeout) throws Exception {
        if (!jumpSession.isConnected()) {
            throw new JSchException("Jump-host SSH session is not connected");
        }
        close();
        ChannelDirectTCPIP next = (ChannelDirectTCPIP) jumpSession.openChannel("direct-tcpip");
        next.setHost(host);
        next.setPort(port);
        next.setOrgIPAddress("127.0.0.1");
        next.setOrgPort(0);
        channel = next;
        input = next.getInputStream();
        output = next.getOutputStream();
        try {
            next.connect(timeout);
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    @Override
    public synchronized InputStream getInputStream() {
        return input;
    }

    @Override
    public synchronized OutputStream getOutputStream() {
        return output;
    }

    @Override
    public Socket getSocket() {
        return null;
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        input = null;
        output = null;
    }
}
