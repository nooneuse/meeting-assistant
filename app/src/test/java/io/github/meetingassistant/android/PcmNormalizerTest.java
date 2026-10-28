package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;

public class PcmNormalizerTest {
  @Test
  public void preservesPcm16LittleEndianAndSaturation() {
    byte[] pcm = new PcmNormalizer(16000, 1).convert(new short[] {-32768, 32767, 0, -1}, 4);
    assertArrayEquals(
        new byte[] {0, (byte) 128, (byte) 255, 127, 0, 0, (byte) 255, (byte) 255}, pcm);
    assertEquals(100, AudioInputController.peak(pcm));
  }

  @Test
  public void fractionalResamplingAndStereoAreIndependentOfChunkBoundaries() {
    short[] in = new short[44100 * 2];
    for (int i = 0; i < in.length; i++) in[i] = (short) (i * 73);
    byte[] whole = new PcmNormalizer(44100, 2).convert(in, in.length);
    PcmNormalizer stream = new PcmNormalizer(44100, 2);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < in.length; i += 137) {
      short[] chunk = Arrays.copyOfRange(in, i, Math.min(i + 137, in.length));
      out.writeBytes(stream.convert(chunk, chunk.length));
    }
    assertEquals(32000, whole.length);
    assertArrayEquals(whole, out.toByteArray());
  }

  @Test
  public void downmixUsesWideAccumulatorAndMaintainsConstantLevel() {
    short[] in = new short[9600];
    Arrays.fill(in, (short) 30000);
    byte[] pcm = new PcmNormalizer(48000, 2).convert(in, in.length);
    assertEquals(3200, pcm.length);
    for (int i = 0; i < pcm.length; i += 2)
      assertEquals(30000, (short) ((pcm[i] & 255) | pcm[i + 1] << 8));
  }

  @Test
  public void eightKhzInputIsConvertedToServiceRate() {
    short[] in = new short[800];
    Arrays.fill(in, (short) 8192);
    byte[] pcm = new PcmNormalizer(8000, 1).convert(in, in.length);
    assertEquals(3200, pcm.length);
    assertEquals(25, AudioInputController.peak(pcm));
  }
}
