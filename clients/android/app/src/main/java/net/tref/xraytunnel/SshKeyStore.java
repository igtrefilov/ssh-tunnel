package net.tref.xraytunnel;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class SshKeyStore {
    private SshKeyStore() {
    }

    static synchronized byte[] privateKey(Context context, TunnelProfile profile)
            throws IOException {
        return privateKey(context, profile.privateKeyAsset);
    }

    static synchronized byte[] privateKey(Context context, String assetName)
            throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
