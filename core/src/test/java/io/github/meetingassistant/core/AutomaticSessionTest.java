package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AutomaticSessionTest {
  SessionEngineTest.FakeFactory f;
  SessionEngine engine;
  ManualClockwork clock;
  ProviderConfig config;
  ProviderConfig.Credentials keys = new ProviderConfig.Credentials("test", "test");

  @Before
  public void setup() {
    f = new SessionEngineTest.FakeFactory();
    clock = new ManualClockwork();
    engine = new SessionEngine(f, clock);
    config = new ProviderConfig();
    config.asrUrl = "wss://example.org/asr";
    config.textBaseUrl = "https://example.org/v1";
    engine.setAutomatic(true);
    engine.start(config, keys, UserSettings.initial().snapshot());
    f.speech.listener.ready();
  }

  @After
  public void cleanup() {
    engine.close();
  }

  void segment(int id, String text, boolean stable) {
    f.speech.listener.segment(
        new SpeechProvider.Segment(id, text, stable, id * 1000L, (id + 1) * 1000L));
  }

  void stable(int id, String text) {
    segment(id, text, true);
  }

  void answer(String text) {
    f.text.listener.delta(text);
    f.text.listener.completed(false);
  }

  void stop() {
    engine.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
  }

  @Test
  public void slowModelNeverInterruptsAudioAndCoalescesLatestMinute() {
    stable(0, "如何合并两个单向链表？");
    clock.advance(0);
    assertEquals(1, f.text.calls);
    for (int i = 1; i <= 60; i++) {
      f.audio.listener.audio(new byte[] {1, 2});
      stable(i, "补充条件" + i);
      clock.advance(1000);
    }
    assertEquals(60, f.speech.frames);
    assertEquals(1, f.audio.starts);
    assertFalse(f.audio.canceled);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertEquals(1, f.text.calls);
    assertTrue(engine.snapshot().transcript.contains("补充条件60"));
    answer("先给已覆盖部分建议");
    clock.advance(1200);
    assertEquals(2, f.text.calls);
    assertTrue(f.text.messages.toString().contains("补充条件60"));
    assertTrue(f.text.messages.toString().contains("补充条件1"));
    answer("完整建议");
    clock.advance(10000);
    assertEquals(2, f.text.calls);
  }

  @Test
  public void fillerAfterAnAnswerDoesNotTriggerAnotherPaidRequest() {
    stable(0, "如何合并？");
    clock.advance(0);
    answer("方案");
    stable(1, "嗯，好的。");
    clock.advance(10000);
    assertEquals(1, f.text.calls);
    stop();
    assertEquals(1, f.text.calls);
  }

  @Test
  public void blockingProviderStartCannotBlockAudioOrStop() throws Exception {
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    SessionEngineTest.FakeFactory blocking =
        new SessionEngineTest.FakeFactory() {
          @Override
          public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
            return new TextProvider() {
              public void analyze(com.google.gson.JsonArray m, Listener listener) {
                entered.countDown();
                try {
                  release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }

              public void cancel() {
                release.countDown();
              }
            };
          }
        };
    try (SessionEngine actual = new SessionEngine(blocking)) {
      actual.setAutomatic(true);
      actual.start(config, keys, UserSettings.initial().snapshot());
      blocking.speech.listener.ready();
      blocking.speech.listener.segment(new SpeechProvider.Segment(0, "如何合并链表？", true, 0, 1000));
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      long before = System.nanoTime();
      for (int i = 0; i < 100; i++) blocking.audio.listener.audio(new byte[] {1, 2});
      actual.stop();
      assertTrue("audio/stop blocked by text request", System.nanoTime() - before < 500_000_000L);
      assertEquals(100, blocking.speech.frames);
      assertNotNull(blocking.audio.drained);
      assertEquals(SessionEngine.State.FINALIZING, actual.snapshot().state);
    } finally {
      release.countDown();
    }
  }

  @Test
  public void partialsDontTriggerAndQuietFinalsDo() {
    segment(0, "如何合并链表", false);
    clock.advance(10000);
    assertEquals(0, f.text.calls);
    stable(0, "合并链表");
    clock.advance(1199);
    assertEquals(0, f.text.calls);
    clock.advance(1);
    assertEquals(1, f.text.calls);
  }

  @Test
  public void continuousSpeechTriggersDespiteRepeatedInterims() {
    stable(0, "讨论合并链表");
    for (int i = 0; i < 16; i++) {
      clock.advance(500);
      segment(1, "正在补充" + i, false);
    }
    clock.advance(0);
    assertEquals(1, f.text.calls);
    assertFalse(f.text.messages.toString().contains("正在补充"));
  }

  @Test
  public void fillerDoesNotCallModel() {
    stable(0, "嗯，好的。");
    clock.advance(10000);
    assertEquals(0, f.text.calls);
    stop();
    assertEquals(0, f.text.calls);
  }

  @Test
  public void stopIsImmediateAndTailGetsExactlyOneFinalUpdate() {
    stable(0, "如何合并？");
    clock.advance(0);
    TextProvider.Listener first = f.text.listener;
    stable(1, "输入已经有序");
    engine.stop();
    assertNotNull(f.audio.drained);
    assertEquals(SessionEngine.State.FINALIZING, engine.snapshot().state);
    stable(2, "要求原地合并");
    f.audio.drained.run();
    f.speech.listener.completed();
    assertEquals(1, f.text.calls);
    first.delta("初步思路");
    first.completed(false);
    assertEquals(2, f.text.calls);
    assertTrue(f.text.messages.toString().contains("要求原地合并"));
    answer("最终方案");
    clock.advance(200000);
    assertEquals(2, f.text.calls);
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
  }

  @Test
  public void stopDoesNotDuplicateCoveredTranscript() {
    stable(0, "如何合并？");
    clock.advance(0);
    answer("答案");
    stop();
    assertEquals(1, f.text.calls);
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
  }

  @Test
  public void textFailureAndTimeoutDoNotStopSpeechOrAutoRetrySameInput() {
    stable(0, "如何合并？");
    clock.advance(0);
    f.text.listener.failed("模拟失败");
    clock.advance(20000);
    assertEquals(1, f.text.calls);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertTrue(engine.snapshot().retryable);
    f.audio.listener.audio(new byte[] {1});
    assertEquals(1, f.speech.frames);
    engine.retry(config, keys);
    assertEquals(2, f.text.calls);
    clock.advance(180000);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertTrue(engine.snapshot().retryable);
    assertEquals(2, f.text.calls);
    stop();
    assertEquals(2, f.text.calls);
    assertEquals(SessionEngine.State.ERROR, engine.snapshot().state);
  }

  @Test
  public void newFactsAfterErrorCanTriggerFreshRequest() {
    stable(0, "如何合并？");
    clock.advance(0);
    f.text.listener.failed("失败");
    stable(1, "条件补充");
    clock.advance(5000);
    assertEquals(2, f.text.calls);
    assertFalse(f.audio.canceled);
  }

  @Test
  public void emptyModelReplyIsAnErrorWithoutStoppingSpeech() {
    stable(0, "如何合并？");
    clock.advance(0);
    f.text.listener.completed(false);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertEquals(SessionEngine.AnalysisState.ERROR, engine.snapshot().analysisState);
    assertTrue(engine.snapshot().retryable);
    clock.advance(10000);
    assertEquals(1, f.text.calls);
  }

  @Test
  public void changingModeKeepsSingleAudioAndOffPreventsNewRequests() {
    stable(0, "如何合并？");
    clock.advance(0);
    engine.setAutomatic(false);
    stable(1, "补充条件");
    clock.advance(30000);
    answer("旧请求正常完成");
    clock.advance(10000);
    assertEquals(1, f.text.calls);
    assertEquals(1, f.audio.starts);
    engine.setAutomatic(true);
    clock.advance(0);
    assertEquals(2, f.text.calls);
    engine.setAutomatic(false);
    answer("新建议");
    stop();
    assertEquals(SessionEngine.State.DONE, engine.snapshot().state);
    assertEquals(1, f.audio.starts);
  }

  @Test
  public void preferenceChangesInvalidateOldResultAndFreezeNextRequest() {
    stable(0, "如何合并？");
    clock.advance(0);
    TextProvider.Listener old = f.text.listener;
    UserSettings settings = UserSettings.initial();
    settings.selectedId = "finance";
    engine.selectPreference(settings.snapshot());
    old.delta("旧偏好的答案");
    old.completed(false);
    assertEquals("", engine.snapshot().answer);
    clock.advance(5000);
    assertEquals(2, f.text.calls);
    settings.find("finance").prompt = "随后修改";
    assertTrue(f.text.messages.toString().contains("负债情况"));
    assertFalse(f.text.messages.toString().contains("随后修改"));
    answer("新偏好答案");
    old.delta("迟到");
    assertEquals("新偏好答案", engine.snapshot().answer);
  }

  @Test
  public void correctionsInvalidateInFlightAndFinalRevisionRecomputes() {
    stable(0, "如何合并无序链表？");
    clock.advance(0);
    stable(1, "不对，输入已经有序");
    answer("依据过期假设的答案");
    assertEquals("", engine.snapshot().answer);
    clock.advance(5000);
    assertEquals(2, f.text.calls);
    answer("有序方案");
    stable(0, "如何原地合并有序链表？");
    clock.advance(5000);
    assertEquals(3, f.text.calls);
  }

  @Test
  public void revisionsAreBufferedAndReaderCanApplyLatest() {
    stable(0, "如何合并？");
    clock.advance(0);
    answer("第一版");
    engine.deferRevisions(true);
    stable(1, "输入有序");
    clock.advance(5000);
    f.text.listener.delta("第二版");
    assertEquals("第一版", engine.snapshot().answer);
    f.text.listener.completed(false);
    assertTrue(engine.snapshot().hasUpdate);
    assertEquals("第一版", engine.snapshot().answer);
    engine.deferRevisions(false);
    assertEquals("第二版", engine.snapshot().answer);
    assertFalse(engine.snapshot().hasUpdate);
  }

  @Test
  public void rawTranscriptSurvivesModelWindowAndHistoryNeverUsesAnswer() {
    stable(0, "如何解决当前问题？");
    clock.advance(0);
    answer("AI机密虚构词");
    stable(1, "中间约束".repeat(7000));
    stable(2, "最新条件");
    clock.advance(5000);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertTrue(engine.snapshot().transcript.length() > 24000);
    assertTrue(f.text.messages.toString().contains("上下文预算省略"));
    assertTrue(f.text.messages.toString().contains("最新条件"));
    assertFalse(f.text.messages.toString().contains("AI机密虚构词"));
    answer("结果");
    stop();
    engine.start(config, keys, UserSettings.initial().snapshot());
    f.speech.listener.ready();
    stable(0, "刚才讨论的问题？");
    clock.advance(5000);
    assertTrue(f.text.messages.toString().contains("如何解决当前问题"));
    assertFalse(f.text.messages.toString().contains("AI机密虚构词"));
  }

  @Test
  public void asrFailureCancelsAnalysisAndRejectsLateCallbacks() {
    stable(0, "如何合并？");
    clock.advance(0);
    TextProvider.Listener old = f.text.listener;
    f.speech.listener.failed("识别断流");
    assertTrue(f.audio.canceled);
    old.delta("迟到");
    old.completed(false);
    assertEquals(SessionEngine.State.ERROR, engine.snapshot().state);
    assertNotEquals(SessionEngine.AnalysisState.GENERATING, engine.snapshot().analysisState);
    assertFalse(engine.snapshot().analysisStatus.contains("更新中"));
    assertEquals("", engine.snapshot().answer);
  }

  @Test
  public void newMeetingRejectsOldScheduledAndNetworkResults() {
    stable(0, "如何合并？");
    clock.advance(0);
    TextProvider.Listener old = f.text.listener;
    engine.newMeeting();
    old.delta("旧答案");
    old.completed(false);
    clock.advance(200000);
    assertEquals(SessionEngine.State.IDLE, engine.snapshot().state);
    assertEquals("", engine.snapshot().answer);
    assertEquals(1, f.text.calls);
  }

  @Test
  public void savedAnswerPreferenceFollowsActualAnswerRatherThanNextSelection() {
    stable(0, "如何合并？");
    clock.advance(0);
    answer("算法方向答案");
    UserSettings settings = UserSettings.initial();
    settings.selectedId = "finance";
    engine.selectPreference(settings.snapshot());
    MeetingRecord.Round old = new MeetingRecord.Round("round", engine.saveState());
    assertEquals("algorithm", old.preferenceSnapshot.id);
    clock.advance(5000);
    answer("财务方向答案");
    assertEquals(
        "finance", new MeetingRecord.Round("round", engine.saveState()).preferenceSnapshot.id);
  }
}
