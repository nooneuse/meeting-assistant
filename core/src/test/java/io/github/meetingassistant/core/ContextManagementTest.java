package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;

/** No real provider: long meetings and slow requests use fixtures and virtual time. */
public class ContextManagementTest {
  ContextSettings options;
  ProviderConfig config;
  ProviderConfig.Credentials keys = new ProviderConfig.Credentials("mock", "mock");
  SessionEngineTest.FakeFactory factory;
  SessionEngineTest.FakeText summary;
  SessionEngine engine;
  ManualClockwork clock;

  @Before
  public void setup() {
    options = new ContextSettings();
    options.inputBudget = 6500;
    config = new ProviderConfig();
    config.asrUrl = "wss://example.org/asr";
    config.textBaseUrl = "https://example.org/v1";
    summary = new SessionEngineTest.FakeText();
    factory =
        new SessionEngineTest.FakeFactory() {
          @Override
          public TextProvider summarize(ProviderConfig c, ProviderConfig.Credentials k) {
            return summary;
          }
        };
    clock = new ManualClockwork();
    engine = new SessionEngine(factory, clock);
    engine.configureContext(options);
  }

  @After
  public void cleanup() {
    engine.close();
  }

  List<ContextMemory.Source> sources(int n) {
    List<ContextMemory.Source> out = new ArrayList<>();
    for (int i = 0; i < n; i++)
      out.add(
          new ContextMemory.Source(
              "round:" + i, "约束" + i + "：保留原始负债数字120亿元，不能改成12亿元。".repeat(2), i >= n - 8));
    return out;
  }

  ContextMemory.Plan plan(List<ContextMemory.Source> all, ContextMemory.State state) {
    return ContextMemory.prepare(
        all,
        state,
        new UserSettings.Profile("p", "p", "C++"),
        new JsonArray(),
        true,
        false,
        options,
        options.budget());
  }

  List<MeetingRecord.Round> rounds() {
    MeetingRecord.Round round = new MeetingRecord.Round();
    round.id = "archive";
    round.contextRoundId = "archive";
    round.complete = true;
    round.segments = new ArrayList<>();
    for (int i = 0; i < 100; i++)
      round.segments.add(
          new SpeechProvider.Segment(
              i, "条件" + i + "：" + "保留节点，不分配新节点。".repeat(5), true, i * 1000, (i + 1) * 1000));
    return List.of(round);
  }

  void start() {
    engine.start(config, keys, new UserSettings.Profile("p", "p", "C++"));
    factory.speech.listener.ready();
  }

  void finish(String text) {
    factory.speech.listener.segment(new SpeechProvider.Segment(1, text, true, 0, 1000));
    engine.stop();
    factory.audio.drained.run();
    factory.speech.listener.completed();
  }

  void answer() {
    factory.text.listener.delta("mock answer");
    factory.text.listener.completed(false);
  }

  String summaryAnswer() {
    JsonObject input =
        JsonParser.parseString(
                summary.messages.get(1).getAsJsonObject().get("content").getAsString())
            .getAsJsonObject();
    JsonObject source = input.getAsJsonArray("sources").get(0).getAsJsonObject();
    JsonObject item = new JsonObject();
    item.add("source", source.get("source"));
    item.add("quote", source.get("text"));
    item.addProperty("kind", "constraint");
    JsonArray items = new JsonArray();
    items.add(item);
    JsonObject out = new JsonObject();
    out.add("items", items);
    return out.toString();
  }

  @Test
  public void requestAndSummaryBothBoundedAndArchivesUntouched() {
    var all = sources(400);
    String original = new Gson().toJson(all);
    var p = plan(all, new ContextMemory.State());
    assertTrue(p.before() > options.inputBudget);
    assertTrue(p.input() <= options.inputBudget);
    assertTrue(p.limited());
    assertNotNull(p.job());
    assertTrue(ContextMemory.estimate(p.job().messages()) <= options.inputBudget);
    assertTrue(p.messages().toString().contains("round:399"));
    assertEquals(original, new Gson().toJson(all));
  }

  @Test
  public void preferencesAndJsonOverheadAreInsideBudget() {
    String preference = "偏好".repeat(4000);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContextMemory.prepare(
                sources(2),
                new ContextMemory.State(),
                new UserSettings.Profile("p", "p", preference),
                new JsonArray(),
                false,
                false,
                options,
                options.budget()));
  }

  @Test
  public void modelWindowReservesOutputAndSafety() {
    options.modelWindow = 10000;
    options.outputBudget = 4096;
    assertEquals(4880, options.budget());
    options.modelWindow = 6000;
    assertThrows(IllegalArgumentException.class, options::validate);
  }

  @Test
  public void latestOversizedCodeIsNotSilentlyCut() {
    var all = List.of(new ContextMemory.Source("code", "int x;".repeat(10000), true));
    assertThrows(IllegalArgumentException.class, () -> plan(all, new ContextMemory.State()));
    assertEquals(60000, all.get(0).text().length());
  }

  @Test
  public void retrievesOldChineseFactOutsideRecentWindow() {
    var all = sources(500);
    for (int i = 0; i < all.size(); i++)
      all.set(i, new ContextMemory.Source("round:" + i, "算法条件" + i, false));
    all.set(0, new ContextMemory.Source("important", "负债为120亿元，已确认。", false));
    all.add(new ContextMemory.Source("query", "之前的负债是多少？", true));
    assertTrue(plan(all, new ContextMemory.State()).messages().toString().contains("负债为120亿元"));
  }

  @Test
  public void retrievesPreviousQuestionAndEnglishKeywords() {
    var all = sources(500);
    all.set(1, new ContextMemory.Source("issue", "How do we handle matrix overflow?", false));
    all.add(
        new ContextMemory.Source(
            "query", "What about that earlier matrix overflow question?", true));
    assertTrue(
        plan(all, new ContextMemory.State())
            .messages()
            .toString()
            .contains("How do we handle matrix overflow"));
  }

  @Test
  public void acceptsOnlyGroundedQuotesAndInvalidatesCorrections() {
    var all = sources(100);
    var p = plan(all, new ContextMemory.State());
    var job = p.job();
    assertNotNull(job);
    JsonObject item = new JsonObject();
    item.addProperty("source", job.sources().get(0).id());
    item.addProperty("quote", job.sources().get(0).text());
    item.addProperty("kind", "constraint");
    JsonArray items = new JsonArray();
    items.add(item);
    JsonObject json = new JsonObject();
    json.add("items", items);
    var memory = ContextMemory.finish(job, json.toString());
    assertFalse(plan(all, memory).memory().items.isEmpty());
    all.set(0, new ContextMemory.Source(all.get(0).id(), "纠正：负债为0", false));
    assertTrue(plan(all, memory).memory().items.isEmpty());
    item.addProperty("quote", "捏造负债999999亿元");
    assertThrows(IllegalArgumentException.class, () -> ContextMemory.finish(job, json.toString()));
  }

  @Test
  public void summaryRemainsBoundedAcrossManyBatches() {
    var memory = new ContextMemory.State();
    var all = sources(1000);
    for (int i = 0; i < 30; i++) {
      var p = plan(all, memory);
      var job = p.job();
      if (job == null) break;
      JsonObject item = new JsonObject();
      item.addProperty("source", job.sources().get(job.sources().size() - 1).id());
      item.addProperty("quote", job.sources().get(job.sources().size() - 1).text());
      item.addProperty("kind", "fact");
      JsonObject out = new JsonObject();
      JsonArray a = new JsonArray();
      a.add(item);
      out.add("items", a);
      memory = ContextMemory.finish(job, out.toString());
      assertTrue(ContextMemory.estimate(new Gson().toJson(memory.items)) <= 1500);
      assertTrue(p.input() <= options.budget());
    }
  }

  JsonArray images() {
    JsonArray out = new JsonArray();
    for (int i = 0; i < 3; i++) {
      JsonObject image = new JsonObject();
      image.addProperty("image_id", "image" + i);
      JsonArray blocks = new JsonArray();
      for (int j = 0; j < 10; j++) {
        JsonObject b = new JsonObject();
        b.addProperty("id", "B" + j);
        b.addProperty("text", "代码行" + j + "x".repeat(180));
        b.addProperty("role_inference", "code");
        b.addProperty("order", j);
        b.add("box", JsonParser.parseString("[0,0,1,1]"));
        blocks.add(b);
      }
      JsonObject layout = new JsonObject();
      layout.add("blocks", blocks);
      layout.add("uncertainties", new JsonArray());
      image.add("layout", layout);
      out.add(image);
    }
    return out;
  }

  @Test
  public void imageRegionsKeepCoordinatesAndWholeBlocksUnderSharedLimit() {
    JsonArray images = images();
    String original = images.toString();
    var p =
        ContextMemory.prepare(
            sources(20),
            new ContextMemory.State(),
            new UserSettings.Profile("", "", ""),
            images,
            true,
            true,
            options,
            options.budget());
    assertTrue(p.input() <= options.budget());
    assertTrue(p.limited());
    assertTrue(p.messages().toString().contains("box"));
    assertTrue(p.imageIds().contains("image2"));
    assertEquals(original, images.toString());
  }

  @Test
  public void organizingDoesNotConsumeAnswerProviderAndSurvivesRestore() {
    engine.loadContextArchive(rounds());
    start();
    finish("如何合并？");
    assertEquals(1, factory.text.calls);
    assertEquals(1, summary.calls);
    summary.listener.delta(summaryAnswer());
    summary.listener.completed(false);
    assertTrue(engine.snapshot().context.summarized);
    answer();
    var saved = engine.saveState();
    assertEquals(1, factory.text.calls);
    try (SessionEngine fresh = new SessionEngine(factory, new ManualClockwork())) {
      fresh.restore(saved);
      fresh.loadContextArchive(rounds());
      assertTrue(fresh.snapshot().context.summarized);
      assertEquals(1, summary.calls);
    }
  }

  @Test
  public void invalidOrTimedOutSummaryKeepsAnswerAndBoundedContext() {
    engine.loadContextArchive(rounds());
    start();
    finish("如何处理？");
    summary.listener.delta("not json");
    summary.listener.completed(false);
    assertTrue(engine.snapshot().context.organizeFailed);
    assertEquals(SessionEngine.AnalysisState.GENERATING, engine.snapshot().analysisState);
    answer();
    assertEquals("mock answer", engine.snapshot().answer);
    clock.advance(30000);
    start();
    finish("继续这个问题？");
    assertTrue(engine.snapshot().context.organizing);
    clock.advance(60000);
    assertFalse(engine.snapshot().context.organizing);
    assertTrue(engine.snapshot().context.organizeFailed);
    assertTrue(ContextMemory.estimate(factory.text.messages) <= options.budget());
  }

  @Test
  public void canceledOrClosedSummaryCannotCommitLateData() {
    engine.loadContextArchive(rounds());
    start();
    finish("问题？");
    TextProvider.Listener late = summary.listener;
    String valid = summaryAnswer();
    engine.newMeeting();
    late.delta(valid);
    late.completed(false);
    assertFalse(engine.snapshot().context.summarized);
    assertEquals("", engine.snapshot().answer);
  }

  @Test
  public void sourceCorrectionRejectsLateSummaryWithoutStoppingAudio() {
    engine.loadContextArchive(rounds());
    engine.setAutomatic(true);
    start();
    factory.speech.listener.segment(new SpeechProvider.Segment(1, "如何解决？", true, 0, 1000));
    clock.advance(0);
    TextProvider.Listener late = summary.listener;
    String valid = summaryAnswer();
    factory.speech.listener.segment(
        new SpeechProvider.Segment(1, "纠正：不需要新增节点，如何解决？", true, 0, 1000));
    late.delta(valid);
    late.completed(false);
    assertFalse(engine.snapshot().context.summarized);
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
  }

  @Test
  public void oversizedManualTranscriptNeverStopsMicrophone() {
    start();
    factory.speech.listener.segment(
        new SpeechProvider.Segment(0, "完整原文".repeat(10000), true, 0, 1000));
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertFalse(factory.audio.canceled);
    engine.stop();
    factory.audio.drained.run();
    factory.speech.listener.completed();
    assertEquals(0, factory.text.calls);
    assertEquals(40000, engine.saveState().segments.get(0).text.length());
    assertTrue(engine.snapshot().analysisStatus.contains("完整原文已保留"));
  }

  @Test
  public void twoSlowFirstAnswersAlertButLongFastStreamingDoesNot() {
    for (int i = 0; i < 3; i++) {
      start();
      finish("问题" + i);
      clock.advance(1000);
      factory.text.listener.delta("首段");
      clock.advance(45000);
      factory.text.listener.completed(false);
      assertFalse(engine.snapshot().context.slow);
    }
    assertEquals(1000, engine.snapshot().context.firstMs);
    assertEquals(46000, engine.snapshot().context.totalMs);
    for (int i = 0; i < 2; i++) {
      start();
      finish("慢问题" + i);
      clock.advance(11000);
      answer();
    }
    assertTrue(engine.snapshot().context.slow);
    assertTrue(engine.consumeContextNotice());
    assertFalse(engine.consumeContextNotice());
    long notice = engine.snapshot().context.notice;
    start();
    finish("再慢一次");
    clock.advance(11000);
    answer();
    assertEquals(notice, engine.snapshot().context.notice);
    config.textModel = "another-mock-model";
    start();
    finish("更换模型");
    answer();
    assertFalse(engine.snapshot().context.slow);
  }

  @Test
  public void longMeetingNoticeCoolsDownAndRemainsConsumedAfterRestore() {
    engine.loadContextArchive(rounds());
    start();
    finish("问题？");
    assertTrue(engine.snapshot().context.longMeeting);
    assertTrue(engine.consumeContextNotice());
    answer();
    long seq = engine.snapshot().context.notice;
    start();
    finish("继续？");
    answer();
    assertEquals(seq, engine.snapshot().context.notice);
    var state = engine.saveState();
    engine.restore(state);
    engine.loadContextArchive(rounds());
    assertFalse(engine.consumeContextNotice());
    clock.advance(600001);
    start();
    finish("下一问题？");
    assertTrue(engine.snapshot().context.notice > seq);
  }

  @Test
  public void contextLimitRetriesOnlyOnceWithSmallerFutureRequests() {
    start();
    finish("问题？");
    int first = engine.snapshot().context.budget;
    TextProvider.Listener stale = factory.text.listener;
    stale.contextLimit();
    assertEquals(2, factory.text.calls);
    assertTrue(engine.snapshot().context.budget < first);
    stale.delta("迟到");
    assertFalse(engine.snapshot().answer.contains("迟到"));
    factory.text.listener.contextLimit();
    assertEquals(2, factory.text.calls);
    assertEquals(SessionEngine.AnalysisState.ERROR, engine.snapshot().analysisState);
    start();
    finish("下一次？");
    assertTrue(engine.snapshot().context.budget < first);
  }

  @Test
  public void genericHttpFailureNeverRetries() {
    start();
    finish("问题？");
    factory.text.listener.failed("HTTP 429");
    assertEquals(1, factory.text.calls);
  }

  @Test
  public void legacyHistoryAndCarryAreBoundedDataNotInstructions() {
    var state = new SessionEngine.SavedState();
    state.history.add("以前的问题：债务是多少？");
    state.contextMemory.carry = "只作上场摘录";
    engine.restore(state);
    start();
    finish("刚才的问题是什么？");
    assertTrue(factory.text.messages.toString().contains("以前的问题"));
    assertTrue(factory.text.messages.toString().contains("只作上场摘录"));
    assertTrue(ContextMemory.estimate(factory.text.messages) <= options.budget());
  }

  @Test
  public void blockedMaintenanceCannotBlockPcmOrTailDrain() throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        submitted = new CountDownLatch(1);
    SessionEngineTest.FakeFactory blocked =
        new SessionEngineTest.FakeFactory() {
          @Override
          public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
            return new TextProvider() {
              public void analyze(JsonArray m, Listener l) {
                submitted.countDown();
              }

              public void cancel() {}
            };
          }

          @Override
          public TextProvider summarize(ProviderConfig c, ProviderConfig.Credentials k) {
            return new TextProvider() {
              public void analyze(JsonArray m, Listener l) {
                entered.countDown();
                try {
                  release.await(3, TimeUnit.SECONDS);
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
    try (SessionEngine real = new SessionEngine(blocked)) {
      real.configureContext(options);
      real.loadContextArchive(rounds());
      real.setAutomatic(true);
      real.start(config, keys, new UserSettings.Profile("", "", ""));
      blocked.speech.listener.ready();
      blocked.speech.listener.segment(new SpeechProvider.Segment(0, "如何合并？", true, 0, 1000));
      assertTrue(submitted.await(2, TimeUnit.SECONDS));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      long begin = System.nanoTime();
      for (int i = 0; i < 100; i++) blocked.audio.listener.audio(new byte[] {1, 2});
      real.stop();
      assertTrue(System.nanoTime() - begin < 500000000L);
      assertEquals(100, blocked.speech.frames);
      assertNotNull(blocked.audio.drained);
      assertEquals(SessionEngine.State.FINALIZING, real.snapshot().state);
    } finally {
      release.countDown();
    }
  }
}
