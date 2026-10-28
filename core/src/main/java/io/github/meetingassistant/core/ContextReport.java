package io.github.meetingassistant.core;

/** Small diagnostics only: never provider URLs, keys or transcript contents. */
public final class ContextReport {
  public int input, before, budget = 12000;
  public long firstMs = -1, totalMs = -1, notice;
  public boolean longMeeting, slow, limited, summarized, organizing, organizeFailed;

  public boolean visible() {
    return longMeeting || slow || limited || summarized || organizing || organizeFailed;
  }

  public ContextReport copy() {
    ContextReport r = new ContextReport();
    r.input = input;
    r.before = before;
    r.budget = budget;
    r.firstMs = firstMs;
    r.totalMs = totalMs;
    r.notice = notice;
    r.longMeeting = longMeeting;
    r.slow = slow;
    r.limited = limited;
    r.summarized = summarized;
    r.organizing = organizing;
    r.organizeFailed = organizeFailed;
    return r;
  }
}
