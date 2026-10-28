package io.github.meetingassistant.android;

import android.app.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.view.*;
import android.widget.EditText;
import com.google.android.material.color.DynamicColors;
import io.github.meetingassistant.R;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs only inside the isolated input-style fixture; temporarily changes and restores system mode.
 */
final class ThemeDeviceCheck {
  static void run(Instrumentation test, MainActivity screen) throws Exception {
    UiModeManager manager = screen.getSystemService(UiModeManager.class);
    int original = manager.getNightMode();
    boolean oldDark =
        (screen.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    View[] root = new View[1];
    EditText[] field = new EditText[1];
    DeviceChecks.onMain(
        test,
        () -> {
          root[0] = screen.findViewById(android.R.id.content);
          field[0] = screen.findViewById(R.id.image_model_key);
          field[0].setText("synthetic-theme-unsaved");
          field[0].requestFocus();
          field[0].setSelection(2, 5);
        });
    test.getUiAutomation().adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE");
    try {
      for (boolean dark : new boolean[] {true, false}) {
        manager.setNightMode(dark ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
        await(test, screen, dark);
        DeviceChecks.onMain(
            test,
            () -> {
              require(
                  !screen.isDestroyed() && root[0] == screen.findViewById(android.R.id.content),
                  "Theme recreated activity");
              require(
                  field[0] == screen.findViewById(R.id.image_model_key),
                  "Theme rebuilt model form");
              require(
                  field[0].getText().toString().equals("synthetic-theme-unsaved"),
                  "Theme discarded unsaved input");
              require(
                  field[0].getSelectionStart() == 2 && field[0].getSelectionEnd() == 5,
                  "Theme reset input selection");
              require(field[0].hasFocus(), "Theme reset focused field");
              require(
                  ((SettingsSection) screen.findViewById(R.id.settings_models)).expanded(),
                  "Theme folded settings");
              int surface = AppTheme.color(screen, AppTheme.SURFACE);
              require(
                  ((ColorDrawable) screen.getWindow().getDecorView().getBackground()).getColor()
                      == surface,
                  "Window surface mismatch");
              require(
                  field[0].getCurrentTextColor() == AppTheme.color(screen, AppTheme.TEXT),
                  "Input text role mismatch");
              if (Build.VERSION.SDK_INT >= 30) {
                int flags = screen.getWindow().getInsetsController().getSystemBarsAppearance();
                require(
                    ((flags & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) == 0) == dark,
                    "Status bar appearance mismatch");
              }
              if (Build.VERSION.SDK_INT >= 31 && DynamicColors.isDynamicColorAvailable()) {
                int role = androidx.appcompat.R.attr.colorPrimary;
                int expected =
                    com.google.android.material.color.MaterialColors.getColor(screen, role, 0);
                require(
                    AppTheme.color(screen, AppTheme.PRIMARY) == expected,
                    "Dynamic primary mismatch");
              }
            });
        save(test, screen, dark ? "theme-device-dark.png" : "theme-device-light.png");
      }
    } finally {
      manager.setNightMode(original);
      try {
        await(test, screen, oldDark);
      } finally {
        test.getUiAutomation().dropShellPermissionIdentity();
      }
      require(manager.getNightMode() == original, "System theme preference not restored");
    }
    Bundle result = new Bundle();
    result.putString(
        "theme_checks",
        "SYSTEM_DAY_NIGHT_DYNAMIC_ROLES_RETAINED_FORM_FOCUS_SELECTION_WINDOWS_RESTORED_SYSTEM_MODE");
    test.sendStatus(0, result);
  }

  private static void await(Instrumentation test, MainActivity screen, boolean dark)
      throws Exception {
    long until = SystemClock.elapsedRealtime() + 10000;
    AtomicBoolean ready = new AtomicBoolean();
    do {
      DeviceChecks.onMain(
          test, () -> ready.set(!screen.isDestroyed() && AppTheme.colors(screen).dark == dark));
      if (ready.get()) {
        test.waitForIdleSync();
        SystemClock.sleep(400);
        return;
      }
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < until);
    throw new AssertionError("Theme did not update in place");
  }

  private static void save(Instrumentation test, MainActivity screen, String name)
      throws Exception {
    DeviceChecks.onMain(
        test, () -> require(screen.hasWindowFocus(), "Unlock phone for theme screenshots"));
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    require(bitmap != null, "No theme screenshot");
    try (FileOutputStream out =
        new FileOutputStream(new File(test.getTargetContext().getCacheDir(), name))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "Theme screenshot failed");
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
