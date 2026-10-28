package io.github.meetingassistant.android;

import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.os.SystemClock;
import android.widget.ScrollView;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Uses real SQLite / Keystore in an isolated test DB, synthetic speech and text, no network/audio.
 */
final class DeviceMeetingCheck {
  static void run(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    MeetingManager original = app.meetings;
    require(!app.session.snapshot().busy(), "meeting test requires idle session");
    String database = "meeting-device-check.db";
    app.deleteDatabase(database);
    AtomicInteger microphoneStarts = new AtomicInteger();
    AtomicReference<SpeechProvider.Listener> speech = new AtomicReference<>();
    java.util.function.Supplier<SessionEngine> factory =
        () ->
            new SessionEngine(
                new SessionEngine.Factory() {
                  public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                    return new SpeechProvider() {
                      public void start(Listener l) {
                        speech.set(l);
                        l.ready();
                      }

                      public void sendAudio(byte[] b) {
                        throw new AssertionError("unexpected real audio");
                      }

                      public void finish() {
                        speech.get().completed();
                      }

                      public void cancel() {}
                    };
                  }

                  public AudioSource audio() {
                    return new AudioSource() {
                      public void start(Listener l) {
                        microphoneStarts.incrementAndGet();
                      }

                      public void stop(Runnable drained) {
                        drained.run();
                      }

                      public void cancel() {}
                    };
                  }

                  public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                    return new TextProvider() {
                      public void analyze(com.google.gson.JsonArray m, Listener l) {
                        l.delta("仅供会议存储检查的答案。\n".repeat(120));
                        l.completed(false);
                      }

                      public void cancel() {}
                    };
                  }
                });
    MeetingRepository repository = new MeetingRepository(app, database);
    MeetingManager manager =
        new MeetingManager(repository, factory, app.settings.snapshot(), false);
    MainActivity screen = null;
    try {
      MeetingManager selected = manager;
      DeviceChecks.onMain(test, () -> app.bindMeetings(selected));
      screen =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity activity = screen;
      DeviceChecks.onMain(
          test, () -> require(activity.hasWindowFocus(), "unlock phone before meeting checks"));
      String first = manager.currentId();
      ProviderConfig config = new ProviderConfig();
      config.asrUrl = "wss://example.org/asr";
      config.textBaseUrl = "https://example.org/v1";
      manager
          .currentEngine()
          .start(
              config,
              new ProviderConfig.Credentials("synthetic", "synthetic"),
              app.settings.snapshot());
      speech.get().segment(new SpeechProvider.Segment(1, "设备验证会议原文", true, 0, 1000));
      manager.currentEngine().stop();
      await(
          () -> selected.currentEngine().snapshot().state == SessionEngine.State.DONE,
          5000,
          "synthetic answer did not complete");
      manager.awaitWrites();
      try (Cursor c =
          repository
              .getReadableDatabase()
              .rawQuery("SELECT payload FROM meetings WHERE id=?", new String[] {first})) {
        require(c.moveToFirst(), "encrypted row missing");
        String row = new String(c.getBlob(0), java.nio.charset.StandardCharsets.UTF_8);
        require(
            !row.contains("设备验证会议原文") && !row.contains("会议存储检查"),
            "plaintext meeting content in SQLite");
      }
      test.getUiAutomation();
      DeviceChecks.onMain(test, () -> activity.findViewById(R.id.meetings_button).performClick());
      // Locate the actual dialog through accessibility and click its new-meeting item.
      await(
          () -> {
            android.view.accessibility.AccessibilityNodeInfo root =
                test.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByText(
                    activity.getString(R.string.new_meeting_action));
            boolean clicked = false;
            for (android.view.accessibility.AccessibilityNodeInfo n : nodes) {
              if ("io.github.meetingassistant".contentEquals(n.getPackageName()) && n.isClickable())
                clicked |=
                    n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
              n.recycle();
            }
            root.recycle();
            return clicked;
          },
          3000,
          "new meeting entry unavailable");
      await(
          () -> !selected.currentId().equals(first),
          3000,
          "new meeting did not select a different ID");
      require(manager.transcript(true).isEmpty(), "new meeting leaked previous transcript");
      require(microphoneStarts.get() == 1, "switch unexpectedly started microphone");
      manager.switchTo(first);
      await(
          () -> app.session == selected.currentEngine(), 3000, "UI did not bind restored meeting");
      SystemClock.sleep(300);
      DeviceChecks.onMain(
          test,
          () -> {
            android.view.ViewGroup panel = activity.findViewById(R.id.answer_panel);
            ScrollView pane = (ScrollView) panel.getChildAt(1);
            pane.scrollTo(0, 60);
            require(
                pane.getScrollY() == 60,
                "could not scroll restored answer: "
                    + pane.getScrollY()
                    + "; content="
                    + pane.getChildAt(0).getHeight()
                    + "; viewport="
                    + pane.getHeight());
          });
      DeviceChecks.finishActivity(test, activity);
      screen = null;
      manager.awaitWrites();
      manager.close();
      repository.close();
      repository = new MeetingRepository(app, database);
      manager = new MeetingManager(repository, factory, app.settings.snapshot(), false);
      require(manager.currentId().equals(first), "selected meeting not restored from disk");
      require(manager.answer().contains("存储检查"), "saved answer lost");
      require(
          manager.current().view.answerScroll == 60,
          "reading position lost on restart: " + manager.current().view.answerScroll);
      require(microphoneStarts.get() == 1, "restart unexpectedly resumed recording");
      manager
          .currentEngine()
          .start(
              config,
              new ProviderConfig.Credentials("synthetic", "synthetic"),
              app.settings.snapshot());
      speech.get().segment(new SpeechProvider.Segment(1, "中断前内容", false, 0, -1));
      manager.awaitWrites();
      manager.close();
      repository.close();
      repository = new MeetingRepository(app, database);
      manager = new MeetingManager(repository, factory, app.settings.snapshot(), false);
      require(
          manager.currentEngine().snapshot().state == SessionEngine.State.ERROR,
          "active round not marked interrupted");
      require(microphoneStarts.get() == 2, "cold restore started microphone");
      CountDownLatch deleted = new CountDownLatch(1);
      java.util.concurrent.atomic.AtomicBoolean success =
          new java.util.concurrent.atomic.AtomicBoolean();
      manager.delete(
          first,
          okay -> {
            success.set(okay);
            deleted.countDown();
          });
      require(deleted.await(5, TimeUnit.SECONDS), "delete did not finish");
      require(success.get(), "delete failed");
      manager.awaitWrites();
      require(manager.get(first) == null, "deleted meeting remained visible");
      for (MeetingRecord r : repository.load())
        require(!r.id.equals(first), "deleted record remained on disk");
      require(manager.list().size() == 1, "current delete created an extra history record");
      MeetingManager last = manager;
      DeviceChecks.onMain(test, () -> app.bindMeetings(last));
      screen =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity deletionScreen = screen;
      test.getUiAutomation();
      DeviceChecks.onMain(
          test, () -> deletionScreen.findViewById(R.id.meetings_button).performClick());
      MeetingRecord current = manager.current();
      String title =
          !current.customTitle && !current.autoNamed
              ? deletionScreen.getString(
                  R.string.new_meeting_title,
                  new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                      .format(new java.util.Date(current.createdAt)))
              : current.title;
      tap(test, deletionScreen.getString(R.string.meeting_actions, title), true);
      tap(test, deletionScreen.getString(R.string.delete_meeting), false);
      tap(test, deletionScreen.getString(R.string.delete), false);
      await(() -> last.summaries().isEmpty(), 5000, "last selected meeting remained in history");
      require(manager.current().draft, "last delete did not return to waiting draft");
      DeviceChecks.finishActivity(test, deletionScreen);
      screen = null;
      manager.close();
      repository.close();
      repository = new MeetingRepository(app, database);
      manager = new MeetingManager(repository, factory, app.settings.snapshot(), false);
      require(manager.summaries().isEmpty(), "restart recreated deleted last history row");
      require(manager.current().draft, "restart did not restore waiting draft");
      require(microphoneStarts.get() == 2, "empty-history restore started microphone");
    } finally {
      if (screen != null) {
        DeviceChecks.finishActivity(test, screen);
      }
      manager.close();
      repository.close();
      DeviceChecks.onMain(test, () -> app.bindMeetings(original));
      app.deleteDatabase(database);
    }
  }

  private static void tap(Instrumentation test, String label, boolean description) {
    android.graphics.Rect point = new android.graphics.Rect();
    await(
        () -> {
          android.view.accessibility.AccessibilityNodeInfo root =
              test.getUiAutomation().getRootInActiveWindow();
          if (root == null) return false;
          java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
              root.findAccessibilityNodeInfosByText(label);
          try {
            for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
              CharSequence actual = description ? node.getContentDescription() : node.getText();
              // Native English dialog buttons may apply an ALL_CAPS transformation.
              boolean matches =
                  description
                      ? label.contentEquals(actual == null ? "" : actual)
                      : label.equalsIgnoreCase(actual == null ? "" : actual.toString());
              if (matches && "io.github.meetingassistant".contentEquals(node.getPackageName())) {
                node.getBoundsInScreen(point);
                return !point.isEmpty();
              }
            }
            return false;
          } finally {
            for (android.view.accessibility.AccessibilityNodeInfo node : nodes) node.recycle();
            root.recycle();
          }
        },
        3000,
        "meeting deletion control unavailable: " + label);
    long now = SystemClock.uptimeMillis();
    android.view.MotionEvent down =
        android.view.MotionEvent.obtain(
            now, now, android.view.MotionEvent.ACTION_DOWN, point.centerX(), point.centerY(), 0);
    android.view.MotionEvent up =
        android.view.MotionEvent.obtain(
            now, now + 50, android.view.MotionEvent.ACTION_UP, point.centerX(), point.centerY(), 0);
    test.sendPointerSync(down);
    test.sendPointerSync(up);
    down.recycle();
    up.recycle();
  }

  private static void await(BooleanSupplier ready, long ms, String message) {
    long until = SystemClock.uptimeMillis() + ms;
    do {
      if (ready.getAsBoolean()) return;
      SystemClock.sleep(50);
    } while (SystemClock.uptimeMillis() < until);
    throw new AssertionError(message);
  }

  private static void require(boolean okay, String message) {
    if (!okay) throw new AssertionError(message);
  }
}
