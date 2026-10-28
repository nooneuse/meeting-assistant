package io.github.meetingassistant.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned AES-GCM envelope. Identity is authenticated to prevent swapping meeting rows. */
public final class EncryptedPayload {
  public interface Key {
    SecretKey get() throws GeneralSecurityException;
  }

  private final Key key;

  public EncryptedPayload(Key key) {
    this.key = key;
  }

  public byte[] encrypt(String id, byte[] plain) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key.get());
      cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
      byte[] iv = cipher.getIV(), ciphertext = cipher.doFinal(plain);
      if (iv.length != 12) throw new GeneralSecurityException();
      byte[] out = new byte[13 + ciphertext.length];
      out[0] = 1;
      System.arraycopy(iv, 0, out, 1, 12);
      System.arraycopy(ciphertext, 0, out, 13, ciphertext.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("会议记录加密失败");
    }
  }

  public byte[] decrypt(String id, byte[] envelope) {
    try {
      if (envelope.length < 29 || envelope[0] != 1) throw new GeneralSecurityException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key.get(),
          new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
      cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(envelope, 13, envelope.length - 13);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("会议记录解密失败，原数据已保留");
    }
  }
}
