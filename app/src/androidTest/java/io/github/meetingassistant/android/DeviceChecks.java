package io.github.meetingassistant.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.AudioSource;
import io.github.meetingassistant.core.MeetingManager;
import io.github.meetingassistant.core.ProviderConfig;
import io.github.meetingassistant.core.SessionEngine;
import io.github.meetingassistant.core.SpeechProvider;
import io.github.meetingassistant.core.TextProvider;
import io.github.meetingassistant.core.UserSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Opt-in tests installed only in the androidTest APK. No diagnostic listener is in the app APK. */
final class DeviceChecks {
  static void run(Instrumentation test, String mode) {
    Bundle result = new Bundle();
    try {
      onMain(test, () -> {});
      switch (mode) {
        case "image-usb-preflight":
          ImageDeviceCheck.preflight(test);
          break;
        case "image-usb-hardware":
          ImageDeviceCheck.run(test, true);
          break;
        case "image-live":
        case "image-live-audio":
        case "image-live-usb":
        case "image-live-usb-audio":
          LiveImageDeviceCheck.run(test, mode.contains("usb"), mode.contains("audio"));
          break;
        case "audio-input-signal":
          AudioInputDeviceCheck.signal(test);
          break;
        case "audio-input":
          AudioInputDeviceCheck.run(test);
          break;
        case "image-hardware":
          ImageDeviceCheck.run(test);
          break;
        case "keyboard-modal-auto":
          KeyboardDeviceCheck.runModalAuto(test);
          break;
        case "keyboard-modal":
          KeyboardDeviceCheck.runModal(test);
          break;
        case "keyboard":
          KeyboardDeviceCheck.run(test);
          break;
        case "topic-collections":
          TopicCollectionsDeviceCheck.run(test);
          break;
        case "topic-migration-baseline":
          TopicMigrationCheck.baseline(test);
          break;
        case "topic-migration-verify":
          TopicMigrationCheck.verify(test);
          break;
        case "meetings":
          DeviceMeetingCheck.run(test);
          break;
        case "theme":
          InputStyleDeviceCheck.run(test,true);
          break;
        case "input-style":
          InputStyleDeviceCheck.run(test);
          break;
        case "toolbar-idle":
          ToolbarIdleDeviceCheck.run(test);
          break;
        case "toolbar":
          toolbar(test);
          break;
        default:
          throw new IllegalArgumentException("Unknown test mode");
      }
      result.putString("result", "PASS: " + mode);
      test.finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("result", "FAIL: " + mode + "; " + e.getClass().getSimpleName());
      if (e instanceof AssertionError) result.putString("check", e.getMessage());
      test.finish(Activity.RESULT_CANCELED, result);
    }
  }

  /** Uses an entirely synthetic session: no microphone, model calls, or saved credential access. */
  private static void toolbar(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    SessionEngine original = app.session;
    MeetingManager originalMeetings = app.meetings;
    require(!original.snapshot().busy(), "toolbar test requires idle session");
    AtomicReference<SpeechProvider.Listener> speech = new AtomicReference<>();
    SessionEngine fake =
        new SessionEngine(
            new SessionEngine.Factory() {
              public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                return new SpeechProvider() {
                  public void start(Listener l) {
                    speech.set(l);
                    l.ready();
                  }

                  public void sendAudio(byte[] pcm) {
                    throw new AssertionError("Unexpected audio");
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
                return new TextProvider() {
                  public void analyze(com.google.gson.JsonArray messages, Listener l) {
                    l.delta("界面测试答案：仅用于检查顶部收起与内容空间。没有采集声音或调用模型。");
                    l.completed(false);
                  }

                  public void cancel() {}
                };
              }
            });
    MainActivity activity = null;
    boolean originalAwake = app.awake;
    try {
      onMain(
          test,
          () ->
              app.bindMeetings(
                  new MeetingManager(
                      new MeetingManager.MemoryStore(),
                      () -> fake,
                      app.settings.snapshot(),
                      app.settings.automatic)));
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(test.getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      test.waitForIdleSync();
      final int[] expandedHeight = new int[1];
      onMain(
          test,
          () -> {
            require(screen.hasWindowFocus(), "unlock the phone before toolbar checks");
            for (int id : new int[] {R.id.awake_button, R.id.meetings_button, R.id.copy_answer}) {
              View button = screen.findViewById(id);
              require(
                  button.getWidth() == button.getHeight() && button.getWidth() > 0,
                  "icon touch area is not square");
              require(button.getContentDescription() != null, "icon has no accessible name");
            }
            View bulb = screen.findViewById(R.id.awake_button);
            bulb.performClick();
            require(app.awake != originalAwake, "bulb did not toggle awake");
            bulb.performClick();
            require(app.awake == originalAwake, "bulb did not restore awake");
            expandedHeight[0] = screen.findViewById(R.id.meeting_content).getHeight();
          });
      toolbarScreenshot(test, screen, "toolbar-visible.png");
      SystemClock.sleep(4500);
      onMain(
          test,
          () -> {
            require(
                screen.findViewById(R.id.meeting_toolbar).getVisibility() == View.GONE,
                "idle toolbar did not collapse");
            require(
                screen.findViewById(R.id.meeting_controls).getVisibility() == View.GONE,
                "idle bottom controls did not collapse");
            TextView state = screen.findViewById(R.id.meeting_status);
            require(
                state.isShown()
                    && screen.getString(R.string.notice_ready).contentEquals(state.getText()),
                "idle status not visible outside toolbar");
          });
      ProviderConfig config = new ProviderConfig();
      config.asrUrl = "wss://example.org/asr";
      config.textBaseUrl = "https://example.org/v1";
      onMain(
          test,
          () ->
              fake.start(
                  config,
                  new ProviderConfig.Credentials("synthetic", "synthetic"),
                  app.settings.snapshot()));
      SystemClock.sleep(1800);
      test.waitForIdleSync();
      onMain(
          test,
          () -> {
            require(
                screen.findViewById(R.id.meeting_toolbar).getVisibility() == View.GONE,
                "toolbar did not collapse after listening started");
            require(
                screen.findViewById(R.id.meeting_content).getHeight() > expandedHeight[0],
                "content did not gain space");
            require(
                screen.findViewById(R.id.meeting_controls).getVisibility() == View.GONE,
                "bottom controls did not collapse with toolbar");
            TextView state = screen.findViewById(R.id.meeting_status);
            require(
                state.isShown()
                    && state.getText().toString().startsWith(screen.getString(R.string.listening)),
                "listening status hidden with toolbar");
          });
      toolbarScreenshot(test, screen, "toolbar-hidden.png");
      int[] point = new int[2];
      onMain(
          test,
          () -> {
            View content = screen.findViewById(R.id.meeting_content);
            content.getLocationOnScreen(point);
            point[0] += content.getWidth() / 2;
            point[1] += content.getHeight() / 2;
          });
      long now = SystemClock.uptimeMillis();
      MotionEvent down =
          MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
      MotionEvent up =
          MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, point[0], point[1], 0);
      test.sendPointerSync(down);
      test.sendPointerSync(up);
      down.recycle();
      up.recycle();
      SystemClock.sleep(400);
      onMain(
          test,
          () -> {
            require(
                screen.findViewById(R.id.meeting_toolbar).getVisibility() == View.VISIBLE,
                "tap did not reveal toolbar");
            require(
                screen.findViewById(R.id.meeting_controls).getVisibility() == View.VISIBLE,
                "tap did not reveal bottom controls");
          });
      SystemClock.sleep(4300);
      onMain(
          test,
          () ->
              require(
                  screen.findViewById(R.id.meeting_toolbar).getVisibility() == View.GONE,
                  "toolbar did not collapse after four seconds"));
      speech.get().segment(new SpeechProvider.Segment(1, "界面测试原文", true, 0, 1));
      Integer recordKey = app.settings.bindings.get(UserSettings.RECORD);
      require(
          app.settings.keyboardEnabled && recordKey != null,
          "enable and bind the recording shortcut before keyboard checks");
      test.sendKeyDownUpSync(recordKey);
      SystemClock.sleep(400);
      onMain(
          test,
          () -> {
            require(
                fake.snapshot().state == SessionEngine.State.DONE,
                "stop did not finish synthetic round");
            require(
                screen.findViewById(R.id.meeting_toolbar).getVisibility() == View.GONE,
                "answer reading mode unexpectedly expanded toolbar");
            require(
                screen.findViewById(R.id.meeting_controls).getVisibility() == View.GONE,
                "answer reading mode unexpectedly expanded bottom controls");
            require(screen.findViewById(R.id.more_button) == null, "old main more button remains");
            View copyAnswer = screen.findViewById(R.id.copy_answer);
            require(copyAnswer.isShown(), "answer copy hidden with toolbar");
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                    screen.getSystemService(Context.CLIPBOARD_SERVICE);
            copyAnswer.performClick();
            require(
                clipboard.hasPrimaryClip()
                    && app.meetings
                        .answer()
                        .contentEquals(clipboard.getPrimaryClip().getItemAt(0).getText()),
                "answer copy did not use current meeting content");
            screen.findViewById(R.id.transcript_toggle).performClick();
          });
      test.waitForIdleSync();
      onMain(
          test,
          () -> {
            View copyTranscript = screen.findViewById(R.id.copy_transcript);
            require(copyTranscript.isShown(), "transcript copy not visible in expanded pane");
            copyTranscript.performClick();
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                    screen.getSystemService(Context.CLIPBOARD_SERVICE);
            require(
                clipboard.hasPrimaryClip()
                    && app.meetings
                        .transcript(true)
                        .contentEquals(clipboard.getPrimaryClip().getItemAt(0).getText()),
                "transcript copy did not use full current meeting content");
          });
      phase(
          test,
          "BOTH_BARS_IDLE_LISTEN_TAP_KEYBOARD_STOP_AND_COPY_PASSED; no audio/network requests");
    } finally {
      if (activity != null) {
        finishActivity(test, activity);
      }
      fake.close();
      onMain(
          test,
          () -> {
            app.meetings.close();
            app.bindMeetings(originalMeetings);
            app.awake = originalAwake;
          });
    }
  }

  private static void toolbarScreenshot(Instrumentation test, MainActivity screen, String name)
      throws Exception {
    onMain(test, () -> require(screen.hasWindowFocus(), "app lost foreground before screenshot"));
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    require(bitmap != null, "screenshot failed");
    try (FileOutputStream out =
        new FileOutputStream(new File(test.getTargetContext().getCacheDir(), name))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "screenshot encoding failed");
    } finally {
      bitmap.recycle();
    }
  }

  static void openSettings(Instrumentation test, MainActivity activity) {
    // Connect before opening the drawer so the automation service receives its window event.
    android.app.UiAutomation automation = test.getUiAutomation();
    await(
        () -> {
          java.util.concurrent.atomic.AtomicBoolean focused =
              new java.util.concurrent.atomic.AtomicBoolean();
          onMain(test, () -> focused.set(activity.hasWindowFocus()));
          return focused.get();
        },
        3000,
        "unlock the phone before menu checks");
    onMain(test, () -> activity.findViewById(R.id.meetings_button).performClick());
    Rect target = new Rect();
    await(
        () -> {
          AccessibilityNodeInfo root = automation.getRootInActiveWindow();
          if (root == null) return false;
          List<AccessibilityNodeInfo> matches =
              root.findAccessibilityNodeInfosByText(activity.getString(R.string.settings));
          try {
            for (AccessibilityNodeInfo node : matches) {
              if (activity.getString(R.string.settings).contentEquals(node.getText())
                  && "io.github.meetingassistant".contentEquals(node.getPackageName())) {
                node.getBoundsInScreen(target);
                if (!target.isEmpty()) break;
              }
            }
          } finally {
            for (AccessibilityNodeInfo node : matches) node.recycle();
            root.recycle();
          }
          return !target.isEmpty();
        },
        3000,
        "could not find settings menu item");
    long now = SystemClock.uptimeMillis();
    MotionEvent down =
        MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY(), 0);
    MotionEvent up =
        MotionEvent.obtain(
            now, now + 50, MotionEvent.ACTION_UP, target.centerX(), target.centerY(), 0);
    test.sendPointerSync(down);
    test.sendPointerSync(up);
    down.recycle();
    up.recycle();
    await(
        () -> {
          java.util.concurrent.atomic.AtomicBoolean opened =
              new java.util.concurrent.atomic.AtomicBoolean();
          onMain(
              test,
              () ->
                  opened.set(
                      find(
                              activity.getWindow().getDecorView(),
                              activity.getString(R.string.save_models))
                          != null));
          return opened.get();
        },
        3000,
        "settings page did not open after menu tap");
  }

  static void onMain(Instrumentation test, Runnable action) {
    AtomicReference<Throwable> failed = new AtomicReference<>();
    test.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failed.set(error);
          }
        });
    Throwable error = failed.get();
    if (error instanceof AssertionError) throw (AssertionError) error;
    if (error != null)
      throw new AssertionError("UI operation failed: " + error.getClass().getSimpleName());
  }

  static void finishActivity(Instrumentation test, Activity activity) {
    onMain(test, activity::finish);
    await(
        () -> {
          java.util.concurrent.atomic.AtomicBoolean destroyed =
              new java.util.concurrent.atomic.AtomicBoolean();
          onMain(test, () -> destroyed.set(activity.isDestroyed()));
          return destroyed.get();
        },
        5000,
        "test screen did not finish lifecycle");
  }

  private static void await(BooleanSupplier condition, long milliseconds, String failure) {
    long deadline = SystemClock.uptimeMillis() + milliseconds;
    do {
      if (condition.getAsBoolean()) return;
      SystemClock.sleep(100);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError(failure);
  }

  private static void phase(Instrumentation test, String value) {
    Bundle b = new Bundle();
    b.putString("phase", value);
    test.sendStatus(2, b);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static TextView find(View v, String text) {
    if (v instanceof TextView && ((TextView) v).getText().toString().equals(text))
      return (TextView) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView found = find(g.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void scrollViews(View v, List<ScrollView> panes) {
    if (v instanceof ScrollView) panes.add((ScrollView) v);
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) scrollViews(g.getChildAt(i), panes);
    }
  }

  private DeviceChecks() {}
}
