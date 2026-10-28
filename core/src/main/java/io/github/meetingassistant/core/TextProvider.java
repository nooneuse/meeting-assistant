package io.github.meetingassistant.core;

import com.google.gson.JsonArray;

public interface TextProvider {
  interface Listener {
    void delta(String content);

    void completed(boolean truncated);

    void failed(String safeMessage);

    default void contextLimit() {
      failed("文字模型上下文超限");
    }
  }

  void analyze(JsonArray messages, Listener listener);

  void cancel();
}
