package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class ImageMeetingTest {
  @Test
  public void imagePromotesDraftPersistsAndStaysWithOriginalMeeting() throws Exception {
    MeetingManager.MemoryStore store = new MeetingManager.MemoryStore();
    Map<SessionEngine, SessionEngineTest.FakeFactory> factories = new IdentityHashMap<>();
    java.util.function.Supplier<SessionEngine> factory =
        () -> {
          var f = new SessionEngineTest.FakeFactory();
          var e = new SessionEngine(f, new ManualClockwork());
          factories.put(e, f);
          return e;
        };
    var profile = UserSettings.initial().snapshot();
    String original;
    try (MeetingManager manager = new MeetingManager(store, factory, profile, false)) {
      SessionEngine owner = manager.currentEngine();
      original = manager.currentId();
      assertTrue(manager.current().draft);
      ImageEvidence image = new ImageEvidence();
      image.preference = profile;
      assertTrue(owner.acceptImage(image, () -> {}));
      assertFalse(manager.current().draft);
      assertEquals(1, manager.summaries().size());
      String next = manager.create(profile, false);
      assertTrue(manager.switchTo(next));
      ProviderConfig config = new ProviderConfig();
      config.textBaseUrl = "https://example.org/v1";
      owner.imageRecognized(
          image.id,
          ImageSessionTest.OCR,
          config,
          new ProviderConfig.Credentials("", "test"),
          profile);
      var text = factories.get(owner).text;
      text.listener.delta("Synthetic image answer");
      text.listener.completed(false);
      assertEquals("", manager.answer());
      assertEquals("Synthetic image answer", manager.get(original).current.answer);
      assertEquals(image.id, manager.get(original).current.answerImages.get(0));
      manager.awaitWrites();
    }
    try (MeetingManager restored = new MeetingManager(store, factory, profile, false)) {
      assertEquals(3, restored.get(original).version);
      assertTrue(restored.switchTo(original));
      assertEquals("Synthetic image answer", restored.answer());
      assertEquals(1, restored.currentEngine().imageCount());
      CountDownLatch deleted = new CountDownLatch(1);
      SessionEngine deletingOwner = restored.currentEngine();
      assertTrue(restored.acceptsImage(original, deletingOwner));
      restored.delete(
          original,
          ok -> {
            assertTrue(ok);
            deleted.countDown();
          });
      assertFalse(restored.acceptsImage(original, deletingOwner));
      assertTrue(deleted.await(3, TimeUnit.SECONDS));
      assertNull(restored.get(original));
    }
  }

  @Test
  public void versionOneRecordMigratesWithoutCreatingImageJobsOrLosingAnswer() {
    MeetingManager.MemoryStore store = new MeetingManager.MemoryStore();
    MeetingRecord old = new MeetingRecord();
    old.version = 1;
    old.current.answer = "Previous answer";
    store.save(old);
    store.select(old.id);
    SessionEngineTest.FakeFactory f = new SessionEngineTest.FakeFactory();
    try (MeetingManager manager =
        new MeetingManager(
            store,
            () -> new SessionEngine(f, new ManualClockwork()),
            UserSettings.initial().snapshot(),
            false)) {
      assertTrue(manager.available());
      assertEquals("Previous answer", manager.answer());
      assertEquals(0, manager.currentEngine().imageCount());
      assertEquals(3, manager.current().version);
      assertEquals(0, f.text.calls);
      assertEquals(0, f.audio.starts);
    }
  }
}
