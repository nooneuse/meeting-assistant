package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import io.github.meetingassistant.BuildConfig;
import io.github.meetingassistant.R;
import java.io.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "zh-rCN-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsAppearanceTest {
  MeetingApp app;
  MainActivity screen;
  ActivityController<MainActivity> lifecycle;

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    app.getSharedPreferences("settings-ui-v1", 0).edit().clear().commit();
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

  void settings() {
    screen.findViewById(R.id.meetings_button).performClick();
    idle();
    ShadowDialog.getLatestDialog().findViewById(R.id.drawer_settings).performClick();
    idle();
  }

  SettingsSection section(int id) {
    return screen.findViewById(id);
  }

  @Test
  public void allSectionsInitiallyFoldAndExposeNativeExpandCollapseActions() throws Exception {
    settings();
    for (int id :
        new int[] {
          R.id.settings_models,
          R.id.settings_image_model,
          R.id.settings_audio,
          R.id.settings_video,
          R.id.settings_context,
          R.id.settings_listening,
          R.id.settings_preferences,
          R.id.settings_keyboard,
          R.id.settings_privacy,
          R.id.settings_about
        }) {
      SettingsSection s = section(id);
      assertNotNull(s);
      assertFalse(s.expanded());
      assertEquals(View.GONE, s.content().getVisibility());
    }
    SettingsSection models = section(R.id.settings_models);
    assertTrue(models.heading().getContentDescription().toString().startsWith("展开"));
    assertTrue(
        models.heading().performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null));
    idle();
    assertTrue(models.expanded());
    assertTrue(models.heading().getContentDescription().toString().startsWith("折叠"));
    assertTrue(
        models.heading().performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null));
    assertFalse(models.expanded());
    capture("settings-sections-collapsed");
  }

  @Test
  public void aboutDisplaysBuildVersionAndPublicProjectWithoutRequestsUntilClicked()
      throws Exception {
    settings();
    section(R.id.settings_about).setExpanded(true);
    assertEquals(
        "折叠 关于", section(R.id.settings_about).heading().getContentDescription().toString());
    assertEquals("会议助手", ((TextView) screen.findViewById(R.id.about_name)).getText().toString());
    String version = ((TextView) screen.findViewById(R.id.about_version)).getText().toString();
    assertEquals(BuildConfig.VERSION_NAME, version);
    assertTrue(version.matches("0\\.1\\.0\\+\\d{8}"));
    LocalDate.parse(version.substring(version.indexOf('+') + 1), DateTimeFormatter.BASIC_ISO_DATE);
    assertEquals("MIT", ((TextView) screen.findViewById(R.id.about_license)).getText().toString());
    TextView project = screen.findViewById(R.id.about_project);
    assertEquals("https://github.com/nooneuse/meeting-assistant", project.getText().toString());
    assertNull(Shadows.shadowOf(screen).getNextStartedActivity());
    project.performClick();
    Intent intent = Shadows.shadowOf(screen).getNextStartedActivity();
    assertEquals(Intent.ACTION_VIEW, intent.getAction());
    assertEquals(project.getText().toString(), intent.getDataString());
  }

  @Test
  public void foldingRetainsUnsavedModelValuesAndDiscardProtection() {
    settings();
    section(R.id.settings_models).setExpanded(true);
    section(R.id.settings_image_model).setExpanded(true);
    EditText name = screen.findViewById(R.id.image_model_name);
    name.setText("unsaved-vision-fixture");
    section(R.id.settings_models).heading().performClick();
    idle();
    assertFalse(name.isShown());
    assertSame(name, screen.findViewById(R.id.image_model_name));
    section(R.id.settings_models).heading().performClick();
    idle();
    assertTrue(name.isShown());
    assertEquals("unsaved-vision-fixture", name.getText().toString());
    section(R.id.settings_models).setExpanded(false);
    screen.findViewById(R.id.settings_back).performClick();
    idle();
    AlertDialog discard = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(discard.getButton(AlertDialog.BUTTON_POSITIVE));
    assertNotEquals("unsaved-vision-fixture", app.repository.load().image.model);
  }

  @Test
  public void expansionSurvivesLeavingSettingsAndActivityRecreation() {
    settings();
    section(R.id.settings_keyboard).setExpanded(true);
    section(R.id.settings_models).setExpanded(true);
    section(R.id.settings_image_model).setExpanded(true);
    screen.findViewById(R.id.settings_back).performClick();
    idle();
    settings();
    assertTrue(section(R.id.settings_keyboard).expanded());
    assertTrue(section(R.id.settings_image_model).expanded());
    lifecycle.recreate();
    screen = lifecycle.get();
    idle();
    assertTrue(section(R.id.settings_keyboard).expanded());
    assertTrue(section(R.id.settings_models).expanded());
    assertFalse(section(R.id.settings_privacy).expanded());
  }

  @Test
  public void audioLevelTrackBlendsWithBackgroundAndStillDisplaysProgress() {
    ProgressBar meter = UiControls.audioMeter(screen);
    meter.measure(
        View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(12, View.MeasureSpec.EXACTLY));
    meter.layout(0, 0, 200, 12);
    Bitmap bitmap = Bitmap.createBitmap(200, 12, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    meter.setProgress(0, false);
    meter.draw(canvas);
    assertEquals(AppTheme.color(screen, AppTheme.SURFACE), bitmap.getPixel(180, 6));
    meter.setProgress(50, false);
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    meter.draw(canvas);
    assertEquals(AppTheme.color(screen, AppTheme.PRIMARY), bitmap.getPixel(25, 6));
    assertEquals(AppTheme.color(screen, AppTheme.SURFACE), bitmap.getPixel(180, 6));
    bitmap.recycle();
  }

  @Test
  public void drawersAndSetupWindowsUseSameThemeSurfaceAsNavigationArea() {
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        ((ColorDrawable) screen.getWindow().getDecorView().getBackground()).getColor());
    screen.findViewById(R.id.meetings_button).performClick();
    idle();
    Dialog left = ShadowDialog.getLatestDialog();
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        ((ColorDrawable) left.getWindow().getDecorView().getBackground()).getColor());
    left.dismiss();
    idle();
    screen.findViewById(R.id.image_input_button).performClick();
    idle();
    Dialog right = ShadowDialog.getLatestDialog();
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        ((ColorDrawable) right.getWindow().getDecorView().getBackground()).getColor());
    ProgressBar meter = right.findViewById(R.id.audio_level);
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        meter.getProgressBackgroundTintList().getDefaultColor());
    right.findViewById(R.id.audio_settings).performClick();
    idle();
    Dialog audio = ShadowDialog.getLatestDialog();
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        ((ColorDrawable) audio.getWindow().getDecorView().getBackground()).getColor());
    meter = audio.findViewById(R.id.audio_level);
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        meter.getProgressBackgroundTintList().getDefaultColor());
  }

  @Test
  @Config(qualifiers = "en-rUS-w852dp-h393dp-land-mdpi")
  public void englishLandscapeHeadersAndExpandedControlsFit() throws Exception {
    settings();
    assertEquals(
        "Expand About", section(R.id.settings_about).heading().getContentDescription().toString());
    assertEquals(
        "Meeting Assistant",
        ((TextView) screen.findViewById(R.id.about_name)).getText().toString());
    assertTrue(
        section(R.id.settings_keyboard)
            .heading()
            .getContentDescription()
            .toString()
            .startsWith("Expand"));
    section(R.id.settings_listening).setExpanded(true);
    capture("settings-sections-landscape-en");
  }

  void capture(String name) throws Exception {
    int w = screen.getResources().getConfiguration().screenWidthDp,
        h = screen.getResources().getConfiguration().screenHeightDp;
    View view = ((ViewGroup) screen.findViewById(android.R.id.content)).getChildAt(0);
    view.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, w, h);
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File out = new File(System.getProperty("meeting.preview.dir"), name + ".png");
    out.getParentFile().mkdirs();
    try (FileOutputStream stream = new FileOutputStream(out)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
    }
    bitmap.recycle();
  }
}
