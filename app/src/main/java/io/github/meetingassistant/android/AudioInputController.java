package io.github.meetingassistant.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.AudioInputSettings;
import io.github.meetingassistant.core.AudioSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** One capture owner, independent of ASR/OCR lifecycles. Hardware is replaceable in local tests. */
final class AudioInputController {
  enum Kind {
    BUILTIN,
    USB,
    BLUETOOTH,
    OTHER
  }

  enum State {
    IDLE,
    OPENING,
    CAPTURING,
    SWITCHING,
    ERROR
  }

  record Device(
      String key, String name, Kind kind, int routeId, int communicationId, boolean ambiguous) {}

  interface Connection {
    byte[] read(); // PCM16LE, mono 16 kHz; bounded blocking read

    Device actual(); // null until routing is verifiable

    void interrupt(); // stop a blocking read without releasing on a competing thread

    void close();
  }

  interface Devices {
    List<Device> list();

    Connection open(Device device, BooleanSupplier canceled);

    void observe(Runnable changed);
  }

  static final class InputFailure extends IllegalStateException {
    final int message;

    InputFailure(int message) {
      this.message = message;
    }
  }

  private final Context context;
  private final AudioInputSettings settings;
  private final Devices devices;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicReference<Run> owner = new AtomicReference<>();
  private volatile State state = State.IDLE;
  private volatile String actual = "", error = "";
  private volatile int level;
  private volatile Runnable observer;
  private final AtomicBoolean updateQueued = new AtomicBoolean();

  AudioInputController(Context context, AudioInputSettings settings) {
    this(context, settings, new AndroidAudioDevices(context));
  }

  AudioInputController(Context context, AudioInputSettings settings, Devices devices) {
    this.context = context.getApplicationContext();
    this.settings = settings;
    this.devices = devices;
    devices.observe(this::devicesChanged);
  }

  private void devicesChanged() {
    Run r = owner.get();
    if (r != null && state == State.CAPTURING && r.selection.equals(r.requested)) {
      try {
        if (devices.list().stream().noneMatch(d -> d.key().equals(r.selection) && !d.ambiguous())) {
          r.failure.compareAndSet(0, R.string.audio_route_changed);
          r.halt(false);
        }
      } catch (RuntimeException e) {
        r.failure.compareAndSet(0, R.string.audio_route_changed);
        r.halt(false);
      }
    }
    changed();
  }

  List<Device> devices() {
    return devices.list();
  }

  State state() {
    return state;
  }

  String actual() {
    return actual;
  }

  String error() {
    return error;
  }

  int level() {
    return level;
  }

  boolean active() {
    return owner.get() != null;
  }

  boolean testing() {
    Run r = owner.get();
    return r != null && r.test;
  }

  void observe(Runnable listener) {
    observer = listener;
  }

  private void changed() {
    if (updateQueued.compareAndSet(false, true))
      main.postDelayed(
          () -> {
            updateQueued.set(false);
            Runnable listener = observer;
            if (listener != null) listener.run();
          },
          80);
  }

  String selected() {
    return settings.source;
  }

  boolean bluetoothSelected() {
    return settings.source.startsWith("bt:");
  }

  void select(String key) {
    Device target = find(key);
    if (target.ambiguous()) throw new InputFailure(R.string.audio_ambiguous);
    settings.source = key;
    Run r = owner.get();
    if (r != null) {
      if (r.test) {
        r.source.cancel();
      } else if (!r.requested.equals(key)) {
        r.requested = key;
        r.revision.incrementAndGet();
        state = State.SWITCHING;
        changed();
        Connection c = r.connection;
        if (c != null) c.interrupt();
      }
    } else {
      state = State.IDLE;
      error = "";
    }
    changed();
  }

  private Device find(String key) {
    List<Device> found =
        devices.list().stream()
            .filter(d -> d.key().equals(key))
            .collect(java.util.stream.Collectors.toList());
    if (found.size() != 1 || found.get(0).ambiguous())
      throw new InputFailure(found.isEmpty() ? R.string.audio_missing : R.string.audio_ambiguous);
    return found.get(0);
  }

  AudioSource source() {
    return new Source(false);
  }

  void test() {
    new Source(true)
        .start(
            new AudioSource.Listener() {
              public void audio(byte[] pcm) {} // local meter only; no model, playback or files

              public void failed(String ignored) {}
            });
  }

  void stopTest() {
    Run r = owner.get();
    if (r != null && r.test) r.source.cancel();
  }

  void background() {
    stopTest();
  }

  private final class Source implements AudioSource {
    private final boolean test;
    private Run run;

    Source(boolean test) {
      this.test = test;
    }

    public synchronized void start(Listener listener) {
      if (run != null) throw new IllegalStateException("Audio source is single use");
      run = new Run(this, listener, test, settings.source);
      if (!owner.compareAndSet(null, run)) {
        run = null;
        throw new InputFailure(R.string.audio_in_use);
      }
      state = State.OPENING;
      error = "";
      actual = "";
      level = 0;
      changed();
      run.start();
    }

    public synchronized void stop(Runnable drained) {
      if (run == null) {
        drained.run();
        return;
      }
      run.drained = drained;
      run.halt(false);
    }

    public synchronized void cancel() {
      if (run != null) run.halt(true);
    }
  }

  private record Delivery(byte[] pcm, String notice) {}

  private final class Run {
    final Source source;
    final AudioSource.Listener listener;
    final boolean test;
    volatile boolean stopping, canceled;
    volatile Connection connection;
    volatile String requested, selection;
    final AtomicLong revision = new AtomicLong();
    volatile Runnable drained;
    volatile boolean producerDone;
    final ArrayBlockingQueue<Delivery> queue = new ArrayBlockingQueue<>(20); // <= 2s
    final AtomicInteger failure = new AtomicInteger();

    Run(Source source, AudioSource.Listener listener, boolean test, String selection) {
      this.source = source;
      this.listener = listener;
      this.test = test;
      requested = this.selection = selection;
    }

    void halt(boolean cancel) {
      canceled |= cancel;
      stopping = true;
      Connection c = connection;
      if (c != null) c.interrupt();
      changed();
    }

    void start() {
      Thread send = new Thread(this::dispatch, "meeting-audio-delivery");
      send.setDaemon(true);
      send.start();
      Thread capture = new Thread(this::capture, "meeting-audio-capture");
      capture.setDaemon(true);
      capture.start();
    }

    boolean aborted() {
      return stopping || owner.get() != this;
    }

    void put(Delivery d) {
      if (!queue.offer(d)) throw new InputFailure(R.string.audio_backlog);
    }

    void capture() {
      try {
        try {
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (SecurityException ignored) {
        }
        boolean first = true;
        while (!aborted()) {
          long ticket = revision.get();
          selection = requested;
          if (!first) {
            String time =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new Date());
            put(new Delivery(null, UiLocale.wrap(context).getString(R.string.audio_gap, time)));
          }
          state = first ? State.OPENING : State.SWITCHING;
          actual = "";
          level = 0;
          changed();
          first = false;
          Device target = find(selection);
          try {
            connection = devices.open(target, () -> aborted() || ticket != revision.get());
          } catch (RuntimeException e) {
            if (aborted() || ticket != revision.get()) continue;
            throw e;
          }
          if (aborted() || ticket != revision.get()) {
            Connection old = connection;
            connection = null;
            old.close();
            continue;
          }
          long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
          while (!aborted() && ticket == revision.get()) {
            byte[] pcm = connection.read();
            if (aborted() || ticket != revision.get()) break;
            Device route = connection.actual();
            if (route == null) {
              // Never forward frames whose physical source cannot yet be verified.
              if (android.os.SystemClock.elapsedRealtime() > deadline)
                throw new InputFailure(R.string.audio_route_failed);
              continue;
            }
            if (!route.key().equals(target.key()))
              throw new InputFailure(R.string.audio_route_changed);
            actual = route.name();
            state = State.CAPTURING;
            level = peak(pcm);
            changed();
            if (!test && pcm.length > 0) put(new Delivery(pcm, null));
          }
          Connection old = connection;
          connection = null;
          old.close();
        }
      } catch (RuntimeException ex) {
        if (!stopping)
          failure.compareAndSet(
              0, ex instanceof InputFailure f ? f.message : R.string.audio_failed);
      } finally {
        Connection c = connection;
        connection = null;
        if (c != null)
          try {
            c.close();
          } catch (RuntimeException ignored) {
          }
        producerDone = true;
      }
    }

    void dispatch() {
      try {
        while (!producerDone || !queue.isEmpty()) {
          Delivery item = queue.poll(100, TimeUnit.MILLISECONDS);
          if (item != null && !canceled) {
            if (item.pcm() != null) listener.audio(item.pcm());
            else listener.discontinuity(item.notice());
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        failure.compareAndSet(0, R.string.audio_failed);
        halt(false);
      } finally {
        // Keep ownership until the capture thread has released its native recorder and route.
        while (!producerDone) {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
        actual = "";
        level = 0;
        int failed = failure.get();
        error = failed == 0 ? "" : UiLocale.wrap(context).getString(failed);
        state = failed == 0 || canceled ? State.IDLE : State.ERROR;
        owner.compareAndSet(this, null);
        changed();
        if (!canceled) {
          if (failed != 0) listener.failed(error);
          else if (drained != null) drained.run();
        }
      }
    }
  }

  static int peak(byte[] pcm) {
    int peak = 0;
    for (int i = 0; i + 1 < pcm.length; i += 2)
      peak = Math.max(peak, Math.abs((short) ((pcm[i] & 255) | pcm[i + 1] << 8)));
    return Math.min(100, peak * 100 / 32768);
  }
}
