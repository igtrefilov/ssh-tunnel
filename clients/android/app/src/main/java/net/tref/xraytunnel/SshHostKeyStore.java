package net.tref.xraytunnel;

import android.content.Context;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persists SSH host keys using trust on first use. Bundled entries are seeded
 * on the first run, bundled hosts are merged on upgrades, unknown hosts are
 * remembered, and changed keys are rejected by JSch's strict host-key
 * checking.
 */
final class SshHostKeyStore implements HostKeyRepository {
    private static final String BUNDLED_KNOWN_HOSTS = "known_hosts";
    private static final String STORED_KNOWN_HOSTS = "known_hosts";

    private final HostKeyRepository delegate;

    private SshHostKeyStore(HostKeyRepository delegate) {
        this.delegate = delegate;
    }

    static synchronized void configure(Context context, JSch jsch) throws Exception {
        File knownHosts = new File(context.getFilesDir(), STORED_KNOWN_HOSTS);
        if (!knownHosts.exists()) {
            seedKnownHosts(context, knownHosts);
        } else {
            mergeBundledKnownHosts(context, knownHosts);
        }
        jsch.setKnownHosts(knownHosts.getAbsolutePath());
        jsch.setHostKeyRepository(new SshHostKeyStore(jsch.getHostKeyRepository()));
    }

    private static void mergeBundledKnownHosts(Context context, File destination)
            throws IOException {
        String existing = readText(new FileInputStream(destination));
        String bundled;
        try (InputStream input = context.getAssets().open(BUNDLED_KNOWN_HOSTS)) {
            bundled = readText(input);
        }

        StringBuilder additions = new StringBuilder();
        for (String line : bundled.split("\\r?\\n")) {
            String normalized = line.trim();
            if (normalized.isEmpty() || containsLine(existing, normalized)) {
                continue;
            }
            if (additions.length() == 0 && !existing.endsWith("\n")) {
                additions.append('\n');
            }
            additions.append(normalized).append('\n');
        }
        if (additions.length() > 0) {
            try (FileOutputStream output = new FileOutputStream(destination, true)) {
                output.write(additions.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static boolean containsLine(String text, String expected) {
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void seedKnownHosts(Context context, File destination) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (InputStream input = context.getAssets().open(BUNDLED_KNOWN_HOSTS);
                FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (!temporary.renameTo(destination) && !destination.exists()) {
            throw new IOException("Could not initialize SSH known_hosts");
        }
        if (temporary.exists()) {
            temporary.delete();
        }
    }

    @Override
    public synchronized int check(String host, byte[] key) {
        int result = delegate.check(host, key);
        if (result != NOT_INCLUDED) {
            return result;
        }
        try {
            delegate.add(new HostKey(host, key), null);
            return delegate.check(host, key);
        } catch (JSchException e) {
            return NOT_INCLUDED;
        }
    }

    @Override
    public synchronized void add(HostKey hostKey, UserInfo userInfo) {
        delegate.add(hostKey, userInfo);
    }

    @Override
    public synchronized void remove(String host, String type) {
        delegate.remove(host, type);
    }

    @Override
    public synchronized void remove(String host, String type, byte[] key) {
        delegate.remove(host, type, key);
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return delegate.getKnownHostsRepositoryID();
    }

    @Override
    public synchronized HostKey[] getHostKey() {
        return delegate.getHostKey();
    }

    @Override
    public synchronized HostKey[] getHostKey(String host, String type) {
        return delegate.getHostKey(host, type);
    }
}
