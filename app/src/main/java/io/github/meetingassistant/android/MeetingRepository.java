package io.github.meetingassistant.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.google.gson.Gson;
import io.github.meetingassistant.core.EncryptedPayload;
import io.github.meetingassistant.core.MeetingManager;
import io.github.meetingassistant.core.MeetingRecord;
import io.github.meetingassistant.core.TopicCatalog;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class MeetingRepository extends SQLiteOpenHelper implements MeetingManager.Store {
  private static final String ALIAS = "meeting-assistant-records-v1";
  private final Gson gson = new Gson();
  private final EncryptedPayload cipher;

  MeetingRepository(Context c) {
    this(c, "meetings-v1.db");
  }

  MeetingRepository(Context c, String name) {
    this(c, name, new EncryptedPayload(MeetingRepository::key));
  }

  MeetingRepository(Context c, String name, EncryptedPayload cipher) {
    super(c, name, null, 2);
    this.cipher = cipher;
  }

  private static synchronized SecretKey key() throws GeneralSecurityException {
    try {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      return generator.generateKey();
    } catch (java.io.IOException e) {
      throw new GeneralSecurityException();
    }
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE meetings (id TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)");
    db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
    db.execSQL("CREATE TABLE topic_catalog (id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion == 1 && newVersion == 2)
      db.execSQL("CREATE TABLE topic_catalog (id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");
    else throw new IllegalStateException("会议数据库版本不兼容");
  }

  public TopicCatalog catalog() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT payload FROM topic_catalog WHERE id=1", null)) {
      if (!c.moveToFirst()) return null;
      byte[] clear = cipher.decrypt("topic-catalog-v1", c.getBlob(0));
      try {
        TopicCatalog catalog =
            gson.fromJson(new String(clear, StandardCharsets.UTF_8), TopicCatalog.class);
        if (catalog == null) throw new IllegalStateException("议题集记录损坏，原数据已保留");
        catalog.validate();
        return catalog;
      } finally {
        java.util.Arrays.fill(clear, (byte) 0);
      }
    }
  }

  public void saveCatalog(TopicCatalog catalog) {
    catalog.validate();
    byte[] clear = gson.toJson(catalog).getBytes(StandardCharsets.UTF_8);
    ContentValues row = new ContentValues();
    row.put("id", 1);
    try {
      row.put("payload", cipher.encrypt("topic-catalog-v1", clear));
    } finally {
      java.util.Arrays.fill(clear, (byte) 0);
    }
    if (getWritableDatabase()
            .insertWithOnConflict("topic_catalog", null, row, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new IllegalStateException("议题集保存失败");
  }

  @Override
  public void structure(
      List<MeetingRecord> upserts, List<String> deletes, TopicCatalog catalog, String selected) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (MeetingRecord r : upserts) save(r);
      for (String id : deletes) delete(id);
      saveCatalog(catalog);
      if (selected != null) select(selected);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public List<MeetingRecord> load() {
    List<MeetingRecord> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .query("meetings", new String[] {"id", "payload"}, null, null, null, null, null)) {
      while (c.moveToNext()) {
        String id = c.getString(0);
        byte[] clear = cipher.decrypt(id, c.getBlob(1));
        MeetingRecord record;
        try {
          record = gson.fromJson(new String(clear, StandardCharsets.UTF_8), MeetingRecord.class);
        } finally {
          java.util.Arrays.fill(clear, (byte) 0);
        }
        if (record == null || !id.equals(record.id))
          throw new IllegalStateException("会议记录损坏，原数据已保留");
        out.add(record);
      }
    }
    return out;
  }

  public String selected() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT value FROM metadata WHERE name=?", new String[] {"selected"})) {
      return c.moveToFirst() ? c.getString(0) : "";
    }
  }

  public void save(MeetingRecord r) {
    byte[] clear = gson.toJson(r).getBytes(StandardCharsets.UTF_8);
    ContentValues row = new ContentValues();
    row.put("id", r.id);
    try {
      row.put("payload", cipher.encrypt(r.id, clear));
    } finally {
      java.util.Arrays.fill(clear, (byte) 0);
    }
    if (getWritableDatabase()
            .insertWithOnConflict("meetings", null, row, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new IllegalStateException("会议保存失败");
  }

  public void select(String id) {
    ContentValues row = new ContentValues();
    row.put("name", "selected");
    row.put("value", id);
    if (getWritableDatabase()
            .insertWithOnConflict("metadata", null, row, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new IllegalStateException("会议选择保存失败");
  }

  public void delete(String id) {
    getWritableDatabase().delete("meetings", "id=?", new String[] {id});
  }

  @Override
  public void replaceAndDelete(String id, MeetingRecord replacement) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      save(replacement);
      delete(id);
      select(replacement.id);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  @Override
  public void deleteAndSelect(String id, String selectedId) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      delete(id);
      select(selectedId);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }
}
