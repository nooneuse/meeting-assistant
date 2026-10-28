package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.SessionEngine;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "en-rUS-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EnglishUiTest {
  ActivityController<MainActivity> controller;
  MainActivity screen;
  MeetingApp app;

  void launch() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
    screen = controller.get();
    layout();
  }

  void layout() {
    var config = screen.getResources().getConfiguration();
    for (int i = 0; i < 4; i++) {
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60));
      View root = root();
      root.measure(
          View.MeasureSpec.makeMeasureSpec(config.screenWidthDp, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(config.screenHeightDp, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, config.screenWidthDp, config.screenHeightDp);
    }
  }

  View root() {
    return ((ViewGroup) screen.findViewById(android.R.id.content)).getChildAt(0);
  }

  @After
  public void close() {
    if (controller != null) controller.pause().stop().destroy();
    ((MeetingApp) RuntimeEnvironment.getApplication()).meetings.close();
  }

  void assertEnglish(View view) {
    if (view instanceof TextView text) {
      assertFalse(
          text.getText().toString(), text.getText().toString().matches("(?s).*[\\p{IsHan}].*"));
      if (text.getHint() != null)
        assertFalse(text.getHint().toString().matches("(?s).*[\\p{IsHan}].*"));
    }
    if (view.getContentDescription() != null)
      assertFalse(view.getContentDescription().toString().matches("(?s).*[\\p{IsHan}].*"));
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) assertEnglish(group.getChildAt(i));
  }

  void verifyEnglishScreens(String suffix) throws Exception {
    launch();
    assertEquals("Ready", ((TextView) screen.findViewById(R.id.meeting_status)).getText());
    assertEquals(
        "Algorithm discussion",
        ((TextView) screen.findViewById(R.id.meeting_preference)).getText());
    assertEnglish(root());
    screenshot("english-main-" + suffix, root());
    screen.findViewById(R.id.awake_button).performClick();
    assertEquals("Screen stays awake", ShadowToast.getTextOfLatestToast());
    screen.findViewById(R.id.awake_button).performClick();
    assertEquals("Normal screen timeout", ShadowToast.getTextOfLatestToast());
    screen.findViewById(R.id.auto_button).performClick();
    assertEquals("Automatic segmentation", ShadowToast.getTextOfLatestToast());
    screen.findViewById(R.id.auto_button).performClick();
    assertEquals("Manual segmentation", ShadowToast.getTextOfLatestToast());
    screen.findViewById(R.id.meetings_button).performClick();
    Dialog drawer = ShadowDialog.getLatestDialog();
    drawer.findViewById(R.id.drawer_new_meeting).performClick();
    layout();
    screen.findViewById(R.id.meetings_button).performClick();
    drawer = ShadowDialog.getLatestDialog();
    View decor = drawer.getWindow().getDecorView();
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(337, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
    decor.layout(0, 0, 337, 852);
    assertEnglish(decor);
    screenshot("english-drawer-" + suffix, decor);
    drawer.findViewById(R.id.drawer_settings).performClick();
    layout();
    assertEnglish(root());
    screenshot("english-settings-" + suffix, root());
    screen.findViewById(R.id.settings_back).performClick();
    layout();
    assertEnglish(root());
  }

  @Test
  public void englishMainDrawerSettingsAndToasts() throws Exception {
    verifyEnglishScreens("portrait");
  }

  @Test
  @Config(qualifiers = "fr-rFR-w393dp-h852dp-port-mdpi")
  public void frenchSystemUsesEnglishEverywhere() throws Exception {
    verifyEnglishScreens("french-system");
  }

  @Test
  @Config(qualifiers = "zh-rTW-w393dp-h852dp-port-mdpi")
  public void traditionalChineseSystemUsesEnglishEverywhere() throws Exception {
    verifyEnglishScreens("traditional-system");
  }

  @Test
  @Config(qualifiers = "en-rUS-w852dp-h393dp-land-mdpi")
  public void englishLandscapeAndExpandedTranscript() throws Exception {
    launch();
    screen.findViewById(R.id.transcript_toggle).performClick();
    layout();
    assertEnglish(root());
    assertTrue(screen.findViewById(R.id.copy_transcript).isShown());
    screenshot("english-landscape", root());
  }

  @Test
  @Config(qualifiers = "en-rUS-w840dp-h900dp-port-mdpi")
  public void englishFoldable() throws Exception {
    launch();
    assertEnglish(root());
    assertTrue(screen.findViewById(R.id.copy_transcript).isShown());
    screenshot("english-foldable", root());
  }

  @Test
  public void localeRecreationKeepsMeetingAndUserContent() {
    launch();
    String id = app.meetings.currentId();
    app.meetings.rename(id, "设置");
    SessionEngine.SavedState saved = new SessionEngine.SavedState();
    saved.state = SessionEngine.State.DONE;
    saved.answer = "会议已删除";
    saved.status = "已完成 · AI 建议请结合原文核对";
    saved.preference = app.settings.snapshot();
    saved.selected = app.settings.snapshot();
    app.session.restore(saved);
    layout();
    assertTrue(
        ((TextView) screen.findViewById(R.id.meeting_status))
            .getText()
            .toString()
            .startsWith("Complete"));
    assertEquals("会议已删除", app.meetings.answer());
    RuntimeEnvironment.setQualifiers("zh-rCN-w393dp-h852dp-port-mdpi");
    controller.recreate();
    screen = controller.get();
    layout();
    assertEquals("设置", screen.getString(R.string.settings));
    assertEquals(id, app.meetings.currentId());
    assertEquals("设置", app.meetings.current().title);
    assertEquals("会议已删除", app.meetings.answer());
  }

  private void screenshot(String name, View view) throws Exception {
    Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(image));
    File folder = new File(System.getProperty("meeting.preview.dir"));
    assertTrue(folder.isDirectory() || folder.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    image.recycle();
  }
}
