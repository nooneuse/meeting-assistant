package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "zh-rCN-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AudioInputUiTest {
  MeetingApp app;
  MainActivity activity;
  ActivityController<MainActivity> lifecycle;
  AudioInputControllerTest.Hardware hardware;

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    hardware = new AudioInputControllerTest.Hardware();
    app.audio = new AudioInputController(app, app.settings.audio, hardware);
    lifecycle = Robolectric.buildActivity(MainActivity.class).setup().visible();
    activity = lifecycle.get();
    idle();
  }

  @After
  public void close() {
    lifecycle.pause().stop().destroy();
    app.meetings.close();
    app.audio.stopTest();
    AudioInputControllerTest.await(() -> !app.audio.active());
  }

  void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
  }

  Dialog drawer() {
    activity.findViewById(R.id.image_input_button).performClick();
    idle();
    return ShadowDialog.getLatestDialog();
  }

  Dialog settings() {
    Dialog d = drawer();
    d.findViewById(R.id.audio_settings).performClick();
    idle();
    return ShadowDialog.getLatestDialog();
  }

  void choose(Dialog d, int index) {
    ((RadioGroup) d.findViewById(R.id.audio_source)).getChildAt(index).performClick();
    idle();
  }

  void screenshot(View root, String name, int w, int h) throws Exception {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, w, h);
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    root.draw(new Canvas(bitmap));
    var file = new java.io.File(System.getProperty("meeting.preview.dir"), name + ".png");
    file.getParentFile().mkdirs();
    try (var out = new java.io.FileOutputStream(file)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  @Test
  public void drawerSelectsAndPersistsWithoutRecordingOrPermissionPrompt() {
    Dialog d = drawer();
    assertNotNull(d.findViewById(R.id.video_enabled));
    choose(d, 1);
    assertEquals("device:usb", app.settings.audio.source);
    assertEquals(0, hardware.opens.get());
    assertEquals("device:usb", app.repository.load().audio.source);
    d.findViewById(R.id.audio_refresh).performClick();
    idle();
    RadioGroup sources = d.findViewById(R.id.audio_source);
    assertFalse(((RadioButton) sources.getChildAt(0)).isChecked());
    assertTrue(((RadioButton) sources.getChildAt(1)).isChecked());
    assertEquals(0, hardware.opens.get());
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
  }

  @Test
  public void settingsLocalTestStopsOnExitAndReturnsToInputDrawer() throws Exception {
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
    Dialog page = settings();
    page.findViewById(R.id.audio_test).performClick();
    AudioInputControllerTest.await(() -> app.audio.state() == AudioInputController.State.CAPTURING);
    idle();
    assertTrue(app.audio.testing());
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    screenshot(page.getWindow().getDecorView(), "audio-settings-portrait", 393, 852);
    page.findViewById(R.id.audio_settings_back).performClick();
    idle();
    AudioInputControllerTest.await(() -> !app.audio.active());
    assertNotNull(ShadowDialog.getLatestDialog().findViewById(R.id.video_enabled));
    assertEquals(1, hardware.closes.get());
  }

  @Test
  public void missingPermissionDoesNotOpenAudioDevice() {
    Dialog page = settings();
    page.findViewById(R.id.audio_test).performClick();
    assertEquals(0, hardware.opens.get());
    var request = Shadows.shadowOf(activity).getLastRequestedPermission();
    assertNotNull(request);
    assertArrayEquals(
        new String[] {Manifest.permission.RECORD_AUDIO}, request.requestedPermissions);
  }

  @Test
  public void bluetoothSelectionRequestsNearbyDevicesButNotMicrophoneUntilRecording() {
    choose(drawer(), 2);
    var request = Shadows.shadowOf(activity).getLastRequestedPermission();
    assertNotNull(request);
    assertArrayEquals(
        new String[] {Manifest.permission.BLUETOOTH_CONNECT}, request.requestedPermissions);
    assertEquals("builtin", app.settings.audio.source);
    assertEquals(0, hardware.opens.get());
  }

  @Test
  public void recordingSwitchRequiresConfirmationAndKeepsSpeechConnection() {
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
    AtomicInteger finishes = new AtomicInteger(), speechStarts = new AtomicInteger();
    var factory =
        new SessionEngine.Factory() {
          public AudioSource audio() {
            return app.audio.source();
          }

          public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
            return new SpeechProvider() {
              public void start(Listener l) {
                speechStarts.incrementAndGet();
                l.ready();
              }

              public void sendAudio(byte[] pcm) {}

              public void finish() {
                finishes.incrementAndGet();
              }

              public void cancel() {}
            };
          }

          public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
            throw new AssertionError("No analysis while switching");
          }
        };
    app.meetings.close();
    app.bindMeetings(
        new MeetingManager(
            new MeetingManager.MemoryStore(),
            () -> new SessionEngine(factory),
            app.settings.snapshot(),
            false));
    idle();
    ProviderConfig config = new ProviderConfig();
    config.asrUrl = "wss://example.org/asr";
    config.textBaseUrl = "https://example.org/v1";
    app.session.start(
        config, new ProviderConfig.Credentials("test", "test"), app.settings.snapshot());
    AudioInputControllerTest.await(() -> app.audio.state() == AudioInputController.State.CAPTURING);
    idle();
    Dialog d = drawer();
    choose(d, 1);
    AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
    assertEquals(1, hardware.opens.get());
    assertEquals("builtin", app.settings.audio.source);
    confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    idle();
    assertEquals("builtin", app.settings.audio.source);
    RadioGroup radios = d.findViewById(R.id.audio_source);
    assertTrue(((RadioButton) radios.getChildAt(0)).isChecked());
    assertFalse(((RadioButton) radios.getChildAt(1)).isChecked());
    choose(d, 1);
    confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
    confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    idle();
    try {
      AudioInputControllerTest.await(
          () ->
              hardware.opens.get() == 2
                  && app.audio.state() == AudioInputController.State.CAPTURING);
    } catch (AssertionError e) {
      throw new AssertionError(
          "opens="
              + hardware.opens.get()
              + " state="
              + app.audio.state()
              + " error="
              + app.audio.error()
              + " selected="
              + app.audio.selected()
              + " session="
              + app.session.snapshot().status,
          e);
    }
    idle();
    assertEquals(1, speechStarts.get());
    assertEquals(0, finishes.get());
    assertEquals(SessionEngine.State.LISTENING, app.session.snapshot().state);
    assertTrue(app.session.snapshot().transcript.contains("应用事件"));
  }

  @Test
  public void shortcutsInsideSettingsDoNotStartListening() {
    settings();
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE));
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE));
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    assertEquals(0, hardware.opens.get());
  }

  @Test
  public void englishLandscapeAndFoldSettingsAreScrollableAndSurviveRecreation() throws Exception {
    RuntimeEnvironment.setQualifiers("en-rUS-w852dp-h393dp-land-mdpi");
    lifecycle.recreate();
    activity = lifecycle.get();
    idle();
    Dialog d = drawer();
    assertEquals(
        "Built-in microphone",
        ((RadioButton) ((RadioGroup) d.findViewById(R.id.audio_source)).getChildAt(0)).getText());
    screenshot(d.getWindow().getDecorView(), "audio-input-drawer-landscape", 360, 393);
    d.findViewById(R.id.audio_settings).performClick();
    idle();
    Dialog page = ShadowDialog.getLatestDialog();
    screenshot(page.getWindow().getDecorView(), "audio-settings-landscape", 852, 393);
    lifecycle.recreate();
    activity = lifecycle.get();
    idle();
    assertNotNull(ShadowDialog.getLatestDialog().findViewById(R.id.audio_test));
    RuntimeEnvironment.setQualifiers("en-rUS-w841dp-h701dp-land-mdpi");
    lifecycle.recreate();
    activity = lifecycle.get();
    idle();
    screenshot(
        ShadowDialog.getLatestDialog().getWindow().getDecorView(), "audio-settings-fold", 841, 701);
    assertEquals(0, hardware.opens.get());
  }

  @Test
  public void legacySettingsKeepProfilesAndDefaultAudioSelection() {
    var prefs = app.getSharedPreferences("audio-migration-test", 0);
    prefs
        .edit()
        .putString(
            "settings", "{\"provider\":{},\"profiles\":[],\"bindings\":{},\"selectedId\":\"\"}")
        .commit();
    UserSettings migrated = new SettingsRepository(app, "audio-migration-test").load();
    assertEquals("builtin", migrated.audio.source);
    assertTrue(migrated.profiles.isEmpty());
  }
}
