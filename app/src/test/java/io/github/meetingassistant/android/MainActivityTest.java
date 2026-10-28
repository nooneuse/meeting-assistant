package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.AudioSource;
import io.github.meetingassistant.core.ProviderConfig;
import io.github.meetingassistant.core.SessionEngine;
import io.github.meetingassistant.core.SpeechProvider;
import io.github.meetingassistant.core.TextProvider;
import io.github.meetingassistant.core.UserSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "zh-rCN-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {
  ActivityController<MainActivity> controller;
  MainActivity activity;
  MeetingApp app;

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
    activity = controller.get();
    Configuration c = activity.getResources().getConfiguration();
    layout(c.screenWidthDp, c.screenHeightDp);
  }

  @After
  public void teardown() {
    controller.pause().stop().destroy();
    app.meetings.close();
  }

  View root() {
    return ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
  }

  void layout(int w, int h) {
    for (int i = 0; i < 4; i++) {
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60));
      root()
          .measure(
              View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
      root().layout(0, 0, w, h);
    }
  }

  TextView find(String text) {
    return findText(root(), text);
  }

  TextView findText(View v, String text) {
    if (v instanceof TextView && ((TextView) v).getText().toString().equals(text))
      return (TextView) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView result = findText(g.getChildAt(i), text);
        if (result != null) return result;
      }
    }
    return null;
  }

  void openSettings() {
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    assertNotNull(drawer.findViewById(R.id.drawer_new_meeting));
    drawer.findViewById(R.id.drawer_settings).performClick();
  }

  void key(int code, int repeat) {
    activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat));
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
  }

  @Test
  public void portraitControlsAndSettingsAreReachable() throws Exception {
    assertNotNull(find("开始听"));
    assertTrue(activity.findViewById(R.id.auto_button).getRight() < find("开始听").getLeft());
    assertNotNull(find("听写原文"));
    assertNull(activity.findViewById(R.id.more_button));
    assertNotNull(activity.findViewById(R.id.copy_answer));
    assertNull(find("会议助手 ▾"));
    TextView pref = activity.findViewById(R.id.meeting_preference);
    assertEquals("算法讨论", pref.getText().toString());
    assertEquals(18f, pref.getTextSize(), .01f);
    assertNull(find("答案与提示"));
    assertEquals(
        AppTheme.color(activity, AppTheme.SURFACE),
        ((android.graphics.drawable.ColorDrawable)
                activity.findViewById(R.id.answer_panel).getBackground())
            .getColor());
    assertEquals(Gravity.START | Gravity.CENTER_VERTICAL, pref.getGravity());
    key(KeyEvent.KEYCODE_0, 0);
    assertEquals("未使用偏好", pref.getText().toString());
    key(KeyEvent.KEYCODE_1, 0);
    for (int id : new int[] {R.id.awake_button, R.id.meetings_button, R.id.copy_answer}) {
      View icon = activity.findViewById(id);
      assertEquals(48, icon.getWidth());
      assertEquals(icon.getWidth(), icon.getHeight());
      assertNotNull(icon.getContentDescription());
    }
    assertTrue(find("开始听").getHeight() >= 48);
    assertTrue(((TextView) activity.findViewById(R.id.meeting_status)).getHeight() > 0);
    screenshot("portrait");
    openSettings();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    layout(393, 852);
    assertNotNull(find("保存模型设置"));
    assertNotNull(find("启用主界面快捷键"));
    key(KeyEvent.KEYCODE_L, 0);
    key(KeyEvent.KEYCODE_SPACE, 0);
    assertFalse(app.awake);
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    screenshot("settings");
  }

  @Test
  public void awakeToggleSuppressesRepeatsAndDoesNotCaptureInSettings() {
    ImageButton bulb = activity.findViewById(R.id.awake_button);
    assertEquals(0x00000000, bulbFill(bulb));
    assertEquals(
        AppTheme.color(activity, AppTheme.TEXT), bulb.getImageTintList().getDefaultColor());
    key(KeyEvent.KEYCODE_L, 0);
    assertTrue(app.awake);
    assertTrue(bulb.isSelected());
    assertEquals("关闭屏幕常亮", bulb.getContentDescription());
    assertEquals(AppTheme.color(activity, AppTheme.PRIMARY), bulbFill(bulb));
    assertEquals(
        AppTheme.color(activity, AppTheme.PRIMARY), bulb.getImageTintList().getDefaultColor());
    assertTrue(
        (activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            != 0);
    key(KeyEvent.KEYCODE_L, 1);
    assertTrue(app.awake);
    key(KeyEvent.KEYCODE_L, 0);
    assertFalse(app.awake);
    app.settings.selectedId = "finance";
    key(KeyEvent.KEYCODE_0, 0);
    assertEquals("", app.settings.selectedId);
    key(KeyEvent.KEYCODE_1, 0);
    assertEquals("algorithm", app.settings.selectedId);
  }

  @Test
  public void preferenceNameOpensChooserAndDialogPinsToolbar() {
    activity.findViewById(R.id.meeting_preference).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(dialog.isShowing());
    assertEquals(3, dialog.getListView().getCount());
    waitMs(5000);
    assertBarsVisibility(View.VISIBLE);
    dialog.getListView().performItemClick(null, 2, 2);
    assertFalse(dialog.isShowing());
    assertEquals("finance", app.settings.selectedId);
    assertEquals("金融会议", ((TextView) activity.findViewById(R.id.meeting_preference)).getText());
    assertEquals("finance", new SettingsRepository(activity).load().selectedId);
    waitMs(4500);
    assertBarsVisibility(View.GONE);
  }

  @Test
  public void chooserSupportsEmptyListAndSingleModeKeepsFrozenPreference() {
    FakeFactory f = fakeSession();
    f.listener.ready();
    activity.findViewById(R.id.meeting_preference).performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    dialog.getListView().performItemClick(null, 2, 2);
    waitMs(200);
    assertTrue(
        ((TextView) activity.findViewById(R.id.meeting_preference))
            .getText()
            .toString()
            .contains("本轮：算法讨论 · 下轮：金融会议"));
    app.session.cancel();
    app.settings.profiles.clear();
    activity.findViewById(R.id.meeting_preference).performClick();
    dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(1, dialog.getListView().getCount());
    dialog.getListView().performItemClick(null, 0, 0);
    assertEquals("", app.settings.selectedId);
  }

  @Test
  public void autoButtonPersistsSynchronizesAndKeyboardRepeatIsSuppressed() {
    View auto = activity.findViewById(R.id.auto_button);
    assertFalse(auto.isSelected());
    assertFalse(app.settings.bindings.containsKey(UserSettings.AUTO));
    auto.performClick();
    waitMs(100);
    assertTrue(auto.isSelected());
    assertEquals(
        AppTheme.color(activity, AppTheme.AUTO),
        ((android.graphics.drawable.GradientDrawable)
                ((android.graphics.drawable.RippleDrawable) auto.getBackground()).getDrawable(0))
            .getColor()
            .getDefaultColor());
    assertTrue(app.session.snapshot().automatic);
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    assertTrue(new SettingsRepository(activity).load().automatic);
    app.settings.bind(UserSettings.AUTO, KeyEvent.KEYCODE_A);
    key(KeyEvent.KEYCODE_A, 1);
    assertTrue(app.settings.automatic);
    key(KeyEvent.KEYCODE_A, 0);
    assertFalse(app.settings.automatic);
    openSettings();
    waitMs(100);
    CheckBox check = (CheckBox) find("自动模式");
    assertFalse(check.isChecked());
    check.performClick();
    assertTrue(app.settings.automatic);
    activity.findViewById(R.id.settings_back).performClick();
    waitMs(100);
    assertTrue(activity.findViewById(R.id.auto_button).isSelected());
    waitMs(4500);
    assertBarsVisibility(View.GONE);
    key(KeyEvent.KEYCODE_A, 0);
    assertFalse(app.settings.automatic);
  }

  @Test
  public void automaticGenerationKeepsStopEnabledAndHoldsUpdatesWhileSelecting() throws Exception {
    FakeFactory f = fakeSession();
    f.listener.ready();
    activity.findViewById(R.id.auto_button).performClick();
    f.listener.segment(new SpeechProvider.Segment(0, "如何合并两个链表？", true, 0, 2000));
    TextProvider.Listener first = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(first);
    waitMs(1500);
    assertEquals(SessionEngine.State.LISTENING, app.session.snapshot().state);
    assertTrue(find("停止听").isEnabled());
    assertBarsVisibility(View.GONE);
    TextView status = activity.findViewById(R.id.meeting_status);
    assertTrue(status.isShown());
    assertTrue(status.getText().toString().contains("聆听中"));
    assertTrue(status.getText().toString().contains("更新中"));
    first.delta("第一版答案。\n".repeat(100));
    first.completed(false);
    waitMs(100);
    // Main-screen selection affects the next automatic request, without ending speech.
    activity.findViewById(R.id.meeting_preference).performClick();
    AlertDialog preferences = ShadowAlertDialog.getLatestAlertDialog();
    preferences.getListView().performItemClick(null, 2, 2);
    assertEquals("finance", app.settings.selectedId);
    // Selection and upward reading hold complete revisions; stopping bypasses the live debounce.
    activity.onActionModeStarted(null);
    f.listener.segment(new SpeechProvider.Segment(1, "补充为有序链表", true, 2000, 4000));
    key(KeyEvent.KEYCODE_SPACE, 0);
    f.listener.completed();
    TextProvider.Listener second = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(
        "state="
            + app.session.snapshot().state
            + "; analysis="
            + app.session.snapshot().analysisState
            + "; auto="
            + app.session.snapshot().automatic
            + "; status="
            + app.session.snapshot().status,
        second);
    assertTrue(f.textMessages.toString().contains("负债情况"));
    second.delta("新版本答案");
    second.completed(false);
    waitMs(200);
    assertTrue(app.session.snapshot().hasUpdate);
    assertTrue(activity.findViewById(R.id.meeting_status).isClickable());
    assertTrue(
        ((TextView) activity.findViewById(R.id.meeting_status))
            .getText()
            .toString()
            .contains("有更新 · 点击查看"));
    assertNotNull(find(Markdown.render(activity, "第一版答案。\n".repeat(100)).toString()));
    activity.onActionModeFinished(null);
    waitMs(200);
    assertFalse(app.session.snapshot().hasUpdate);
    assertNotNull(find(Markdown.render(activity, "新版本答案").toString()));
    assertEquals(1, f.audioStarts);
    screenshot("automatic-completed");
  }

  @Test
  public void drawerCreatesAndSwitchesMeetingsWithoutStartingMicrophone() throws Exception {
    String original = app.meetings.currentId();
    app.meetings.rename(original, "旧会议");
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    waitMs(5000);
    assertBarsVisibility(View.VISIBLE);
    key(KeyEvent.KEYCODE_SPACE, 0);
    assertFalse(app.session.snapshot().busy());
    drawer.findViewById(R.id.drawer_new_meeting).performClick();
    waitMs(200);
    String created = app.meetings.currentId();
    assertNotEquals(original, created);
    assertEquals(2, app.meetings.list().size());
    assertFalse(app.session.snapshot().busy());
    assertEquals("", app.meetings.transcript(true));
    app.meetings.rename(original, "旧会议");
    activity.findViewById(R.id.meetings_button).performClick();
    drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    View choose = drawer.getWindow().getDecorView().findViewWithTag(original);
    assertNotNull(choose);
    choose.performClick();
    waitMs(200);
    assertEquals(original, app.meetings.currentId());
    activity.findViewById(R.id.meetings_button).performClick();
    drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    screenshotDialog(drawer, "meetings-drawer-portrait");
    ViewGroup decor = (ViewGroup) drawer.getWindow().getDecorView();
    ViewGroup selected = (ViewGroup) decor.findViewWithTag(original).getParent();
    ViewGroup unselected = (ViewGroup) decor.findViewWithTag(created).getParent();
    assertEquals(2, selected.getChildCount());
    assertTrue(selected.getChildAt(1) instanceof ImageButton);
    assertEquals(48, selected.getChildAt(1).getWidth());
    assertEquals(1, unselected.getChildCount());
    assertNotNull(selected.getBackground());
    assertTrue(selected.getClipToOutline());
    assertEquals(
        selected.getRight() - selected.getLeft(),
        selected.getChildAt(0).getWidth() + selected.getChildAt(1).getWidth());
    assertNull(findText(decor, "会议"));
    drawer.dismiss();
  }

  @Test
  public void listeningSwitchRequiresConfirmationAndKeepsTail() {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "正在讨论", false, 0, -1));
    String source = app.meetings.currentId();
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    drawer.findViewById(R.id.drawer_new_meeting).performClick();
    AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(confirm.isShowing());
    assertEquals(SessionEngine.State.LISTENING, app.session.snapshot().state);
    confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    waitMs(100);
    assertEquals(source, app.meetings.currentId());
    assertTrue(app.meetings.navigating());
    assertEquals(SessionEngine.State.FINALIZING, app.session.snapshot().state);
    f.listener.segment(new SpeechProvider.Segment(1, "正在讨论的尾句", true, 0, 1500));
    f.listener.completed();
    waitMs(200);
    assertNotEquals(source, app.meetings.currentId());
    assertEquals("", app.meetings.transcript(true));
    assertTrue(app.meetings.get(source).current.transcript(false).contains("尾句"));
  }

  @Test
  public void copyTranscriptIncludesEarlierRoundsAndMarksPartialText() throws Exception {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "先前定稿", true, 0, 1000));
    app.session.stop();
    f.listener.completed();
    TextProvider.Listener text = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(text);
    text.delta("第一轮答案");
    text.completed(false);
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    app.session.start(c, new ProviderConfig.Credentials("test", "test"), app.settings.snapshot());
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(2, "当前临时原文", false, 0, -1));
    waitMs(100);
    activity.findViewById(R.id.transcript_toggle).performClick();
    waitMs(100);
    activity.findViewById(R.id.copy_transcript).performClick();
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager)
            activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
    assertTrue(copied.contains("先前定稿"));
    assertTrue(copied.contains("当前临时原文"));
    assertTrue(copied.contains("[未定稿]"));
  }

  @Test
  public void continueKeepsLastAnswerAndHistoryCanOpenPreviousRound() throws Exception {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "第一轮问题", true, 0, 1000));
    app.session.stop();
    f.listener.completed();
    TextProvider.Listener first = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(first);
    first.delta("旧答案");
    first.completed(false);
    waitMs(100);
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    app.session.start(c, new ProviderConfig.Credentials("test", "test"), app.settings.snapshot());
    f.listener.ready();
    waitMs(100);
    assertNotNull(find(Markdown.render(activity, "旧答案").toString()));
    f.listener.segment(new SpeechProvider.Segment(1, "第二轮问题", true, 0, 1000));
    app.session.stop();
    f.listener.completed();
    TextProvider.Listener second = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(second);
    second.delta("新答案");
    second.completed(false);
    waitMs(100);
    assertTrue(activity.findViewById(R.id.answer_history).isShown());
    activity.findViewById(R.id.answer_history).performClick();
    AlertDialog history = ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(2, history.getListView().getCount());
    history.getListView().performItemClick(null, 0, 0);
    AlertDialog detail = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(
        findText(
            detail.getWindow().getDecorView(),
            Markdown.render(activity, "当时偏好：算法讨论\n\n旧答案\n\n--- 原文 ---\n第一轮问题").toString()));
    detail.dismiss();
  }

  @Test
  public void oldMeetingCallbacksCannotPaintTheNextMeetingDuringSelectionDispatch()
      throws Exception {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "原会议问题", true, 0, 1000));
    app.session.stop();
    f.listener.completed();
    TextProvider.Listener source = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(source);
    source.delta("原会议已有答案");
    waitMs(100);
    String target = app.meetings.create(app.settings.snapshot(), false);
    app.meetings.switchTo(target);
    source.delta("不应闪现的后台补充");
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
    assertNotNull(find(Markdown.render(activity, "原会议已有答案").toString()));
    waitMs(200);
    assertEquals(target, app.meetings.currentId());
    assertNull(find(Markdown.render(activity, "原会议已有答案不应闪现的后台补充").toString()));
    assertEquals("", app.meetings.answer());
    source.completed(false);
  }

  @Test
  public void deletingCurrentMeetingThroughDrawerPreservesTheOtherMeeting() {
    String first = app.meetings.currentId();
    app.meetings.rename(first, "保留的会议");
    String second = app.meetings.create(app.settings.snapshot(), false);
    app.meetings.switchTo(second);
    waitMs(200);
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    View item = drawer.getWindow().getDecorView().findViewWithTag(second);
    assertNotNull(item);
    item.performLongClick();
    AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
    actions.getListView().performItemClick(null, 1, 1);
    AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
    confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    waitMs(100);
    app.meetings.awaitWrites();
    waitMs(200);
    assertNull(app.meetings.get(second));
    assertNotNull(app.meetings.get(first));
    assertNotEquals(second, app.meetings.currentId());
    assertEquals(first, app.meetings.currentId());
    assertEquals(1, app.meetings.list().size());
    drawer.dismiss();
  }

  @Test
  public void deletingLastNewMeetingThroughSelectedMoreShowsEmptyHistory() {
    String id = app.meetings.create(app.settings.snapshot(), false);
    app.meetings.switchTo(id);
    waitMs(200);
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    View item = drawer.getWindow().getDecorView().findViewWithTag(id);
    ((ViewGroup) item.getParent()).getChildAt(1).performClick();
    AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
    actions.getListView().performItemClick(null, 1, 1);
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    app.meetings.awaitWrites();
    waitMs(200);
    assertNull(app.meetings.get(id));
    assertTrue(app.meetings.summaries().isEmpty());
    assertNotNull(findText(drawer.getWindow().getDecorView(), "暂无议题记录"));
    assertNull(drawer.getWindow().getDecorView().findViewWithTag(id));
    assertTrue(app.meetings.current().draft);
    assertFalse(app.session.snapshot().busy());
    assertEquals("议题已删除", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    assertFalse(
        Shadows.shadowOf(org.robolectric.shadows.ShadowToast.getLatestToast()).isCancelled());
    drawer.dismiss();
  }

  @Test
  public void autoAndAwakeUseShortToastsAndReplacePreviousHint() {
    activity.findViewById(R.id.awake_button).performClick();
    android.widget.Toast first = org.robolectric.shadows.ShadowToast.getLatestToast();
    assertEquals("保持屏幕常亮", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    assertEquals(android.widget.Toast.LENGTH_SHORT, first.getDuration());
    activity.findViewById(R.id.awake_button).performClick();
    assertEquals("屏幕正常熄灭", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    assertNotSame(first, org.robolectric.shadows.ShadowToast.getLatestToast());
    assertTrue(Shadows.shadowOf(first).isCancelled());
    activity.findViewById(R.id.auto_button).performClick();
    assertEquals("自动分段", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    activity.findViewById(R.id.auto_button).performClick();
    assertEquals("手动分段", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    assertEquals(
        android.widget.Toast.LENGTH_SHORT,
        org.robolectric.shadows.ShadowToast.getLatestToast().getDuration());
    assertFalse(app.session.snapshot().busy());
    assertNull(Shadows.shadowOf(app).getLatestPopupWindow());
    openSettings();
    assertTrue(
        Shadows.shadowOf(org.robolectric.shadows.ShadowToast.getLatestToast()).isCancelled());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
  public void landscapeDrawerKeepsNewMeetingAndSettingsReachable() throws Exception {
    verifyDrawerBounds("meetings-drawer-landscape");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w840dp-h900dp-port-mdpi")
  public void foldableDrawerKeepsACompactOverlay() throws Exception {
    verifyDrawerBounds("meetings-drawer-foldable");
  }

  void verifyDrawerBounds(String name) throws Exception {
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    screenshotDialog(drawer, name);
    View decor = drawer.getWindow().getDecorView();
    View settings = drawer.findViewById(R.id.drawer_settings);
    assertTrue(decor.getWidth() <= 360);
    assertTrue(settings.getHeight() >= 48);
    assertTrue(settings.getBottom() <= decor.getHeight());
    assertNotNull(drawer.findViewById(R.id.drawer_new_meeting));
    drawer.dismiss();
  }

  void screenshotDialog(android.app.Dialog dialog, String name) throws Exception {
    View decor = dialog.getWindow().getDecorView();
    int width = Math.min(360, (int) (root().getWidth() * .86));
    int height = root().getHeight();
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    decor.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    decor.draw(new Canvas(bitmap));
    File dir = new File(System.getProperty("meeting.preview.dir"));
    assertTrue(dir.isDirectory() || dir.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  int bulbFill(ImageButton bulb) {
    Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
    bulb.getDrawable().setBounds(0, 0, 24, 24);
    bulb.getDrawable().draw(new Canvas(bitmap));
    int fill = bitmap.getPixel(12, 8);
    bitmap.recycle();
    return fill;
  }

  FakeFactory fakeSession() {
    app.meetings.close();
    FakeFactory f = new FakeFactory();
    app.bindMeetings(
        new io.github.meetingassistant.core.MeetingManager(
            new io.github.meetingassistant.core.MeetingManager.MemoryStore(),
            () -> new SessionEngine(f),
            app.settings.snapshot(),
            app.settings.automatic));
    controller.recreate();
    activity = controller.get();
    layoutCurrent();
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    app.session.start(c, new ProviderConfig.Credentials("test", "test"), app.settings.snapshot());
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    return f;
  }

  void waitMs(int milliseconds) {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    layoutCurrent();
  }

  void layoutCurrent() {
    Configuration c = activity.getResources().getConfiguration();
    layout(c.screenWidthDp, c.screenHeightDp);
  }

  void gesture(boolean drag, boolean longPress) {
    long start = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 220, 250, 0);
    activity.dispatchTouchEvent(down);
    down.recycle();
    if (drag) {
      MotionEvent move =
          MotionEvent.obtain(start, start + 40, MotionEvent.ACTION_MOVE, 220, 350, 0);
      activity.dispatchTouchEvent(move);
      move.recycle();
    }
    MotionEvent up =
        MotionEvent.obtain(
            start, start + (longPress ? 700 : 80), MotionEvent.ACTION_UP, 220, drag ? 350 : 250, 0);
    activity.dispatchTouchEvent(up);
    up.recycle();
  }

  boolean isDescendant(View parent, View child) {
    for (android.view.ViewParent p = child.getParent(); p != null; p = p.getParent())
      if (p == parent) return true;
    return false;
  }

  void assertBarsVisibility(int expected) {
    assertEquals(expected, activity.findViewById(R.id.meeting_toolbar).getVisibility());
    assertEquals(expected, activity.findViewById(R.id.meeting_controls).getVisibility());
  }

  @Test
  public void idleBarsCollapseTogetherAndTapReturnsBoth() throws Exception {
    int expanded = activity.findViewById(R.id.meeting_content).getHeight();
    waitMs(3000);
    assertBarsVisibility(View.VISIBLE);
    waitMs(1500);
    assertBarsVisibility(View.GONE);
    assertFalse(find("开始听").isShown());
    TextView status = activity.findViewById(R.id.meeting_status);
    assertTrue(status.isShown());
    assertEquals("准备就绪", status.getText().toString());
    assertFalse(isDescendant(activity.findViewById(R.id.meeting_toolbar), status));
    assertEquals(expanded + 116, activity.findViewById(R.id.meeting_content).getHeight());
    screenshot("portrait-idle-bars-hidden");
    gesture(false, false);
    waitMs(400);
    assertBarsVisibility(View.VISIBLE);
    assertTrue(find("开始听").isShown());
    assertTrue(activity.findViewById(R.id.transcript_toggle).isShown());
    waitMs(4500);
    assertBarsVisibility(View.GONE);
  }

  @Test
  public void keyboardScrollAndStopWorkWhileBothBarsHidden() {
    FakeFactory f = fakeSession();
    f.listener.ready();
    waitMs(1600);
    assertBarsVisibility(View.GONE);
    key(KeyEvent.KEYCODE_DPAD_DOWN, 0);
    waitMs(300);
    assertBarsVisibility(View.GONE);
    key(KeyEvent.KEYCODE_PAGE_DOWN, 0);
    waitMs(300);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    assertBarsVisibility(View.GONE);
    key(KeyEvent.KEYCODE_SPACE, 0);
    assertEquals(SessionEngine.State.FINALIZING, app.session.snapshot().state);
    assertBarsVisibility(View.GONE);
    key(KeyEvent.KEYCODE_SPACE, 1);
    assertEquals(SessionEngine.State.FINALIZING, app.session.snapshot().state);
    f.listener.completed();
    waitMs(4500);
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    assertBarsVisibility(View.GONE);
  }

  @Test
  public void listeningCollapsesBothBarsTapRestoresAndKeyboardStops() throws Exception {
    FakeFactory f = fakeSession();
    View toolbar = activity.findViewById(R.id.meeting_toolbar);
    View content = activity.findViewById(R.id.meeting_content);
    int expandedContent = content.getHeight();
    waitMs(1500); // Connection has not opened the microphone: keep the status visible.
    assertEquals(View.VISIBLE, toolbar.getVisibility());
    f.listener.ready();
    waitMs(700);
    assertEquals(View.VISIBLE, toolbar.getVisibility());
    waitMs(800);
    assertEquals(View.GONE, toolbar.getVisibility());
    assertEquals(expandedContent + 116, content.getHeight());
    assertBarsVisibility(View.GONE);
    assertFalse(find("停止听").isShown());
    assertNotNull(find("停止听"));
    TextView status = activity.findViewById(R.id.meeting_status);
    assertTrue(status.isShown());
    assertTrue(status.getText().toString().startsWith("聆听中"));
    screenshot("portrait-listening-hidden");
    gesture(false, false);
    waitMs(400);
    assertEquals(View.VISIBLE, toolbar.getVisibility());
    assertBarsVisibility(View.VISIBLE);
    waitMs(2500);
    gesture(false, false); // Another tap restarts the four-second window.
    waitMs(2500);
    assertEquals(View.VISIBLE, toolbar.getVisibility());
    waitMs(2000);
    assertEquals(View.GONE, toolbar.getVisibility());
    key(KeyEvent.KEYCODE_SPACE, 0);
    waitMs(100);
    assertEquals(SessionEngine.State.FINALIZING, app.session.snapshot().state);
    assertEquals(View.GONE, toolbar.getVisibility());
    f.listener.completed(); // Empty speech: no text-model call.
    waitMs(100);
    assertNotNull(find("开始听"));
  }

  @Test
  public void scrollingAndLongPressKeepBarsHiddenAndErrorsRemainInStatus() {
    FakeFactory f = fakeSession();
    f.listener.ready();
    waitMs(1600);
    View toolbar = activity.findViewById(R.id.meeting_toolbar);
    assertEquals(View.GONE, toolbar.getVisibility());
    gesture(true, false);
    waitMs(300);
    assertEquals(View.GONE, toolbar.getVisibility());
    gesture(false, true);
    waitMs(300);
    assertEquals(View.GONE, toolbar.getVisibility());
    f.listener.failed("测试连接中断");
    waitMs(300);
    assertEquals(View.VISIBLE, toolbar.getVisibility());
    waitMs(5000);
    assertEquals(View.GONE, toolbar.getVisibility());
    assertTrue(
        ((TextView) activity.findViewById(R.id.meeting_status))
            .getText()
            .toString()
            .contains("测试连接中断"));
  }

  @Test
  public void hiddenToolbarSurvivesRotationWithoutRestartingAudio() {
    FakeFactory f = fakeSession();
    f.listener.ready();
    waitMs(1600);
    controller.recreate();
    activity = controller.get();
    layout(393, 852);
    assertBarsVisibility(View.GONE);
    assertEquals(1, f.audioStarts);
    key(KeyEvent.KEYCODE_2, 0);
    waitMs(300);
    assertBarsVisibility(View.VISIBLE);
    assertTrue(
        ((TextView) activity.findViewById(R.id.meeting_preference))
            .getText()
            .toString()
            .contains("下轮：金融会议"));
  }

  @Test
  public void analysisAndReadingKeepAutoHideAndPreserveScroll() throws Exception {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "合并两个单向链表", true, 0, 1));
    waitMs(1600);
    find("停止听").performClick();
    f.listener.completed();
    waitMs(300);
    assertEquals(SessionEngine.State.ANALYZING, app.session.snapshot().state);
    assertBarsVisibility(View.GONE);
    String result = "测试答案，需要保留阅读位置。\n".repeat(150);
    f.textListener.delta(result);
    f.textListener.completed(false);
    waitMs(5000);
    assertEquals(SessionEngine.State.DONE, app.session.snapshot().state);
    TextView answer = find(Markdown.render(activity, result).toString());
    assertNotNull(answer);
    ScrollView pane = (ScrollView) answer.getParent();
    pane.scrollTo(0, 300);
    gesture(false, false);
    waitMs(300);
    assertEquals(300, pane.getScrollY());
    screenshot("portrait-reading-toolbar");
    waitMs(4500);
    assertBarsVisibility(View.GONE);
    assertEquals(300, pane.getScrollY());
  }

  @Test
  public void copyIconsStayVisibleWhenToolbarsHideAndCopyCurrentContent() throws Exception {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "复制按钮位置检查", true, 0, 1000));
    app.session.stop();
    f.listener.completed();
    TextProvider.Listener text = f.textRequests.poll(2, TimeUnit.SECONDS);
    assertNotNull(text);
    text.delta("## 会议答案\n保留原始 Markdown 的复制内容。");
    text.completed(false);
    activity.findViewById(R.id.transcript_toggle).performClick();
    waitMs(4500);
    assertBarsVisibility(View.GONE);
    View copyAnswer = activity.findViewById(R.id.copy_answer);
    View copyTranscript = activity.findViewById(R.id.copy_transcript);
    assertTrue(copyAnswer.isShown());
    assertTrue(copyTranscript.isShown());
    ViewGroup answerHeader = (ViewGroup) copyAnswer.getParent();
    ViewGroup rawHeader = (ViewGroup) copyTranscript.getParent();
    assertSame(copyAnswer, answerHeader.getChildAt(answerHeader.getChildCount() - 1));
    assertSame(copyTranscript, rawHeader.getChildAt(rawHeader.getChildCount() - 1));
    assertSame(activity.findViewById(R.id.meeting_status).getParent(), answerHeader);
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager)
            activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    copyAnswer.performClick();
    assertEquals(
        app.meetings.answer(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    copyTranscript.performClick();
    assertEquals(
        app.meetings.transcript(true),
        clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    assertNull(activity.findViewById(R.id.more_button));
    screenshot("portrait-copy-controls-hidden-toolbar");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
  public void toolbarAnimationDoesNotRebuildLandscapePanes() throws Exception {
    FakeFactory f = fakeSession();
    key(KeyEvent.KEYCODE_PAGE_UP, 0);
    layoutCurrent();
    TextView label = ((TextView) activity.findViewById(R.id.meeting_status));
    f.listener.ready();
    waitMs(1600);
    assertBarsVisibility(View.GONE);
    assertSame(label, ((TextView) activity.findViewById(R.id.meeting_status)));
    assertNotNull(find("停止听"));
    screenshot("landscape-listening-hidden");
    gesture(false, false);
    waitMs(400);
    assertSame(label, ((TextView) activity.findViewById(R.id.meeting_status)));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
  public void landscapeHasVisibleIndependentPanes() throws Exception {
    layout(852, 393);
    screenshot("landscape");
    assertNotNull(find("开始听"));
    assertTrue(((TextView) activity.findViewById(R.id.meeting_status)).getHeight() > 0);
    key(KeyEvent.KEYCODE_PAGE_UP, 0);
    layout(852, 393);
    assertNotNull(find("听写原文"));
    screenshot("landscape-expanded");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w840dp-h900dp-port-mdpi")
  public void foldableCanHideAndRestoreTranscriptWithButtonOrPagingKeys() throws Exception {
    layout(840, 900);
    View toggle = activity.findViewById(R.id.transcript_toggle);
    assertTrue(toggle.isEnabled());
    assertNotNull(find("收起原文"));
    assertTrue(((TextView) activity.findViewById(R.id.meeting_status)).getHeight() > 0);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    screenshot("foldable");
    toggle.performClick();
    layout(840, 900);
    assertNull(activity.findViewById(R.id.transcript_panel));
    assertEquals(
        activity.findViewById(R.id.meeting_content).getWidth(),
        activity.findViewById(R.id.answer_panel).getWidth());
    assertNotNull(find("听写原文"));
    screenshot("foldable-transcript-hidden");
    toggle.performClick();
    layout(840, 900);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    for (int keyCode : new int[] {KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN}) {
      toggle.performClick();
      layout(840, 900);
      assertNull(activity.findViewById(R.id.transcript_panel));
      key(keyCode, 0);
      layout(840, 900);
      assertNotNull(activity.findViewById(R.id.transcript_panel));
      assertNotNull(find("收起原文"));
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w840dp-h900dp-port-mdpi")
  public void transcriptChoiceSurvivesFoldUnfoldAndRecreation() {
    activity.findViewById(R.id.transcript_toggle).performClick();
    layout(840, 900);
    assertNull(activity.findViewById(R.id.transcript_panel));
    controller.recreate();
    activity = controller.get();
    layout(840, 900);
    assertNull(activity.findViewById(R.id.transcript_panel));
    resizeWindow(393, 852);
    assertNull(activity.findViewById(R.id.transcript_panel));
    resizeWindow(840, 900);
    assertNull(activity.findViewById(R.id.transcript_panel));
    activity.findViewById(R.id.transcript_toggle).performClick();
    layout(840, 900);
    resizeWindow(393, 852);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    resizeWindow(840, 900);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
  }

  void resizeWindow(int width, int height) {
    Configuration changed = new Configuration(activity.getResources().getConfiguration());
    changed.screenWidthDp = width;
    changed.screenHeightDp = height;
    controller.configurationChange(changed);
    activity = controller.get();
    layout(width, height);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w840dp-h900dp-port-mdpi")
  public void hiddenTranscriptKeepsTextAndReadingPositionDuringRecognition() {
    FakeFactory f = fakeSession();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "需要保留的会议原文。\n".repeat(150), true, 0, 1));
    waitMs(400);
    ViewGroup panel = activity.findViewById(R.id.transcript_panel);
    ScrollView pane = (ScrollView) panel.getChildAt(1);
    pane.scrollTo(0, 250);
    assertEquals(250, app.transcriptScroll);
    activity.findViewById(R.id.transcript_toggle).performClick();
    waitMs(300);
    assertNull(activity.findViewById(R.id.transcript_panel));
    f.listener.segment(new SpeechProvider.Segment(2, "隐藏期间继续听写。", true, 1, 2));
    waitMs(300);
    assertEquals(250, app.transcriptScroll);
    assertTrue(app.session.snapshot().transcript.contains("隐藏期间继续听写"));
    activity.findViewById(R.id.transcript_toggle).performClick();
    waitMs(300);
    panel = activity.findViewById(R.id.transcript_panel);
    pane = (ScrollView) panel.getChildAt(1);
    assertEquals(250, pane.getScrollY());
    assertEquals(SessionEngine.State.LISTENING, app.session.snapshot().state);
    assertEquals(1, f.audioStarts);
  }

  @Test
  public void settingsPersistenceDoesNotReseedDeletedProfiles() {
    app.settings.delete("algorithm");
    app.settings.delete("finance");
    app.persist();
    UserSettings restored = new SettingsRepository(activity).load();
    assertTrue(restored.profiles.isEmpty());
    assertEquals("", restored.selectedId);
  }

  @Test
  public void rotationKeepsCurrentSessionAndAwakeChoice() {
    FakeFactory f = new FakeFactory();
    app.meetings.close();
    app.bindMeetings(
        new io.github.meetingassistant.core.MeetingManager(
            new io.github.meetingassistant.core.MeetingManager.MemoryStore(),
            () -> new SessionEngine(f),
            app.settings.snapshot(),
            app.settings.automatic));
    ProviderConfig c = new ProviderConfig();
    c.asrUrl = "wss://example.org/asr";
    c.textBaseUrl = "https://example.org/v1";
    app.session.start(c, new ProviderConfig.Credentials("test", "test"), app.settings.snapshot());
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "保留的原文", true, 0, 1));
    app.awake = true;
    Configuration changed = new Configuration(activity.getResources().getConfiguration());
    changed.orientation = Configuration.ORIENTATION_LANDSCAPE;
    changed.screenWidthDp = 852;
    changed.screenHeightDp = 393;
    controller.configurationChange(changed);
    activity = controller.get();
    layout(852, 393);
    assertEquals(SessionEngine.State.LISTENING, app.session.snapshot().state);
    assertEquals("保留的原文", app.session.snapshot().transcript);
    assertTrue(app.awake);
    assertEquals(1, f.audioStarts);
  }

  void screenshot(String name) throws Exception {
    Bitmap bitmap =
        Bitmap.createBitmap(root().getWidth(), root().getHeight(), Bitmap.Config.ARGB_8888);
    root().draw(new Canvas(bitmap));
    File dir = new File(System.getProperty("meeting.preview.dir"));
    assertTrue(dir.isDirectory() || dir.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  void bindMeetingShortcuts() {
    app.settings.bind(UserSettings.MEETING_NEW, KeyEvent.KEYCODE_F5);
    app.settings.bind(UserSettings.MEETING_PREVIOUS, KeyEvent.KEYCODE_F6);
    app.settings.bind(UserSettings.MEETING_NEXT, KeyEvent.KEYCODE_F7);
    app.settings.bind(UserSettings.TRANSCRIPT_TOGGLE, KeyEvent.KEYCODE_F8);
  }

  @Test
  public void meetingKeysFollowSidebarOrderStopAtEdgesAndIgnoreHeldKeys() {
    bindMeetingShortcuts();
    String original = app.meetings.currentId();
    app.meetings.rename(original, "第一场");
    key(KeyEvent.KEYCODE_F5, 0);
    waitMs(200);
    key(KeyEvent.KEYCODE_F5, 0);
    waitMs(200);
    assertEquals(3, app.meetings.summaries().size());
    java.util.List<String> order = new java.util.ArrayList<>();
    for (var meeting : app.meetings.summaries()) order.add(meeting.id);
    app.meetings.switchTo(order.get(1));
    waitMs(200);
    key(KeyEvent.KEYCODE_F6, 0);
    waitMs(200);
    assertEquals(order.get(0), app.meetings.currentId());
    key(KeyEvent.KEYCODE_F6, 0);
    assertEquals(order.get(0), app.meetings.currentId());
    assertEquals(
        activity.getString(R.string.meeting_list_edge),
        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    key(KeyEvent.KEYCODE_F7, 2);
    assertEquals(order.get(0), app.meetings.currentId());
    key(KeyEvent.KEYCODE_F7, 0);
    waitMs(200);
    assertEquals(order.get(1), app.meetings.currentId());
    key(KeyEvent.KEYCODE_F7, 0);
    waitMs(200);
    assertEquals(order.get(2), app.meetings.currentId());
    key(KeyEvent.KEYCODE_F7, 0);
    assertEquals(order.get(2), app.meetings.currentId());
    key(KeyEvent.KEYCODE_F5, 1);
    assertEquals(3, app.meetings.summaries().size());
    assertFalse(app.session.snapshot().busy());
    for (var meeting : app.meetings.list()) assertEquals("", meeting.current.transcript(false));
  }

  @Test
  public void meetingKeysDoNotInventHistoryForEmptyDraftOrSingleMeeting() {
    bindMeetingShortcuts();
    String draft = app.meetings.currentId();
    key(KeyEvent.KEYCODE_F6, 0);
    key(KeyEvent.KEYCODE_F7, 0);
    assertEquals(draft, app.meetings.currentId());
    assertTrue(app.meetings.summaries().isEmpty());
    assertEquals(
        activity.getString(R.string.no_meetings_to_switch),
        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    key(KeyEvent.KEYCODE_F5, 0);
    waitMs(200);
    String only = app.meetings.currentId();
    assertNotEquals(draft, only);
    assertNull(app.meetings.get(draft));
    key(KeyEvent.KEYCODE_F6, 0);
    key(KeyEvent.KEYCODE_F7, 0);
    assertEquals(only, app.meetings.currentId());
    assertEquals(1, app.meetings.summaries().size());
  }

  @Test
  public void transcriptShortcutWorksWithHiddenBarsAndPersistsPerMeeting() {
    bindMeetingShortcuts();
    app.meetings.rename(app.meetings.currentId(), "原会议");
    String original = app.meetings.currentId();
    waitMs(5000);
    assertBarsVisibility(View.GONE);
    assertNull(activity.findViewById(R.id.transcript_panel));
    key(KeyEvent.KEYCODE_F8, 0);
    waitMs(300);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    assertBarsVisibility(View.GONE);
    assertTrue(app.meetings.current().view.transcriptExpanded);
    assertTrue(app.meetings.current().view.transcriptVisibilityChosen);
    key(KeyEvent.KEYCODE_F8, 1);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    key(KeyEvent.KEYCODE_F5, 0);
    waitMs(200);
    assertNull(activity.findViewById(R.id.transcript_panel));
    app.meetings.switchTo(original);
    waitMs(200);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    key(KeyEvent.KEYCODE_F8, 0);
    waitMs(200);
    assertNull(activity.findViewById(R.id.transcript_panel));
    assertFalse(app.meetings.current().view.transcriptExpanded);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w840dp-h900dp-port-mdpi")
  public void transcriptKeyTogglesFoldableDefaultAndSurvivesRecreation() {
    bindMeetingShortcuts();
    assertNotNull(activity.findViewById(R.id.transcript_panel));
    key(KeyEvent.KEYCODE_F8, 0);
    assertNull(activity.findViewById(R.id.transcript_panel));
    controller.recreate();
    activity = controller.get();
    layoutCurrent();
    assertNull(activity.findViewById(R.id.transcript_panel));
    key(KeyEvent.KEYCODE_F8, 0);
    assertNotNull(activity.findViewById(R.id.transcript_panel));
  }

  @Test
  public void newMeetingKeyRequiresConfirmationAndCannotRepeatDuringFinalization() {
    FakeFactory f = fakeSession();
    bindMeetingShortcuts();
    f.listener.ready();
    f.listener.segment(new SpeechProvider.Segment(1, "尾句尚未完整", false, 0, -1));
    String source = app.meetings.currentId();
    key(KeyEvent.KEYCODE_F5, 0);
    AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(confirm.isShowing());
    key(KeyEvent.KEYCODE_F5, 0); // Modal isolation: no second request/record.
    assertEquals(1, app.meetings.summaries().size());
    confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    assertEquals(SessionEngine.State.LISTENING, app.session.snapshot().state);
    assertEquals(source, app.meetings.currentId());
    key(KeyEvent.KEYCODE_F5, 0);
    confirm = ShadowAlertDialog.getLatestAlertDialog();
    confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    waitMs(100);
    assertTrue(app.meetings.navigating());
    assertEquals(SessionEngine.State.FINALIZING, app.session.snapshot().state);
    int count = app.meetings.summaries().size();
    key(KeyEvent.KEYCODE_F5, 0);
    key(KeyEvent.KEYCODE_F6, 0);
    assertEquals(count, app.meetings.summaries().size());
    assertFalse(confirm.isShowing());
    f.listener.segment(new SpeechProvider.Segment(1, "收齐后的完整尾句", true, 0, 1500));
    f.listener.completed();
    waitMs(200);
    assertNotEquals(source, app.meetings.currentId());
    assertTrue(app.meetings.get(source).current.transcript(false).contains("完整尾句"));
    assertEquals("", app.meetings.transcript(true));
    assertEquals(1, f.audioStarts);
  }

  @Test
  public void adjacentMeetingKeyUsesStopConfirmationAndRetainsSourceTail() {
    FakeFactory f = fakeSession();
    bindMeetingShortcuts();
    f.listener.ready();
    String source = app.meetings.currentId();
    String target = app.meetings.create(app.settings.snapshot(), false);
    waitMs(100);
    java.util.List<io.github.meetingassistant.core.MeetingManager.Summary> order =
        app.meetings.summaries();
    int keyCode = order.get(0).id.equals(source) ? KeyEvent.KEYCODE_F7 : KeyEvent.KEYCODE_F6;
    key(keyCode, 0);
    AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(confirm.isShowing());
    assertEquals(source, app.meetings.currentId());
    confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    f.listener.segment(new SpeechProvider.Segment(1, "切换前保留的尾句", true, 0, 1000));
    f.listener.completed();
    waitMs(200);
    assertEquals(target, app.meetings.currentId());
    assertTrue(app.meetings.get(source).current.transcript(false).contains("尾句"));
    assertEquals(1, f.audioStarts);
    assertEquals(2, app.meetings.summaries().size());
  }

  @Test
  public void newMeetingKeysCanBeCapturedClearedAndAreIsolatedFromSettingsAndModals() {
    openSettings();
    for (int label :
        new int[] {
          R.string.shortcut_meeting_new,
          R.string.shortcut_meeting_previous,
          R.string.shortcut_meeting_next,
          R.string.shortcut_transcript_toggle
        }) {
      assertNotNull(find(activity.getString(label) + "   " + activity.getString(R.string.unbound)));
    }
    find(activity.getString(R.string.shortcut_meeting_new)
            + "   "
            + activity.getString(R.string.unbound))
        .performClick();
    AlertDialog capture = ShadowAlertDialog.getLatestAlertDialog();
    capture.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5));
    assertEquals(
        Integer.valueOf(KeyEvent.KEYCODE_F5), app.settings.bindings.get(UserSettings.MEETING_NEW));
    assertEquals(
        Integer.valueOf(KeyEvent.KEYCODE_F5),
        new SettingsRepository(activity).load().bindings.get(UserSettings.MEETING_NEW));
    String source = app.meetings.currentId();
    key(KeyEvent.KEYCODE_F5, 0);
    assertEquals(source, app.meetings.currentId());
    TextView assigned =
        find(
            activity.getString(R.string.shortcut_meeting_new)
                + "   "
                + KeyEvent.keyCodeToString(KeyEvent.KEYCODE_F5).replace("KEYCODE_", ""));
    assertNotNull(assigned);
    assigned.performClick();
    capture = ShadowAlertDialog.getLatestAlertDialog();
    capture.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
    waitMs(100);
    assertFalse(app.settings.bindings.containsKey(UserSettings.MEETING_NEW));
    bindMeetingShortcuts();
    activity.findViewById(R.id.settings_back).performClick();
    waitMs(200);
    app.settings.keyboardEnabled = false;
    key(KeyEvent.KEYCODE_F5, 0);
    key(KeyEvent.KEYCODE_F8, 0);
    assertEquals(source, app.meetings.currentId());
    assertNull(activity.findViewById(R.id.transcript_panel));
    app.settings.keyboardEnabled = true;
    activity.findViewById(R.id.meetings_button).performClick();
    android.app.Dialog drawer = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    key(KeyEvent.KEYCODE_F5, 0);
    key(KeyEvent.KEYCODE_F8, 0);
    assertEquals(source, app.meetings.currentId());
    assertNull(activity.findViewById(R.id.transcript_panel));
    drawer.dismiss();
  }

  static class FakeFactory implements SessionEngine.Factory {
    SpeechProvider.Listener listener;
    volatile TextProvider.Listener textListener;
    volatile com.google.gson.JsonArray textMessages;
    final LinkedBlockingQueue<TextProvider.Listener> textRequests = new LinkedBlockingQueue<>();
    int audioStarts;

    public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
      return new SpeechProvider() {
        public void start(Listener l) {
          listener = l;
        }

        public void sendAudio(byte[] b) {}

        public void finish() {}

        public void cancel() {}
      };
    }

    public AudioSource audio() {
      return new AudioSource() {
        public void start(Listener l) {
          audioStarts++;
        }

        public void stop(Runnable r) {
          r.run();
        }

        public void cancel() {}
      };
    }

    public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
      return new TextProvider() {
        public void analyze(com.google.gson.JsonArray messages, Listener l) {
          textMessages = messages;
          textListener = l;
          textRequests.add(l);
        }

        public void cancel() {}
      };
    }
  }

  @Test
  public void restoredInterruptedMeetingStillHidesBarsAndKeepsStatusVisible() {
    SessionEngine.SavedState saved = new SessionEngine.SavedState();
    saved.state = SessionEngine.State.LISTENING;
    app.session.restore(saved);
    waitMs(300);
    assertEquals(SessionEngine.State.ERROR, app.session.snapshot().state);
    waitMs(4500);
    assertBarsVisibility(View.GONE);
    assertTrue(activity.findViewById(R.id.meeting_status).isShown());
    assertTrue(
        ((TextView) activity.findViewById(R.id.meeting_status))
            .getText()
            .toString()
            .contains("上次任务已中断"));
    controller.recreate();
    activity = controller.get();
    layoutCurrent();
    assertBarsVisibility(View.GONE);
    gesture(false, false);
    waitMs(400);
    assertBarsVisibility(View.VISIBLE);
    waitMs(4500);
    assertBarsVisibility(View.GONE);
  }

  @Test
  public void focusReturnClearsInterruptedTouchAndRestartsIdleCountdown() {
    long at = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 120, 120, 0);
    activity.dispatchTouchEvent(down);
    down.recycle();
    activity.onWindowFocusChanged(false);
    waitMs(5500);
    assertBarsVisibility(View.VISIBLE);
    // A system dialog can consume the UP event; returning focus must not retain 'touching'.
    activity.onWindowFocusChanged(true);
    waitMs(3000);
    assertBarsVisibility(View.VISIBLE);
    waitMs(1500);
    assertBarsVisibility(View.GONE);
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
  }
}
