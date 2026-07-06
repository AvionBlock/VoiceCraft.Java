package team.avion.adapter.simplevoicechat.net;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public final class SimpleVoiceChatSecret {
    public static final int SECRET_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] value;
    private final SecretKeySpec key;

    private SimpleVoiceChatSecret(byte[] value) {
        if (value.length != SECRET_BYTES) {
            throw new IllegalArgumentException("Simple Voice Chat secret must be " + SECRET_BYTES + " bytes");
        }
        this.value = value.clone();
        this.key = new SecretKeySpec(this.value, "AES");
    }

    public static SimpleVoiceChatSecret random() {
        byte[] value = new byte[SECRET_BYTES];
        RANDOM.nextBytes(value);
        return new SimpleVoiceChatSecret(value);
    }

    public static SimpleVoiceChatSecret of(byte[] value) {
        return new SimpleVoiceChatSecret(value);
    }

    public byte[] value() {
        return value.clone();
    }

    public byte[] encrypt(byte[] data) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(data);
        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return payload;
    }

    public byte[] decrypt(byte[] payload) throws Exception {
        if (payload.length < IV_BYTES) {
            throw new IllegalArgumentException("Encrypted payload is too small");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] encrypted = new byte[payload.length - IV_BYTES];
        System.arraycopy(payload, 0, iv, 0, iv.length);
        System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof SimpleVoiceChatSecret other)) {
            return false;
        }
        return Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }
}
