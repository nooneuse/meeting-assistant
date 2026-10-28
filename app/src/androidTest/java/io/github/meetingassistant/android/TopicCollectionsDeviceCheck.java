package io.github.meetingassistant.android;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Native collection workflow in an isolated encrypted DB; no real capture or model adapters. */
final class TopicCollectionsDeviceCheck {
  static void run(Instrumentation test) throws Exception {
    MeetingApp app = (MeetingApp) test.getTargetContext().getApplicationContext();
    require(
        !app.session.snapshot().busy() && !app.audio.active() && !app.video.enabled(),
        "Idle app required");
    MeetingManager originalManager = app.meetings;
    SettingsRepository originalRepository = app.repository;
    UserSettings originalSettings = app.settings;
    boolean originalAwake = app.awake;
    Map<String, ?> originalPreferences =
        new HashMap<>(app.getSharedPreferences("settings-v1", 0).getAll());
    String database = "topic-collections-device-check.db",
        preferences = "topic-collections-device-check";
    app.deleteDatabase(database);
    MeetingRepository repository = new MeetingRepository(app, database);
    MeetingManager manager = null;
    MainActivity activity = null;
    try {
      // Real v1 database upgrade using the phone's Keystore; only synthetic content.
      MeetingRecord legacy = new MeetingRecord();
      legacy.version = 2;
      legacy.title = "Fixture linked lists";
      legacy.customTitle = true;
      legacy.current.answer = "Synthetic answer for native collection validation.\n".repeat(60);
      legacy.current.selected = UserSettings.initial().snapshot();
      legacy.current.state = SessionEngine.State.DONE;
      legacy.current.analysis = SessionEngine.AnalysisState.DONE;
      legacy.view.answerScroll = 30;
      repository.save(legacy);
      repository.select(legacy.id);
      repository.getWritableDatabase().execSQL("DROP TABLE topic_catalog");
      repository.getWritableDatabase().setVersion(1);
      repository.close();
      repository = new MeetingRepository(app, database);
      manager =
          new MeetingManager(
              repository,
              TopicCollectionsDeviceCheck::engine,
              UserSettings.initial().snapshot(),
              false);
      require(
          manager.available() && manager.currentId().equals(legacy.id),
          "Fixture migration/selection failed");
      require(
          manager.current().version == 3
              && manager.current().collectionId.equals(TopicCatalog.DEFAULT),
          "Legacy membership failed");
      require(
          manager.answer().equals(legacy.current.answer)
              && manager.current().view.answerScroll == 30,
          "Migration changed content/view");
      MeetingManager fixture = manager;
      DeviceChecks.onMain(
          test,
          () -> {
            app.repository = new SettingsRepository(app, preferences);
            app.settings = UserSettings.initial();
            app.settings.bind(UserSettings.MEETING_NEW, KeyEvent.KEYCODE_F5);
            app.settings.bind(UserSettings.MEETING_PREVIOUS, KeyEvent.KEYCODE_F6);
            app.settings.bind(UserSettings.MEETING_NEXT, KeyEvent.KEYCODE_F7);
            app.settings.bind(UserSettings.COLLECTION_PREVIOUS, KeyEvent.KEYCODE_F8);
            app.settings.bind(UserSettings.COLLECTION_NEXT, KeyEvent.KEYCODE_F9);
            app.awake = true;
            app.bindMeetings(fixture);
          });
      activity = launch(test, app);
      MainActivity screen = activity;
      String a = createCollection(test, screen, app, "Fixture algorithms");
      DeviceChecks.onMain(
          test,
          () -> {
            Dialog drawer = drawer(screen);
            require(tag(drawer, "collection-actions:" + a) != null, "Selected folder menu missing");
            require(
                drawer
                        .getWindow()
                        .getDecorView()
                        .findViewWithTag("collection-actions:" + TopicCatalog.DEFAULT)
                    == null,
                "Unselected folder menu shown");
            require(
                count(
                        drawer.getWindow().getDecorView(),
                        screen.getString(R.string.new_meeting_action))
                    == 1,
                "Duplicate new topic entry");
            drawer.findViewById(R.id.drawer_new_meeting).performClick();
          });
      await(
          test,
          () -> !fixture.current().draft && fixture.current().collectionId.equals(a),
          "New topic target incorrect");
      key(test, KeyEvent.KEYCODE_F5);
      await(test, () -> fixture.collectionTopics(a).size() == 2, "New-topic shortcut failed");
      List<MeetingManager.Summary> siblings = fixture.collectionTopics(a);
      DeviceChecks.onMain(test, () -> fixture.switchTo(siblings.get(0).id));
      await(test, () -> app.session == fixture.currentEngine(), "Session binding delayed");
      key(test, KeyEvent.KEYCODE_F6);
      require(fixture.currentId().equals(siblings.get(0).id), "Previous topic crossed boundary");
      key(test, KeyEvent.KEYCODE_F7);
      await(
          test, () -> fixture.currentId().equals(siblings.get(1).id), "Next topic shortcut failed");
      key(test, KeyEvent.KEYCODE_F7);
      require(fixture.current().collectionId.equals(a), "Next topic crossed collection");
      String b = createCollection(test, screen, app, "Fixture trees");
      DeviceChecks.onMain(
          test, () -> drawer(screen).findViewById(R.id.drawer_new_meeting).performClick());
      await(test, () -> fixture.collectionTopics(b).size() == 1, "Second folder creation failed");
      String bTopic = fixture.currentId();
      key(test, KeyEvent.KEYCODE_F6);
      key(test, KeyEvent.KEYCODE_F7);
      require(fixture.currentId().equals(bTopic), "Single-topic collection leaked navigation");
      DeviceChecks.onMain(
          test,
          () -> {
            screen.findViewById(R.id.meetings_button).performClick();
            tag(drawer(screen), "collection:" + TopicCatalog.DEFAULT).performClick();
          });
      await(
          test,
          () -> fixture.selectedCollection().equals(TopicCatalog.DEFAULT),
          "Folder selection failed");
      DeviceChecks.onMain(test, () -> drawer(screen).dismiss());
      List<TopicCatalog.Collection> ordered = fixture.catalog().ordered();
      String previous = ordered.get(ordered.size() - 2).id;
      String remembered = fixture.catalog().remembered.get(previous);
      key(test, KeyEvent.KEYCODE_F8);
      await(
          test,
          () -> fixture.currentId().equals(remembered),
          "Previous collection did not restore topic");
      key(test, KeyEvent.KEYCODE_F9);
      await(
          test,
          () -> fixture.currentId().equals(legacy.id),
          "Default collection did not restore legacy topic");
      DeviceChecks.onMain(
          test,
          () -> {
            screen.findViewById(R.id.meetings_button).performClick();
            String before = fixture.currentId();
            tag(drawer(screen), "expand:" + a).performClick();
            require(before.equals(fixture.currentId()), "Expansion navigated");
            require(
                fixture.selectedCollection().equals(TopicCatalog.DEFAULT),
                "Expansion selected folder");
          });
      save(test, "topic-collections-device.png");
      boolean expanded = fixture.catalog().expanded.contains(a);
      DeviceChecks.finishActivity(test, screen);
      activity = null;
      manager.awaitWrites();
      manager.close();
      repository.close();
      repository = new MeetingRepository(app, database);
      manager =
          new MeetingManager(
              repository, TopicCollectionsDeviceCheck::engine, app.settings.snapshot(), false);
      require(
          manager.currentId().equals(legacy.id)
              && manager.catalog().expanded.contains(a) == expanded,
          "Selection/expansion restart failed");
      require(
          manager.catalog().remembered.get(b).equals(bTopic), "Remembered topic lost on restart");
      try (Cursor row =
          repository.getReadableDatabase().rawQuery("SELECT payload FROM topic_catalog", null)) {
        require(
            row.moveToFirst()
                && !new String(row.getBlob(0), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("Fixture"),
            "Collection stored in plaintext");
      }
      MeetingManager restored = manager;
      DeviceChecks.onMain(test, () -> app.bindMeetings(restored));
      activity = launch(test, app);
      MainActivity resumed = activity;
      DeviceChecks.onMain(test, () -> restored.switchTo(bTopic));
      await(test, () -> app.session == restored.currentEngine(), "Restart binding failed");
      SessionEngine owner = restored.currentEngine();
      // Drive the actual topic menu's move entry.
      DeviceChecks.onMain(
          test,
          () -> {
            resumed.findViewById(R.id.meetings_button).performClick();
            View selected = tag(drawer(resumed), bTopic);
            ((ViewGroup) selected.getParent()).getChildAt(1).performClick();
          });
      test.waitForIdleSync();
      DeviceChecks.onMain(
          test,
          () ->
              ((AlertDialog) field(resumed, "managedDialog"))
                  .getListView()
                  .performItemClick(null, 2, 2));
      test.waitForIdleSync();
      DeviceChecks.onMain(
          test,
          () ->
              choose(
                  (AlertDialog) field(field(resumed, "topicCollections"), "modal"),
                  "Fixture algorithms"));
      await(test, () -> restored.get(bTopic).collectionId.equals(a), "Move menu failed");
      require(
          restored.currentEngine() == owner && restored.selectedCollection().equals(a),
          "Move replaced owner or selection");
      // Empty B can be deleted; selected A can then be deleted with its topics.
      deleteCollection(test, resumed, restored, b);
      deleteCollection(test, resumed, restored, a);
      require(
          restored.currentId().equals(legacy.id) && restored.catalog().collections.size() == 1,
          "Delete fallback failed");
      require(
          restored.answer().equals(legacy.current.answer),
          "Collection deletion changed other content");
      await(
          test,
          () -> hasTag(resumed, "collection-actions:" + TopicCatalog.DEFAULT),
          "Default menu not restored");
      DeviceChecks.onMain(
          test,
          () -> {
            Dialog d = drawer(resumed);
            tag(d, "collection-actions:" + TopicCatalog.DEFAULT).performClick();
          });
      test.waitForIdleSync();
      DeviceChecks.onMain(
          test,
          () -> {
            AlertDialog menu = (AlertDialog) field(field(resumed, "topicCollections"), "modal");
            require(
                menu.getListView()
                    .getAdapter()
                    .getItem(1)
                    .equals(resumed.getString(R.string.clear_collection)),
                "Default folder deletion exposed");
            menu.dismiss();
          });
      save(test, "topic-collections-default-device.png");
      Bundle result = new Bundle();
      result.putString(
          "checks",
          "V1_SQLITE_KEYSTORE_MIGRATION_TREE_CREATION_SCOPE_NATIVE_KEYS_RESTART_MOVE_DELETE_DEFAULT_PROTECTION_NO_CAPTURE");
      test.sendStatus(0, result);
    } finally {
      if (activity != null) DeviceChecks.finishActivity(test, activity);
      if (manager != null) manager.close();
      repository.close();
      DeviceChecks.onMain(
          test,
          () -> {
            app.repository = originalRepository;
            app.settings = originalSettings;
            app.awake = originalAwake;
            app.bindMeetings(originalManager);
          });
      app.deleteDatabase(database);
      app.getSharedPreferences(preferences, 0).edit().clear().commit();
      require(
          originalPreferences.equals(app.getSharedPreferences("settings-v1", 0).getAll()),
          "Production settings changed");
    }
  }

  private static SessionEngine engine() {
    return new SessionEngine(
        new SessionEngine.Factory() {
          public AudioSource audio() {
            throw new AssertionError("Unexpected capture");
          }

          public SpeechProvider speech(ProviderConfig p, ProviderConfig.Credentials k) {
            throw new AssertionError("Unexpected ASR");
          }

          public TextProvider text(ProviderConfig p, ProviderConfig.Credentials k) {
            throw new AssertionError("Unexpected model");
          }
        });
  }

  private static MainActivity launch(Instrumentation test, MeetingApp app) {
    MainActivity screen =
        (MainActivity)
            test.startActivitySync(
                new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(test, screen::hasWindowFocus, "Unlock phone to check collections");
    return screen;
  }

  private static String createCollection(
      Instrumentation test, MainActivity screen, MeetingApp app, String name) {
    DeviceChecks.onMain(
        test,
        () -> {
          if (!showing(screen)) screen.findViewById(R.id.meetings_button).performClick();
          drawer(screen).findViewById(R.id.drawer_new_collection).performClick();
        });
    test.waitForIdleSync();
    DeviceChecks.onMain(
        test,
        () -> {
          AlertDialog modal = (AlertDialog) field(field(screen, "topicCollections"), "modal");
          edit(modal.getWindow().getDecorView()).setText(name);
          modal.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
    await(
        test,
        () ->
            app.meetings.catalog().collections.stream().anyMatch(c -> c.name.equals(name))
                && app.meetings.catalog().find(app.meetings.selectedCollection()) != null
                && app.meetings.catalog().find(app.meetings.selectedCollection()).name.equals(name)
                && hasTag(screen, "collection-actions:" + app.meetings.selectedCollection()),
        "New collection UI failed");
    return app.meetings.selectedCollection();
  }

  private static void deleteCollection(
      Instrumentation test, MainActivity screen, MeetingManager manager, String id) {
    DeviceChecks.onMain(test, () -> tag(drawer(screen), "collection:" + id).performClick());
    await(
        test,
        () -> manager.selectedCollection().equals(id) && hasTag(screen, "collection-actions:" + id),
        "Delete selection failed");
    DeviceChecks.onMain(test, () -> tag(drawer(screen), "collection-actions:" + id).performClick());
    test.waitForIdleSync();
    DeviceChecks.onMain(
        test,
        () ->
            ((AlertDialog) field(field(screen, "topicCollections"), "modal"))
                .getListView()
                .performItemClick(null, 1, 1));
    test.waitForIdleSync();
    DeviceChecks.onMain(
        test,
        () ->
            ((AlertDialog) field(field(screen, "topicCollections"), "modal"))
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick());
    await(test, () -> manager.catalog().find(id) == null, "Delete collection UI failed");
  }

  private static void key(Instrumentation test, int code) {
    test.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, code));
    test.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, code));
    test.waitForIdleSync();
    SystemClock.sleep(150);
  }

  private static void choose(AlertDialog dialog, String label) {
    ListView list = dialog.getListView();
    for (int i = 0; i < list.getCount(); i++)
      if (label.equals(list.getItemAtPosition(i))) {
        list.performItemClick(null, i, i);
        return;
      }
    throw new AssertionError("Destination choice missing");
  }

  private static boolean hasTag(MainActivity screen, String value) {
    return showing(screen)
        && drawer(screen).getWindow().getDecorView().findViewWithTag(value) != null;
  }

  private static View tag(Dialog d, String value) {
    View v = d.getWindow().getDecorView().findViewWithTag(value);
    require(v != null, "Missing row: " + value);
    return v;
  }

  private static Dialog drawer(MainActivity screen) {
    Dialog d = (Dialog) field(screen, "drawerDialog");
    require(d != null && d.isShowing(), "Drawer not shown");
    return d;
  }

  private static boolean showing(MainActivity screen) {
    Dialog d = (Dialog) field(screen, "drawerDialog");
    return d != null && d.isShowing();
  }

  private static Object field(Object owner, String name) {
    try {
      Field f = owner.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(owner);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static EditText edit(View v) {
    if (v instanceof EditText e) return e;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        EditText e = edit(g.getChildAt(i));
        if (e != null) return e;
      }
    return null;
  }

  private static int count(View v, String label) {
    int n = v instanceof TextView t && label.contentEquals(t.getText()) ? 1 : 0;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) n += count(g.getChildAt(i), label);
    return n;
  }

  private static void await(Instrumentation test, BooleanSupplier condition, String message) {
    java.util.concurrent.atomic.AtomicBoolean result =
        new java.util.concurrent.atomic.AtomicBoolean();
    long until = SystemClock.elapsedRealtime() + 8000;
    do {
      DeviceChecks.onMain(test, () -> result.set(condition.getAsBoolean()));
      if (result.get()) return;
      SystemClock.sleep(40);
    } while (SystemClock.elapsedRealtime() < until);
    throw new AssertionError(message);
  }

  private static void save(Instrumentation test, String name) throws IOException {
    test.waitForIdleSync();
    SystemClock.sleep(350);
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    require(bitmap != null, "Screenshot missing");
    try (FileOutputStream out =
        new FileOutputStream(new File(test.getTargetContext().getCacheDir(), name))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "Screenshot failed");
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
