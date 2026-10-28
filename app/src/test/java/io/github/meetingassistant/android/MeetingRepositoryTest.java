package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.database.Cursor;
import com.google.gson.Gson;
import io.github.meetingassistant.core.EncryptedPayload;
import io.github.meetingassistant.core.MeetingRecord;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35)
public class MeetingRepositoryTest {
  EncryptedPayload cipher() throws Exception {
    SecretKey key = KeyGenerator.getInstance("AES").generateKey();
    return new EncryptedPayload(() -> key);
  }

  MeetingRecord record() {
    MeetingRecord r = new MeetingRecord();
    r.title = "机密预算会议";
    r.current.answer = "内部债务详情";
    r.current.contextMemory.carry = "private synthetic context excerpt";
    r.current.contextMemory.digest = "synthetic-prefix-digest";
    io.github.meetingassistant.core.ContextMemory.Item memory =
        new io.github.meetingassistant.core.ContextMemory.Item();
    memory.source = "synthetic-source";
    memory.quote = "private synthetic quote";
    memory.kind = "constraint";
    r.current.contextMemory.items.add(memory);
    r.current.contextReport.input = 6000;
    r.current.contextReport.notice = 1;
    io.github.meetingassistant.core.ImageEvidence image =
        new io.github.meetingassistant.core.ImageEvidence();
    image.structure = "Synthetic image evidence with private budget text";
    image.state = io.github.meetingassistant.core.ImageEvidence.State.READY;
    r.current.images.add(image);
    r.rounds.add(new MeetingRecord.Round("previous", r.current));
    return r;
  }

  @Test
  public void sqlitePersistsOnlyCiphertextAndRestoresWithSameKey() throws Exception {
    EncryptedPayload box = cipher();
    MeetingRecord r = record();
    MeetingRepository repo =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "test-records.db", box);
    repo.save(r);
    repo.select(r.id);
    try (Cursor c = repo.getReadableDatabase().rawQuery("SELECT payload FROM meetings", null)) {
      assertTrue(c.moveToFirst());
      String payload = new String(c.getBlob(0), java.nio.charset.StandardCharsets.UTF_8);
      assertFalse(payload.contains(r.title));
      assertFalse(payload.contains(r.current.answer));
      assertFalse(payload.contains("rounds"));
      assertFalse(payload.contains("private synthetic context excerpt"));
      assertFalse(payload.contains("synthetic-prefix-digest"));
      assertFalse(payload.contains("private synthetic quote"));
      assertFalse(payload.contains("Synthetic image evidence"));
    }
    repo.close();
    repo = new MeetingRepository(RuntimeEnvironment.getApplication(), "test-records.db", box);
    assertEquals(r.id, repo.selected());
    assertEquals(new Gson().toJson(r), new Gson().toJson(repo.load().get(0)));
    repo.delete(r.id);
    assertTrue(repo.load().isEmpty());
    repo.close();
  }

  @Test
  public void modifiedCiphertextIsRejectedAndRecordIsRetained() throws Exception {
    MeetingRepository repo =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "tamper.db", cipher());
    MeetingRecord r = record();
    repo.save(r);
    byte[] bytes;
    try (Cursor c = repo.getReadableDatabase().rawQuery("SELECT payload FROM meetings", null)) {
      assertTrue(c.moveToFirst());
      bytes = c.getBlob(0);
    }
    bytes[bytes.length - 1] ^= 1;
    ContentValues values = new ContentValues();
    values.put("payload", bytes);
    repo.getWritableDatabase().update("meetings", values, "id=?", new String[] {r.id});
    assertThrows(IllegalStateException.class, repo::load);
    try (Cursor c = repo.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM meetings", null)) {
      assertTrue(c.moveToFirst());
      assertEquals(1, c.getInt(0));
    }
    repo.close();
  }

  @Test
  public void wrongKeyAndEncryptionFailureNeverReplaceExistingRecord() throws Exception {
    MeetingRecord r = record();
    EncryptedPayload box = cipher();
    MeetingRepository repo =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "wrong-key.db", box);
    repo.save(r);
    repo.close();
    MeetingRepository wrong =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "wrong-key.db", cipher());
    assertThrows(IllegalStateException.class, wrong::load);
    wrong.close();
    EncryptedPayload failed =
        new EncryptedPayload(
            () -> {
              throw new java.security.GeneralSecurityException();
            });
    MeetingRepository failing =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "wrong-key.db", failed);
    r.title = "不应写入的新标题";
    assertThrows(IllegalStateException.class, () -> failing.save(r));
    failing.close();
    repo = new MeetingRepository(RuntimeEnvironment.getApplication(), "wrong-key.db", box);
    assertEquals("机密预算会议", repo.load().get(0).title);
    repo.close();
  }

  @Test
  public void currentDeleteAndReplacementAreOneTransaction() throws Exception {
    MeetingRepository repo =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "transaction.db", cipher());
    MeetingRecord old = record(), replacement = record();
    repo.save(old);
    repo.select(old.id);
    repo.getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER reject_selection BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT,"
                + " 'local-test'); END");
    assertThrows(RuntimeException.class, () -> repo.replaceAndDelete(old.id, replacement));
    assertEquals(1, repo.load().size());
    assertEquals(old.id, repo.load().get(0).id);
    assertEquals(old.id, repo.selected());
    repo.close();
  }

  @Test
  public void selectingExistingMeetingAndDeletingCurrentRollBackTogether() throws Exception {
    MeetingRepository repo =
        new MeetingRepository(
            RuntimeEnvironment.getApplication(), "fallback-transaction.db", cipher());
    MeetingRecord old = record(), other = record();
    repo.save(old);
    repo.save(other);
    repo.select(old.id);
    repo.getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER reject_selection BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT,"
                + " 'local-test'); END");
    assertThrows(RuntimeException.class, () -> repo.deleteAndSelect(old.id, other.id));
    assertEquals(2, repo.load().size());
    assertEquals(old.id, repo.selected());
    repo.close();
  }

  @Test
  public void encryptedCatalogHidesNamesAndAuthenticatesItsIdentity() throws Exception {
    var repo =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "catalog-private.db", cipher());
    var catalog = new io.github.meetingassistant.core.TopicCatalog();
    var group = new io.github.meetingassistant.core.TopicCatalog.Collection();
    group.name = "private synthetic collection";
    catalog.collections.add(group);
    repo.saveCatalog(catalog);
    try (Cursor c =
        repo.getReadableDatabase().rawQuery("SELECT payload FROM topic_catalog", null)) {
      assertTrue(c.moveToFirst());
      byte[] payload = c.getBlob(0);
      assertFalse(
          new String(payload, java.nio.charset.StandardCharsets.UTF_8).contains(group.name));
      ContentValues row = new ContentValues();
      payload[payload.length - 1] ^= 1;
      row.put("payload", payload);
      repo.getWritableDatabase().update("topic_catalog", row, "id=1", null);
    }
    assertThrows(IllegalStateException.class, repo::catalog);
    try (Cursor c =
        repo.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM topic_catalog", null)) {
      assertTrue(c.moveToFirst());
      assertEquals(1, c.getInt(0));
    }
    repo.close();
  }

  @Test
  public void structuralTransactionRollsBackRecordFolderAndSelectionTogether() throws Exception {
    var repo =
        new MeetingRepository(RuntimeEnvironment.getApplication(), "catalog-rollback.db", cipher());
    MeetingRecord original = record();
    repo.save(original);
    repo.select(original.id);
    var catalog = new io.github.meetingassistant.core.TopicCatalog();
    repo.saveCatalog(catalog);
    var changed = new io.github.meetingassistant.core.TopicCatalog();
    var target = new io.github.meetingassistant.core.TopicCatalog.Collection();
    target.name = "target";
    changed.collections.add(target);
    MeetingRecord moved = new Gson().fromJson(new Gson().toJson(original), MeetingRecord.class);
    moved.collectionId = target.id;
    repo.getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER reject_catalog_selection BEFORE INSERT ON metadata BEGIN SELECT"
                + " RAISE(ABORT, 'synthetic'); END");
    assertThrows(
        RuntimeException.class,
        () -> repo.structure(java.util.List.of(moved), java.util.List.of(), changed, original.id));
    assertEquals(
        io.github.meetingassistant.core.TopicCatalog.DEFAULT, repo.load().get(0).collectionId);
    assertEquals(1, repo.catalog().collections.size());
    assertEquals(original.id, repo.selected());
    repo.close();
  }

  @Test
  public void versionOneDatabaseUpgradeRetainsPayloadAndMigrationCommitsOnlyAfterValidation()
      throws Exception {
    var context = RuntimeEnvironment.getApplication();
    var box = cipher();
    var repo = new MeetingRepository(context, "catalog-v1.db", box);
    MeetingRecord old = record();
    old.current.images.clear();
    old.version = 1;
    repo.save(old);
    repo.select(old.id);
    repo.getWritableDatabase().execSQL("DROP TABLE topic_catalog");
    repo.getWritableDatabase().setVersion(1);
    repo.close();
    repo = new MeetingRepository(context, "catalog-v1.db", box);
    assertEquals(1, repo.load().get(0).version);
    assertNull(repo.catalog());
    repo.getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER reject_catalog BEFORE INSERT ON topic_catalog BEGIN SELECT RAISE(ABORT,"
                + " 'synthetic'); END");
    var manager =
        new io.github.meetingassistant.core.MeetingManager(
            repo,
            this::isolatedEngine,
            io.github.meetingassistant.core.UserSettings.initial().snapshot(),
            false);
    assertFalse(manager.available());
    manager.close();
    assertEquals(1, repo.load().get(0).version);
    assertNull(repo.catalog());
    assertEquals(old.id, repo.selected());
    repo.getWritableDatabase().execSQL("DROP TRIGGER reject_catalog");
    manager =
        new io.github.meetingassistant.core.MeetingManager(
            repo,
            this::isolatedEngine,
            io.github.meetingassistant.core.UserSettings.initial().snapshot(),
            false);
    assertTrue(manager.available());
    assertEquals(old.id, manager.currentId());
    assertEquals("内部债务详情", manager.answer());
    manager.close();
    assertNotNull(repo.catalog());
    assertEquals(3, repo.load().get(0).version);
    repo.close();
  }

  private io.github.meetingassistant.core.SessionEngine isolatedEngine() {
    return new io.github.meetingassistant.core.SessionEngine(
        new io.github.meetingassistant.core.SessionEngine.Factory() {
          public io.github.meetingassistant.core.AudioSource audio() {
            throw new AssertionError("unexpected audio");
          }

          public io.github.meetingassistant.core.SpeechProvider speech(
              io.github.meetingassistant.core.ProviderConfig c,
              io.github.meetingassistant.core.ProviderConfig.Credentials k) {
            throw new AssertionError("unexpected ASR");
          }

          public io.github.meetingassistant.core.TextProvider text(
              io.github.meetingassistant.core.ProviderConfig c,
              io.github.meetingassistant.core.ProviderConfig.Credentials k) {
            throw new AssertionError("unexpected model");
          }
        });
  }
}
