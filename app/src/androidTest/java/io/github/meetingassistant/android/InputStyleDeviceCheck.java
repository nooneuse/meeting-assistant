package io.github.meetingassistant.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native UI and Keystore regression using isolated preferences and unopened synthetic devices. */
final class InputStyleDeviceCheck {
  static void run(Instrumentation test) throws Exception {
    run(test, false);
  }

  static void run(Instrumentation test, boolean theme) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    require(
        !app.session.snapshot().busy() && !app.audio.active() && !app.video.enabled(),
        "Input UI check requires idle capture");
    SettingsRepository repository = app.repository;
    UserSettings original = app.settings;
    MeetingManager meetings = app.meetings;
    AudioInputController audio = app.audio;
    ImageInputController video = app.video;
    boolean awake = app.awake;
    String name = "input-style-device-check";
    SharedPreferences prefs = app.getSharedPreferences(name, 0);
    Map<String, ?> before = new HashMap<>(app.getSharedPreferences("settings-v1", 0).getAll());
    Map<String, ?> uiBefore = new HashMap<>(app.getSharedPreferences("settings-ui-v1", 0).getAll());
    MainActivity activity = null;
    try {
      prefs.edit().clear().commit();
      app.getSharedPreferences("settings-ui-v1", 0).edit().clear().commit();
      DeviceChecks.onMain(
          test,
          () -> {
            app.repository = new SettingsRepository(app, name);
            app.settings = UserSettings.initial();
            app.settings.provider.asrUrl = "wss://example.org/asr";
            app.settings.provider.textBaseUrl = "https://example.org/v1";
            app.settings.image.sourceId = "camera:fixture";
            app.settings.image.cropSource = "camera:fixture";
            app.settings.image.left = .2f;
            app.repository.saveModels(
                app.settings, "synthetic-asr", "synthetic-text", "synthetic-image");
            app.awake = true;
            app.audio =
                new AudioInputController(
                    app,
                    app.settings.audio,
                    new AudioInputController.Devices() {
                      public List<AudioInputController.Device> list() {
                        return List.of(
                            new AudioInputController.Device(
                                AudioInputSettings.BUILTIN,
                                "",
                                AudioInputController.Kind.BUILTIN,
                                1,
                                0,
                                false),
                            new AudioInputController.Device(
                                "usb:fixture",
                                "USB fixture",
                                AudioInputController.Kind.USB,
                                2,
                                0,
                                false));
                      }

                      public AudioInputController.Connection open(
                          AudioInputController.Device d, BooleanSupplier canceled) {
                        throw new AssertionError("UI check opened microphone");
                      }

                      public void observe(Runnable changed) {}
                    });
            app.video =
                new ImageInputController(
                    app,
                    app.settings.image,
                    new ImageInputController.Sources() {
                      public List<ImageSource.Device> list() {
                        return List.of(
                            new ImageSource.Device("camera:fixture", ImageSource.Kind.BACK),
                            new ImageSource.Device("usb:fixture", ImageSource.Kind.USB));
                      }

                      public ImageSource create(String id) {
                        throw new AssertionError("UI check opened camera");
                      }
                    });
            app.bindMeetings(
                new MeetingManager(
                    new MeetingManager.MemoryStore(),
                    () ->
                        new SessionEngine(
                            new SessionEngine.Factory() {
                              public AudioSource audio() {
                                throw new AssertionError("Unexpected audio");
                              }

                              public SpeechProvider speech(
                                  ProviderConfig c, ProviderConfig.Credentials k) {
                                throw new AssertionError("Unexpected ASR");
                              }

                              public TextProvider text(
                                  ProviderConfig c, ProviderConfig.Credentials k) {
                                throw new AssertionError("Unexpected model");
                              }
                            }),
                    app.settings.snapshot(),
                    false));
          });
      Map<String, String> encrypted = new HashMap<>();
      for (String key : List.of("asr-key", "text-key", "image-key")) {
        String value = prefs.getString(key, "");
        require(!value.isEmpty() && !value.contains("synthetic"), "Fixture key not encrypted");
        encrypted.put(key, value);
      }
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      long until = SystemClock.elapsedRealtime() + 10000;
      while (!screen.hasWindowFocus() && SystemClock.elapsedRealtime() < until)
        SystemClock.sleep(50);
      require(screen.hasWindowFocus(), "Unlock phone to check input UI");
      DeviceChecks.onMain(
          test,
          () -> {
            screen.findViewById(R.id.image_input_button).performClick();
            Dialog drawer = dialog(screen, "imageUi", "drawer");
            surface(drawer);
            level(drawer);
            require(
                drawer.findViewById(R.id.video_enabled) instanceof Switch,
                "Video control not switch");
            for (int id : new int[] {R.id.image_refresh, R.id.audio_refresh}) {
              View refresh = drawer.findViewById(id);
              require(refresh.getContentDescription() != null, "Refresh description missing");
              transparent(refresh);
            }
            RadioGroup images = drawer.findViewById(R.id.image_sources);
            images.getChildAt(1).performClick();
            drawer.findViewById(R.id.image_refresh).performClick();
            require(
                ((RadioButton) images.getChildAt(1)).isChecked(),
                "Video refresh lost selected source");
            require(!((RadioButton) images.getChildAt(0)).isChecked(), "Video has two selections");
            RadioGroup inputs = drawer.findViewById(R.id.audio_source);
            inputs.getChildAt(1).performClick();
            drawer.findViewById(R.id.audio_refresh).performClick();
            require(
                ((RadioButton) inputs.getChildAt(1)).isChecked(),
                "Audio refresh lost selected source");
            require(!((RadioButton) inputs.getChildAt(0)).isChecked(), "Audio has two selections");
            require(!app.video.enabled() && !app.audio.active(), "Selection started capture");
          });
      saveScreen(test, "input-style-drawer.png");
      DeviceChecks.onMain(
          test,
          () -> {
            Dialog drawer = dialog(screen, "imageUi", "drawer");
            find(drawer.getWindow().getDecorView(), screen.getString(R.string.image_settings))
                .performClick();
            Dialog page = dialog(screen, "imageUi", "settings");
            surface(page);
            require(page.findViewById(R.id.image_preview) != null, "Video preview missing");
            require(
                page.findViewById(R.id.image_model_url) == null, "Provider still on video page");
          });
      saveScreen(test, "input-style-video.png");
      DeviceChecks.onMain(
          test,
          () -> {
            dialog(screen, "imageUi", "settings")
                .findViewById(R.id.image_settings_back)
                .performClick();
          });
      test.waitForIdleSync();
      DeviceChecks.onMain(
          test,
          () -> {
            dialog(screen, "imageUi", "drawer").findViewById(R.id.audio_settings).performClick();
            Dialog page = dialog(screen, "audioUi", "settings");
            surface(page);
            level(page);
            require(
                page.findViewById(R.id.audio_source) instanceof RadioGroup,
                "Audio page lacks inline radio group");
            require(page.findViewById(R.id.audio_refresh) != null, "Audio page lacks refresh icon");
          });
      saveScreen(test, "input-style-audio.png");
      DeviceChecks.onMain(
          test,
          () -> {
            dialog(screen, "audioUi", "settings")
                .findViewById(R.id.audio_settings_back)
                .performClick();
          });
      test.waitForIdleSync();
      DeviceChecks.onMain(
          test,
          () -> {
            dialog(screen, "imageUi", "drawer").dismiss();
            screen.findViewById(R.id.meetings_button).performClick();
            surface(dialog(screen, "drawerDialog"));
          });
      saveScreen(test, "input-style-topics.png");
      DeviceChecks.onMain(
          test,
          () -> {
            dialog(screen, "drawerDialog").findViewById(R.id.drawer_settings).performClick();
            transparent(screen.findViewById(R.id.settings_back));
            for (int id :
                new int[] {
                  R.id.settings_models,
                  R.id.settings_audio,
                  R.id.settings_video,
                  R.id.settings_context,
                  R.id.settings_listening,
                  R.id.settings_preferences,
                  R.id.settings_keyboard,
                  R.id.settings_privacy,
                  R.id.settings_about
                }) {
              SettingsSection section = screen.findViewById(id);
              require(
                  !section.expanded() && section.content().getVisibility() == View.GONE,
                  "Settings section not initially folded");
            }
          });
      saveScreen(test, "input-style-settings-folded.png");
      DeviceChecks.onMain(
          test,
          () -> {
            SettingsSection models = screen.findViewById(R.id.settings_models);
            models.heading().performClick();
            SettingsSection image = screen.findViewById(R.id.settings_image_model);
            image.heading().performClick();
            EditText url = screen.findViewById(R.id.image_model_url);
            EditText key = screen.findViewById(R.id.image_model_key);
            require(
                url != null && key != null && key.length() == 0 && !key.isSaveEnabled(),
                "Provider form or empty key field missing");
            url.setText("https://images.example.org/v1");
            models.heading().performClick();
            require(!url.isShown(), "Folded settings body still shown");
            models.heading().performClick();
            require(
                url == screen.findViewById(R.id.image_model_url)
                    && url.getText().toString().equals("https://images.example.org/v1"),
                "Folding discarded unsaved form");
            ((EditText) screen.findViewById(R.id.image_model_name)).setText("vision-fixture");
            ((Switch) screen.findViewById(R.id.image_model_format)).setChecked(false);
            screen.findViewById(R.id.models_save).performClick();
            require(
                app.repository.load().image.model.equals("vision-fixture"), "Provider not saved");
            require(
                app.repository.load().image.sourceId.equals("usb:fixture"),
                "Provider save lost video source");
            require(
                app.repository.load().audio.source.equals("usb:fixture"),
                "Provider save lost audio source");
            require(app.repository.load().image.left == .2f, "Provider save lost crop");
            require(key.length() == 0, "Saved secret exposed");
            for (Map.Entry<String, String> e : encrypted.entrySet())
              require(
                  e.getValue().equals(prefs.getString(e.getKey(), "")),
                  "Blank field replaced encrypted key");
            require(
                app.repository.imageKey().equals("synthetic-image"),
                "Android Keystore image round trip failed");
            url.requestFocus();
            View ancestor = url;
            int top = 0;
            while (!(ancestor instanceof ScrollView)) {
              top += ancestor.getTop();
              ancestor = (View) ancestor.getParent();
            }
            ((ScrollView) ancestor).scrollTo(0, Math.max(0, top - 80));
          });
      SystemClock.sleep(350);
      saveScreen(test, "input-style-models.png");
      if (theme) ThemeDeviceCheck.run(test, screen);
      Bundle result = new Bundle();
      result.putString(
          "checks",
          "NATIVE_THEMED_SURFACES_LEVEL_TRACK_FOLDED_SETTINGS_UNSAVED_RETENTION_SWITCH_RADIOS_REFRESH_TRANSPARENT_BACK_PROVIDER_MOVE_KEYSTORE_BLANK_KEY_RETENTION_NO_CAPTURE");
      test.sendStatus(0, result);
    } finally {
      MainActivity screen = activity;
      if (screen != null) {
        DeviceChecks.onMain(test, screen::finish);
        test.waitForIdleSync();
      }
      DeviceChecks.onMain(
          test,
          () -> {
            app.audio.stopTest();
            app.video.setEnabled(false);
            app.meetings.close();
            app.repository = repository;
            app.settings = original;
            app.audio = audio;
            app.video = video;
            app.awake = awake;
            app.bindMeetings(meetings);
          });
      prefs.edit().clear().commit();
      var ui = app.getSharedPreferences("settings-ui-v1", 0).edit().clear();
      for (Map.Entry<String, ?> entry : uiBefore.entrySet())
        ui.putBoolean(entry.getKey(), (Boolean) entry.getValue());
      ui.commit();
      require(
          before.equals(app.getSharedPreferences("settings-v1", 0).getAll()),
          "Production preferences changed");
    }
  }

  private static void surface(Dialog dialog) {
    require(
        ((ColorDrawable) dialog.getWindow().getDecorView().getBackground()).getColor()
            == AppTheme.color(dialog.getContext(), AppTheme.SURFACE),
        "Drawer or setup surface differs from theme background");
  }

  private static void level(Dialog dialog) {
    ProgressBar meter = dialog.findViewById(R.id.audio_level);
    require(
        meter.getProgressBackgroundTintList().getDefaultColor()
            == AppTheme.color(dialog.getContext(), AppTheme.SURFACE),
        "Audio level track differs from page background");
  }

  private static Object field(Object owner, String name) {
    try {
      Field f = owner.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(owner);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static Dialog dialog(Object owner, String... fields) {
    for (String f : fields) owner = field(owner, f);
    Dialog dialog = (Dialog) owner;
    require(
        dialog != null && dialog.isShowing(),
        "Expected dialog not showing: " + String.join(".", fields));
    return dialog;
  }

  private static TextView find(View view, String label) {
    if (view instanceof TextView t && label.contentEquals(t.getText())) return t;
    if (view instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView found = find(g.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static void transparent(View view) {
    require(
        view.getBackground() instanceof RippleDrawable
            && ((RippleDrawable) view.getBackground()).getNumberOfLayers() == 1,
        "Icon has permanent background");
  }

  private static void saveScreen(Instrumentation test, String name) throws IOException {
    test.waitForIdleSync();
    SystemClock.sleep(350); // Wait for the native surface transaction, beyond the main queue.
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    require(bitmap != null, "No screenshot");
    try (FileOutputStream out =
        new FileOutputStream(new File(test.getTargetContext().getCacheDir(), name))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "Screenshot failed");
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
