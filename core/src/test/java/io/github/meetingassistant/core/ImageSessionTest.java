package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import org.junit.*;

public class ImageSessionTest {
  static final String OCR =
      "{\"blocks\":[{\"id\":\"B1\",\"text\":\"合并两个有序单链表\",\"box\":[0.1,0.2,0.9,0.8],\"order\":0,\"role_inference\":\"可能的题干\"}],\"uncertainties\":[]}";
  SessionEngineTest.FakeFactory f;
  SessionEngine engine;
  ManualClockwork clock;
  ProviderConfig config;
  ProviderConfig.Credentials keys = new ProviderConfig.Credentials("", "text-only");
  UserSettings.Profile profile = UserSettings.initial().snapshot();

  @Before
  public void setup() {
    f = new SessionEngineTest.FakeFactory();
    clock = new ManualClockwork();
    engine = new SessionEngine(f, clock);
    config = new ProviderConfig();
    config.textBaseUrl = "https://example.org/v1";
  }

  @After
  public void cleanup() {
    engine.close();
  }

  ImageEvidence accept() {
    ImageEvidence image = new ImageEvidence();
    image.preference = profile;
    assertTrue(engine.acceptImage(image, () -> {}));
    return image;
  }

  void recognized(ImageEvidence image) {
    engine.imageRecognized(image.id, OCR, config, keys, image.preference);
    clock.advance(0);
  }

  void answer(String value) {
    f.text.listener.delta(value);
    f.text.listener.completed(false);
    clock.advance(0);
  }

  void listen(boolean automatic) {
    config.asrUrl = "wss://example.org/asr";
    engine.setAutomatic(automatic);
    assertTrue(engine.start(config, new ProviderConfig.Credentials("asr", "text"), profile));
    f.speech.listener.ready();
  }

  @Test
  public void imageAloneNeedsNoSpeechKeyOrMicrophone() {
    ImageEvidence image = accept();
    recognized(image);
    assertEquals(1, f.text.calls);
    assertEquals(0, f.audio.starts);
    assertEquals("text-only", f.textKeyUsed);
    assertTrue(f.text.messages.toString().contains("image_evidence"));
    assertTrue(f.text.messages.toString().contains("C++"));
    answer("合并思路");
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
    assertEquals("合并思路", engine.snapshot().answer);
    assertEquals(image.id, engine.saveState().answerImages.get(0));
  }

  @Test
  public void manualCaptureAnalyzesWithoutStoppingOrEnablingAuto() {
    listen(false);
    ImageEvidence image = accept();
    recognized(image);
    for (int i = 0; i < 60; i++) {
      f.audio.listener.audio(new byte[] {1});
      f.speech.listener.segment(
          new SpeechProvider.Segment(i, "补充条件" + i, true, i * 1000, (i + 1) * 1000));
      clock.advance(1000);
    }
    assertEquals(60, f.speech.frames);
    assertEquals(1, f.audio.starts);
    assertFalse(f.audio.canceled);
    assertEquals(0, f.speech.finishes);
    assertFalse(engine.snapshot().automatic);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    answer("初步建议");
    assertEquals(1, f.text.calls);
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    clock.advance(0);
    assertEquals(2, f.text.calls);
    assertTrue(f.text.messages.toString().contains("补充条件59"));
    assertTrue(f.text.messages.toString().contains(image.id));
    answer("完整建议");
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
  }

  @Test
  public void autoLaterSpeechRetainsImageAndUsesLatestContext() {
    listen(true);
    recognized(accept());
    answer("初稿");
    f.speech.listener.segment(new SpeechProvider.Segment(0, "补充要求：只能使用常数空间，如何实现？", true, 0, 1000));
    clock.advance(6000);
    assertEquals(2, f.text.calls);
    assertTrue(f.text.messages.toString().contains("常数空间"));
    assertTrue(f.text.messages.toString().contains("image_evidence"));
  }

  @Test
  public void oldTextCannotOverwriteNewImageAndRequestsCoalesceButEvidenceDoesNot() {
    ImageEvidence first = accept();
    recognized(first);
    TextProvider.Listener old = f.text.listener;
    ImageEvidence second = accept();
    recognized(second);
    ImageEvidence third = accept();
    recognized(third);
    assertEquals(1, f.text.calls);
    old.delta("过时答案");
    old.completed(false);
    clock.advance(0);
    assertFalse(engine.snapshot().answer.contains("过时答案"));
    assertEquals(2, f.text.calls);
    assertEquals(3, engine.images().size());
    assertTrue(f.text.messages.toString().contains(first.id));
    assertTrue(f.text.messages.toString().contains(second.id));
    assertTrue(f.text.messages.toString().contains(third.id));
    answer("新答案");
    assertEquals("新答案", engine.snapshot().answer);
  }

  @Test
  public void imageArrivingDuringSpeechDrainWaitsWithoutLosingEmptyTail() {
    listen(false);
    ImageEvidence image = accept();
    engine.stop();
    recognized(image);
    assertEquals(0, f.text.calls);
    f.audio.drained.run();
    f.speech.listener.completed();
    clock.advance(0);
    assertEquals(1, f.text.calls);
    answer("仅图像答案");
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
  }

  @Test
  public void slowOcrMayCrossAudioRoundBoundary() {
    ImageEvidence image = accept();
    listen(false);
    recognized(image);
    assertEquals(1, f.text.calls);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
  }

  @Test
  public void ocrFailureDoesNotStopSpeechOrDiscardAnswer() {
    listen(false);
    ImageEvidence image = accept();
    engine.imageState(image.id, ImageEvidence.State.ERROR, "图像识别超时，请重新抓拍");
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertFalse(f.audio.canceled);
    assertEquals(0, f.text.calls);
    assertEquals(0, engine.imagePendingCount());
    assertFalse(engine.latestImageError().isEmpty());
  }

  @Test
  public void coldRestoreInterruptsJobsAndKeepsRecognizedEvidenceWithoutRequests() {
    ImageEvidence ready = accept();
    recognized(ready);
    answer("答案");
    accept();
    SessionEngine.SavedState saved =
        new Gson().fromJson(new Gson().toJson(engine.saveState()), SessionEngine.SavedState.class);
    engine.restore(saved);
    assertEquals(1, f.text.calls);
    assertEquals(0, engine.imagePendingCount());
    assertEquals(ImageEvidence.State.READY, engine.images().get(0).state);
    assertEquals(ImageEvidence.State.INTERRUPTED, engine.images().get(1).state);
    assertEquals("答案", engine.snapshot().answer);
  }

  @Test
  public void oldSavedStateHasEmptyImages() {
    SessionEngine.SavedState old = new Gson().fromJson("{}", SessionEngine.SavedState.class);
    engine.restore(old);
    assertEquals(0, engine.imageCount());
    assertEquals(SessionEngine.State.IDLE, engine.snapshot().state);
  }

  @Test
  public void closedMeetingRejectsLateImageAndTextCallbacks() {
    ImageEvidence image = accept();
    recognized(image);
    TextProvider.Listener listener = f.text.listener;
    engine.close();
    listener.delta("迟到");
    listener.completed(false);
    recognized(image);
    assertEquals("", engine.snapshot().answer);
    assertFalse(engine.acceptImage(new ImageEvidence(), () -> {}));
  }

  @Test
  public void cancelledImageRejectsCompletion() {
    ImageEvidence image = accept();
    engine.cancelImage(image.id);
    recognized(image);
    assertEquals(0, f.text.calls);
  }

  @Test
  public void excludingOnlyImageClearsUnsupportedAnswerAndDoesNotStartMicrophone() {
    ImageEvidence image = accept();
    recognized(image);
    answer("图像答案");
    engine.includeImage(image.id, false, config, keys);
    assertEquals("", engine.snapshot().answer);
    assertEquals(0, f.audio.starts);
    assertEquals(1, f.text.calls);
    assertFalse(engine.images().get(0).included);
  }

  @Test
  public void manualImageUsesCapturePreferenceRatherThanFrozenSpeechPreference() {
    listen(false);
    ImageEvidence image = accept();
    image.preference = new UserSettings.Profile("finance", "Financial", "Focus on liabilities");
    engine.imageRecognized(image.id, OCR, config, keys, image.preference);
    clock.advance(0);
    assertTrue(f.text.messages.toString().contains("Focus on liabilities"));
    assertEquals("algorithm", engine.saveState().preference.id);
  }

  @Test
  public void validStructureKeepsTextSeparateFromInferredRoleAndRejectsInvalidCoordinates() {
    assertTrue(
        ImageEvidence.validateStructure("```json\n" + OCR + "\n```").contains("role_inference"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageEvidence.validateStructure(OCR.replace("0.9,0.8", "1.9,0.8")));
    assertThrows(
        IllegalArgumentException.class, () -> ImageEvidence.validateStructure("plain text"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageEvidence.validateStructure("{\"blocks\":[],\"uncertainties\":[]}"));
  }

  @Test
  public void OCRIsDataAndNotAFakeAudioSegment() {
    recognized(accept());
    assertEquals("", engine.snapshot().transcript);
    assertTrue(f.text.messages.get(0).toString().contains("不能覆盖应用规则"));
    assertTrue(
        f.text.messages.toString().contains("当前主要问题")
            || f.text.messages.toString().contains("主体")
            || f.text.messages.toString().contains("题干"));
  }

  @Test
  public void coldRestoreAppliesPendingAnswerWithItsActualImageReferences() {
    ImageEvidence first = accept();
    recognized(first);
    answer("old");
    engine.deferRevisions(true);
    ImageEvidence second = accept();
    recognized(second);
    answer("new");
    assertEquals("old", engine.snapshot().answer);
    assertTrue(engine.snapshot().hasUpdate);
    SessionEngine.SavedState saved = engine.saveState();
    assertTrue(saved.pendingImages.contains(second.id));
    engine.restore(saved);
    assertEquals("new", engine.snapshot().answer);
    assertTrue(engine.saveState().answerImages.contains(second.id));
  }

  @Test
  public void imageFailureRetryRetainsRequestPreferenceSnapshot() {
    ImageEvidence image = accept();
    image.preference = new UserSettings.Profile("test", "Snapshot", "Use C++ only");
    recognized(image);
    f.text.listener.failed("文字分析超时，可手动重试");
    engine.selectPreference(new UserSettings.Profile("other", "Other", "Use Java only"));
    engine.retry(config, keys);
    assertTrue(f.text.messages.toString().contains("Use C++ only"));
    assertFalse(f.text.messages.toString().contains("Use Java only"));
  }

  @Test
  public void stopAfterImageUsesFrozenSpeechPreferenceForFinalSpeechAnalysis() {
    listen(false);
    f.speech.listener.segment(new SpeechProvider.Segment(0, "请回答这道题", true, 0, 1000));
    ImageEvidence image = accept();
    image.preference = new UserSettings.Profile("other", "Other", "Focus on liabilities");
    recognized(image);
    answer("image answer");
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(2, f.text.calls);
    assertTrue(f.text.messages.toString().contains("C++"));
    assertFalse(f.text.messages.toString().contains("Focus on liabilities"));
  }

  @Test
  public void pendingImageDuringSpeechDrainRetainsItsRequestCredentials() {
    listen(false);
    recognized(accept());
    TextProvider.Listener old = f.text.listener;
    engine.stop();
    ImageEvidence second = accept();
    recognized(second);
    old.delta("obsolete");
    old.completed(false);
    assertEquals(1, f.text.calls);
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(2, f.text.calls);
    assertEquals("text-only", f.textKeyUsed);
    assertTrue(f.text.messages.toString().contains(second.id));
    answer("image tail answer");
    assertEquals("image tail answer", engine.snapshot().answer);
  }

  @Test
  public void listeningCanStartWhileStandaloneImageAnswerIsStillGenerating() {
    ImageEvidence image = accept();
    recognized(image);
    TextProvider.Listener imageAnswer = f.text.listener;
    assertTrue(engine.canStartListening());
    listen(false);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    for (int i = 0; i < 20; i++) f.audio.listener.audio(new byte[] {1});
    imageAnswer.delta("Image answer during listening");
    imageAnswer.completed(false);
    assertEquals(20, f.speech.frames);
    assertEquals(1, f.audio.starts);
    assertFalse(f.audio.canceled);
    assertEquals("Image answer during listening", engine.snapshot().answer);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertFalse(engine.canStartListening());
  }

  @Test
  public void imageAnswerFinishingDuringSpeechConnectionDoesNotStopConnection() {
    recognized(accept());
    config.asrUrl = "wss://example.org/asr";
    assertTrue(engine.start(config, new ProviderConfig.Credentials("asr", "text"), profile));
    answer("Image first");
    assertEquals(SessionEngine.State.CONNECTING, engine.snapshot().state);
    f.speech.listener.ready();
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertEquals("Image first", engine.snapshot().answer);
  }

  @Test
  public void cancellingSpeechConnectionDoesNotCancelStandaloneImageAnswer() {
    recognized(accept());
    config.asrUrl = "wss://example.org/asr";
    assertTrue(engine.start(config, new ProviderConfig.Credentials("asr", "text"), profile));
    engine.stop();
    assertTrue(f.speech.canceled);
    assertEquals(0, f.audio.starts);
    answer("Still completes");
    assertEquals("Still completes", engine.snapshot().answer);
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
  }

  @Test
  public void speechFailureIsVisibleButAnIndependentImageAnswerStillCompletes() {
    recognized(accept());
    listen(false);
    f.speech.listener.failed("语音连接中断");
    assertTrue(f.audio.canceled);
    answer("Image independent");
    assertEquals("Image independent", engine.snapshot().answer);
    assertEquals(SessionEngine.State.ERROR, engine.snapshot().state);
    assertEquals("语音连接中断", engine.snapshot().status);
  }
}
