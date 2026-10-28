package io.github.meetingassistant.android;

import java.io.ByteArrayOutputStream;

/** Streaming area resampling/downmix; retains fractional phase and split stereo frames. */
final class PcmNormalizer {
  private final int channels;
  private final double width;
  private double occupied, sum;
  private int channel, frameSum;

  PcmNormalizer(int rate, int channels) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2)
      throw new IllegalArgumentException("Unsupported PCM format");
    this.channels = channels;
    width = rate / 16000.0;
  }

  byte[] convert(short[] input, int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      frameSum += input[i];
      if (++channel != channels) continue;
      double value = frameSum / (double) channels;
      frameSum = channel = 0;
      double left = 1;
      while (left > 1e-8) {
        double used = Math.min(left, width - occupied);
        sum += value * used;
        occupied += used;
        left -= used;
        if (occupied + 1e-8 >= width) {
          int sample = Math.max(-32768, Math.min(32767, (int) Math.round(sum / width)));
          out.write(sample & 255);
          out.write((sample >>> 8) & 255);
          occupied = sum = 0;
        }
      }
    }
    return out.toByteArray();
  }
}
