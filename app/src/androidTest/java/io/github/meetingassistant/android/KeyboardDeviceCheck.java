package io.github.meetingassistant.android;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Human-assisted external-keyboard checks using an isolated store and nonfunctional credentials.
 */
final class KeyboardDeviceCheck {
  private static final String PREFS = "keyboard-device-check";
  private final Instrumentation test;
  private final ConcurrentHashMap<Integer, AtomicInteger> downs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, AtomicInteger> repeats = new ConcurrentHashMap<>();
  private final AtomicInteger audioStarts = new AtomicInteger(),
      packets = new AtomicInteger(),
      analyses = new AtomicInteger();
  private volatile boolean abort;
  private boolean automaticModal;
  private volatile int lastKey = -1;
  private MainActivity screen;
  private MeetingApp app;
  private TextView banner;

  private KeyboardDeviceCheck(Instrumentation test) {
    this.test = test;
  }

  static void run(Instrumentation test) throws Exception {
    new KeyboardDeviceCheck(test).run(false);
  }

  static void runModal(Instrumentation test) throws Exception {
    new KeyboardDeviceCheck(test).run(true);
  }

  static void runModalAuto(Instrumentation test) throws Exception {
    KeyboardDeviceCheck check = new KeyboardDeviceCheck(test);
    check.automaticModal = true;
    check.run(true);
  }

  private void main(Runnable action) {
    DeviceChecks.onMain(test, action);
  }

  private void phase(String id) {
    Bundle b = new Bundle();
    b.putString("keyboard_step", id);
    test.sendStatus(2, b);
  }

  private int count(ConcurrentHashMap<Integer, AtomicInteger> map, int code) {
    AtomicInteger value = map.get(code);
    return value == null ? 0 : value.get();
  }

  /** Only external input is counted. No text, descriptor, address or credentials are logged. */
  private void observe(Window window) {
    Window.Callback original = window.getCallback();
    window.setCallback(
        (Window.Callback)
            Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("dispatchKeyEvent")
                      && args[0] instanceof KeyEvent event
                      && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) abort = true;
                    InputDevice device = event.getDevice();
                    if (device != null && device.isExternal()) {
                      lastKey = event.getKeyCode();
                      (event.getRepeatCount() == 0 ? downs : repeats)
                          .computeIfAbsent(event.getKeyCode(), k -> new AtomicInteger())
                          .incrementAndGet();
                    }
                  }
                  try {
                    return method.invoke(original, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                }));
  }

  private void hint(String text) {
    main(
        () -> {
          FrameLayout content = screen.findViewById(android.R.id.content);
          if (banner != null && banner.getParent() instanceof ViewGroup old) old.removeView(banner);
          banner = new TextView(screen);
          banner.setText("蓝牙测试 · Bluetooth test\n" + text + "\nEsc 可终止 / Esc to abort");
          banner.setTextColor(Color.WHITE);
          banner.setBackgroundColor(0xff164b70);
          banner.setTextSize(16);
          banner.setPadding(20, 12, 20, 12);
          banner.setFocusable(false);
          content.addView(banner, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        });
  }

  private void await(String id, BooleanSupplier condition) {
    long until = SystemClock.uptimeMillis() + 120_000;
    do {
      if (abort) throw new AssertionError("Keyboard test aborted");
      AtomicReference<Boolean> ready = new AtomicReference<>(false);
      main(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) {
        phase("PASS_" + id);
        return;
      }
      SystemClock.sleep(50);
    } while (SystemClock.uptimeMillis() < until);
    throw new AssertionError("Keyboard step timed out: " + id + "; last external key=" + lastKey);
  }

  private void press(String id, String instruction, int code, BooleanSupplier condition) {
    int before = count(downs, code);
    phase(id);
    hint(instruction);
    await(id, () -> count(downs, code) > before && condition.getAsBoolean());
    SystemClock.sleep(250);
  }

  private void hold(String id, String instruction, int code, BooleanSupplier condition) {
    int before = count(downs, code), beforeRepeats = count(repeats, code);
    phase(id);
    hint(instruction + "，保持到提示改变 / hold until the prompt changes");
    await(
        id,
        () ->
            count(downs, code) > before
                && count(repeats, code) >= beforeRepeats + 3
                && condition.getAsBoolean());
    SystemClock.sleep(200);
    main(() -> require(condition.getAsBoolean(), "Held key repeated a toggle/navigation"));
  }

  private static void require(boolean value, String text) {
    if (!value) throw new AssertionError(text);
  }

  private TextView find(View view, String text) {
    if (view instanceof TextView t && text.contentEquals(t.getText())) return t;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView result = find(group.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private ScrollView pane(int id) {
    return scroll(screen.findViewById(id));
  }

  private ScrollView scroll(View view) {
    if (view instanceof ScrollView s) return s;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        ScrollView result = scroll(group.getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private void bind(String action, int label, int key, boolean conflict) {
    AtomicReference<AlertDialog> dialog = new AtomicReference<>();
    main(
        () -> {
          TextView button =
              find(
                  screen.getWindow().getDecorView(),
                  screen.getString(label) + "   " + screen.getString(R.string.unbound));
          require(button != null, "Key binding entry missing");
          button.performClick();
        });
    // Read the settings UI's dialog reference in the test, retaining its original key listener.
    main(
        () -> {
          try {
            var field = MainActivity.class.getDeclaredField("captureDialog");
            field.setAccessible(true);
            dialog.set((AlertDialog) field.get(screen));
          } catch (ReflectiveOperationException e) {
            throw new AssertionError("Capture dialog unavailable");
          }
          observe(dialog.get().getWindow());
        });
    if (conflict) {
      main(() -> dialog.get().setMessage("蓝牙测试：先按空格，验证已绑定按键不会被覆盖。"));
      press(
          "BIND_CONFLICT",
          "先按一次空格 / press Space once to check conflict",
          KeyEvent.KEYCODE_SPACE,
          () -> dialog.get().isShowing() && !app.settings.bindings.containsKey(action));
    }
    main(
        () ->
            dialog
                .get()
                .setMessage(
                    "蓝牙测试：请按 "
                        + KeyEvent.keyCodeToString(key).replace("KEYCODE_", "")
                        + "，绑定「"
                        + screen.getString(label)
                        + "」。"));
    press(
        "BIND_" + action,
        "请按 " + KeyEvent.keyCodeToString(key).replace("KEYCODE_", ""),
        key,
        () ->
            Integer.valueOf(key).equals(app.settings.bindings.get(action))
                && !dialog.get().isShowing());
    require(
        Integer.valueOf(key).equals(app.repository.load().bindings.get(action)),
        "Binding not saved");
  }

  private SessionEngine.Factory factory() {
    return new SessionEngine.Factory() {
      public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
        return new SpeechProvider() {
          Listener listener;

          public void start(Listener l) {
            listener = l;
            l.ready();
            l.segment(new Segment(1, lines("模拟听写 · synthetic transcript"), true, 0, 1000));
            l.segment(new Segment(2, "模拟尾句（未定稿）", false, 1000, -1));
          }

          public void sendAudio(byte[] pcm) {
            packets.incrementAndGet();
          }

          public void finish() {
            listener.segment(new Segment(2, "模拟尾句已完整保存", true, 1000, 2000));
            listener.completed();
          }

          public void cancel() {}
        };
      }

      public AudioSource audio() {
        return new AudioSource() {
          ScheduledExecutorService worker;

          public void start(Listener l) {
            audioStarts.incrementAndGet();
            worker = Executors.newSingleThreadScheduledExecutor();
            worker.scheduleAtFixedRate(
                () -> l.audio(new byte[3200]), 0, 100, TimeUnit.MILLISECONDS);
          }

          public void stop(Runnable done) {
            cancel();
            done.run();
          }

          public void cancel() {
            if (worker != null) worker.shutdownNow();
          }
        };
      }

      public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
        return new TextProvider() {
          public void analyze(com.google.gson.JsonArray messages, Listener l) {
            analyses.incrementAndGet();
            l.delta(lines("模拟答案 · synthetic answer"));
            l.completed(false);
          }

          public void cancel() {}
        };
      }
    };
  }

  private static String lines(String prefix) {
    StringBuilder out = new StringBuilder();
    for (int i = 1; i <= 110; i++) out.append(prefix).append(' ').append(i).append("\n");
    return out.toString();
  }

  private MeetingRecord record(String title, UserSettings settings, long at) {
    MeetingRecord r = new MeetingRecord();
    r.title = title;
    r.customTitle = true;
    r.createdAt = r.updatedAt = at;
    r.view.transcriptVisibilityChosen = true;
    r.view.transcriptExpanded = false;
    r.view.followTranscript = false;
    r.view.followAnswer = false;
    r.current.state = SessionEngine.State.DONE;
    r.current.analysis = SessionEngine.AnalysisState.DONE;
    r.current.finalized = true;
    r.current.finalText = lines("模拟听写 · synthetic transcript");
    r.current.segments.add(new SpeechProvider.Segment(1, r.current.finalText, true, 0, 1000));
    r.current.answer = lines("模拟答案 · synthetic answer");
    r.current.preference = r.current.selected = settings.snapshot();
    return r;
  }

  private void drawerCheck(int expectedMeetings) {
    int before = count(downs, KeyEvent.KEYCODE_N) + count(repeats, KeyEvent.KEYCODE_N);
    AtomicReference<android.app.Dialog> dialog = new AtomicReference<>();
    main(
        () -> {
          screen.findViewById(R.id.meetings_button).performClick();
          try {
            var field = MainActivity.class.getDeclaredField("drawerDialog");
            field.setAccessible(true);
            dialog.set((android.app.Dialog) field.get(screen));
          } catch (ReflectiveOperationException e) {
            throw new AssertionError("Drawer dialog unavailable");
          }
          observe(dialog.get().getWindow());
        });
    if (automaticModal) {
      phase("AUTO_DRAWER_BEFORE_KEY");
      hint("自动检查侧栏，请暂时不要按键 / automatic check, do not press keys");
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_N);
      test.waitForIdleSync();
      SystemClock.sleep(500);
      main(
          () ->
              require(
                  dialog.get().isShowing() && app.meetings.summaries().size() == expectedMeetings,
                  "Auto drawer state: showing="
                      + dialog.get().isShowing()
                      + "; rows="
                      + app.meetings.summaries().size()
                      + "; destroyed="
                      + screen.isDestroyed()));
      phase("PASS_AUTO_DRAWER_ISOLATION");
      return;
    }
    phase("DRAWER_ISOLATION");
    main(
        () -> {
          FrameLayout content = dialog.get().findViewById(android.R.id.content);
          TextView prompt = new TextView(screen);
          prompt.setText("蓝牙补测：请按 N 一次\n侧栏应保持打开，不新建会议。\nPress N once; drawer should stay open.");
          prompt.setTextColor(Color.WHITE);
          prompt.setTextSize(18);
          prompt.setPadding(24, 24, 24, 24);
          prompt.setBackgroundColor(0xff164b70);
          prompt.setFocusable(false);
          content.addView(prompt, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        });
    int[] logged = {-1};
    await(
        "DRAWER_ISOLATION",
        () -> {
          int total = count(downs, KeyEvent.KEYCODE_N) + count(repeats, KeyEvent.KEYCODE_N);
          if (total != logged[0]) {
            phase(
                "DRAWER_STATE: downs="
                    + count(downs, KeyEvent.KEYCODE_N)
                    + "; repeats="
                    + count(repeats, KeyEvent.KEYCODE_N)
                    + "; showing="
                    + dialog.get().isShowing()
                    + "; rows="
                    + app.meetings.summaries().size()
                    + "; destroyed="
                    + screen.isDestroyed());
            logged[0] = total;
          }
          return total > before
              && dialog.get().isShowing()
              && app.meetings.summaries().size() == expectedMeetings;
        });
  }

  private void run(boolean modalOnly) throws Exception {
    app = (MeetingApp) test.getTargetContext().getApplicationContext();
    MeetingManager original = app.meetings;
    UserSettings originalSettings = app.settings;
    SettingsRepository originalRepository = app.repository;
    boolean originalAwake = app.awake;
    java.util.Map<String, ?> uiBefore =
        new java.util.HashMap<>(app.getSharedPreferences("settings-ui-v1", 0).getAll());
    require(!app.session.snapshot().busy(), "Keyboard check requires an idle app");
    MeetingManager.MemoryStore store = new MeetingManager.MemoryStore();
    UserSettings settings = UserSettings.initial();
    settings.provider.asrUrl = "wss://speech.example.org/asr";
    settings.provider.textBaseUrl = "https://text.example.org/v1";
    settings.bind(UserSettings.AUTO, KeyEvent.KEYCODE_A);
    long now = System.currentTimeMillis();
    MeetingRecord first = record("蓝牙测试 · A", settings, now - 2000);
    MeetingRecord second = record("蓝牙测试 · B", settings, now - 1000);
    store.save(first);
    store.save(second);
    store.select(first.id);
    MeetingManager fixture =
        new MeetingManager(store, () -> new SessionEngine(factory()), settings.snapshot(), false);
    try {
      main(
          () -> {
            app.repository = new SettingsRepository(app, PREFS);
            app.repository.saveModels(settings, "test-only", "test-only");
            app.settings = settings;
            app.awake = false;
            app.bindMeetings(fixture);
          });
      screen =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      test.waitForIdleSync();
      main(
          () -> {
            require(screen.hasWindowFocus(), "Unlock phone before keyboard check");
            observe(screen.getWindow());
          });
      if (modalOnly) {
        main(() -> app.settings.bind(UserSettings.MEETING_NEW, KeyEvent.KEYCODE_N));
        drawerCheck(2);
        phase(
            automaticModal
                ? "MODAL_AUTOMATED_CHECK_PASSED; no microphone/model requests"
                : "MODAL_EXTERNAL_KEYBOARD_CHECK_PASSED; no microphone/model requests");
        return;
      }
      DeviceChecks.openSettings(test, screen);
      main(() -> ((SettingsSection) screen.findViewById(R.id.settings_keyboard)).setExpanded(true));
      bind(UserSettings.MEETING_NEW, R.string.shortcut_meeting_new, KeyEvent.KEYCODE_N, true);
      bind(
          UserSettings.MEETING_PREVIOUS,
          R.string.shortcut_meeting_previous,
          KeyEvent.KEYCODE_U,
          false);
      bind(UserSettings.MEETING_NEXT, R.string.shortcut_meeting_next, KeyEvent.KEYCODE_J, false);
      bind(
          UserSettings.TRANSCRIPT_TOGGLE,
          R.string.shortcut_transcript_toggle,
          KeyEvent.KEYCODE_T,
          false);
      press(
          "SETTINGS_ISOLATION",
          "请按 N；设置页应不会新建会议 / press N",
          KeyEvent.KEYCODE_N,
          () -> app.meetings.summaries().size() == 2);
      main(() -> screen.findViewById(R.id.settings_back).performClick());
      SystemClock.sleep(300);
      press(
          "PROFILE_FINANCE",
          "请按 2，切换金融偏好 / press 2",
          KeyEvent.KEYCODE_2,
          () -> app.settings.selectedId.equals("finance"));
      press(
          "PROFILE_NONE",
          "请按 0，不使用偏好 / press 0",
          KeyEvent.KEYCODE_0,
          () -> app.settings.selectedId.isEmpty());
      press(
          "PROFILE_ALGORITHM",
          "请按 1，切回算法偏好 / press 1",
          KeyEvent.KEYCODE_1,
          () -> app.settings.selectedId.equals("algorithm"));
      hold("AWAKE_REPEAT", "请长按 L，打开常亮 / hold L", KeyEvent.KEYCODE_L, () -> app.awake);
      press(
          "AWAKE_OFF",
          "松开后再按 L，关闭常亮 / release, then press L",
          KeyEvent.KEYCODE_L,
          () -> !app.awake);
      press("AUTO_ON", "请按 A，开启 Auto / press A", KeyEvent.KEYCODE_A, () -> app.settings.automatic);
      press(
          "AUTO_OFF",
          "请再按 A，关闭 Auto / press A again",
          KeyEvent.KEYCODE_A,
          () -> !app.settings.automatic);
      press(
          "PREVIOUS",
          "请按 U，切到上一个会议 B / press U",
          KeyEvent.KEYCODE_U,
          () -> app.meetings.currentId().equals(second.id));
      press(
          "NEXT",
          "请按 J，切回下一个会议 A / press J",
          KeyEvent.KEYCODE_J,
          () -> app.meetings.currentId().equals(first.id));
      hold(
          "NEW_REPEAT",
          "请长按 N，新建会议仅应触发一次 / hold N",
          KeyEvent.KEYCODE_N,
          () -> app.meetings.summaries().size() == 3 && !app.meetings.currentId().equals(first.id));
      press(
          "BACK_TO_B",
          "请按 J，切到会议 B / press J",
          KeyEvent.KEYCODE_J,
          () -> app.meetings.currentId().equals(second.id));
      press(
          "BACK_TO_A",
          "请再按 J，切到会议 A / press J again",
          KeyEvent.KEYCODE_J,
          () -> app.meetings.currentId().equals(first.id));
      phase("WAITING_BARS_HIDDEN");
      hint("请松开按键，等待工具栏收起 / release keys and wait");
      SystemClock.sleep(4500);
      press(
          "TRANSCRIPT_OPEN_HIDDEN",
          "请按 T，展开原文；工具栏继续隐藏 / press T",
          KeyEvent.KEYCODE_T,
          () ->
              screen.findViewById(R.id.transcript_panel) != null
                  && screen.findViewById(R.id.meeting_toolbar).getVisibility() == View.GONE);
      hold(
          "TRANSCRIPT_REPEAT",
          "请长按 T，原文只应收起一次 / hold T",
          KeyEvent.KEYCODE_T,
          () -> screen.findViewById(R.id.transcript_panel) == null);
      hold(
          "ANSWER_DOWN",
          "请长按下箭头 ↓，向下滚动答案 / hold Down",
          KeyEvent.KEYCODE_DPAD_DOWN,
          () -> pane(R.id.answer_panel).getScrollY() > 0);
      hold(
          "ANSWER_UP",
          "请长按上箭头 ↑，回到答案顶部 / hold Up",
          KeyEvent.KEYCODE_DPAD_UP,
          () -> pane(R.id.answer_panel).getScrollY() == 0);
      press(
          "RAW_PAGE_DOWN",
          "请按 Page Down；小键盘可尝试 Fn+↓ / Page Down or Fn+Down",
          KeyEvent.KEYCODE_PAGE_DOWN,
          () ->
              screen.findViewById(R.id.transcript_panel) != null
                  && pane(R.id.transcript_panel).getScrollY() > 0);
      press(
          "RAW_PAGE_UP",
          "请按 Page Up；小键盘可尝试 Fn+↑ / Page Up or Fn+Up",
          KeyEvent.KEYCODE_PAGE_UP,
          () -> pane(R.id.transcript_panel).getScrollY() == 0);
      press(
          "LISTEN_START",
          "请按空格，开始模拟聆听（无需说话） / press Space",
          KeyEvent.KEYCODE_SPACE,
          () -> app.session.snapshot().state == SessionEngine.State.LISTENING);
      int beforePackets = packets.get();
      press(
          "RAW_WHILE_LISTENING",
          "请按 T；模拟聆听应保持 / press T",
          KeyEvent.KEYCODE_T,
          () ->
              app.session.snapshot().state == SessionEngine.State.LISTENING
                  && packets.get() > beforePackets);
      press(
          "LISTEN_STOP",
          "请再按空格，停止模拟聆听并保留尾句 / press Space again",
          KeyEvent.KEYCODE_SPACE,
          () ->
              app.session.snapshot().state == SessionEngine.State.DONE
                  && app.session.snapshot().transcript.contains("模拟尾句已完整保存"));
      require(
          audioStarts.get() == 1 && analyses.get() == 1,
          "Unexpected microphone/model fixture count");
      drawerCheck(3);
      phase("ALL_EXTERNAL_KEYBOARD_CHECKS_PASSED; no microphone/model requests");
    } finally {
      try {
        if (screen != null) DeviceChecks.finishActivity(test, screen);
      } finally {
        try {
          fixture.close();
        } finally {
          main(
              () -> {
                app.repository = originalRepository;
                app.settings = originalSettings;
                app.awake = originalAwake;
                app.bindMeetings(original);
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
              });
          app.deleteSharedPreferences(PREFS);
          var ui = app.getSharedPreferences("settings-ui-v1", 0).edit().clear();
          for (java.util.Map.Entry<String, ?> entry : uiBefore.entrySet())
            ui.putBoolean(entry.getKey(), (Boolean) entry.getValue());
          ui.commit();
        }
      }
    }
  }
}
