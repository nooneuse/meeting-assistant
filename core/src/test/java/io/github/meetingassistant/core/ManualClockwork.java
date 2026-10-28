package io.github.meetingassistant.core;

import java.util.PriorityQueue;

/** Deterministic virtual time: a minute of slow model output takes no wall-clock sleep. */
final class ManualClockwork implements SessionEngine.Clockwork {
  private static final class Task implements Comparable<Task> {
    final long at, order;
    final Runnable run;

    Task(long at, long order, Runnable run) {
      this.at = at;
      this.order = order;
      this.run = run;
    }

    public int compareTo(Task other) {
      int c = Long.compare(at, other.at);
      return c != 0 ? c : Long.compare(order, other.order);
    }
  }

  private final PriorityQueue<Task> tasks = new PriorityQueue<>();
  long time, order;

  public long now() {
    return time;
  }

  public void schedule(Runnable task, long delay) {
    tasks.add(new Task(time + Math.max(0, delay), ++order, task));
  }

  public void execute(Runnable task) {
    task.run();
  }

  public void close() {
    tasks.clear();
  }

  void advance(long ms) {
    long end = time + ms;
    while (!tasks.isEmpty() && tasks.peek().at <= end) {
      Task t = tasks.remove();
      time = t.at;
      t.run.run();
    }
    time = end;
  }
}
