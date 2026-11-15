package com.cet4.focus;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials never enter progress preferences, backups, exports, or logs. */
final class AiSettingsStore {
    private static final String ALIAS = "study-ai-local-v1";
    private final AtomicFile file;

    static final class Settings {
        final String address, model, key;
        Settings(String address, String model, String key) {
            this.address = address; this.model = model; this.key = key;
        }
    }

    AiSettingsStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "ai-settings-v1.bin"));
    }

    private SecretKey encryptionKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new java.io.IOException("本机密钥不可用，请清除后重新配置。");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    synchronized Settings load() throws Exception {
        byte[] data;
        try { data = file.readFully(); }
        catch (java.io.FileNotFoundException empty) { return new Settings("", "", ""); }
        if (data.length < 30 || data[0] != 1) throw new java.io.IOException("AI 设置损坏，请清除后重新配置。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false), new GCMParameterSpec(128, Arrays.copyOfRange(data, 1, 13)));
        JSONObject config = new JSONObject(new String(cipher.doFinal(data, 13, data.length - 13), java.nio.charset.StandardCharsets.UTF_8));
        return new Settings(config.getString("address"), config.getString("model"), config.getString("key"));
    }

    synchronized void save(Settings settings) throws Exception {
        AiClient.validate(settings.address, settings.model, settings.key);
        byte[] plaintext = new JSONObject().put("address", settings.address).put("model", settings.model)
                .put("key", settings.key).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(1); bytes.write(cipher.getIV()); bytes.write(cipher.doFinal(plaintext));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes.toByteArray());
            file.finishWrite(output);
        } catch (Exception error) { file.failWrite(output); throw error; }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    synchronized void clear() throws Exception {
        file.delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
}
