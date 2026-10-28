package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
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
public class InputSettingsTest {
  MeetingApp app;
  MainActivity activity;
  ActivityController<MainActivity> lifecycle;

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    lifecycle = Robolectric.buildActivity(MainActivity.class).setup().visible();
    activity = lifecycle.get();
    idle();
  }

  @After
  public void close() {
    lifecycle.pause().stop().destroy();
    app.meetings.close();
    app.audio.stopTest();
  }

  void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
  }

  void mainSettings() {
    activity.findViewById(R.id.meetings_button).performClick();
    idle();
    ShadowDialog.getLatestDialog().findViewById(R.id.drawer_settings).performClick();
    ((SettingsSection) activity.findViewById(R.id.settings_models)).setExpanded(true);
    ((SettingsSection) activity.findViewById(R.id.settings_image_model)).setExpanded(true);
    idle();
  }

  EditText field(int id) {
    return activity.findViewById(id);
  }

  @Test
  public void imageServiceMovedToModelSettingsAndBlankKeysPreserveExistingBlobs() throws Exception {
    var prefs = app.getSharedPreferences("settings-v1", 0);
    prefs
        .edit()
        .putString("asr-key", "opaque-asr")
        .putString("text-key", "opaque-text")
        .putString("image-key", "opaque-image")
        .commit();
    app.settings.image.sourceId = "camera:selected";
    app.settings.image.cropSource = "camera:selected";
    app.settings.image.left = .2f;
    app.video.settings(app.settings.image);
    mainSettings();
    assertNotNull(field(R.id.image_model_url));
    assertNotNull(field(R.id.image_model_key));
    assertEquals("", field(R.id.image_model_key).getText().toString());
    assertFalse(field(R.id.image_model_key).isSaveEnabled());
    field(R.id.image_model_url).setText("https://images.example.org/v1");
    field(R.id.image_model_name).setText("vision-fixture");
    ((Switch) activity.findViewById(R.id.image_model_format)).setChecked(false);
    captureModelSection();
    activity.findViewById(R.id.models_save).performClick();
    idle();
    UserSettings saved = app.repository.load();
    assertEquals("https://images.example.org/v1", saved.image.baseUrl);
    assertEquals("vision-fixture", saved.image.model);
    assertFalse(saved.image.qwen35Coordinates);
    assertEquals("camera:selected", saved.image.sourceId);
    assertEquals(.2f, saved.image.left, .001f);
    assertEquals("opaque-asr", prefs.getString("asr-key", ""));
    assertEquals("opaque-text", prefs.getString("text-key", ""));
    assertEquals("opaque-image", prefs.getString("image-key", ""));
    activity.findViewById(R.id.settings_back).performClick();
    idle();
    activity.findViewById(R.id.image_input_button).performClick();
    idle();
    Dialog drawer = ShadowDialog.getLatestDialog();
    find(drawer.getWindow().getDecorView(), activity.getString(R.string.image_settings))
        .performClick();
    idle();
    Dialog page = ShadowDialog.getLatestDialog();
    assertNotNull(page.findViewById(R.id.image_preview));
    assertNull(page.findViewById(R.id.image_model_url));
    assertNull(
        find(page.getWindow().getDecorView(), activity.getString(R.string.image_model_connection)));
  }

  @Test
  public void invalidImageServiceDoesNotPartiallySaveAnyModelConfiguration() {
    app.repository.save(app.settings);
    String originalModel = app.settings.image.model;
    mainSettings();
    field(R.id.image_model_url).setText("http://images.example.org/v1");
    field(R.id.image_model_name).setText("unsaved");
    activity.findViewById(R.id.models_save).performClick();
    idle();
    assertEquals(originalModel, app.settings.image.model);
    assertEquals(originalModel, app.repository.load().image.model);
    assertEquals("", app.settings.image.baseUrl);
    activity.findViewById(R.id.settings_back).performClick();
    idle();
    assertTrue(ShadowDialog.getLatestDialog() instanceof AlertDialog);
  }

  @Test
  public void imageModelChangesCanBeDiscardedWithoutModifyingStoredSettings() {
    String original = app.settings.image.model;
    mainSettings();
    field(R.id.image_model_name).setText("unsaved-model");
    activity.findViewById(R.id.settings_back).performClick();
    idle();
    AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
    confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    idle();
    assertEquals(original, app.repository.load().image.model);
    assertNull(activity.findViewById(R.id.image_model_url));
  }

  @Test
  public void imageRefreshAndSwitchOffSelectionNeverImplicitlyOpenCamera() {
    var sources = new ImageUiTest.FakeSources();
    app.video = new ImageInputController(app, app.settings.image, sources);
    lifecycle.recreate();
    activity = lifecycle.get();
    idle();
    activity.findViewById(R.id.image_input_button).performClick();
    idle();
    Dialog drawer = ShadowDialog.getLatestDialog();
    assertTrue(drawer.findViewById(R.id.video_enabled) instanceof Switch);
    RadioGroup list = drawer.findViewById(R.id.image_sources);
    list.getChildAt(1).performClick();
    idle();
    drawer.findViewById(R.id.image_refresh).performClick();
    idle();
    list = drawer.findViewById(R.id.image_sources);
    assertTrue(((RadioButton) list.getChildAt(1)).isChecked());
    assertFalse(((RadioButton) list.getChildAt(0)).isChecked());
    assertNull(sources.latest);
    assertFalse(app.video.enabled());
    assertEquals("usb:external", app.repository.load().image.sourceId);
  }

  private void captureModelSection() throws Exception {
    View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 393, 852);
    EditText url = field(R.id.image_model_url);
    View ancestor = url;
    int top = 0;
    while (!(ancestor instanceof ScrollView)) {
      top += ancestor.getTop();
      ancestor = (View) ancestor.getParent();
    }
    ((ScrollView) ancestor).scrollTo(0, Math.max(0, top - 100));
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(393, 852, android.graphics.Bitmap.Config.ARGB_8888);
    root.draw(new android.graphics.Canvas(bitmap));
    java.io.File file =
        new java.io.File(System.getProperty("meeting.preview.dir"), "settings-image-service.png");
    file.getParentFile().mkdirs();
    try (var out = new java.io.FileOutputStream(file)) {
      assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  private TextView find(View view, String label) {
    if (view instanceof TextView t && label.contentEquals(t.getText())) return t;
    if (view instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView found = find(g.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }
}
