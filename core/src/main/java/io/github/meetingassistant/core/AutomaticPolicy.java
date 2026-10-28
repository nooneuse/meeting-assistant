package io.github.meetingassistant.core;

/** Lightweight timing heuristics; finalized transcript remains the source of truth. */
final class AutomaticPolicy {
  static final long QUIET_MS = 1200, CONTINUOUS_MS = 8000, INTERVAL_MS = 5000;
  private long changedAt, pendingAt = -1, lastRequestAt = -INTERVAL_MS;
  private boolean urgent;

  void reset(long now) {
    changedAt = now;
    pendingAt = -1;
    lastRequestAt = now - INTERVAL_MS;
    urgent = false;
  }

  void changed(long now, boolean stableChanged, String addition) {
    changedAt = now;
    if (stableChanged) {
      if (pendingAt < 0) pendingAt = now;
      urgent |= addition.matches("(?s).*(？|\\?|怎么|如何|为什么|请问|求解|不对|更正|改为|换个问题|换一个问题).*");
    }
  }

  long delay(long now) {
    long ready = urgent ? now : Math.min(changedAt + QUIET_MS, pendingAt + CONTINUOUS_MS);
    return Math.max(0, Math.max(ready, lastRequestAt + INTERVAL_MS) - now);
  }

  void requested(long now) {
    pendingAt = -1;
    urgent = false;
    lastRequestAt = now;
  }

  static boolean meaningful(String text) {
    return !text.replaceAll("[\\s\\p{Punct}，。！？、嗯啊哦呃好的对是]", "").isEmpty();
  }

  static boolean correction(String text) {
    return text.matches("(?s).*(不对|更正|纠正|改为|换个问题|换一个问题|不是.{0,20}而是).*");
  }
}
