package io.github.meetingassistant.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Basic smoke is synthetic; explicitly selected image hardware/live modes use real devices. */
public final class SmokeInstrumentation extends Instrumentation {
  private Bundle arguments;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    this.arguments = arguments == null ? new Bundle() : arguments;
    start();
  }

  @Override
  public void onStart() {
    String mode = arguments.getString("mode", "basic");
    if (!mode.equals("basic")) {
      DeviceChecks.run(this, mode);
      return;
    }
    Bundle result = new Bundle();
    try {
      Intent intent =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      MainActivity activity = (MainActivity) startActivitySync(intent);
      waitForIdleSync();
      DeviceChecks.onMain(
          this,
          () -> {
            MeetingApp app = (MeetingApp) activity.getApplication();
            require(activity.hasWindowFocus(), "Unlock the phone before UI smoke test");
            require(!app.session.snapshot().busy(), "Run smoke test while idle");
            require(
                find(
                        activity.getWindow().getDecorView(),
                        activity.getString(io.github.meetingassistant.R.string.start_listening))
                    != null,
                "Missing listen button");
            boolean wasAwake = app.awake;
            if (app.settings.keyboardEnabled
                && Integer.valueOf(KeyEvent.KEYCODE_L)
                    .equals(app.settings.bindings.get("awake.toggle"))) {
              activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L));
              activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_L));
              require(app.awake != wasAwake, "Awake shortcut failed");
              activity.dispatchKeyEvent(
                  new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, 1));
              require(app.awake != wasAwake, "Repeat toggled awake twice");
              activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L));
              activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_L));
            }
            require(app.awake == wasAwake, "Awake not restored");
          });
      DeviceChecks.openSettings(this, activity);
      DeviceChecks.onMain(
          this,
          () -> {
            MeetingApp app = (MeetingApp) activity.getApplication();
            require(
                find(
                        activity.getWindow().getDecorView(),
                        activity.getString(io.github.meetingassistant.R.string.save_models))
                    != null,
                "Missing settings");
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE));
            require(!app.session.snapshot().busy(), "Settings captured meeting shortcut");
            activity.findViewById(io.github.meetingassistant.R.id.settings_back).performClick();
          });
      waitForIdleSync();
      result.putString(
          "result",
          "PASS: foreground UI, keyboard repeat guard, settings isolation; no audio/network"
              + " requests");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("result", "FAIL: " + e.getClass().getSimpleName());
      if (e instanceof AssertionError) result.putString("check", e.getMessage());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static TextView find(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView result = find(group.getChildAt(i), text);
        if (result != null) return result;
      }
    }
    return null;
  }
}
