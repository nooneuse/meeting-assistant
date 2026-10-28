package io.github.meetingassistant.android;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import java.util.*;

/** Compact right-drawer controls and a local-only setup page. */
final class AudioInputUi {
  private final MainActivity activity;
  private final MeetingApp app;
  private final Runnable changed;
  private Dialog settings;
  private AlertDialog modal;
  private boolean returnDrawer;
  private Runnable reopenDrawer, permissionAction;
  private final List<Controls> controls = new ArrayList<>();

  private static final class Controls {
    final TextView status;
    final RadioGroup sources;
    final ProgressBar meter;
    final Button test;
    String signature = "";

    Controls(TextView status, RadioGroup sources, ProgressBar meter, Button test) {
      this.status = status;
      this.sources = sources;
      this.meter = meter;
      this.test = test;
    }
  }

  AudioInputUi(MainActivity activity, MeetingApp app, Runnable changed) {
    this.activity = activity;
    this.app = app;
    this.changed = changed;
  }

  private String str(int id) {
    return activity.getString(id);
  }

  private int dp(int v) {
    return Math.round(v * activity.getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private TextView text(String s, int size) {
    TextView v = new TextView(activity);
    v.setText(s);
    AppTheme.text(v, AppTheme.TEXT);
    v.setTextSize(size);
    v.setPadding(0, dp(6), 0, dp(6));
    return v;
  }

  private Button button(String label, Runnable action) {
    Button button = UiControls.button(activity, label, false, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(8);
    button.setLayoutParams(lp);
    return button;
  }

  boolean settingsShowing() {
    return settings != null && settings.isShowing();
  }

  boolean showing() {
    return settingsShowing() || modal != null && modal.isShowing();
  }

  boolean returnsToDrawer() {
    return returnDrawer;
  }

  void addDrawer(LinearLayout parent, Runnable openSettings) {
    addControls(parent, false);
    Button entry = button(str(R.string.audio_settings), openSettings);
    entry.setId(R.id.audio_settings);
    parent.addView(entry);
  }

  private void addControls(LinearLayout parent, boolean setup) {
    parent.addView(text(str(R.string.audio_input), 18));
    TextView state = text("", 14);
    state.setId(R.id.audio_status);
    parent.addView(
        UiControls.status(
            activity,
            state,
            R.id.audio_refresh,
            str(R.string.audio_refresh_label),
            () -> {
              for (Controls c : controls) c.signature = "";
              refresh();
            }));
    RadioGroup sources = new RadioGroup(activity);
    sources.setId(R.id.audio_source);
    parent.addView(sources, new LinearLayout.LayoutParams(-1, -2));
    ProgressBar meter = UiControls.audioMeter(activity);
    meter.setContentDescription(str(R.string.audio_level));
    meter.setId(R.id.audio_level);
    parent.addView(meter, new LinearLayout.LayoutParams(-1, dp(12)));
    Button test = null;
    if (setup) {
      parent.addView(text(str(R.string.audio_test_help), 14));
      test =
          button(
              str(R.string.audio_test_start),
              () -> {
                if (app.audio.testing()) app.audio.stopTest();
                else if (!app.audio.active())
                  permission(
                      () -> {
                        try {
                          app.audio.test();
                        } catch (RuntimeException e) {
                          tell(R.string.audio_in_use);
                        }
                      },
                      app.audio.bluetoothSelected());
              });
      test.setId(R.id.audio_test);
      parent.addView(test);
    }
    parent.addView(
        button(
            str(R.string.audio_bluetooth_devices),
            () ->
                permission(
                    () ->
                        activity.startActivity(
                            new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)),
                    true,
                    false)));
    controls.add(new Controls(state, sources, meter, test));
    refresh();
  }

  private String name(AudioInputController.Device d) {
    if (d.kind() == AudioInputController.Kind.BUILTIN) return str(R.string.audio_builtin);
    String type =
        str(
            switch (d.kind()) {
              case USB -> R.string.audio_usb;
              case BLUETOOTH -> R.string.audio_bluetooth;
              default -> R.string.audio_other;
            });
    return d.name()
        + " · "
        + type
        + (d.ambiguous() ? " · " + str(R.string.audio_ambiguous_short) : "");
  }

  void refresh() {
    if (controls.isEmpty()) return;
    List<AudioInputController.Device> devices = app.audio.devices();
    String status =
        switch (app.audio.state()) {
          case IDLE -> str(R.string.audio_idle);
          case OPENING -> str(R.string.audio_opening);
          case SWITCHING -> str(R.string.audio_switching);
          case CAPTURING -> activity.getString(R.string.audio_actual, app.audio.actual());
          case ERROR -> app.audio.error();
        };
    // Discard detached controls on subsequent openings, without retaining old dialog trees.
    controls.removeIf(c -> c.status.getParent() == null);
    for (Controls c : controls) {
      boolean enabled =
          app.audio.state() != AudioInputController.State.OPENING
              && app.audio.state() != AudioInputController.State.SWITCHING;
      String next = devices.toString() + app.audio.selected() + enabled;
      if (!next.equals(c.signature)) {
        c.signature = next;
        c.sources.removeAllViews();
        for (AudioInputController.Device d : devices)
          c.sources.addView(
              UiControls.radio(
                  activity,
                  name(d),
                  d.key().equals(app.audio.selected()),
                  enabled && !d.ambiguous(),
                  () -> {
                    choose(d);
                    for (Controls item : controls) item.signature = "";
                    refresh();
                  }));
        if (devices.stream().noneMatch(d -> d.key().equals(app.audio.selected())))
          c.sources.addView(text(str(R.string.audio_missing), 14));
      }
      c.status.setText(status);
      c.meter.setProgress(app.audio.level());
      if (c.test != null) {
        c.test.setText(
            str(app.audio.testing() ? R.string.audio_test_stop : R.string.audio_test_start));
        c.test.setEnabled(app.audio.testing() || !app.audio.active());
      }
    }
  }

  private void choose(AudioInputController.Device target) {
    if (target.ambiguous()) {
      tell(R.string.audio_ambiguous);
      return;
    }
    if (target.key().equals(app.audio.selected())) return;
    var state = app.session.snapshot().state;
    if (state == io.github.meetingassistant.core.SessionEngine.State.CONNECTING
        || state == io.github.meetingassistant.core.SessionEngine.State.FINALIZING) {
      tell(R.string.audio_wait);
      return;
    }
    Runnable select =
        () ->
            permission(
                () -> select(target), target.kind() == AudioInputController.Kind.BLUETOOTH, false);
    if (state == io.github.meetingassistant.core.SessionEngine.State.LISTENING
        && !app.audio.testing()) {
      modal =
          AppTheme.alert(activity)
              .setMessage(R.string.audio_switch_confirm)
              .setNegativeButton(R.string.cancel, null)
              .setPositiveButton(R.string.audio_switch, (d, w) -> select.run())
              .create();
      guard(modal);
      modal.show();
    } else select.run();
  }

  private void select(AudioInputController.Device target) {
    String previous = app.audio.selected();
    // Persist before changing a live route, and roll back if local storage refuses the write.
    app.settings.audio.source = target.key();
    try {
      app.repository.save(app.settings);
      app.audio.select(target.key());
    } catch (IllegalStateException e) {
      app.settings.audio.source = previous;
      try {
        app.repository.save(app.settings);
      } catch (IllegalStateException ignored) {
      }
      if (e instanceof AudioInputController.InputFailure f) tell(f.message);
      else activity.imageNotice(new LocalizedMessages(activity).notice(e.getMessage()));
    }
    refresh();
    changed.run();
  }

  void permission(Runnable action, boolean bluetooth) {
    permission(action, bluetooth, true);
  }

  private void permission(Runnable action, boolean bluetooth, boolean record) {
    List<String> missing = new ArrayList<>();
    if (record
        && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
    if (bluetooth
        && Build.VERSION.SDK_INT >= 31
        && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED)
      missing.add(Manifest.permission.BLUETOOTH_CONNECT);
    if (missing.isEmpty()) {
      action.run();
      return;
    }
    permissionAction = action;
    activity.requestPermissions(missing.toArray(new String[0]), 102);
  }

  void permissionResult(String[] permissions, int[] results) {
    Runnable action = permissionAction;
    permissionAction = null;
    boolean allowed = results.length > 0 && results.length == permissions.length;
    for (int r : results) allowed &= r == PackageManager.PERMISSION_GRANTED;
    if (allowed && action != null && activity.audioPermissionCanRun()) action.run();
    else if (!allowed) tell(R.string.audio_permission);
    refresh();
  }

  void showSettings(boolean fromDrawer, Runnable reopen) {
    if (settingsShowing()) return;
    returnDrawer = fromDrawer;
    reopenDrawer = reopen;
    controls.clear();
    settings = AppTheme.page(activity);
    LinearLayout page = column();
    page.setPadding(dp(12), dp(8), dp(12), dp(12));
    AppTheme.background(page, AppTheme.SURFACE);
    LinearLayout heading = new LinearLayout(activity);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton back =
        UiControls.icon(activity, R.drawable.ic_back, str(R.string.back), () -> settings.dismiss());
    back.setId(R.id.audio_settings_back);
    heading.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    heading.addView(text(str(R.string.audio_settings), 20));
    page.addView(heading);
    ScrollView scroll = new ScrollView(activity);
    LinearLayout content = column();
    scroll.addView(content);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    addControls(content, true);
    content.addView(text(str(R.string.audio_compatibility), 14));
    settings.setContentView(page);
    guard(settings);
    settings.setOnDismissListener(
        d -> {
          app.audio.stopTest();
          controls.clear();
          if (returnDrawer
              && !activity.isDestroyed()
              && !activity.isFinishing()
              && reopenDrawer != null) reopenDrawer.run();
          changed.run();
        });
    settings.show();
    Window w = settings.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(new ColorDrawable(AppTheme.color(activity, AppTheme.SURFACE)));
      w.setLayout(-1, -1);
    }
    page.setOnApplyWindowInsetsListener(
        (v, i) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b =
                i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            page.setPadding(dp(12) + b.left, dp(8) + b.top, dp(12) + b.right, dp(12) + b.bottom);
          } else
            page.setPadding(
                dp(12) + i.getSystemWindowInsetLeft(),
                dp(8) + i.getSystemWindowInsetTop(),
                dp(12) + i.getSystemWindowInsetRight(),
                dp(12) + i.getSystemWindowInsetBottom());
          return i;
        });
    page.requestApplyInsets();
    changed.run();
  }

  void drawerClosed() {
    if (!settingsShowing()) controls.clear();
  }

  void destroy() {
    returnDrawer = false;
    permissionAction = null;
    if (modal != null) modal.dismiss();
    if (settings != null) settings.dismiss();
    controls.clear();
    app.audio.stopTest();
  }

  private void tell(int id) {
    activity.imageNotice(str(id));
  }

  private void guard(Dialog d) {
    d.setOnKeyListener(
        (dialog, code, event) -> {
          if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) d.dismiss();
            return true;
          }
          return app.settings.actionFor(code, event.getRepeatCount(), false, false) != null;
        });
  }
}
