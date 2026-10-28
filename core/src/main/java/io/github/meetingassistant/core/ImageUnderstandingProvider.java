package io.github.meetingassistant.core;

public interface ImageUnderstandingProvider {
  interface Listener {
    void completed(String validatedStructure);

    void failed(String safeMessage);
  }

  void recognize(byte[] jpeg, Listener listener);

  void cancel();
}
