package com.winlator.wowmobile;

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
 * The WoW password for auto-login, encrypted with an AES key that lives in the Android
 * Keystore (it can't be read out of the device). Only the ciphertext is kept, in this
 * app's private preferences; nothing goes into the game folder.
 */
public final class LoginSecret {
    private static final String KEY_ALIAS = "wowmobile_login";
    private static final String PREFS = "wow_login";
    private static final String PREF_DATA = "password";
    private static final String PREF_ENABLED = "auto_login";
    private static final String PREF_ACCOUNT = "account_name";

    private LoginSecret() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false) && hasPassword(context);
    }

    public static boolean hasPassword(Context context) {
        return prefs(context).contains(PREF_DATA);
    }

    /** Saves the password (if not null) and turns auto-login on. */
    public static boolean save(Context context, String password) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(PREF_ENABLED, true);
        if (password != null) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key());
                byte[] data = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
                byte[] iv = cipher.getIV();
                byte[] out = new byte[1 + iv.length + data.length];
                out[0] = (byte)iv.length;
                System.arraycopy(iv, 0, out, 1, iv.length);
                System.arraycopy(data, 0, out, 1 + iv.length, data.length);
                editor.putString(PREF_DATA, Base64.encodeToString(out, Base64.NO_WRAP));
            }
            catch (Exception e) {
                return false;
            }
        }
        editor.apply();
        return true;
    }

    /** The WoW account name from WoW Settings, or "" if none. */
    public static String accountName(Context context) {
        return prefs(context).getString(PREF_ACCOUNT, "").trim();
    }

    public static void setAccountName(Context context, String name) {
        prefs(context).edit().putString(PREF_ACCOUNT, name.trim()).apply();
    }

    /** Turns auto-login off and deletes the saved password. */
    public static void clear(Context context) {
        prefs(context).edit().remove(PREF_DATA).putBoolean(PREF_ENABLED, false).apply();
    }

    /** The saved password, or null if there is none (or it can't be decrypted). */
    public static String load(Context context) {
        String stored = prefs(context).getString(PREF_DATA, null);
        if (stored == null) return null;
        try {
            byte[] in = Base64.decode(stored, Base64.NO_WRAP);
            int ivLength = in[0];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 1, ivLength));
            byte[] data = cipher.doFinal(in, 1 + ivLength, in.length - 1 - ivLength);
            return new String(data, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry)keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
