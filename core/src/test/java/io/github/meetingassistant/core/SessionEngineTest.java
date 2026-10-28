package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SessionEngineTest {
  FakeFactory f;
  SessionEngine engine;
  ProviderConfig config;
  ProviderConfig.Credentials keys = new ProviderConfig.Credentials("test-asr", "test-text");

  @Before
  public void setup() {
    f = new FakeFactory();
    engine = new SessionEngine(f, new ManualClockwork());
    config = new ProviderConfig();
    config.asrUrl = "wss://example.org/asr";
    config.textBaseUrl = "https://example.org/v1";
  }

  @After
  public void teardown() {
    engine.close();
  }

  void start() {
    assertTrue(engine.start(config, keys, UserSettings.initial().snapshot()));
  }

  @Test
  public void waitsForReadyAndDrainsBeforeFinishAndAnalyzesOnce() {
    start();
    assertEquals(SessionEngine.State.CONNECTING, engine.snapshot().state);
    assertFalse(f.audio.started);
    f.speech.listener.ready();
    assertTrue(f.audio.started);
    f.speech.listener.segment(new SpeechProvider.Segment(1, "合并两个链", false, 0, -1));
    engine.stop();
    engine.stop();
    assertEquals(0, f.speech.finishes);
    assertEquals(0, f.text.calls);
    f.audio.listener.audio(new byte[] {1, 2});
    assertEquals(1, f.speech.frames);
    f.audio.drained.run();
    assertEquals(1, f.speech.finishes);
    f.speech.listener.segment(new SpeechProvider.Segment(1, "合并两个有序链表", true, 0, 900));
    f.speech.listener.completed();
    f.speech.listener.completed();
    assertEquals(1, f.text.calls);
    assertTrue(f.text.messages.toString().contains("合并两个有序链表"));
    f.text.listener.delta("思路");
    f.text.listener.completed(false);
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
    assertEquals("思路", engine.snapshot().answer);
  }

  @Test
  public void emptyAudioDoesNotCallText() {
    start();
    f.speech.listener.ready();
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(0, f.text.calls);
    assertEquals(SessionEngine.State.IDLE, engine.snapshot().state);
  }

  @Test
  public void pendingSegmentsNeverSilentlyDropped() {
    start();
    f.speech.listener.ready();
    f.speech.listener.segment(new SpeechProvider.Segment(1, "没有定稿", false, 0, -1));
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(0, f.text.calls);
    assertFalse(engine.snapshot().retryable);
    assertEquals(SessionEngine.State.ERROR, engine.snapshot().state);
  }

  @Test
  public void cancelRejectsLateCallbacks() {
    start();
    SpeechProvider.Listener old = f.speech.listener;
    engine.cancel();
    old.ready();
    old.segment(new SpeechProvider.Segment(1, "迟到", true, 0, 1));
    old.completed();
    assertFalse(f.audio.started);
    assertEquals("", engine.snapshot().transcript);
    assertEquals(0, f.text.calls);
  }

  @Test
  public void recordingKeepsPreferenceSnapshot() {
    UserSettings s = UserSettings.initial();
    engine.start(config, keys, s.snapshot());
    s.find("algorithm").prompt = "修改后的偏好";
    s.selectedId = "finance";
    f.speech.listener.ready();
    finish("题目");
    assertTrue(f.text.messages.toString().contains("语言偏好是 C++"));
    assertFalse(f.text.messages.toString().contains("修改后的偏好"));
    assertEquals("算法讨论", engine.snapshot().preference);
  }

  void finish(String text) {
    f.speech.listener.segment(new SpeechProvider.Segment(1, text, true, 0, 1));
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
  }

  @Test
  public void failedAnalysisCanRetryWithoutNewAudioOrSelfHistory() {
    start();
    f.speech.listener.ready();
    finish("前一个问题");
    f.text.listener.failed("模拟失败");
    assertTrue(engine.snapshot().retryable);
    engine.retry(config, keys);
    assertEquals(2, f.text.calls);
    assertEquals(1, f.audio.starts);
    String data =
        f.text
            .messages
            .get(f.text.messages.size() - 1)
            .getAsJsonObject()
            .get("content")
            .getAsString();
    assertTrue(data.contains("previous_meeting_transcripts\":[]"));
  }

  @Test
  public void retryRejectsClearedKeysAndUsesUpdatedModel() {
    start();
    f.speech.listener.ready();
    finish("测试问题");
    f.text.listener.failed("模拟失败");
    engine.retry(config, new ProviderConfig.Credentials("", ""));
    assertEquals(1, f.text.calls);
    assertEquals(SessionEngine.State.ERROR, engine.snapshot().state);
    config.textModel = "replacement-model";
    engine.retry(config, new ProviderConfig.Credentials("", "new-text"));
    assertEquals(2, f.text.calls);
    assertEquals("replacement-model", f.textModelUsed);
    assertEquals("new-text", f.textKeyUsed);
  }

  @Test
  public void nextRoundRemembersTranscriptEvenIfAnalysisWasCanceled() {
    start();
    f.speech.listener.ready();
    finish("前一个问题");
    engine.cancel();
    start();
    f.speech.listener.ready();
    finish("刚才的问题是什么");
    String content =
        f.text
            .messages
            .get(f.text.messages.size() - 1)
            .getAsJsonObject()
            .get("content")
            .getAsString();
    assertTrue(content.contains("previous_meeting_transcripts\":[\"前一个问题\"]"));
  }

  @Test
  public void backgroundCancelsAudioButDoesNotInterruptFinishedAnalysis() {
    start();
    f.speech.listener.ready();
    engine.background();
    assertTrue(f.audio.canceled);
    assertEquals(SessionEngine.State.ERROR, engine.snapshot().state);
    start();
    f.speech.listener.ready();
    finish("题目");
    engine.background();
    assertEquals(SessionEngine.State.ANALYZING, engine.snapshot().state);
  }

  @Test
  public void noOverlappingRoundsAndNewMeetingInvalidatesRequests() {
    start();
    assertFalse(engine.start(config, keys, UserSettings.initial().snapshot()));
    f.speech.listener.ready();
    finish("内容");
    TextProvider.Listener old = f.text.listener;
    engine.newMeeting();
    old.delta("迟到的答案");
    old.completed(false);
    assertEquals("", engine.snapshot().answer);
    assertEquals(SessionEngine.State.IDLE, engine.snapshot().state);
  }

  static class FakeFactory implements SessionEngine.Factory {
    String textModelUsed, textKeyUsed;
    FakeSpeech speech = new FakeSpeech();
    FakeAudio audio = new FakeAudio();
    FakeText text = new FakeText();

    public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
      speech = new FakeSpeech();
      return speech;
    }

    public AudioSource audio() {
      return audio;
    }

    public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
      textModelUsed = c.textModel;
      textKeyUsed = k.textKey;
      return text;
    }
  }

  static class FakeSpeech implements SpeechProvider {
    Listener listener;
    int finishes, frames;
    boolean canceled;

    public void start(Listener l) {
      listener = l;
    }

    public void sendAudio(byte[] b) {
      frames++;
    }

    public void finish() {
      finishes++;
    }

    public void cancel() {
      canceled = true;
    }
  }

  static class FakeAudio implements AudioSource {
    Listener listener;
    Runnable drained;
    boolean started, canceled;
    int starts;

    public void start(Listener l) {
      listener = l;
      started = true;
      canceled = false;
      starts++;
    }

    public void stop(Runnable r) {
      drained = r;
    }

    public void cancel() {
      canceled = true;
    }
  }

  static class FakeText implements TextProvider {
    Listener listener;
    JsonArray messages;
    int calls;

    public void analyze(JsonArray m, Listener l) {
      messages = m;
      listener = l;
      calls++;
    }

    public void cancel() {}
  }
}
