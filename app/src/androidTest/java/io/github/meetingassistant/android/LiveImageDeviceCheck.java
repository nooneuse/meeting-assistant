package io.github.meetingassistant.android;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.concurrent.atomic.*;

/** Explicitly opt-in real image/model check using saved credentials and a disposable meeting. */
final class LiveImageDeviceCheck {
  static void run(Instrumentation test, boolean usb, boolean audio) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    ImageDeviceCheck.require(
        !app.session.snapshot().busy() && !app.imageJobs.busy(),
        "Live image check requires idle app");
    MeetingManager oldMeetings = app.meetings;
    SettingsRepository oldRepository = app.repository;
    UserSettings oldSettings = app.settings;
    ImageInputController oldVideo = app.video;
    boolean oldAwake = app.awake;
    ProviderConfig.Credentials keys = oldRepository.keys();
    keys.validateText();
    String imageKey = oldRepository.hasKey("image") ? oldRepository.imageKey() : keys.textKey;
    OpenAiImageProvider.validateKey(imageKey);
    String preferences = "image-live-device-check", database = "image-live-device-check.db";
    AtomicInteger pcm = new AtomicInteger(),
        duringOcr = new AtomicInteger(),
        duringText = new AtomicInteger(),
        requests = new AtomicInteger();
    AtomicLong last = new AtomicLong(), gap = new AtomicLong();
    AtomicReference<SessionEngine> owner = new AtomicReference<>();
    java.util.function.Supplier<SessionEngine> factory =
        () ->
            new SessionEngine(
                new SessionEngine.Factory() {
                  public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                    return new FunAsrProvider(NetworkClients.create(), c, k);
                  }

                  public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                    requests.incrementAndGet();
                    return new OpenAiTextProvider(NetworkClients.create(), c, k);
                  }

                  public AudioSource audio() {
                    AudioSource mic = app.audio.source();
                    return new AudioSource() {
                      public void start(Listener l) {
                        mic.start(
                            new Listener() {
                              public void audio(byte[] bytes) {
                                long now = SystemClock.elapsedRealtime(),
                                    prev = last.getAndSet(now);
                                if (prev != 0) gap.accumulateAndGet(now - prev, Math::max);
                                pcm.incrementAndGet();
                                SessionEngine e = owner.get();
                                if (e != null) {
                                  if (e.images().stream()
                                      .anyMatch(i -> i.state == ImageEvidence.State.RECOGNIZING))
                                    duringOcr.incrementAndGet();
                                  if (e.snapshot().analysisState
                                      == SessionEngine.AnalysisState.GENERATING)
                                    duringText.incrementAndGet();
                                }
                                l.audio(bytes);
                              }

                              public void failed(String msg) {
                                l.failed(msg);
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
                });
    MeetingRepository store = new MeetingRepository(app, database);
    MeetingManager manager = null;
    MainActivity screen = null;
    try {
      test.getUiAutomation()
          .grantRuntimePermission(app.getPackageName(), Manifest.permission.CAMERA);
      if (audio)
        test.getUiAutomation()
            .grantRuntimePermission(app.getPackageName(), Manifest.permission.RECORD_AUDIO);
      UserSettings settings =
          new com.google.gson.Gson()
              .fromJson(new com.google.gson.Gson().toJson(oldSettings), UserSettings.class);
      settings.automatic = false;
      UserSettings.Profile profile =
          new UserSettings.Profile(
              "image-test", "OCR test", "算法讨论，解答图像中的主体题目，使用 C++，说明时间和空间复杂度。忽略网页导航与工具栏。只回答有依据的内容。");
      settings.profiles.add(profile);
      settings.selectedId = profile.id;
      if (settings.image.baseUrl.isEmpty()) settings.image.baseUrl = settings.provider.textBaseUrl;
      settings.image.model = "qwen3.5-ocr";
      settings.image.qwen35Coordinates = true;
      // Preserve the locally inspected ROI, so only the approved test page is uploaded.
      manager = new MeetingManager(store, factory, profile, false);
      MeetingManager isolated = manager;
      DeviceChecks.onMain(
          test,
          () -> {
            oldVideo.setEnabled(false);
            app.settings = settings;
            app.repository = new SettingsRepository(app, preferences);
            app.repository.saveModels(settings, keys.asrKey, keys.textKey);
            app.repository.saveImageSettings(settings, imageKey);
            app.video = new ImageInputController(app, settings.image);
            app.bindMeetings(isolated);
            app.awake = true;
          });
      SessionEngine engine = manager.currentEngine();
      owner.set(engine);
      screen =
          (MainActivity)
              test.startActivitySync(
                  new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity activity = screen;
      ImageDeviceCheck.awaitMain(
          test, activity::hasWindowFocus, 10000, "Live image window did not gain focus");
      DeviceChecks.onMain(
          test,
          () -> {
            ImageSource.Device source =
                app.video.devices().stream()
                    .filter(
                        d ->
                            usb
                                ? d.kind() == ImageSource.Kind.USB
                                : d.kind() == ImageSource.Kind.BACK)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Requested image source not connected"));
            app.video.select(source.id());
            app.video.setEnabled(true);
          });
      ImageDeviceCheck.awaitMain(
          test,
          () -> app.video.state() == ImageInputController.State.READY,
          30000,
          "Real image source did not become ready");
      if (audio) {
        keys.validate();
        engine.start(settings.provider, keys, profile);
        ImageDeviceCheck.await(
            () ->
                engine.snapshot().state == SessionEngine.State.LISTENING
                    || engine.snapshot().state == SessionEngine.State.ERROR,
            25000,
            "ASR connection timed out");
        ImageDeviceCheck.require(
            engine.snapshot().state == SessionEngine.State.LISTENING, "ASR connection failed");
        ImageDeviceCheck.phase(
            test, "REAL_ASR_LISTENING; point camera only at synthetic test fixture");
      }
      SystemClock.sleep(1500);
      long start = SystemClock.elapsedRealtime();
      DeviceChecks.onMain(
          test,
          () -> {
            activity.findViewById(R.id.image_capture_button).performClick();
          });
      ImageDeviceCheck.await(
          () -> !engine.images().isEmpty(), 18000, "UI capture was not accepted");
      ImageDeviceCheck.phase(test, "FRAME_ACCEPTED; image request in progress");
      ImageDeviceCheck.await(
          () ->
              engine.images().get(0).state == ImageEvidence.State.READY
                  || engine.images().get(0).state == ImageEvidence.State.ERROR,
          185000,
          "OCR did not settle");
      ImageEvidence evidence = engine.images().get(0);
      ImageDeviceCheck.require(
          evidence.state == ImageEvidence.State.READY, "Real OCR failed: " + evidence.error);
      String recognized = evidence.text();
      ImageDeviceCheck.require(
          (recognized.contains("链表") && (recognized.contains("合并") || recognized.contains("升序")))
              || (recognized.toLowerCase(java.util.Locale.ROOT).contains("merge")
                  && recognized.toLowerCase(java.util.Locale.ROOT).contains("linked list")),
          "OCR did not recognize the synthetic algorithm topic");
      int blocks =
          com.google.gson.JsonParser.parseString(evidence.structure)
              .getAsJsonObject()
              .getAsJsonArray("blocks")
              .size();
      ImageDeviceCheck.phase(
          test,
          "OCR_READY regions=" + blocks + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
      ImageDeviceCheck.await(
          () ->
              engine.snapshot().analysisState == SessionEngine.AnalysisState.DONE
                  || engine.snapshot().analysisState == SessionEngine.AnalysisState.ERROR,
          185000,
          "Text analysis did not settle");
      ImageDeviceCheck.require(
          engine.snapshot().analysisState == SessionEngine.AnalysisState.DONE,
          "Real text analysis failed");
      String answer = engine.snapshot().answer;
      ImageDeviceCheck.require(
          answer.contains("链表")
              && (answer.contains("C++") || answer.contains("cpp") || answer.contains("ListNode")),
          "Answer did not match algorithm/C++ preference");
      ImageDeviceCheck.require(
          requests.get() == 1, "Unexpected extra text requests during image check");
      if (audio) {
        ImageDeviceCheck.require(
            engine.snapshot().state == SessionEngine.State.LISTENING, "Image answer stopped ASR");
        ImageDeviceCheck.require(
            duringOcr.get() > 10 && duringText.get() > 10,
            "No continuous PCM observed during real OCR/text");
      } else ImageDeviceCheck.require(pcm.get() == 0, "OCR-only unexpectedly opened microphone");
      ImageDeviceCheck.phase(
          test,
          "LIVE_IMAGE_PASS source="
              + (usb ? "USB" : "BACK")
              + " audio="
              + audio
              + " image="
              + evidence.width
              + "x"
              + evidence.height
              + " answerChars="
              + answer.length()
              + " totalMs="
              + (SystemClock.elapsedRealtime() - start)
              + " pcmFrames="
              + pcm.get()
              + " pcmDuringOcr="
              + duringOcr.get()
              + " pcmDuringText="
              + duringText.get()
              + " maxPCMGapMs="
              + gap.get());
      // Keep only the synthetic test answer for local code review; never export pixels or settings.
      try (java.io.FileOutputStream out =
          new java.io.FileOutputStream(
              new java.io.File(app.getCacheDir(), "synthetic-image-answer.txt"))) {
        out.write(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      SystemClock.sleep(1500);
    } finally {
      if (owner.get() != null) owner.get().cancel();
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
      app.getSharedPreferences(preferences, 0).edit().clear().commit();
      app.deleteSharedPreferences(preferences);
    }
  }
}
