package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import org.junit.Test;

public class AudioInputTest {
  @Test
  public void gapDoesNotFinishRecognitionOrAnalyzeAndSurvivesRestore() {
    var f = new SessionEngineTest.FakeFactory();
    var engine = new SessionEngine(f, new ManualClockwork());
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    engine.start(
        c, new ProviderConfig.Credentials("test", "test"), UserSettings.initial().snapshot());
    f.speech.listener.ready();
    f.speech.listener.segment(new SpeechProvider.Segment(1, "First question", true, 0, 1000));
    f.audio.listener.discontinuity("[12:00:00 App event: input switched, possible gap]");
    f.audio.listener.audio(new byte[3200]);
    f.speech.listener.segment(new SpeechProvider.Segment(2, "Additional detail", true, 1000, 2000));
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertEquals(0, f.speech.finishes);
    assertEquals(0, f.text.calls);
    assertEquals(1, f.speech.frames);
    String text = engine.snapshot().transcript;
    assertTrue(text.indexOf("question") < text.indexOf("possible gap"));
    assertTrue(text.indexOf("possible gap") < text.indexOf("Additional"));
    SessionEngine.SavedState saved =
        new Gson().fromJson(new Gson().toJson(engine.saveState()), SessionEngine.SavedState.class);
    assertFalse(saved.stableText().contains("possible gap"));
    assertTrue(
        new MeetingRecord.Round("round", saved).transcript("partial").contains("possible gap"));
    engine.restore(saved);
    assertTrue(engine.snapshot().transcript.contains("possible gap"));
    assertFalse(engine.snapshot().busy());
    engine.close();
  }

  @Test
  public void gapIsSentAsAppEventWhenUserFinallyStops() {
    var f = new SessionEngineTest.FakeFactory();
    var engine = new SessionEngine(f, new ManualClockwork());
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    engine.start(
        c, new ProviderConfig.Credentials("test", "test"), UserSettings.initial().snapshot());
    f.speech.listener.ready();
    f.speech.listener.segment(
        new SpeechProvider.Segment(1, "Explain merging sorted lists", true, 0, 1000));
    f.audio.listener.discontinuity("possible sound gap");
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(1, f.text.calls);
    assertTrue(f.text.messages.toString().contains("Application event, not spoken content"));
    assertTrue(f.text.messages.toString().contains("possible sound gap"));
    engine.close();
  }

  @Test
  public void eventOnlyCaptureDoesNotInventQuestionOrCallModel() {
    var f = new SessionEngineTest.FakeFactory();
    var engine = new SessionEngine(f, new ManualClockwork());
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    engine.start(
        c, new ProviderConfig.Credentials("test", "test"), UserSettings.initial().snapshot());
    f.speech.listener.ready();
    f.audio.listener.discontinuity("[gap]");
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(0, f.text.calls);
    assertEquals(SessionEngine.State.IDLE, engine.snapshot().state);
    engine.close();
  }

  @Test
  public void legacySettingsDefaultToBuiltinAndInvalidSelectionRepairsIndependently() {
    UserSettings s = new Gson().fromJson("{\"selectedId\":\"custom\"}", UserSettings.class);
    assertEquals(AudioInputSettings.BUILTIN, s.audio.source);
    s.audio.source = null;
    s.audio.repair();
    assertEquals("builtin", s.audio.source);
    assertEquals("custom", s.selectedId);
  }
}
