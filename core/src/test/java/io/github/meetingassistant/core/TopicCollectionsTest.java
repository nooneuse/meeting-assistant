package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.*;

public class TopicCollectionsTest {
  MeetingManager.MemoryStore store = new MeetingManager.MemoryStore();
  MeetingManager manager;
  UserSettings.Profile profile = UserSettings.initial().snapshot();
  Map<SessionEngine, SessionEngineTest.FakeFactory> factories = new IdentityHashMap<>();

  SessionEngine engine() {
    SessionEngineTest.FakeFactory f = new SessionEngineTest.FakeFactory();
    SessionEngine e = new SessionEngine(f, new ManualClockwork());
    factories.put(e, f);
    return e;
  }

  @Before
  public void setup() {
    manager = new MeetingManager(store, this::engine, profile, false);
  }

  @After
  public void cleanup() {
    manager.close();
  }

  String folder(String name) throws Exception {
    CompletableFuture<String> id = new CompletableFuture<>();
    manager.createCollection(name, id::complete);
    String value = id.get(3, TimeUnit.SECONDS);
    assertNotNull(value);
    return value;
  }

  void done(Consumer<Consumer<Boolean>> action) throws Exception {
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    action.accept(result::complete);
    assertTrue(result.get(3, TimeUnit.SECONDS));
  }

  void select(String folder, boolean restore) {
    manager.switchTo(manager.collectionTarget(folder, restore, profile, false));
  }

  String topic() {
    String id = manager.create(profile, false);
    manager.switchTo(id);
    return id;
  }

  @Test
  public void noSelectionCreatesInDefaultAndExpansionNeverChangesTarget() throws Exception {
    assertEquals("", manager.selectedCollection());
    String other = folder("Algorithms");
    manager.setExpanded(other, true);
    assertEquals(TopicCatalog.DEFAULT, manager.creationCollection());
    String a = topic();
    assertEquals(TopicCatalog.DEFAULT, manager.get(a).collectionId);
    select(other, false);
    assertTrue(manager.current().draft);
    String b = topic();
    assertEquals(other, manager.get(b).collectionId);
    assertEquals(1, manager.collectionTopics(other).size());
  }

  @Test
  public void legacyRecordsMigrateOnceWithSelectionAndContentIntact() {
    manager.close();
    store = new MeetingManager.MemoryStore();
    MeetingRecord a = new MeetingRecord();
    a.version = 1;
    a.title = "original";
    a.current.answer = "original answer";
    a.view.answerScroll = 42;
    MeetingRecord b = new MeetingRecord();
    b.version = 2;
    b.title = "second";
    store.save(a);
    store.save(b);
    store.select(a.id);
    manager = new MeetingManager(store, this::engine, profile, false);
    assertEquals(a.id, manager.currentId());
    assertEquals(3, manager.get(a.id).version);
    assertEquals("original answer", manager.answer());
    assertEquals(42, manager.current().view.answerScroll);
    assertEquals(TopicCatalog.DEFAULT, manager.get(b.id).collectionId);
    manager.awaitWrites();
    manager.close();
    manager = new MeetingManager(store, this::engine, profile, false);
    assertEquals(1, manager.catalog().collections.size());
    assertEquals(2, manager.list().size());
  }

  @Test
  public void rememberedTopicSurvivesPreparationAndRestart() throws Exception {
    String a = folder("A");
    select(a, false);
    String first = topic();
    select(a, false);
    assertTrue(manager.current().draft);
    select(TopicCatalog.DEFAULT, false);
    select(a, true);
    assertEquals(first, manager.currentId());
    manager.setExpanded(a, false);
    manager.awaitWrites();
    manager.close();
    manager = new MeetingManager(store, this::engine, profile, false);
    assertEquals(a, manager.selectedCollection());
    assertEquals(first, manager.currentId());
    assertEquals(first, manager.catalog().remembered.get(a));
  }

  @Test
  public void movingLiveTopicRetainsEngineAndContentAndUpdatesBothParents() throws Exception {
    String a = folder("A"), b = folder("B");
    select(a, false);
    String id = topic();
    SessionEngine owned = manager.currentEngine();
    SessionEngine.SavedState state = owned.saveState();
    state.answer = "kept answer";
    owned.restore(state);
    manager.rename(id, "kept title");
    done(callback -> manager.moveTopic(id, b, callback));
    assertSame(owned, manager.currentEngine());
    assertEquals("kept answer", manager.answer());
    assertEquals(b, manager.selectedCollection());
    assertEquals(b, manager.get(id).collectionId);
    assertTrue(manager.collectionTopics(a).isEmpty());
    assertEquals(id, manager.catalog().remembered.get(b));
    assertEquals(manager.catalog().find(a).updatedAt, manager.catalog().find(b).updatedAt);
    manager.awaitWrites();
    assertEquals(
        b, store.load().stream().filter(r -> r.id.equals(id)).findFirst().get().collectionId);
  }

  @Test
  public void deletingLastTopicPreparesSameCollectionWithoutCrossingIntoOthers() throws Exception {
    String a = folder("A"), b = folder("B");
    select(b, false);
    topic();
    select(a, false);
    String id = topic();
    done(callback -> manager.delete(id, callback));
    assertEquals(a, manager.selectedCollection());
    assertEquals(a, manager.current().collectionId);
    assertTrue(manager.current().draft);
    assertTrue(manager.collectionTopics(a).isEmpty());
    assertEquals(1, manager.collectionTopics(b).size());
  }

  @Test
  public void deletingCollectionCancelsOwnedTasksAndLateAnswersCannotResurrectIt()
      throws Exception {
    String a = folder("A");
    select(a, false);
    String id = topic();
    SessionEngine e = manager.currentEngine();
    SessionEngineTest.FakeFactory f = factories.get(e);
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    e.start(c, new ProviderConfig.Credentials("synthetic", "synthetic"), profile);
    f.speech.listener.ready();
    f.speech.listener.segment(new SpeechProvider.Segment(1, "tree question", true, 0, 1000));
    e.stop();
    f.audio.drained.run();
    f.speech.listener.completed();
    TextProvider.Listener late = f.text.listener;
    done(callback -> manager.deleteCollection(a, false, callback));
    late.delta("late answer");
    late.completed(false);
    manager.awaitWrites();
    assertNull(manager.catalog().find(a));
    assertNull(manager.get(id));
    assertTrue(store.load().stream().noneMatch(r -> r.id.equals(id)));
    assertEquals(TopicCatalog.DEFAULT, manager.selectedCollection());
    assertEquals("", manager.answer());
  }

  @Test
  public void defaultCannotBeDeletedButCanBeRenamedAndCleared() throws Exception {
    String id = topic();
    assertThrows(
        IllegalArgumentException.class,
        () -> manager.deleteCollection(TopicCatalog.DEFAULT, false, okay -> {}));
    done(callback -> manager.renameCollection(TopicCatalog.DEFAULT, "Inbox", callback));
    done(callback -> manager.deleteCollection(TopicCatalog.DEFAULT, true, callback));
    assertNotNull(manager.catalog().find(TopicCatalog.DEFAULT));
    assertEquals("Inbox", manager.catalog().find(TopicCatalog.DEFAULT).name);
    assertNull(manager.get(id));
    assertTrue(manager.summaries().isEmpty());
  }

  @Test
  public void mergeDeletesFolderAndMovesAllTopicsAtomicallyWithoutCombiningContext()
      throws Exception {
    String a = folder("A"), b = folder("B");
    select(a, false);
    String first = topic(), second = topic();
    SessionEngine owned = manager.currentEngine();
    done(callback -> manager.mergeCollection(a, b, callback));
    assertNull(manager.catalog().find(a));
    assertEquals(2, manager.collectionTopics(b).size());
    assertEquals(b, manager.get(first).collectionId);
    assertEquals(b, manager.get(second).collectionId);
    assertSame(owned, manager.currentEngine());
    assertEquals(b, manager.selectedCollection());
  }

  @Test
  public void failedMoveRollsBackCatalogAndMembershipInMemoryAndOnDisk() throws Exception {
    manager.close();
    class Flaky extends MeetingManager.MemoryStore {
      boolean fail;

      @Override
      public synchronized void saveCatalog(TopicCatalog c) {
        if (fail) {
          fail = false;
          throw new IllegalStateException();
        }
        super.saveCatalog(c);
      }
    }
    Flaky flaky = new Flaky();
    store = flaky;
    manager = new MeetingManager(store, this::engine, profile, false);
    String a = folder("A"), b = folder("B");
    select(a, false);
    String id = topic();
    manager.awaitWrites();
    flaky.fail = true;
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    manager.moveTopic(id, b, result::complete);
    assertFalse(result.get(3, TimeUnit.SECONDS));
    assertEquals(a, manager.get(id).collectionId);
    assertEquals(a, manager.selectedCollection());
    assertEquals(
        a, store.load().stream().filter(r -> r.id.equals(id)).findFirst().get().collectionId);
    assertTrue(manager.error().contains("原数据已保留"));
  }

  @Test
  public void selectionAndReadingDoNotTouchDatesAndDefaultAlwaysSortsLast() throws Exception {
    String a = folder("A"), b = folder("B");
    long modified = manager.catalog().find(a).updatedAt;
    select(a, false);
    manager.setExpanded(a, false);
    manager.saveView(new MeetingRecord.ViewState());
    assertEquals(modified, manager.catalog().find(a).updatedAt);
    List<TopicCatalog.Collection> ordered = manager.catalog().ordered();
    assertEquals(TopicCatalog.DEFAULT, ordered.get(ordered.size() - 1).id);
    assertNull(UserSettings.initial().bindings.get(UserSettings.COLLECTION_NEXT));
    assertNull(UserSettings.initial().bindings.get(UserSettings.COLLECTION_PREVIOUS));
  }

  @Test
  public void calendarSectionsHandleDstAndMonthBoundaries() {
    ZoneId zone = ZoneId.of("America/New_York");
    long now = ZonedDateTime.of(2026, 3, 9, 0, 15, 0, 0, zone).toInstant().toEpochMilli();
    long yesterday = ZonedDateTime.of(2026, 3, 8, 0, 10, 0, 0, zone).toInstant().toEpochMilli();
    assertEquals("yesterday", CollectionDates.section(yesterday, now, zone));
    assertEquals(
        "week",
        CollectionDates.section(
            ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), now, zone));
    assertEquals(
        "2026-03",
        CollectionDates.section(
            ZonedDateTime.of(2026, 3, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), now, zone));
  }

  @Test
  public void corruptCatalogIsNotReplacedWithFreshDefault() {
    manager.close();
    store = new MeetingManager.MemoryStore();
    TopicCatalog invalid = new TopicCatalog();
    invalid.collections.clear();
    store.saveCatalog(invalid);
    manager = new MeetingManager(store, this::engine, profile, false);
    assertFalse(manager.available());
    manager.awaitWrites();
    assertTrue(store.catalog().collections.isEmpty());
  }

  @Test
  public void blockedDirectoryWriteDoesNotHoldAudioOrLoseLiveSnapshots() throws Exception {
    manager.close();
    class Slow extends MeetingManager.MemoryStore {
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      volatile boolean slow;

      @Override
      public void structure(
          java.util.List<MeetingRecord> upserts,
          java.util.List<String> deletes,
          TopicCatalog catalog,
          String selected) {
        if (slow) {
          entered.countDown();
          try {
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        }
        super.structure(upserts, deletes, catalog, selected);
      }
    }
    Slow slow = new Slow();
    store = slow;
    manager = new MeetingManager(store, this::engine, profile, false);
    String a = folder("A"), b = folder("B");
    select(a, false);
    String id = topic();
    SessionEngine e = manager.currentEngine();
    SessionEngineTest.FakeFactory f = factories.get(e);
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    e.start(c, new ProviderConfig.Credentials("synthetic", "synthetic"), profile);
    f.speech.listener.ready();
    manager.awaitWrites();
    slow.slow = true;
    CompletableFuture<Boolean> moved = new CompletableFuture<>();
    manager.moveTopic(id, b, moved::complete);
    assertTrue(slow.entered.await(1, TimeUnit.SECONDS));
    ExecutorService producer = Executors.newSingleThreadExecutor();
    try {
      producer
          .submit(
              () -> {
                f.speech.listener.segment(
                    new SpeechProvider.Segment(1, "arrived during transaction", true, 0, 1000));
                f.audio.listener.audio(new byte[] {1, 2});
              })
          .get(1, TimeUnit.SECONDS);
      assertEquals(1, f.speech.frames);
      assertTrue(manager.transcript(false).contains("arrived during transaction"));
    } finally {
      slow.release.countDown();
      producer.shutdownNow();
    }
    assertTrue(moved.get(3, TimeUnit.SECONDS));
    manager.awaitWrites();
    assertEquals(b, manager.get(id).collectionId);
    assertTrue(manager.get(id).current.stableText().contains("arrived during transaction"));
    MeetingRecord persisted = store.load().stream().filter(r -> r.id.equals(id)).findFirst().get();
    assertEquals(b, persisted.collectionId);
    assertTrue(persisted.current.stableText().contains("arrived during transaction"));
  }

  @Test
  public void restoringCompletedPendingAnswerDoesNotMakeOldCollectionLookEditedToday() {
    manager.close();
    store = new MeetingManager.MemoryStore();
    TopicCatalog catalog = new TopicCatalog();
    TopicCatalog.Collection group = new TopicCatalog.Collection();
    group.name = "Older";
    group.createdAt = 100;
    group.updatedAt = 1000;
    catalog.collections.add(group);
    MeetingRecord record = new MeetingRecord();
    record.collectionId = group.id;
    record.title = "Saved topic";
    record.customTitle = true;
    record.updatedAt = 1000;
    record.current.state = SessionEngine.State.DONE;
    record.current.analysis = SessionEngine.AnalysisState.DONE;
    record.current.answer = "old";
    record.current.pendingAnswer = "completed update";
    store.save(record);
    store.select(record.id);
    store.saveCatalog(catalog);
    manager = new MeetingManager(store, this::engine, profile, false);
    assertEquals("completed update", manager.answer());
    assertEquals(1000, manager.catalog().find(group.id).updatedAt);
    assertEquals(1000, manager.current().updatedAt);
  }

  @Test
  public void missingCatalogCannotSilentlyReassignVersionThreeTopics() {
    manager.close();
    store = new MeetingManager.MemoryStore();
    MeetingRecord record = new MeetingRecord();
    record.collectionId = "missing-parent";
    store.save(record);
    manager = new MeetingManager(store, this::engine, profile, false);
    assertFalse(manager.available());
    manager.awaitWrites();
    assertNull(store.catalog());
    assertEquals("missing-parent", store.load().get(0).collectionId);
  }
}
