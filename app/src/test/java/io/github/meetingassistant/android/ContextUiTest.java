package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.gson.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "zh-rCN-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContextUiTest {
  ActivityController<MainActivity> lifecycle;
  MainActivity activity;
  MeetingApp app;

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    lifecycle = Robolectric.buildActivity(MainActivity.class).setup().visible();
    activity = lifecycle.get();
    layout();
  }

  @After
  public void cleanup() {
    lifecycle.pause().stop().destroy();
    app.meetings.close();
  }

  View root() {
    return ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
  }

  void layout() {
    for (int i = 0; i < 4; i++) {
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60));
      int w = activity.getResources().getConfiguration().screenWidthDp,
          h = activity.getResources().getConfiguration().screenHeightDp;
      root()
          .measure(
              View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
      root().layout(0, 0, w, h);
    }
  }

  void warn() {
    SessionEngine.SavedState saved = app.session.saveState();
    saved.segments.add(new SpeechProvider.Segment(0, "合并升序链表，使用C++。", true, 0, 1000));
    saved.contextReport.longMeeting = true;
    saved.contextReport.notice = 1;
    saved.contextReport.input = 6000;
    saved.contextReport.before = 20000;
    saved.contextReport.budget = 12000;
    app.session.restore(saved);
    layout();
  }

  TextView text(View node, String target) {
    if (node instanceof TextView t && t.getText().toString().equals(target)) return t;
    if (node instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView t = text(group.getChildAt(i), target);
        if (t != null) return t;
      }
    return null;
  }

  void openContextSettings() {
    activity.findViewById(R.id.meetings_button).performClick();
    Dialog drawer = ShadowDialog.getLatestDialog();
    drawer.findViewById(R.id.drawer_settings).performClick();
    activity.findViewById(R.id.context_settings).performClick();
  }

  @Test
  public void noticeIsInlineDismissibleAndDoesNotNavigate() {
    String id = app.meetings.currentId();
    warn();
    assertNotNull(activity.findViewById(R.id.context_banner));
    assertTrue(activity.findViewById(R.id.meeting_status) instanceof TextView);
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    text(root(), "继续当前").performClick();
    assertNull(activity.findViewById(R.id.context_banner));
    assertEquals(id, app.meetings.currentId());
    assertEquals(View.VISIBLE, activity.findViewById(R.id.context_info).getVisibility());
  }

  @Test
  public void warningDoesNotReturnAfterActivityRecreation() {
    warn();
    assertTrue(app.session.saveState().contextNoticeConsumed > 0);
    lifecycle.recreate();
    activity = lifecycle.get();
    layout();
    assertNull(activity.findViewById(R.id.context_banner));
    assertEquals(View.VISIBLE, activity.findViewById(R.id.context_info).getVisibility());
  }

  @Test
  public void bannerExpiresAndStatusRemainsVisible() {
    warn();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
    assertNull(activity.findViewById(R.id.context_banner));
    assertEquals(View.VISIBLE, activity.findViewById(R.id.meeting_status).getVisibility());
  }

  @Test
  public void detailsShowsBoundedContextAndKeyboardCannotStartRecording() {
    warn();
    activity.findViewById(R.id.context_info).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(dialog.isShowing());
    assertNotNull(
        text(
            dialog.getWindow().getDecorView(),
            activity.getString(R.string.context_metrics, 6000, 12000, 20000)));
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE));
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE));
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
  }

  @Test
  public void newMeetingIsEmptyUnlessCarryIsChosen() {
    warn();
    String old = app.meetings.currentId();
    activity.findViewById(R.id.context_info).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertFalse(((CheckBox) dialog.findViewById(R.id.context_carry)).isChecked());
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    layout();
    assertNotEquals(old, app.meetings.currentId());
    assertEquals("", app.session.saveState().contextMemory.carry);
    assertNotNull(app.meetings.get(old));
  }

  @Test
  public void explicitCarryIsOnlyAShortExcerptAndOldMeetingRemains() {
    warn();
    String old = app.meetings.currentId();
    activity.findViewById(R.id.context_info).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    ((CheckBox) dialog.findViewById(R.id.context_carry)).setChecked(true);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    layout();
    assertTrue(app.session.saveState().contextMemory.carry.contains("合并升序链表"));
    assertEquals("", app.session.snapshot().transcript);
    assertTrue(app.meetings.get(old).current.transcript(false).contains("合并升序链表"));
  }

  @Test
  public void validatesAndSavesContextSettingsWithoutChangingModels() {
    String model = app.settings.provider.textModel;
    openContextSettings();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    ((EditText) dialog.findViewById(R.id.context_input)).setText("0");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertTrue(dialog.isShowing());
    assertEquals(12000, app.settings.context.inputBudget);
    ((EditText) dialog.findViewById(R.id.context_input)).setText("6500");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertFalse(dialog.isShowing());
    assertEquals(6500, app.repository.load().context.inputBudget);
    assertEquals(6500, app.session.snapshot().context.budget);
    assertEquals(model, app.settings.provider.textModel);
  }

  @Test
  public void migratesMissingOrInvalidLimitsWithoutDiscardingOtherSettings() {
    UserSettings settings = UserSettings.initial();
    settings.find("algorithm").name = "我的名字";
    settings.provider.textModel = "mock-model";
    JsonObject json = new Gson().toJsonTree(settings).getAsJsonObject();
    json.remove("context");
    app.getSharedPreferences("context-migration", 0)
        .edit()
        .putString("settings", json.toString())
        .commit();
    SettingsRepository repo = new SettingsRepository(app, "context-migration");
    UserSettings loaded = repo.load();
    assertEquals(12000, loaded.context.inputBudget);
    assertEquals("我的名字", loaded.find("algorithm").name);
    loaded.context.inputBudget = -1;
    repo.save(loaded);
    assertEquals(12000, repo.load().context.inputBudget);
    assertEquals("mock-model", repo.load().provider.textModel);
  }

  @Test
  @Config(qualifiers = "en-rUS-w393dp-h852dp-port-mdpi")
  public void englishNoticeDetailsAndSettingsAreLocalized() {
    warn();
    assertNotNull(text(root(), activity.getString(R.string.context_length_notice)));
    assertTrue(activity.getString(R.string.context_length_notice).startsWith("This topic"));
    activity.findViewById(R.id.context_info).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(
        ((CheckBox) dialog.findViewById(R.id.context_carry))
            .getText()
            .toString()
            .startsWith("Carry"));
    LocalizedMessages messages = new LocalizedMessages(activity);
    assertEquals(
        activity.getString(R.string.context_text_error),
        messages.notice("最新原文超过上下文预算，请分段处理或提高预算；完整原文已保留"));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
  public void landscapeNoticeUsesLessThanHalfTheScreen() {
    warn();
    View banner = activity.findViewById(R.id.context_banner);
    assertTrue(banner.getHeight() > 0);
    assertTrue(banner.getHeight() < root().getHeight() / 2);
    activity.findViewById(R.id.context_info).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w840dp-h730dp-mdpi")
  public void foldLayoutKeepsStatusAndContextActionAvailable() {
    warn();
    assertNotNull(activity.findViewById(R.id.answer_panel));
    assertEquals(View.VISIBLE, activity.findViewById(R.id.context_info).getVisibility());
    assertTrue(activity.findViewById(R.id.context_banner).getHeight() < root().getHeight() / 2);
  }
}
