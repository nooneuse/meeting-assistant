package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MeetingManagerTest {
  MeetingManager.MemoryStore store;
  MeetingManager manager;
  Map<SessionEngine, SessionEngineTest.FakeFactory> factories = new IdentityHashMap<>();
  ProviderConfig config = new ProviderConfig();
  ProviderConfig.Credentials keys = new ProviderConfig.Credentials("test-asr", "test-text");

  SessionEngine newEngine() {
    SessionEngineTest.FakeFactory f = new SessionEngineTest.FakeFactory();
    SessionEngine e = new SessionEngine(f, new ManualClockwork());
    factories.put(e, f);
    return e;
  }

  @Before
  public void setup() {
    store = new MeetingManager.MemoryStore();
    manager = new MeetingManager(store, this::newEngine, UserSettings.initial().snapshot(), false);
    config.asrUrl = "wss://example.org/asr";
    config.textBaseUrl = "https://example.org/v1";
  }

  @After
  public void teardown() {
    manager.close();
  }

  SessionEngine e() {
    return manager.currentEngine();
  }

  SessionEngineTest.FakeFactory f() {
    return factories.get(e());
  }

  void start() {
    assertTrue(e().start(config, keys, UserSettings.initial().snapshot()));
    f().speech.listener.ready();
  }

  void stop(String text) {
    f().speech.listener.segment(new SpeechProvider.Segment(1, text, true, 0, 1000));
    e().stop();
    f().audio.drained.run();
    f().speech.listener.completed();
  }

  void complete(String text) {
    f().text.listener.delta(text);
    f().text.listener.completed(false);
  }

  String another() {
    String id = manager.create(UserSettings.initial().snapshot(), false);
    manager.switchTo(id);
    return id;
  }

  @Test
  public void meetingsKeepIndependentHistoryAndContinueAppendsRounds() {
    String a = manager.currentId();
    start();
    stop("甲会议的链表问题");
    complete("甲答案");
    String b = another();
    start();
    stop("乙会议的负债问题");
    complete("乙答案");
    manager.switchTo(a);
    assertEquals("甲答案", manager.answer());
    start();
    assertEquals("甲答案", manager.answer());
    assertEquals(1, manager.current().rounds.size());
    stop("刚才的问题是什么");
    String messages = f().text.messages.toString();
    assertTrue(messages.contains("甲会议的链表问题"));
    assertFalse(messages.contains("乙会议的负债问题"));
    complete("甲跟进");
    assertTrue(manager.transcript(true).contains("甲会议的链表问题"));
    assertTrue(manager.transcript(true).contains("刚才的问题是什么"));
    assertEquals(2, manager.answers().size());
    manager.switchTo(b);
    assertEquals("乙答案", manager.answer());
    assertFalse(manager.transcript(true).contains("甲会议"));
  }

  @Test
  public void switchingWhileAnalysisRunsRoutesLateOutputToOriginalMeeting() {
    String a = manager.currentId();
    start();
    stop("原会议问题");
    TextProvider.Listener old = f().text.listener;
    String b = another();
    old.delta("原会议后台答案");
    old.completed(false);
    assertEquals(b, manager.currentId());
    assertEquals("", manager.answer());
    assertEquals("原会议后台答案", manager.get(a).current.answer);
    manager.switchTo(a);
    assertEquals("原会议后台答案", manager.answer());
  }

  @Test
  public void listeningSwitchWaitsForTailButNotForTextAnswer() {
    String a = manager.currentId();
    start();
    SessionEngineTest.FakeFactory source = f();
    SessionEngine engine = e();
    source.speech.listener.segment(new SpeechProvider.Segment(1, "题目", false, 0, -1));
    String b = manager.create(UserSettings.initial().snapshot(), false);
    assertFalse(manager.switchTo(b));
    assertTrue(manager.navigating());
    assertEquals(a, manager.currentId());
    assertEquals(SessionEngine.State.FINALIZING, engine.snapshot().state);
    source.audio.drained.run();
    source.speech.listener.segment(new SpeechProvider.Segment(1, "题目尾句", true, 0, 1000));
    source.speech.listener.completed();
    assertEquals(b, manager.currentId());
    assertFalse(manager.navigating());
    assertEquals(SessionEngine.State.ANALYZING, engine.snapshot().state);
    source.text.listener.delta("尾句答案");
    source.text.listener.completed(false);
    assertEquals("尾句答案", manager.get(a).current.answer);
    assertTrue(manager.get(a).current.finalized);
  }

  @Test
  public void navigationFailureAndCancellationDoNotLoseSource() {
    String a = manager.currentId();
    start();
    SessionEngine source = e();
    SessionEngineTest.FakeFactory original = f();
    String b = manager.create(UserSettings.initial().snapshot(), false);
    manager.switchTo(b);
    manager.cancelNavigation();
    original.speech.listener.failed("模拟断流");
    assertEquals(a, manager.currentId());
    assertEquals(SessionEngine.State.ERROR, source.snapshot().state);
    manager.switchTo(b);
    assertNotNull(manager.get(a));
  }

  @Test
  public void failedTailStillSwitchesAndMarksIncompleteTranscript() {
    String a = manager.currentId();
    start();
    SessionEngineTest.FakeFactory source = f();
    source.speech.listener.segment(new SpeechProvider.Segment(1, "不完整段", false, 0, -1));
    String b = manager.create(UserSettings.initial().snapshot(), false);
    manager.switchTo(b);
    source.speech.listener.failed("断流");
    assertEquals(b, manager.currentId());
    assertFalse(manager.get(a).current.finalized);
    manager.switchTo(a);
    assertTrue(manager.transcript(true).contains("[未定稿]"));
  }

  @Test
  public void restartRestoresDataButNeverRestartsActiveResources() {
    String a = manager.currentId();
    start();
    f().speech.listener.segment(new SpeechProvider.Segment(1, "重启前的定稿", true, 0, 1000));
    String b = manager.create(UserSettings.initial().snapshot(), true);
    manager.awaitWrites();
    manager.close();
    factories.clear();
    manager = new MeetingManager(store, this::newEngine, UserSettings.initial().snapshot(), false);
    assertEquals(a, manager.currentId());
    assertEquals(SessionEngine.State.ERROR, e().snapshot().state);
    assertFalse(f().audio.started);
    assertEquals(0, f().text.calls);
    assertTrue(manager.transcript(true).contains("重启前的定稿"));
    manager.switchTo(b);
    assertTrue(e().snapshot().automatic);
    assertFalse(f().audio.started);
  }

  @Test
  public void restartBackgroundAnalysisIsMarkedInterruptedAndManualRetryHasNoSelfHistory() {
    String a = manager.currentId();
    start();
    stop("必须保留的当前轮");
    another();
    manager.awaitWrites();
    manager.close();
    manager = new MeetingManager(store, this::newEngine, UserSettings.initial().snapshot(), false);
    assertEquals(SessionEngine.State.ERROR, manager.get(a).current.state);
    manager.switchTo(a);
    assertTrue(e().snapshot().retryable);
    e().retry(config, keys);
    String data =
        f().text
            .messages
            .get(f().text.messages.size() - 1)
            .getAsJsonObject()
            .get("content")
            .getAsString();
    assertTrue(data.contains("previous_meeting_transcripts\":[]"));
    complete("重试成功");
  }

  @Test
  public void viewSelectionAndCustomTitleSurvivePersistence() {
    String a = manager.currentId();
    MeetingRecord.ViewState v = new MeetingRecord.ViewState();
    v.answerScroll = 300;
    v.transcriptScroll = 120;
    v.transcriptExpanded = true;
    v.transcriptVisibilityChosen = true;
    v.followAnswer = false;
    manager.saveView(v);
    manager.rename(a, "人工命名");
    start();
    f().speech.listener.segment(new SpeechProvider.Segment(1, "不应覆盖命名", true, 0, 1000));
    e().cancel();
    another();
    manager.switchTo(a);
    manager.awaitWrites();
    manager.close();
    manager = new MeetingManager(store, this::newEngine, UserSettings.initial().snapshot(), false);
    assertEquals(a, manager.currentId());
    assertEquals("人工命名", manager.current().title);
    assertEquals(300, manager.current().view.answerScroll);
    assertFalse(manager.current().view.followAnswer);
  }

  @Test
  public void autoTitleUsesFirstStableTextWithoutNetwork() {
    start();
    f().speech.listener.segment(new SpeechProvider.Segment(1, "临时标题", false, 0, -1));
    assertTrue(manager.current().title.startsWith("新议题 "));
    f().speech.listener.segment(new SpeechProvider.Segment(1, "合并链表。其余内容", true, 0, 1000));
    assertEquals("合并链表", manager.current().title);
    assertEquals(0, f().text.calls);
  }

  @Test
  public void localizedTranscriptLabelsNeverReplaceWordsInsideMeetingContent() {
    start();
    stop("Keep the quoted token [未定稿] exactly.");
    complete("Answer");
    start();
    f().speech.listener.segment(new SpeechProvider.Segment(2, "Current draft", false, 0, -1));
    String text =
        manager.transcript(true, "[Draft] ", "[Incomplete]\n", "Round %1$d", " · Incomplete");
    assertTrue(text.contains("Round 1"));
    assertTrue(text.contains("Keep the quoted token [未定稿] exactly."));
    assertTrue(text.contains("Round 2 · Incomplete\n[Draft] Current draft"));
    assertTrue(manager.transcript(true).contains("第 1 轮"));
  }

  @Test
  public void deleteCancelsAndRejectsLateCallbacksWithoutResurrection() throws Exception {
    String a = manager.currentId();
    start();
    stop("待删除的问题");
    TextProvider.Listener old = f().text.listener;
    another();
    CountDownLatch done = new CountDownLatch(1);
    manager.delete(
        a,
        success -> {
          assertTrue(success);
          done.countDown();
        });
    assertTrue(done.await(3, TimeUnit.SECONDS));
    old.delta("迟到输出");
    old.completed(false);
    manager.awaitWrites();
    assertNull(manager.get(a));
    assertTrue(store.load().stream().noneMatch(r -> r.id.equals(a)));
  }

  @Test
  public void deletingCurrentSelectsExistingInsteadOfCreatingAnotherRecord() throws Exception {
    String a = manager.currentId();
    start();
    stop("保留的会议原文");
    complete("保留的答案");
    String b = another();
    CountDownLatch done = new CountDownLatch(1);
    manager.delete(b, okay -> done.countDown());
    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertEquals(a, manager.currentId());
    assertEquals(1, manager.list().size());
    assertTrue(manager.transcript(true).contains("保留的会议原文"));
    assertEquals("保留的答案", manager.answer());
    assertNotNull(manager.get(a));
    assertNull(manager.get(b));
  }

  @Test
  public void deletingLastNewMeetingLeavesHiddenDraftEvenAfterRestart() throws Exception {
    String id = another();
    assertEquals(1, manager.list().size());
    CountDownLatch done = new CountDownLatch(1);
    manager.delete(id, okay -> done.countDown());
    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertNull(manager.get(id));
    assertTrue(manager.list().isEmpty());
    assertTrue(manager.summaries().isEmpty());
    assertTrue(manager.current().draft);
    assertEquals("", manager.transcript(true));
    manager.awaitWrites();
    assertTrue(store.load().stream().noneMatch(r -> r.id.equals(id)));
    manager.close();
    manager = new MeetingManager(store, this::newEngine, UserSettings.initial().snapshot(), false);
    assertTrue(manager.list().isEmpty());
    assertTrue(manager.current().draft);
    assertFalse(f().audio.started);
  }

  @Test
  public void preferenceAndViewChangesDoNotSaveDraftInHistoryAndSwitchDiscardsIt() {
    String draft = manager.currentId();
    assertTrue(manager.list().isEmpty());
    e().setAutomatic(true);
    e().selectPreference(UserSettings.initial().snapshot());
    manager.saveView(new MeetingRecord.ViewState());
    assertTrue(manager.summaries().isEmpty());
    String explicit = another();
    manager.awaitWrites();
    assertNull(manager.get(draft));
    assertTrue(store.load().stream().noneMatch(r -> r.id.equals(draft)));
    assertEquals(1, manager.list().size());
    assertEquals(explicit, manager.list().get(0).id);
  }

  @Test
  public void startingDraftMakesOnePersistentHistoryRecord() {
    String id = manager.currentId();
    start();
    assertFalse(manager.current().draft);
    assertEquals(1, manager.list().size());
    assertEquals(id, manager.list().get(0).id);
    e().cancel();
    manager.awaitWrites();
    manager.close();
    manager = new MeetingManager(store, this::newEngine, UserSettings.initial().snapshot(), false);
    assertEquals(id, manager.list().get(0).id);
    assertFalse(manager.current().draft);
  }

  @Test
  public void readFailureIsNotOverwrittenWithAnEmptyDatabase() {
    manager.close();
    java.util.concurrent.atomic.AtomicInteger saves =
        new java.util.concurrent.atomic.AtomicInteger();
    MeetingManager.Store broken =
        new MeetingManager.Store() {
          public java.util.List<MeetingRecord> load() {
            throw new IllegalStateException();
          }

          public String selected() {
            return "";
          }

          public void save(MeetingRecord r) {
            saves.incrementAndGet();
          }

          public void select(String id) {}

          public void delete(String id) {}
        };
    manager = new MeetingManager(broken, this::newEngine, UserSettings.initial().snapshot(), false);
    assertFalse(manager.available());
    manager.awaitWrites();
    assertEquals(0, saves.get());
    assertThrows(
        IllegalStateException.class,
        () -> manager.create(UserSettings.initial().snapshot(), false));
  }

  @Test
  public void diskFailureDoesNotBlockAudioOrHideTheError() {
    manager.close();
    MeetingManager.MemoryStore failing =
        new MeetingManager.MemoryStore() {
          @Override
          public synchronized void save(MeetingRecord r) {
            throw new IllegalStateException();
          }
        };
    manager =
        new MeetingManager(failing, this::newEngine, UserSettings.initial().snapshot(), false);
    start();
    f().audio.listener.audio(new byte[] {1, 2});
    manager.awaitWrites();
    assertEquals(1, f().speech.frames);
    assertTrue(manager.error().contains("保存失败"));
  }

  @Test
  public void savedStateExplicitlyExcludesCredentialsAndProviderEndpoints() {
    start();
    stop("普通会议");
    String saved = new Gson().toJson(manager.current());
    assertFalse(saved.contains("test-asr"));
    assertFalse(saved.contains("test-text"));
    assertFalse(saved.contains("example.org"));
    assertFalse(saved.contains("credentials"));
  }

  @Test
  public void deleteFailureKeepsCanceledRecordAndCanBeRetried() throws Exception {
    manager.close();
    class Flaky extends MeetingManager.MemoryStore {
      boolean fail = true;

      @Override
      public synchronized void delete(String id) {
        if (fail) throw new IllegalStateException();
        super.delete(id);
      }
    }
    Flaky flaky = new Flaky();
    manager = new MeetingManager(flaky, this::newEngine, UserSettings.initial().snapshot(), false);
    String id = manager.currentId();
    start();
    stop("不要丢弃的会议");
    another();
    CountDownLatch first = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean success =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    manager.delete(
        id,
        okay -> {
          success.set(okay);
          first.countDown();
        });
    assertTrue(first.await(3, TimeUnit.SECONDS));
    assertFalse(success.get());
    assertNotNull(manager.get(id));
    assertTrue(manager.error().contains("删除失败"));
    flaky.fail = false;
    CountDownLatch second = new CountDownLatch(1);
    manager.delete(
        id,
        okay -> {
          success.set(okay);
          second.countDown();
        });
    assertTrue(second.await(3, TimeUnit.SECONDS));
    assertTrue(success.get());
    manager.awaitWrites();
    assertNull(manager.get(id));
  }

  @Test
  public void deletingCurrentRollsBackIfReplacementCannotBeSaved() throws Exception {
    manager.close();
    class Flaky extends MeetingManager.MemoryStore {
      boolean fail;

      @Override
      public synchronized void save(MeetingRecord r) {
        if (fail) throw new IllegalStateException();
        super.save(r);
      }
    }
    Flaky flaky = new Flaky();
    manager = new MeetingManager(flaky, this::newEngine, UserSettings.initial().snapshot(), false);
    String id = manager.currentId();
    start();
    stop("现有记录");
    complete("应保留的答案");
    manager.awaitWrites();
    flaky.fail = true;
    CountDownLatch done = new CountDownLatch(1);
    manager.delete(id, okay -> done.countDown());
    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertNotNull(manager.get(id));
    assertEquals(id, manager.currentId());
    assertTrue(flaky.load().stream().anyMatch(r -> r.id.equals(id)));
    flaky.fail = false;
  }

  @Test
  public void eachMeetingRestoresItsOwnPreferenceModeAndRoundPromptSnapshot() {
    String a = manager.currentId();
    manager.rename(a, "偏好配置测试会议");
    UserSettings settings = UserSettings.initial();
    settings.selectedId = "finance";
    e().selectPreference(settings.snapshot());
    e().setAutomatic(true);
    String b = another();
    assertFalse(e().snapshot().automatic);
    manager.switchTo(a);
    assertTrue(e().snapshot().automatic);
    assertEquals("finance", manager.current().current.selected.id);
    e().setAutomatic(false);
    assertTrue(e().start(config, keys, settings.snapshot()));
    f().speech.listener.ready();
    stop("财务问题");
    complete("分析结果");
    start();
    assertTrue(manager.current().rounds.get(0).preferenceSnapshot.prompt.contains("负债情况"));
    e().cancel();
    manager.switchTo(b);
    assertEquals("algorithm", manager.current().current.selected.id);
  }

  @Test
  public void durableSnapshotKeepsPendingRevisionWithoutChangingVisibleAnswer() {
    // A cold snapshot may restore the newer complete answer; live readers retain the old version.
    SessionEngine.SavedState s = new SessionEngine.SavedState();
    s.answer = "旧答案";
    s.pendingAnswer = "已完成的新答案";
    s.state = SessionEngine.State.DONE;
    SessionEngine restored = newEngine();
    restored.restore(s);
    assertEquals("已完成的新答案", restored.snapshot().answer);
    assertEquals(0, factories.get(restored).text.calls);
    restored.close();
  }

  @Test
  public void ongoingTokenStreamIsSavedPeriodicallyBeforeItFinishes() throws Exception {
    manager.close();
    CountDownLatch draft = new CountDownLatch(1);
    MeetingManager.MemoryStore watched =
        new MeetingManager.MemoryStore() {
          @Override
          public synchronized void save(MeetingRecord r) {
            super.save(r);
            if (r.current.state == SessionEngine.State.ANALYZING && !r.current.answer.isEmpty())
              draft.countDown();
          }
        };
    manager =
        new MeetingManager(watched, this::newEngine, UserSettings.initial().snapshot(), false);
    start();
    stop("持续生成问题");
    TextProvider.Listener listener = f().text.listener;
    java.util.concurrent.ScheduledExecutorService producer =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    try {
      producer.scheduleAtFixedRate(() -> listener.delta("持续内容"), 0, 40, TimeUnit.MILLISECONDS);
      assertTrue(
          "draft never saved during continuous deltas", draft.await(2500, TimeUnit.MILLISECONDS));
      assertEquals(SessionEngine.State.ANALYZING, e().snapshot().state);
    } finally {
      producer.shutdownNow();
    }
  }
}
