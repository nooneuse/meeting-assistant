package io.github.meetingassistant.android;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit native-window regression. In-memory meeting, no microphone, providers or clipboard. */
final class ToolbarIdleDeviceCheck {
  static void run(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    MeetingManager original = app.meetings;
    require(!app.session.snapshot().busy(), "Toolbar check requires idle session");
    AtomicReference<SpeechProvider.Listener> speech = new AtomicReference<>();
    SessionEngine fake =
        new SessionEngine(
            new SessionEngine.Factory() {
              public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                return new SpeechProvider() {
                  public void start(Listener l) {
                    speech.set(l);
                  }

                  public void sendAudio(byte[] pcm) {
                    throw new AssertionError("Unexpected PCM");
                  }

                  public void finish() {
                    speech.get().completed();
                  }

                  public void cancel() {}
                };
              }

              public AudioSource audio() {
                return new AudioSource() {
                  public void start(Listener l) {}

                  public void stop(Runnable done) {
                    done.run();
                  }

                  public void cancel() {}
                };
              }

              public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                throw new AssertionError("Unexpected model");
              }
            });
    MainActivity activity = null;
    Dialog overlay = null;
    try {
      DeviceChecks.onMain(
          test,
          () ->
              app.bindMeetings(
                  new MeetingManager(
                      new MeetingManager.MemoryStore(),
                      () -> fake,
                      app.settings.snapshot(),
                      false)));
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      awaitFocus(test, screen, true);
      DeviceChecks.onMain(
          test,
          () -> {
            View bulb = screen.findViewById(R.id.awake_button),
                input = screen.findViewById(R.id.image_input_button);
            require(input.getLeft() >= bulb.getRight(), "Input selector not rightmost");
            for (View button : new View[] {bulb, input}) {
              require(button.getBackground() instanceof RippleDrawable, "Icon feedback missing");
              require(
                  ((RippleDrawable) button.getBackground()).getNumberOfLayers() == 1,
                  "Icon has permanent background");
            }
          });
      saveScreen(test, "toolbar-idle-visible.png");
      SystemClock.sleep(4600);
      DeviceChecks.onMain(test, () -> bars(screen, false));
      DeviceChecks.onMain(
          test,
          () -> {
            SessionEngine.SavedState interrupted = new SessionEngine.SavedState();
            interrupted.state = SessionEngine.State.LISTENING;
            fake.restore(interrupted);
          });
      SystemClock.sleep(4600);
      DeviceChecks.onMain(
          test,
          () -> {
            bars(screen, false);
            require(
                screen.findViewById(R.id.meeting_status).isShown(), "Interrupted status hidden");
            require(
                fake.snapshot().state == SessionEngine.State.ERROR, "Interrupted restore missing");
          });
      ProviderConfig config = new ProviderConfig();
      config.asrUrl = "wss://example.org/asr";
      config.textBaseUrl = "https://example.org/v1";
      DeviceChecks.onMain(
          test,
          () ->
              fake.start(
                  config,
                  new ProviderConfig.Credentials("synthetic", "synthetic"),
                  app.settings.snapshot()));
      SystemClock.sleep(4600);
      DeviceChecks.onMain(
          test,
          () -> {
            bars(screen, false);
            require(
                fake.snapshot().state == SessionEngine.State.CONNECTING,
                "Synthetic connection changed");
          });
      DeviceChecks.onMain(test, () -> speech.get().ready());
      SystemClock.sleep(1600);
      DeviceChecks.onMain(test, () -> bars(screen, false));
      tap(test, screen);
      SystemClock.sleep(350);
      DeviceChecks.onMain(test, () -> bars(screen, true));
      SystemClock.sleep(4600);
      DeviceChecks.onMain(test, () -> bars(screen, false));
      tap(test, screen);
      SystemClock.sleep(350);
      DeviceChecks.onMain(test, () -> bars(screen, true));
      // Emulate an interrupted gesture, then let a real native dialog take/return window focus.
      AtomicReference<Dialog> dialog = new AtomicReference<>();
      DeviceChecks.onMain(
          test,
          () -> {
            long at = SystemClock.uptimeMillis();
            MotionEvent down =
                MotionEvent.obtain(
                    at,
                    at,
                    MotionEvent.ACTION_DOWN,
                    screen.getWindow().getDecorView().getWidth() / 2f,
                    screen.getWindow().getDecorView().getHeight() / 2f,
                    0);
            screen.dispatchTouchEvent(down);
            down.recycle();
            Dialog d = new Dialog(screen);
            d.setContentView(new TextView(screen));
            dialog.set(d);
            d.show();
          });
      overlay = dialog.get();
      awaitFocus(test, screen, false);
      SystemClock.sleep(500);
      DeviceChecks.onMain(test, () -> dialog.get().dismiss());
      awaitFocus(test, screen, true);
      SystemClock.sleep(4600);
      DeviceChecks.onMain(
          test,
          () -> {
            bars(screen, false);
          });
      saveScreen(test, "toolbar-idle-hidden.png");
      Bundle result = new Bundle();
      result.putString(
          "checks",
          "IDLE_RESTORED_ERROR_CONNECTING_LISTENING_TAP_NATIVE_FOCUS_AND_TRANSPARENT_RIGHTMOST_ICONS");
      test.sendStatus(0, result);
    } finally {
      Dialog d = overlay;
      if (d != null) DeviceChecks.onMain(test, d::dismiss);
      MainActivity screen = activity;
      if (screen != null) DeviceChecks.onMain(test, screen::finish);
      fake.close();
      DeviceChecks.onMain(
          test,
          () -> {
            app.meetings.close();
            app.bindMeetings(original);
          });
    }
  }

  private static void bars(MainActivity screen, boolean shown) {
    int visibility = shown ? View.VISIBLE : View.GONE;
    require(
        screen.findViewById(R.id.meeting_toolbar).getVisibility() == visibility,
        "Top toolbar visibility incorrect");
    require(
        screen.findViewById(R.id.meeting_controls).getVisibility() == visibility,
        "Bottom toolbar visibility incorrect");
    require(screen.findViewById(R.id.meeting_status).isShown(), "Status disappeared");
  }

  private static void awaitFocus(Instrumentation test, MainActivity screen, boolean focus) {
    long until = SystemClock.elapsedRealtime() + 10000;
    AtomicReference<Boolean> current = new AtomicReference<>(!focus);
    do {
      DeviceChecks.onMain(test, () -> current.set(screen.hasWindowFocus()));
      if (current.get() == focus) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < until);
    throw new AssertionError("Native window focus did not change; unlock phone");
  }

  private static void tap(Instrumentation test, MainActivity screen) {
    int[] point = new int[2];
    DeviceChecks.onMain(
        test,
        () -> {
          View v = screen.findViewById(R.id.meeting_content);
          v.getLocationOnScreen(point);
          point[0] += v.getWidth() / 2;
          point[1] += v.getHeight() / 2;
        });
    long at = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, point[0], point[1], 0),
        up = MotionEvent.obtain(at, at + 50, MotionEvent.ACTION_UP, point[0], point[1], 0);
    test.sendPointerSync(down);
    test.sendPointerSync(up);
    down.recycle();
    up.recycle();
  }

  private static void saveScreen(Instrumentation test, String name) {
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    require(bitmap != null, "No screenshot");
    try (FileOutputStream out =
        new FileOutputStream(new File(test.getTargetContext().getCacheDir(), name))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "Screenshot encoding failed");
    } catch (java.io.IOException e) {
      throw new AssertionError("Cannot save screenshot");
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
