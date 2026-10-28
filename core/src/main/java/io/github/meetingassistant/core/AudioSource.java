package io.github.meetingassistant.core;

public interface AudioSource {
  interface Listener {
    void audio(byte[] pcm);

    void failed(String safeMessage);

    /** An application event, never a recognized utterance. Does not end the ASR session. */
    default void discontinuity(String notice) {}
  }

  void start(Listener listener);

  /** Completion runs after the read loop has delivered its last captured frame. */
  void stop(Runnable drained);

  void cancel();
}
