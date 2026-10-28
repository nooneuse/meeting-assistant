package io.github.meetingassistant.core;

import java.util.TreeMap;

public final class Transcript {
  private final TreeMap<Integer, SpeechProvider.Segment> segments = new TreeMap<>();

  public record Notice(int afterId, String text) {}

  private final java.util.List<Notice> notices = new java.util.ArrayList<>();

  public void notice(String text) {
    // Cap event spam without discarding recognized speech.
    if (notices.size() < 128)
      notices.add(new Notice(segments.isEmpty() ? Integer.MIN_VALUE : segments.lastKey(), text));
  }

  public java.util.List<Notice> notices() {
    return new java.util.ArrayList<>(notices);
  }

  public void restoreNotices(java.util.List<Notice> saved) {
    notices.clear();
    if (saved != null) notices.addAll(saved.subList(0, Math.min(128, saved.size())));
  }

  public static String render(
      java.util.List<SpeechProvider.Segment> segments,
      java.util.List<Notice> notices,
      boolean annotate,
      String partialMarker) {
    StringBuilder out = new StringBuilder();
    java.util.List<Notice> remaining =
        new java.util.ArrayList<>(notices == null ? java.util.List.of() : notices);
    for (SpeechProvider.Segment s : segments) {
      for (java.util.Iterator<Notice> it = remaining.iterator(); it.hasNext(); ) {
        Notice n = it.next();
        if (n.afterId() < s.id) {
          append(out, n.text());
          it.remove();
        }
      }
      if (!s.text.isEmpty()) append(out, (annotate && !s.isFinal ? partialMarker : "") + s.text);
    }
    for (Notice n : remaining) append(out, n.text());
    return out.toString();
  }

  private static void append(StringBuilder out, String value) {
    if (out.length() > 0) out.append('\n');
    out.append(value);
  }

  public void clear() {
    segments.clear();
    notices.clear();
  }

  public void update(SpeechProvider.Segment segment) {
    SpeechProvider.Segment old = segments.get(segment.id);
    if (old != null && old.isFinal && !segment.isFinal) return;
    segments.put(segment.id, segment);
  }

  public java.util.List<SpeechProvider.Segment> segments() {
    return new java.util.ArrayList<>(segments.values());
  }

  public String text() {
    return render(segments(), notices, false, "");
  }

  public String finalText() {
    return join(true);
  }

  public boolean hasPending() {
    for (SpeechProvider.Segment s : segments.values())
      if (!s.isFinal && !s.text.trim().isEmpty()) return true;
    return false;
  }

  public long finalEndMs() {
    long end = 0;
    for (SpeechProvider.Segment s : segments.values()) if (s.isFinal) end = Math.max(end, s.endMs);
    return end;
  }

  private String join(boolean finalsOnly) {
    StringBuilder out = new StringBuilder();
    for (SpeechProvider.Segment s : segments.values()) {
      if (finalsOnly && !s.isFinal || s.text.isEmpty()) continue;
      if (out.length() > 0) out.append('\n');
      out.append(s.text);
    }
    return out.toString();
  }
}
