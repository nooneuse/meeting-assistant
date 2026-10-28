package io.github.meetingassistant.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Opt-in physical audio routing test: local counters only, no model or audio file. */
final class AudioInputDeviceCheck {
  static void run(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    require(!app.session.snapshot().busy() && !app.audio.active(), "Audio test requires idle app");
    String previous = app.settings.audio.source;
    boolean awake = app.awake;
    int mode = app.getSystemService(AudioManager.class).getMode();
    AudioSource source = null;
    MainActivity activity = null;
    try {
      test.getUiAutomation()
          .grantRuntimePermission(app.getPackageName(), Manifest.permission.RECORD_AUDIO);
      if (Build.VERSION.SDK_INT >= 31)
        test.getUiAutomation()
            .grantRuntimePermission(app.getPackageName(), Manifest.permission.BLUETOOTH_CONNECT);
      DeviceChecks.onMain(test, () -> app.awake = true);
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      awaitMain(test, screen::hasWindowFocus, 10000, "Unlock phone to test audio");
      AudioInputController.Device builtin =
          app.audio.devices().stream()
              .filter(d -> d.kind() == AudioInputController.Kind.BUILTIN && !d.ambiguous())
              .findFirst()
              .orElseThrow(() -> new AssertionError("Built-in input missing"));
      AudioInputController.Device bluetooth =
          app.audio.devices().stream()
              .filter(d -> d.kind() == AudioInputController.Kind.BLUETOOTH && !d.ambiguous())
              .findFirst()
              .orElseThrow(() -> new AssertionError("No Bluetooth voice microphone available"));
      DeviceChecks.onMain(test, () -> app.audio.select(builtin.key()));
      AtomicInteger frames = new AtomicInteger(),
          gaps = new AtomicInteger(),
          peak = new AtomicInteger();
      AtomicLong bytes = new AtomicLong(),
          previousAt = new AtomicLong(),
          maximumGap = new AtomicLong();
      AtomicReference<String> error = new AtomicReference<>("");
      source = app.audio.source();
      source.start(
          new AudioSource.Listener() {
            public void audio(byte[] pcm) {
              frames.incrementAndGet();
              bytes.addAndGet(pcm.length);
              peak.accumulateAndGet(AudioInputController.peak(pcm), Math::max);
              long at = SystemClock.elapsedRealtime(), before = previousAt.getAndSet(at);
              if (before > 0) maximumGap.accumulateAndGet(at - before, Math::max);
            }

            public void discontinuity(String notice) {
              gaps.incrementAndGet();
            }

            public void failed(String message) {
              error.set(message);
            }
          });
      await(() -> frames.get() >= 15 || !error.get().isEmpty(), 10000, "Built-in PCM unavailable");
      require(error.get().isEmpty(), "Built-in capture: " + error.get());
      require(app.audio.state() == AudioInputController.State.CAPTURING, "Built-in not capturing");
      progress(test, "builtin_pcm", frames.get());
      int before = frames.get();
      int bluetoothTarget = before + 25;
      for (AudioInputController.Device d : app.audio.devices())
        progress(
            test,
            "candidate",
            d.kind()
                + ":"
                + d.routeId()
                + ":"
                + d.communicationId()
                + ":"
                + d.ambiguous());
      DeviceChecks.onMain(
          test,
          () -> {
            try {
              app.audio.select(bluetooth.key());
            } catch (AudioInputController.InputFailure failure) {
              throw new AssertionError("Bluetooth selection: " + app.getString(failure.message));
            }
          });
      await(
          () ->
              app.audio.state() == AudioInputController.State.CAPTURING
                      && frames.get() >= bluetoothTarget
                  || !error.get().isEmpty(),
          40000,
          "Bluetooth capture timeout");
      require(error.get().isEmpty(), "Bluetooth capture: " + error.get());
      require(app.audio.selected().equals(bluetooth.key()), "Bluetooth selection lost");
      require(gaps.get() == 1, "Switch event missing or duplicated");
      progress(test, "bluetooth_pcm", frames.get() - before);
      progress(test, "local_peak_percent", peak.get());
      before = frames.get();
      DeviceChecks.onMain(test, () -> app.audio.select(builtin.key()));
      int target = before + 15;
      await(
          () -> frames.get() >= target || !error.get().isEmpty(), 15000, "Built-in return timeout");
      require(error.get().isEmpty(), "Return capture: " + error.get());
      require(gaps.get() == 2, "Return switch event missing");
      CountDownLatch drained = new CountDownLatch(1);
      source.stop(drained::countDown);
      require(drained.await(5, TimeUnit.SECONDS), "Audio did not drain");
      require(!app.audio.active(), "Capture still owned after stop");
      require(
          app.getSystemService(AudioManager.class).getMode() == mode, "Audio mode not restored");
      if (Build.VERSION.SDK_INT >= 31) {
        var communication = app.getSystemService(AudioManager.class).getCommunicationDevice();
        require(
            communication == null || !AndroidAudioDevices.bluetooth(communication.getType()),
            "Bluetooth communication route still held");
      }
      progress(test, "pcm_bytes", bytes.get());
      progress(test, "maximum_gap_including_switch_ms", maximumGap.get());
      // Verify the shipped settings UI's test button uses the same controller, never ASR.
      DeviceChecks.onMain(test, () -> screen.findViewById(R.id.image_input_button).performClick());
      test.waitForIdleSync();
      AtomicReference<Dialog> page = new AtomicReference<>();
      DeviceChecks.onMain(
          test,
          () -> {
            Dialog drawer = topDialog(screen);
            require(
                drawer != null && drawer.findViewById(R.id.audio_settings) != null,
                "Audio drawer entry missing");
            drawer.findViewById(R.id.audio_settings).performClick();
          });
      test.waitForIdleSync();
      DeviceChecks.onMain(
          test,
          () -> {
            page.set(topDialog(screen));
            require(
                page.get() != null && page.get().findViewById(R.id.audio_test) != null,
                "Audio settings test missing");
            page.get().findViewById(R.id.audio_test).performClick();
          });
      await(
          () -> app.audio.testing() && app.audio.state() == AudioInputController.State.CAPTURING,
          10000,
          "Settings local test did not start");
      require(
          app.session.snapshot().state != SessionEngine.State.LISTENING, "Local test started ASR");
      DeviceChecks.onMain(
          test, () -> page.get().findViewById(R.id.audio_settings_back).performClick());
      await(() -> !app.audio.active(), 5000, "Leaving settings retained microphone");
      progress(
          test, "result_local_only", "BUILTIN_BT_BUILTIN_DRAIN_MODE_RELEASE_AND_SETTINGS_PASSED");
    } finally {
      if (source != null) source.cancel();
      app.audio.stopTest();
      await(() -> !app.audio.active(), 6000, "Audio cleanup timed out");
      MainActivity screen = activity;
      DeviceChecks.onMain(
          test,
          () -> {
            app.settings.audio.source = previous;
            app.repository.save(app.settings);
            app.awake = awake;
            if (screen != null) screen.finish();
          });
    }
  }

  static void signal(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    require(!app.session.snapshot().busy() && !app.audio.active(), "Signal test requires idle app");
    String previous = app.settings.audio.source;
    boolean awake = app.awake;
    MainActivity activity = null;
    AudioSource source = null;
    try {
      require(
          !app.getSystemService(AudioManager.class).isMicrophoneMute(),
          "System microphone is muted");
      DeviceChecks.onMain(test, () -> app.awake = true);
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      awaitMain(test, screen::hasWindowFocus, 10000, "Unlock phone to test audio");
      AudioInputController.Device bluetooth =
          app.audio.devices().stream()
              .filter(d -> d.kind() == AudioInputController.Kind.BLUETOOTH && !d.ambiguous())
              .findFirst()
              .orElseThrow(() -> new AssertionError("No Bluetooth microphone available"));
      DeviceChecks.onMain(test, () -> app.audio.select(bluetooth.key()));
      AtomicInteger frames = new AtomicInteger(),
          rawPeak = new AtomicInteger(),
          nonzero = new AtomicInteger();
      AtomicLong squares = new AtomicLong(), samples = new AtomicLong();
      AtomicReference<String> error = new AtomicReference<>("");
      source = app.audio.source();
      source.start(
          new AudioSource.Listener() {
            public void audio(byte[] pcm) {
              frames.incrementAndGet();
              for (int i = 0; i + 1 < pcm.length; i += 2) {
                int value = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                rawPeak.accumulateAndGet(Math.abs(value), Math::max);
                if (value != 0) nonzero.incrementAndGet();
                squares.addAndGet((long) value * value);
                samples.incrementAndGet();
              }
            }

            public void failed(String message) {
              error.set(message);
            }
          });
      await(
          () -> frames.get() > 0 || !error.get().isEmpty(), 35000, "Bluetooth signal not started");
      require(error.get().isEmpty(), "Bluetooth signal: " + error.get());
      progress(test, "signal_window", "SPEAK_NOW_20_SECONDS_LOCAL_ONLY");
      for (int i = 0; i < 20; i++) {
        Thread.sleep(1000);
        require(error.get().isEmpty(), "Bluetooth signal: " + error.get());
        if (i % 5 == 4) progress(test, "raw_peak_so_far", rawPeak.get());
      }
      CountDownLatch drained = new CountDownLatch(1);
      source.stop(drained::countDown);
      require(drained.await(5, TimeUnit.SECONDS), "Signal source did not drain");
      progress(test, "signal_frames", frames.get());
      progress(test, "signal_raw_peak", rawPeak.get());
      progress(test, "signal_nonzero_samples", nonzero.get());
      progress(
          test, "signal_rms", (int) Math.sqrt(squares.get() / (double) Math.max(1, samples.get())));
      require(nonzero.get() > 0, "Bluetooth supplied all-zero PCM; check wear/mute/privacy");
      progress(test, "result_local_only", "BLUETOOTH_NONZERO_PCM_NO_FILE_NO_MODEL");
    } finally {
      if (source != null) source.cancel();
      await(() -> !app.audio.active(), 6000, "Signal cleanup timeout");
      MainActivity screen = activity;
      DeviceChecks.onMain(
          test,
          () -> {
            app.settings.audio.source = previous;
            app.repository.save(app.settings);
            app.awake = awake;
            if (screen != null) screen.finish();
          });
    }
  }

  // Activity owns these dialogs; reflection is confined to the explicitly installed test APK.
  private static Dialog topDialog(MainActivity activity) {
    try {
      java.lang.reflect.Field image = MainActivity.class.getDeclaredField("imageUi");
      image.setAccessible(true);
      Object ui = image.get(activity);
      java.lang.reflect.Field drawer = ImageInputUi.class.getDeclaredField("drawer");
      drawer.setAccessible(true);
      java.lang.reflect.Field audio = MainActivity.class.getDeclaredField("audioUi");
      audio.setAccessible(true);
      Object a = audio.get(activity);
      java.lang.reflect.Field settings = AudioInputUi.class.getDeclaredField("settings");
      settings.setAccessible(true);
      Dialog s = (Dialog) settings.get(a);
      return s != null && s.isShowing() ? s : (Dialog) drawer.get(ui);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Cannot inspect test dialogs", e);
    }
  }

  private static void progress(Instrumentation test, String key, Object value) {
    Bundle b = new Bundle();
    b.putString(key, String.valueOf(value));
    test.sendStatus(0, b);
  }

  private static void await(BooleanSupplier done, long timeout, String message) throws Exception {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (!done.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
    require(done.getAsBoolean(), message);
  }

  private static void awaitMain(
      Instrumentation t, BooleanSupplier done, long timeout, String message) throws Exception {
    AtomicBoolean ready = new AtomicBoolean();
    await(
        () -> {
          DeviceChecks.onMain(t, () -> ready.set(done.getAsBoolean()));
          return ready.get();
        },
        timeout,
        message);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
