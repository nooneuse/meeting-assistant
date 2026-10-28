package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "zh-rCN-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TopicCollectionsUiTest {
  MeetingApp app;
  MainActivity screen;
  ActivityController<MainActivity> lifecycle;

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    lifecycle = Robolectric.buildActivity(MainActivity.class).setup().visible();
    screen = lifecycle.get();
    idle();
  }

  @After
  public void close() {
    lifecycle.pause().stop().destroy();
    app.meetings.close();
  }

  void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
  }

  void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    do {
      idle();
      if (condition.getAsBoolean()) return;
      Thread.sleep(10);
    } while (System.nanoTime() < until);
    fail("UI condition did not settle");
  }

  Dialog drawer() {
    screen.findViewById(R.id.meetings_button).performClick();
    idle();
    return ShadowDialog.getLatestDialog();
  }

  View tag(Dialog d, String tag) {
    return d.getWindow().getDecorView().findViewWithTag(tag);
  }

  String folder(String name) throws Exception {
    CompletableFuture<String> result = new CompletableFuture<>();
    app.meetings.createCollection(name, result::complete);
    String id = result.get(3, TimeUnit.SECONDS);
    assertNotNull(id);
    idle();
    return id;
  }

  void select(String id) {
    app.meetings.switchTo(app.meetings.collectionTarget(id, false, app.settings.snapshot(), false));
    idle();
  }

  String topic() {
    String id = app.meetings.create(app.settings.snapshot(), false);
    app.meetings.switchTo(id);
    idle();
    return id;
  }

  TextView find(View root, String text) {
    if (root instanceof TextView t && text.contentEquals(t.getText())) return t;
    if (root instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView t = find(g.getChildAt(i), text);
        if (t != null) return t;
      }
    return null;
  }

  EditText edit(View v) {
    if (v instanceof EditText e) return e;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        EditText e = edit(g.getChildAt(i));
        if (e != null) return e;
      }
    return null;
  }

  int count(View v, String text) {
    int n = v instanceof TextView t && text.contentEquals(t.getText()) ? 1 : 0;
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) n += count(g.getChildAt(i), text);
    return n;
  }

  void key(int code) {
    screen.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
    screen.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    idle();
  }

  @Test
  public void createCollectionFromIconSelectsPreparationAndOnlyTopNewTopicExists()
      throws Exception {
    Dialog d = drawer();
    d.findViewById(R.id.drawer_new_collection).performClick();
    idle();
    AlertDialog name = ShadowAlertDialog.getLatestAlertDialog();
    edit(name.getWindow().getDecorView()).setText("算法讨论会");
    name.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    await(() -> !app.meetings.selectedCollection().isEmpty());
    String id = app.meetings.selectedCollection();
    assertEquals("算法讨论会", app.meetings.catalog().find(id).name);
    assertTrue(app.meetings.current().draft);
    assertTrue(app.meetings.summaries().isEmpty());
    assertEquals(
        1, count(d.getWindow().getDecorView(), screen.getString(R.string.new_meeting_action)));
    assertNotNull(tag(d, "collection-actions:" + id));
    assertNull(tag(d, "collection-actions:" + TopicCatalog.DEFAULT));
    d.findViewById(R.id.drawer_new_meeting).performClick();
    idle();
    assertEquals(id, app.meetings.current().collectionId);
    assertFalse(app.meetings.current().draft);
    assertFalse(app.session.snapshot().busy());
  }

  @Test
  public void expansionDoesNotSelectAndSelectedFolderAndTopicAloneHaveMenus() throws Exception {
    String a = folder("A"), b = folder("B");
    select(a);
    String first = topic(), second = topic();
    Dialog d = drawer();
    String current = app.meetings.currentId();
    tag(d, "expand:" + b).performClick();
    idle();
    assertEquals(a, app.meetings.selectedCollection());
    assertEquals(current, app.meetings.currentId());
    assertNotNull(tag(d, "collection-actions:" + a));
    assertNull(tag(d, "collection-actions:" + b));
    assertEquals(2, ((ViewGroup) tag(d, second).getParent()).getChildCount());
    assertEquals(1, ((ViewGroup) tag(d, first).getParent()).getChildCount());
    tag(d, "collection:" + b).performClick();
    idle();
    assertEquals(b, app.meetings.selectedCollection());
    assertTrue(app.meetings.current().draft);
    assertEquals("", app.meetings.answer());
  }

  @Test
  public void keyboardTopicNavigationStaysInsideFolderAndCollectionKeysRestoreRememberedTopic()
      throws Exception {
    String a = folder("A"), b = folder("B");
    select(a);
    topic();
    topic();
    select(b);
    String bTopic = topic();
    app.settings.bind(UserSettings.MEETING_PREVIOUS, KeyEvent.KEYCODE_F6);
    app.settings.bind(UserSettings.MEETING_NEXT, KeyEvent.KEYCODE_F7);
    app.settings.bind(UserSettings.COLLECTION_PREVIOUS, KeyEvent.KEYCODE_F8);
    app.settings.bind(UserSettings.MEETING_NEW, KeyEvent.KEYCODE_F9);
    key(KeyEvent.KEYCODE_F6);
    assertEquals(bTopic, app.meetings.currentId());
    key(KeyEvent.KEYCODE_F7);
    assertEquals(bTopic, app.meetings.currentId());
    select(TopicCatalog.DEFAULT);
    List<TopicCatalog.Collection> ordered = app.meetings.catalog().ordered();
    String previous = ordered.get(ordered.size() - 2).id;
    String remembered = app.meetings.catalog().remembered.get(previous);
    key(KeyEvent.KEYCODE_F8);
    assertEquals(previous, app.meetings.selectedCollection());
    assertEquals(remembered, app.meetings.currentId());
    key(KeyEvent.KEYCODE_F9);
    assertEquals(previous, app.meetings.current().collectionId);
    assertFalse(app.settings.bindings.containsKey(UserSettings.COLLECTION_NEXT));
  }

  @Test
  public void moveThroughTopicMenuKeepsAnswerAndSelectsDestinationCollection() throws Exception {
    String a = folder("A"), b = folder("B");
    select(a);
    String id = topic();
    SessionEngine.SavedState state = app.session.saveState();
    state.answer = "保留答案";
    app.session.restore(state);
    idle();
    Dialog d = drawer();
    ((ViewGroup) tag(d, id).getParent()).getChildAt(1).performClick();
    idle();
    AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
    menu.getListView().performItemClick(null, 2, 2);
    idle();
    AlertDialog choices = ShadowAlertDialog.getLatestAlertDialog();
    int index = -1;
    for (int i = 0; i < choices.getListView().getCount(); i++)
      if ("B".equals(choices.getListView().getItemAtPosition(i))) index = i;
    assertTrue(index >= 0);
    choices.getListView().performItemClick(null, index, index);
    await(() -> app.meetings.get(id).collectionId.equals(b));
    assertEquals(b, app.meetings.selectedCollection());
    assertEquals("保留答案", app.meetings.answer());
  }

  @Test
  public void folderOrderDoesNotJumpWhileOpenAndRefreshesWhenReopened() throws Exception {
    String a = folder("A"), b = folder("B");
    select(a);
    topic();
    Dialog d = drawer();
    ViewGroup host = (ViewGroup) tag(d, "collection:" + a).getParent().getParent();
    int before = host.indexOfChild((View) tag(d, "collection:" + a).getParent());
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    app.meetings.renameCollection(a, "A renamed", result::complete);
    assertTrue(result.get(3, TimeUnit.SECONDS));
    idle();
    assertEquals(before, host.indexOfChild((View) tag(d, "collection:" + a).getParent()));
    assertNotNull(find(d.getWindow().getDecorView(), "A renamed"));
    capture(d, "topic-collections-portrait");
    d.dismiss();
    idle();
    d = drawer();
    host = (ViewGroup) tag(d, "collection:" + a).getParent().getParent();
    assertTrue(
        host.indexOfChild((View) tag(d, "collection:" + a).getParent())
            < host.indexOfChild((View) tag(d, "collection:" + b).getParent()));
    assertTrue(
        host.indexOfChild((View) tag(d, "collection:" + TopicCatalog.DEFAULT).getParent())
            > host.indexOfChild((View) tag(d, "collection:" + b).getParent()));
  }

  @Test
  @Config(qualifiers = "en-rUS-w840dp-h900dp-port-mdpi")
  public void englishFoldableShowsLocalizedFoldersAndSingleCreationButton() throws Exception {
    String a = folder("Algorithms");
    select(a);
    topic();
    Dialog d = drawer();
    assertNotNull(find(d.getWindow().getDecorView(), "Today"));
    assertNotNull(find(d.getWindow().getDecorView(), "Default collection"));
    assertEquals(1, count(d.getWindow().getDecorView(), "＋ New topic"));
    capture(d, "topic-collections-foldable-en");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
  public void landscapeDrawerKeepsFooterAndScrollableCollectionTree() throws Exception {
    String a = folder("算法讨论会");
    select(a);
    for (int i = 0; i < 8; i++) topic();
    Dialog d = drawer();
    assertNotNull(d.findViewById(R.id.drawer_settings));
    assertEquals(1, count(d.getWindow().getDecorView(), "＋ 新建议题"));
    capture(d, "topic-collections-landscape");
  }

  @Test
  public void collectionDeletionDialogShowsCountAndReturnsToDefault() throws Exception {
    String a = folder("Delete fixture");
    select(a);
    String id = topic();
    Dialog d = drawer();
    tag(d, "collection-actions:" + a).performClick();
    idle();
    ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 1, 1);
    idle();
    AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(
        find(
            confirm.getWindow().getDecorView(),
            screen
                .getResources()
                .getQuantityString(R.plurals.collection_delete_detail, 1, "Delete fixture", 1)));
    assertNotNull(confirm.getButton(AlertDialog.BUTTON_NEUTRAL));
    confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    await(() -> app.meetings.catalog().find(a) == null);
    assertNull(app.meetings.get(id));
    assertEquals(TopicCatalog.DEFAULT, app.meetings.selectedCollection());
    assertNull(tag(d, "collection:" + a));
    assertNotNull(tag(d, "collection:" + TopicCatalog.DEFAULT));
  }

  @Test
  public void modifiedDateSectionsExcludeDefaultAndUseLocalCalendar() throws Exception {
    lifecycle.pause().stop().destroy();
    app.meetings.close();
    TopicCatalog c = new TopicCatalog();
    java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
    for (int days : new int[] {0, 1, 4, 40}) {
      TopicCatalog.Collection item = new TopicCatalog.Collection();
      item.id = "fixture-" + days;
      item.name = "Collection " + days;
      item.updatedAt = now.minusDays(days).toInstant().toEpochMilli();
      c.collections.add(item);
      c.expanded.add(item.id);
    }
    MeetingManager.MemoryStore store = new MeetingManager.MemoryStore();
    store.saveCatalog(c);
    app.bindMeetings(
        new MeetingManager(
            store,
            () ->
                new SessionEngine(
                    new SessionEngine.Factory() {
                      public AudioSource audio() {
                        throw new AssertionError("unexpected audio");
                      }

                      public SpeechProvider speech(ProviderConfig p, ProviderConfig.Credentials k) {
                        throw new AssertionError("unexpected ASR");
                      }

                      public TextProvider text(ProviderConfig p, ProviderConfig.Credentials k) {
                        throw new AssertionError("unexpected model");
                      }
                    }),
            app.settings.snapshot(),
            false));
    lifecycle = Robolectric.buildActivity(MainActivity.class).setup().visible();
    screen = lifecycle.get();
    idle();
    Dialog d = drawer();
    View root = d.getWindow().getDecorView();
    assertNotNull(find(root, "今天"));
    assertNotNull(find(root, "昨天"));
    assertNotNull(find(root, "近 7 天"));
    assertNotNull(
        find(
            root,
            now.minusDays(40).format(java.time.format.DateTimeFormatter.ofPattern("yyyy 年 M 月"))));
    ViewGroup host =
        (ViewGroup) tag(d, "collection:" + TopicCatalog.DEFAULT).getParent().getParent();
    assertTrue(
        host.indexOfChild((View) tag(d, "collection:" + TopicCatalog.DEFAULT).getParent())
            > host.indexOfChild((View) tag(d, "collection:fixture-40").getParent()));
    capture(d, "topic-collections-date-sections");
  }

  void capture(Dialog d, String name) throws Exception {
    int width =
        Math.min(360, (int) (screen.getResources().getConfiguration().screenWidthDp * .86f));
    int height = screen.getResources().getConfiguration().screenHeightDp;
    View v = d.getWindow().getDecorView();
    v.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    v.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    v.draw(new Canvas(bitmap));
    File file = new File(System.getProperty("meeting.preview.dir"), name + ".png");
    file.getParentFile().mkdirs();
    try (FileOutputStream out = new FileOutputStream(file)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }
}
