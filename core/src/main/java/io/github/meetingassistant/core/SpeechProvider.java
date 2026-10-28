package io.github.meetingassistant.core;

public interface SpeechProvider {
  final class Segment {
    public final int id;
    public final String text;
    public final boolean isFinal;
    public final long startMs, endMs;

    public Segment(int id, String text, boolean isFinal, long startMs, long endMs) {
      this.id = id;
      this.text = text;
      this.isFinal = isFinal;
      this.startMs = startMs;
      this.endMs = endMs;
    }
  }

  interface Listener {
    void ready();

    void segment(Segment segment);

    void completed();

    void failed(String safeMessage);
  }

  void start(Listener listener);

  void sendAudio(byte[] pcm);

  void finish();

  void cancel();
}
