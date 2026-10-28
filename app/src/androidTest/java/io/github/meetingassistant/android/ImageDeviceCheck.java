package io.github.meetingassistant.android;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Opt-in real camera/microphone checks. Pixel data stays in memory; providers are synthetic. */
final class ImageDeviceCheck {
  /** One opt-in local preflight frame, kept in app-private cache for inspection, never uploaded. */
  static void preflight(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    require(
        !app.session.snapshot().busy() && !app.video.enabled(),
        "Preflight requires idle app/input off");
    boolean oldAwake = app.awake;
    // Match the app's selected input and ROI instead of silently testing a different configuration.
    ImageSettings imageSettings =
        new com.google.gson.Gson()
            .fromJson(new com.google.gson.Gson().toJson(app.settings.image), ImageSettings.class);
    ImageInputController source = new ImageInputController(app, imageSettings);
    MainActivity activity = null;
    try {
      test.getUiAutomation()
          .grantRuntimePermission(app.getPackageName(), Manifest.permission.CAMERA);
      DeviceChecks.onMain(test, () -> app.awake = true);
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      awaitMain(test, screen::hasWindowFocus, 10000, "USB preflight window did not gain focus");
      DeviceChecks.onMain(
          test,
          () -> {
            ImageSource.Device usb =
                source.devices().stream()
                    .filter(d -> d.kind() == ImageSource.Kind.USB)
                    .sorted(
                        java.util.Comparator.comparing(d -> !d.id().equals(imageSettings.sourceId)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("USB source missing"));
            source.select(usb.id());
            source.setEnabled(true);
          });
      awaitMain(
          test,
          () ->
              source.state() == ImageInputController.State.READY
                  || source.state() == ImageInputController.State.ERROR,
          30000,
          "USB preflight source not ready");
      require(
          source.state() == ImageInputController.State.READY,
          "USB preflight error: " + source.error());
      // Inspect a settled stream, rather than its first available startup frame.
      SystemClock.sleep(2000);
      AtomicReference<ImageInputController.Prepared> frame = new AtomicReference<>();
      java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
      DeviceChecks.onMain(
          test,
          () ->
              source.capture(
                  p -> {
                    frame.set(p);
                    latch.countDown();
                  },
                  e -> latch.countDown()));
      require(
          latch.await(15, TimeUnit.SECONDS) && frame.get() != null, "USB preflight capture failed");
      try (java.io.FileOutputStream out =
          new java.io.FileOutputStream(new java.io.File(app.getCacheDir(), "usb-preflight.jpg"))) {
        out.write(frame.get().jpeg());
      }
      phase(
          test,
          "USB_PREFLIGHT_SAVED_LOCAL_ONLY "
              + frame.get().evidence().width
              + "x"
              + frame.get().evidence().height);
    } finally {
      DeviceChecks.onMain(
          test,
          () -> {
            source.setEnabled(false);
            app.awake = oldAwake;
          });
      if (activity != null) DeviceChecks.finishActivity(test, activity);
      await(() -> usbHandles() == 0, 7000, "USB preflight cleanup left device handles open");
    }
  }

  static void run(Instrumentation test) throws Exception {
    run(test, false);
  }

  static void run(Instrumentation test, boolean usb) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    require(
        !app.session.snapshot().busy() && !app.imageJobs.busy(), "Image test requires idle app");
    MeetingManager oldMeetings = app.meetings;
    SettingsRepository oldRepository = app.repository;
    UserSettings oldSettings = app.settings;
    ImageInputController oldVideo = app.video;
    boolean oldAwake = app.awake;
    int initialUsbHandles = usb ? usbHandles() : 0;
    String database = "image-device-check.db";
    String preferences = "image-device-check";
    AtomicInteger frames = new AtomicInteger();
    AtomicLong last = new AtomicLong(), maxGap = new AtomicLong();
    AtomicReference<SpeechProvider.Listener> speech = new AtomicReference<>();
    AtomicReference<String> audioError = new AtomicReference<>("");
    CountDownLatch textEntered = new CountDownLatch(1), textRelease = new CountDownLatch(1);
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

                      public void sendAudio(byte[] pcm) {}

                      public void finish() {
                        speech.get().completed();
                      }

                      public void cancel() {}
                    };
                  }

                  public AudioSource audio() {
                    AudioSource mic = app.audio.source();
                    return new AudioSource() {
                      public void start(Listener l) {
                        mic.start(
                            new Listener() {
                              public void audio(byte[] pcm) {
                                long now = SystemClock.elapsedRealtime(),
                                    prev = last.getAndSet(now);
                                if (prev != 0) maxGap.accumulateAndGet(now - prev, Math::max);
                                frames.incrementAndGet();
                                l.audio(pcm);
                              }

                              public void failed(String message) {
                                audioError.set(message);
                                l.failed(message);
                              }
                            });
                      }

                      public void stop(Runnable done) {
                        mic.stop(done);
                      }

                      public void cancel() {
                        mic.cancel();
                      }
                    };
                  }

                  public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                    return new TextProvider() {
                      public void analyze(com.google.gson.JsonArray messages, Listener l) {
                        textEntered.countDown();
                        new Thread(
                                () -> {
                                  try {
                                    textRelease.await(15, TimeUnit.SECONDS);
                                    l.delta("Synthetic local image answer; no network.");
                                    l.completed(false);
                                  } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                  }
                                },
                                "image-device-text")
                            .start();
                      }

                      public void cancel() {
                        textRelease.countDown();
                      }
                    };
                  }
                });
    MeetingRepository store = new MeetingRepository(app, database);
    MeetingManager manager = null;
    MainActivity screen = null;
    try {
      test.getUiAutomation()
          .grantRuntimePermission(app.getPackageName(), Manifest.permission.CAMERA);
      test.getUiAutomation()
          .grantRuntimePermission(app.getPackageName(), Manifest.permission.RECORD_AUDIO);
      require(
          app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
          "Camera permission missing");
      UserSettings settings =
          new com.google.gson.Gson()
              .fromJson(new com.google.gson.Gson().toJson(oldSettings), UserSettings.class);
      settings.image = new ImageSettings();
      settings.automatic = false;
      settings.bind(UserSettings.IMAGE_TOGGLE, KeyEvent.KEYCODE_V);
      settings.bind(UserSettings.IMAGE_CAPTURE, KeyEvent.KEYCODE_C);
      settings.keyboardEnabled = true;
      settings.provider.asrUrl = "wss://example.org/asr";
      settings.provider.textBaseUrl = "https://example.org/v1";
      manager = new MeetingManager(store, factory, settings.snapshot(), false);
      MeetingManager isolated = manager;
      DeviceChecks.onMain(
          test,
          () -> {
            oldVideo.setEnabled(false);
            app.settings = settings;
            app.repository = new SettingsRepository(app, preferences);
            app.repository.save(settings);
            app.video = new ImageInputController(app, settings.image);
            if (usb)
              settings.image.sourceId =
                  app.video.devices().stream()
                      .filter(d -> d.kind() == ImageSource.Kind.USB)
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("Connect a USB video source first"))
                      .id();
            app.bindMeetings(isolated);
            app.awake = true;
          });
      screen =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity activity = screen;
      awaitMain(test, activity::hasWindowFocus, 10000, "Image check window did not gain focus");
      DeviceChecks.onMain(
          test,
          () -> {
            require(
                activity.findViewById(R.id.image_capture_button).getVisibility() == View.GONE,
                "Capture visible with input off");
            key(activity, KeyEvent.KEYCODE_V);
          });
      awaitMain(
          test,
          () ->
              app.video.state() == ImageInputController.State.READY
                  || app.video.state() == ImageInputController.State.ERROR,
          25000,
          "Initial camera did not become ready");
      require(
          app.video.state() == ImageInputController.State.READY,
          "Initial source error: " + app.video.error());
      List<ImageSource.Device> cameras =
          app.video.devices().stream()
              .filter(d -> usb ? d.kind() == ImageSource.Kind.USB : d.id().startsWith("camera:"))
              .toList();
      require(!cameras.isEmpty(), "No requested image sources");
      phase(test, "IMAGE_SOURCES=" + cameras.size() + "; no pixel files/network");
      SessionEngine engine = manager.currentEngine();
      engine.start(
          settings.provider,
          new ProviderConfig.Credentials("synthetic", "synthetic"),
          settings.snapshot());
      await(() -> frames.get() >= 10, 7000, "Microphone delivered no PCM frames");
      for (ImageSource.Device device : cameras) {
        DeviceChecks.onMain(test, () -> app.video.select(device.id()));
        awaitMain(
            test,
            () -> app.video.state() == ImageInputController.State.READY,
            25000,
            "Selected camera did not become ready");
        AtomicInteger previews = new AtomicInteger();
        DeviceChecks.onMain(
            test,
            () ->
                app.video.preview(
                    bitmap -> {
                      previews.incrementAndGet();
                      bitmap.recycle();
                    }));
        await(() -> previews.get() >= 2, 15000, "Preview did not deliver frames");
        DeviceChecks.onMain(test, () -> app.video.preview(null));
        awaitMain(test, () -> !app.video.capturing(), 10000, "Preview did not drain");
        ImageInputController.Prepared full = capture(test, app);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(full.jpeg(), 0, full.jpeg().length, bounds);
        require(
            bounds.outWidth == full.evidence().width && bounds.outHeight == full.evidence().height,
            "JPEG dimensions differ from evidence");
        require(
            full.evidence().width > 100 && full.evidence().height > 100,
            "Captured image too small");
        ImageSettings crop = settings.image;
        DeviceChecks.onMain(
            test,
            () -> {
              crop.cropSource = device.id();
              crop.cropWidth = full.evidence().originalWidth;
              crop.cropHeight = full.evidence().originalHeight;
              crop.cropRotation = full.evidence().rotation;
              crop.left = .2f;
              crop.top = .2f;
              crop.right = .8f;
              crop.bottom = .8f;
            });
        ImageInputController.Prepared cropped = capture(test, app);
        require(
            cropped.evidence().width < full.evidence().width * .7
                && cropped.evidence().height < full.evidence().height * .7,
            "Physical camera crop not applied");
        phase(
            test,
            "CAMERA_CAPTURE kind="
                + device.kind()
                + " full="
                + full.evidence().width
                + "x"
                + full.evidence().height
                + " crop="
                + cropped.evidence().width
                + "x"
                + cropped.evidence().height
                + " rotation="
                + full.evidence().rotation);
        DeviceChecks.onMain(
            test,
            () -> {
              crop.cropSource = "";
              crop.left = crop.top = 0;
              crop.right = crop.bottom = 1;
            });
      }
      ImageSource.Device back =
          cameras.stream()
              .filter(
                  d -> usb ? d.kind() == ImageSource.Kind.USB : d.kind() == ImageSource.Kind.BACK)
              .findFirst()
              .orElse(cameras.get(0));
      DeviceChecks.onMain(test, () -> app.video.select(back.id()));
      awaitMain(
          test,
          () -> app.video.state() == ImageInputController.State.READY,
          25000,
          "Back camera did not reopen");
      // Exercise the real settings dialog and drag-to-crop gesture, without exporting pixels.
      DeviceChecks.onMain(
          test, () -> activity.findViewById(R.id.image_input_button).performClick());
      tapText(test, activity.getString(R.string.image_settings));
      awaitMain(
          test,
          () -> app.video.previewWidth > 0 && app.video.previewHeight > 0,
          15000,
          "Settings preview did not initialize");
      SystemClock.sleep(1400);
      android.graphics.Rect previewBounds = new android.graphics.Rect();
      android.view.accessibility.AccessibilityNodeInfo previewNode = null;
      for (int attempt = 0; attempt < 5; attempt++) {
        android.view.accessibility.AccessibilityNodeInfo root =
            test.getUiAutomation().getRootInActiveWindow();
        if (root != null) {
          java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
              root.findAccessibilityNodeInfosByViewId(app.getPackageName() + ":id/image_preview");
          if (!nodes.isEmpty()) {
            previewNode = nodes.get(0);
            previewNode.getBoundsInScreen(previewBounds);
            if (previewBounds.height() > 200) {
              root.recycle();
              break;
            }
          }
          scrollFirst(root);
          root.recycle();
        }
        SystemClock.sleep(350);
      }
      require(previewNode != null && previewBounds.height() > 200, "Settings preview inaccessible");
      previewNode.recycle();
      float scale =
          Math.min(
              (float) previewBounds.width() / app.video.previewWidth,
              (float) previewBounds.height() / app.video.previewHeight);
      float imageW = app.video.previewWidth * scale, imageH = app.video.previewHeight * scale;
      float x1 = previewBounds.exactCenterX() - .3f * imageW,
          y1 = previewBounds.exactCenterY() - .3f * imageH;
      float x2 = previewBounds.exactCenterX() + .3f * imageW,
          y2 = previewBounds.exactCenterY() + .3f * imageH;
      long down = SystemClock.uptimeMillis();
      for (int step = 0; step <= 6; step++) {
        int action =
            step == 0
                ? android.view.MotionEvent.ACTION_DOWN
                : step == 6
                    ? android.view.MotionEvent.ACTION_UP
                    : android.view.MotionEvent.ACTION_MOVE;
        android.view.MotionEvent event =
            android.view.MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                action,
                x1 + (x2 - x1) * step / 6,
                y1 + (y2 - y1) * step / 6,
                0);
        test.sendPointerSync(event);
        event.recycle();
        SystemClock.sleep(45);
      }
      awaitMain(
          test,
          () -> !settings.image.cropSource.isEmpty(),
          3000,
          "Preview gesture did not save crop");
      AtomicReference<ImageInputUi> inspectedUi = new AtomicReference<>();
      DeviceChecks.onMain(
          test,
          () -> {
            try {
              java.lang.reflect.Field uiField = MainActivity.class.getDeclaredField("imageUi");
              uiField.setAccessible(true);
              ImageInputUi ui = (ImageInputUi) uiField.get(activity);
              inspectedUi.set(ui);
              java.lang.reflect.Field dialogField = ImageInputUi.class.getDeclaredField("settings");
              dialogField.setAccessible(true);
              android.app.Dialog page = (android.app.Dialog) dialogField.get(ui);
              require(
                  page.findViewById(R.id.image_settings_back).performClick(),
                  "Settings back icon unavailable");

              require(!activity.isFinishing(), "Settings back finished meeting activity");
              require(
                  app.video.state() == ImageInputController.State.READY,
                  "Settings back closed image source");
            } catch (ReflectiveOperationException e) {
              throw new AssertionError("Could not inspect image dialog");
            }
          });
      test.waitForIdleSync();
      DeviceChecks.onMain(test, () -> inspectedUi.get().closeDrawer());
      test.waitForIdleSync();
      awaitMain(test, () -> !inspectedUi.get().showing(), 3000, "Settings/drawer did not dismiss");
      awaitMain(test, () -> !app.video.capturing(), 10000, "Settings preview did not drain");
      ImageInputController.Prepared gestureCrop = capture(test, app);
      require(
          gestureCrop.evidence().cropLeft > .1f && gestureCrop.evidence().cropRight < .9f,
          "Gesture crop metadata not reflected in capture");
      phase(test, "SETTINGS_PREVIEW_DRAG_CROP_PASS");
      ImageInputController.Prepared prepared = capture(test, app);
      CountDownLatch ocrEntered = new CountDownLatch(1), ocrRelease = new CountDownLatch(1);
      int before = frames.get();
      require(
          app.imageJobs.submit(
              engine,
              prepared.evidence(),
              prepared.jpeg(),
              () ->
                  new ImageUnderstandingProvider() {
                    public void recognize(byte[] jpeg, Listener l) {
                      ocrEntered.countDown();
                      new Thread(
                              () -> {
                                try {
                                  ocrRelease.await(15, TimeUnit.SECONDS);
                                  l.completed(
                                      "{\"blocks\":[{\"id\":\"1\",\"text\":\"Synthetic meeting"
                                          + " image"
                                          + " question\",\"box\":[0.1,0.1,0.9,0.9],\"order\":0,\"role_inference\":\"unknown\"}],\"uncertainties\":[]}");
                                } catch (InterruptedException e) {
                                  Thread.currentThread().interrupt();
                                }
                              },
                              "image-device-ocr")
                          .start();
                    }

                    public void cancel() {
                      ocrRelease.countDown();
                    }
                  },
              settings.provider,
              new ProviderConfig.Credentials("synthetic", "synthetic")),
          "Image queue rejected real camera frame");
      require(ocrEntered.await(5, TimeUnit.SECONDS), "Synthetic OCR did not begin");
      await(() -> frames.get() >= before + 25, 6000, "PCM stalled during OCR");
      int during = frames.get();
      ocrRelease.countDown();
      require(textEntered.await(5, TimeUnit.SECONDS), "Text analysis did not begin");
      await(() -> frames.get() >= during + 25, 6000, "PCM stalled during text analysis");
      require(
          engine.snapshot().state == SessionEngine.State.LISTENING,
          "Image analysis interrupted listening");
      textRelease.countDown();
      await(
          () -> engine.snapshot().analysisState == SessionEngine.AnalysisState.DONE,
          5000,
          "Synthetic image answer did not finish");
      require(audioError.get().isEmpty(), "Microphone capture failed during camera/OCR");
      engine.cancel();
      await(() -> !engine.snapshot().busy(), 5000, "Listening cancellation did not settle");
      DeviceChecks.onMain(test, () -> key(activity, KeyEvent.KEYCODE_V));
      awaitMain(
          test,
          () -> app.video.state() == ImageInputController.State.OFF,
          3000,
          "Video shortcut did not disable camera");
      DeviceChecks.onMain(test, () -> key(activity, KeyEvent.KEYCODE_V));
      awaitMain(
          test,
          () -> app.video.state() == ImageInputController.State.READY,
          25000,
          "Video shortcut did not reopen camera");
      DeviceChecks.finishActivity(test, screen);
      screen = null;
      awaitMain(
          test,
          () -> app.video.state() == ImageInputController.State.OFF,
          3000,
          "Background did not close image source");
      phase(
          test,
          "REAL_HARDWARE_PASS sources="
              + cameras.size()
              + " pcmFrames="
              + frames.get()
              + " maxPCMGapMs="
              + maxGap.get()
              + "; synthetic OCR/text; no network or pixel files");
    } finally {
      textRelease.countDown();
      if (screen != null) DeviceChecks.finishActivity(test, screen);
      DeviceChecks.onMain(test, () -> app.video.setEnabled(false));
      if (manager != null) manager.close();
      store.close();
      app.deleteDatabase(database);
      DeviceChecks.onMain(
          test,
          () -> {
            app.settings = oldSettings;
            app.repository = oldRepository;
            app.video = oldVideo;
            app.awake = oldAwake;
            app.bindMeetings(oldMeetings);
          });
      if (usb) {
        await(
            () -> usbHandles() <= initialUsbHandles, 7000, "USB cleanup left device handles open");
        phase(test, "USB_DEVICE_HANDLES_RELEASED");
      }
      app.getSharedPreferences(preferences, 0).edit().clear().commit();
      app.deleteSharedPreferences(preferences);
    }
  }

  static int usbHandles() {
    java.io.File[] fds = new java.io.File("/proc/self/fd").listFiles();
    require(fds != null, "Cannot inspect own USB handles");
    int count = 0;
    for (java.io.File fd : fds)
      try {
        if (android.system.Os.readlink(fd.getPath()).startsWith("/dev/bus/usb/")) count++;
      } catch (android.system.ErrnoException ignored) {
      }
    return count;
  }

  static void tapText(Instrumentation test, String value) throws Exception {
    await(
        () -> {
          android.view.accessibility.AccessibilityNodeInfo root =
              test.getUiAutomation().getRootInActiveWindow();
          if (root == null) return false;
          boolean clicked = false;
          for (android.view.accessibility.AccessibilityNodeInfo n :
              root.findAccessibilityNodeInfosByText(value)) {
            if (value.contentEquals(n.getText()) && n.isClickable())
              clicked |=
                  n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            n.recycle();
          }
          root.recycle();
          return clicked;
        },
        5000,
        "Image settings entry unavailable");
    test.waitForIdleSync();
  }

  static boolean scrollFirst(android.view.accessibility.AccessibilityNodeInfo node) {
    if (node.isScrollable()
        && node.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
    for (int i = 0; i < node.getChildCount(); i++) {
      android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
      if (child != null) {
        boolean done = scrollFirst(child);
        child.recycle();
        if (done) return true;
      }
    }
    return false;
  }

  static ImageInputController.Prepared capture(Instrumentation test, MeetingApp app)
      throws Exception {
    AtomicReference<ImageInputController.Prepared> result = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>("");
    CountDownLatch done = new CountDownLatch(1);
    DeviceChecks.onMain(
        test,
        () ->
            app.video.capture(
                p -> {
                  result.set(p);
                  done.countDown();
                },
                e -> {
                  error.set(e);
                  done.countDown();
                }));
    require(done.await(15, TimeUnit.SECONDS), "Camera capture timed out");
    require(result.get() != null, "Camera capture failed: " + error.get());
    return result.get();
  }

  static void key(MainActivity a, int code) {
    a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
    a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
  }

  static void phase(Instrumentation test, String msg) {
    Bundle b = new Bundle();
    b.putString("image-check", msg);
    test.sendStatus(0, b);
  }

  static void awaitMain(
      Instrumentation test, BooleanSupplier condition, long timeout, String failure)
      throws Exception {
    await(
        () -> {
          AtomicBoolean value = new AtomicBoolean();
          DeviceChecks.onMain(test, () -> value.set(condition.getAsBoolean()));
          return value.get();
        },
        timeout,
        failure);
  }

  static void await(BooleanSupplier condition, long timeout, String failure) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.getAsBoolean()) return;
      SystemClock.sleep(80);
    }
    throw new AssertionError(failure);
  }

  static void require(boolean ok, String msg) {
    if (!ok) throw new AssertionError(msg);
  }
}
