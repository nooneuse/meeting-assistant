package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public class EncryptedPayloadTest {
  @Test
  public void randomIvAndAuthenticationProtectContentAndIdentity() throws Exception {
    SecretKey key = KeyGenerator.getInstance("AES").generateKey();
    EncryptedPayload box = new EncryptedPayload(() -> key);
    byte[] clear = "私有会议标题和原文".getBytes(StandardCharsets.UTF_8);
    byte[] first = box.encrypt("id-a", clear), second = box.encrypt("id-a", clear);
    assertFalse(java.util.Arrays.equals(first, second));
    assertArrayEquals(clear, box.decrypt("id-a", first));
    assertThrows(IllegalStateException.class, () -> box.decrypt("id-b", first));
    first[first.length - 1] ^= 1;
    assertThrows(IllegalStateException.class, () -> box.decrypt("id-a", first));
    SecretKey other = KeyGenerator.getInstance("AES").generateKey();
    assertThrows(
        IllegalStateException.class,
        () -> new EncryptedPayload(() -> other).decrypt("id-a", second));
  }
}
