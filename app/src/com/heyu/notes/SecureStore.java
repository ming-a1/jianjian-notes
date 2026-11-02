package com.heyu.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 备份密码的安全存储：使用 Android Keystore 中的设备密钥（AES/GCM）加密后再落盘，
 * 明文密码不会出现在 SharedPreferences 里。
 */
public final class SecureStore {

    private static final String PREFS = "jj_secure";
    private static final String KEY_CIPHER = "encpwd";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "jianjian_backup_pwd_key";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setBackupPassword(Context c, String pwd) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(pwd.getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        prefs(c).edit().putString(KEY_CIPHER, stored).apply();
    }

    public static String getBackupPassword(Context c) {
        String stored = prefs(c).getString(KEY_CIPHER, null);
        if (stored == null) return "";
        int idx = stored.indexOf(':');
        if (idx <= 0) return "";
        try {
            byte[] iv = Base64.decode(stored.substring(0, idx), Base64.NO_WRAP);
            byte[] ct = Base64.decode(stored.substring(idx + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasBackupPassword(Context c) {
        return getBackupPassword(c).length() > 0;
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY_CIPHER).apply();
    }

    private SecureStore() { }
}
