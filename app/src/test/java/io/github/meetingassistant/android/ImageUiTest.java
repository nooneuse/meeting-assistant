package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "zh-rCN-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageUiTest {
  MeetingApp app;
  MainActivity activity;
  ActivityController<MainActivity> lifecycle;
  FakeSources sources;

  static class FakeSource implements ImageSource {
    StateListener listener;
    boolean closed;
    int rotation;

    public void open(StateListener l) {
      listener = l;
      l.ready();
    }

    public void capture(Capture cb) {
      Bitmap bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(0xffeeeeee);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
      bitmap.recycle();
      cb.frame(new Frame(out.toByteArray(), 80, 60, rotation, false));
    }

    public void close() {
      closed = true;
    }
  }

  static class FakeSources implements ImageInputController.Sources {
    List<ImageSource.Device> devices =
        new ArrayList<>(
            List.of(
                new ImageSource.Device("camera:back", ImageSource.Kind.BACK),
                new ImageSource.Device("usb:external", ImageSource.Kind.USB)));
    FakeSource latest;

    public List<ImageSource.Device> list() {
      return new ArrayList<>(devices);
    }

    public ImageSource create(String id) {
      latest = new FakeSource();
      return latest;
    }
  }

  @Before
  public void setup() {
    app = (MeetingApp) RuntimeEnvironment.getApplication();
    sources = new FakeSources();
    app.video = new ImageInputController(app, app.settings.image, sources);
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
    lifecycle = Robolectric.buildActivity(MainActivity.class).setup().visible();
    activity = lifecycle.get();
    idle();
    layout();
  }

  @After
  public void teardown() {
    lifecycle.pause().stop().destroy();
    app.meetings.close();
    app.imageJobs.close();
  }

  void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
  }

  void layout() {
    View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 393, 852);
  }

  void key(int code) {
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    idle();
  }

  void enable() {
    app.video.setEnabled(true);
    idle();
  }

  private TextView findText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView result = findText(((ViewGroup) view).getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private void screenshot(View view, String name, int width, int height) throws Exception {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new android.graphics.Canvas(bitmap));
    java.io.File folder = new java.io.File(System.getProperty("meeting.preview.dir"));
    assertTrue(folder.isDirectory() || folder.mkdirs());
    try (java.io.FileOutputStream out =
        new java.io.FileOutputStream(new java.io.File(folder, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  @Test
  public void imageSettingsReachableFromRightDrawerAndReturnsThere() throws Exception {
    enable();
    layout();
    View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    screenshot(root, "image-input-main-portrait", 393, 852);
    Dialog drawer = openDrawer();
    View right = drawer.getWindow().getDecorView();
    screenshot(right, "image-input-drawer", 338, 852);
    TextView settingsButton = findText(right, activity.getString(R.string.image_settings));
    assertNotNull(settingsButton);
    settingsButton.performClick();
    idle();
    Dialog page = ShadowDialog.getLatestDialog();
    assertNotNull(page.findViewById(R.id.image_preview));
    assertTrue(app.video.enabled());
    screenshot(page.getWindow().getDecorView(), "image-input-settings", 393, 852);
    page.findViewById(R.id.image_settings_back).performClick();
    idle();
    assertFalse(page.isShowing());
    assertTrue(ShadowDialog.getLatestDialog().isShowing());
  }

  Dialog openDrawer() {
    activity.findViewById(R.id.image_input_button).performClick();
    idle();
    return ShadowDialog.getLatestDialog();
  }

  @Test
  public void inputOffHidesCaptureAndDrawerRemainsReachable() {
    assertEquals(View.GONE, activity.findViewById(R.id.image_capture_button).getVisibility());
    Dialog drawer = openDrawer();
    assertTrue(drawer.isShowing());
    assertFalse(((Switch) drawer.findViewById(R.id.video_enabled)).isChecked());
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
  }

  @Test
  public void togglingVideoShowsCaptureWithoutStartingMicrophone() {
    Dialog drawer = openDrawer();
    ((Switch) drawer.findViewById(R.id.video_enabled)).performClick();
    idle();
    assertEquals(ImageInputController.State.READY, app.video.state());
    assertEquals(View.VISIBLE, activity.findViewById(R.id.image_capture_button).getVisibility());
    drawer.dismiss();
    assertTrue(app.video.enabled());
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    app.video.setEnabled(false);
    idle();
    assertEquals(View.GONE, activity.findViewById(R.id.image_capture_button).getVisibility());
    assertTrue(sources.latest.closed);
  }

  @Test
  public void drawersAreMutuallyExclusiveAndOpeningDoesNotChangeSource() {
    Dialog right = openDrawer();
    right.dismiss();
    activity.findViewById(R.id.meetings_button).performClick();
    Dialog left = ShadowDialog.getLatestDialog();
    activity.findViewById(R.id.image_input_button).performClick();
    assertFalse(left.isShowing());
    assertTrue(ShadowDialog.getLatestDialog().isShowing());
  }

  @Test
  public void captureKeyOffDoesNotImplicitlyOpenCameraAndRepeatDoesNotToggle() {
    app.settings.bind(UserSettings.IMAGE_CAPTURE, KeyEvent.KEYCODE_C);
    key(KeyEvent.KEYCODE_C);
    assertFalse(app.video.enabled());
    assertEquals("请先开启视频输入", ShadowToast.getTextOfLatestToast());
    app.settings.bind(UserSettings.IMAGE_TOGGLE, KeyEvent.KEYCODE_V);
    activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0));
    idle();
    assertTrue(app.video.enabled());
    activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 5));
    assertTrue(app.video.enabled());
    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V));
    key(KeyEvent.KEYCODE_V);
    assertFalse(app.video.enabled());
  }

  @Test
  public void sourceSelectionWhileOffDoesNotOpenDeviceAndMissingSourceNeverFallsBack() {
    app.video.select("usb:external");
    assertNull(sources.latest);
    sources.devices.remove(1);
    enable();
    assertEquals(ImageInputController.State.ERROR, app.video.state());
    assertNull(sources.latest);
    assertEquals("usb:external", app.settings.image.sourceId);
  }

  @Test
  public void switchingSourceClosesOldAndLateEventsCannotReopenIt() {
    enable();
    FakeSource old = sources.latest;
    app.video.select("usb:external");
    idle();
    assertTrue(old.closed);
    app.video.setEnabled(false);
    old.listener.ready();
    idle();
    assertEquals(ImageInputController.State.OFF, app.video.state());
  }

  @Test
  public void sourceFailureDoesNotModifyListeningSessionAndDisablesCapture() {
    enable();
    sources.latest.listener.failed("图像来源已断开");
    idle();
    assertEquals(ImageInputController.State.ERROR, app.video.state());
    assertFalse(activity.findViewById(R.id.image_capture_button).isEnabled());
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
    assertTrue(
        ((TextView) activity.findViewById(R.id.meeting_status))
            .getText()
            .toString()
            .contains("图像来源已断开"));
  }

  @Test
  public void englishControlsAndConfigurationChangeKeepVideoAvailable() throws Exception {
    enable();
    FakeSource source = sources.latest;
    RuntimeEnvironment.setQualifiers("en-rUS-w393dp-h852dp-port-mdpi");
    lifecycle.recreate();
    activity = lifecycle.get();
    idle();
    layout();
    assertTrue(app.video.enabled());
    assertFalse(source.closed);
    assertEquals(
        "Input sources", activity.findViewById(R.id.image_input_button).getContentDescription());
    assertEquals("Text", ((TextView) activity.findViewById(R.id.transcript_toggle)).getText());
    screenshot(
        ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0),
        "image-input-main-english",
        393,
        852);
    Dialog drawer = openDrawer();
    assertEquals("Video input", ((Switch) drawer.findViewById(R.id.video_enabled)).getText());
    key(KeyEvent.KEYCODE_SPACE);
    assertEquals(SessionEngine.State.IDLE, app.session.snapshot().state);
  }

  @Test
  public void capturedRegionIsCroppedAndKeepsOriginalCoordinateMapping() throws Exception {
    enable();
    ImageSettings s = app.settings.image;
    s.cropSource = s.sourceId;
    s.cropWidth = 80;
    s.cropHeight = 60;
    s.left = .25f;
    s.top = .25f;
    s.right = .75f;
    s.bottom = .75f;
    AtomicReference<ImageInputController.Prepared> result = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    app.video.capture(result::set, error::set);
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (result.get() == null && error.get() == null && System.nanoTime() < end) {
      idle();
      Thread.yield();
    }
    assertNull(error.get());
    assertNotNull(result.get());
    assertEquals(40, result.get().evidence().width);
    assertEquals(30, result.get().evidence().height);
    assertEquals(80, result.get().evidence().originalWidth);
    assertEquals(.25f, result.get().evidence().cropLeft, .001f);
    assertEquals(0, app.session.imageCount());
  }

  @Test
  public void changingDimensionsInvalidatesSavedCropRatherThanUsingWrongRegion() throws Exception {
    enable();
    ImageSettings s = app.settings.image;
    s.cropSource = s.sourceId;
    s.cropWidth = 800;
    s.cropHeight = 600;
    s.left = .25f;
    s.top = .25f;
    s.right = .75f;
    s.bottom = .75f;
    AtomicReference<ImageInputController.Prepared> result = new AtomicReference<>();
    app.video.capture(result::set, e -> fail(e));
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (result.get() == null && System.nanoTime() < end) {
      idle();
      Thread.yield();
    }
    assertNotNull(result.get());
    assertEquals(80, result.get().evidence().width);
    assertEquals(1f, result.get().evidence().cropRight, .001f);
  }

  @Test
  public void halfTurnInvalidatesCropEvenWhenPixelDimensionsAreUnchanged() throws Exception {
    enable();
    sources.latest.rotation = 180;
    ImageSettings crop = app.settings.image;
    crop.cropSource = crop.sourceId;
    crop.cropWidth = 80;
    crop.cropHeight = 60;
    crop.left = crop.top = .25f;
    crop.right = crop.bottom = .75f;
    crop.cropRotation = 0;
    AtomicReference<ImageInputController.Prepared> result = new AtomicReference<>();
    app.video.capture(result::set, e -> fail(e));
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (result.get() == null && System.nanoTime() < end) {
      idle();
      Thread.yield();
    }
    assertNotNull(result.get());
    assertEquals(80, result.get().evidence().width);
    assertEquals(180, result.get().evidence().rotation);
    assertEquals(0f, result.get().evidence().cropLeft, .001f);
  }

  @Test
  public void backgroundReleasesCameraWithoutAutoRestart() {
    enable();
    FakeSource current = sources.latest;
    lifecycle.pause().stop();
    assertTrue(current.closed);
    assertFalse(app.video.enabled());
    lifecycle.start().resume();
    idle();
    assertFalse(app.video.enabled());
  }

  @Test
  public void cameraSizeChoiceKeepsTextResolutionInsideMemoryBudget() {
    android.util.Size picked =
        CameraImageSource.choose(
            new android.util.Size[] {
              new android.util.Size(4000, 3000),
              new android.util.Size(2560, 1440),
              new android.util.Size(640, 480)
            },
            8_000_000);
    assertEquals(2560, picked.getWidth());
  }

  @Test
  public void startListeningButtonStaysEnabledWhileImageOnlyAnswerGenerates() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    java.util.function.Supplier<SessionEngine> factory =
        () ->
            new SessionEngine(
                new SessionEngine.Factory() {
                  public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                    throw new AssertionError("No speech requested");
                  }

                  public AudioSource audio() {
                    throw new AssertionError("No microphone requested");
                  }

                  public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                    return new TextProvider() {
                      public void analyze(com.google.gson.JsonArray messages, Listener listener) {
                        started.countDown();
                      }

                      public void cancel() {}
                    };
                  }
                });
    app.meetings.close();
    app.bindMeetings(
        new MeetingManager(
            new MeetingManager.MemoryStore(), factory, app.settings.snapshot(), false));
    idle();
    ImageEvidence image = new ImageEvidence();
    image.preference = app.settings.snapshot();
    assertTrue(app.session.acceptImage(image, () -> {}));
    ProviderConfig config = new ProviderConfig();
    config.textBaseUrl = "https://example.org/v1";
    app.session.imageRecognized(
        image.id,
        "{\"blocks\":[{\"id\":\"B1\",\"text\":\"Question\",\"box\":[0,0,1,1],\"order\":0,\"role_inference\":\"Unknown\"}],\"uncertainties\":[]}",
        config,
        new ProviderConfig.Credentials("", "test"),
        image.preference);
    assertTrue(started.await(3, TimeUnit.SECONDS));
    idle();
    assertEquals(SessionEngine.State.ANALYZING, app.session.snapshot().state);
    TextView record =
        findText(((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0), "开始听");
    assertNotNull(record);
    assertTrue(record.isEnabled());
  }

  @Test
  public void usbPrefersMjpegAndLowerSupportedFrameRateWithoutMutatingCapabilities() {
    var raw = new com.serenegiant.usb.Size(5, 1920, 1080, 30, new ArrayList<>(List.of(15, 30)));
    var jpeg =
        new com.serenegiant.usb.Size(7, 1920, 1080, 30, new ArrayList<>(List.of(10, 15, 30)));
    var selected = UsbImageSource.chooseSize(List.of(raw, jpeg));
    assertEquals(7, selected.type);
    assertEquals(10, selected.fps);
    assertEquals(30, jpeg.fps);
    assertThrows(IllegalArgumentException.class, () -> UsbImageSource.chooseSize(List.of()));
  }

  private static byte[] usbFrame(int kind, int width, int height, int... intervals) {
    java.nio.ByteBuffer b =
        java.nio.ByteBuffer.allocate(26 + 4 * intervals.length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put(0, (byte) b.capacity());
    b.put(1, (byte) 0x24);
    b.put(2, (byte) kind);
    b.put(3, (byte) 1);
    b.putShort(5, (short) width);
    b.putShort(7, (short) height);
    b.putInt(21, intervals[0]);
    b.put(25, (byte) intervals.length);
    for (int i = 0; i < intervals.length; i++) b.putInt(26 + 4 * i, intervals[i]);
    return b.array();
  }

  private static byte[] join(byte[]... parts) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part, 0, part.length);
    return out.toByteArray();
  }

  private static byte[] videoInterface() {
    return new byte[] {9, 4, 1, 0, 1, 14, 2, 0, 0};
  }

  @Test
  public void usbInitialFormatUsesFrameDescriptorsInsteadOfNativeMaximum() {
    byte[] descriptors =
        join(
            videoInterface(),
            usbFrame(7, 3840, 2160, 166666),
            usbFrame(7, 1920, 1080, 166666, 333333),
            usbFrame(5, 1280, 720, 166666));
    var sizes = UsbImageSource.descriptorSizes(descriptors);
    assertEquals(3, sizes.size());
    var chosen = UsbImageSource.chooseSize(sizes);
    assertEquals(1920, chosen.width);
    assertEquals(1080, chosen.height);
    assertEquals(30, chosen.fps);
    assertEquals(7, chosen.type);
  }

  @Test
  public void usbDescriptorParserRejectsTruncationAndIgnoresOtherInterfaces() {
    byte[] audio = new byte[] {9, 4, 2, 0, 0, 1, 2, 0, 0};
    assertTrue(
        UsbImageSource.descriptorSizes(join(audio, usbFrame(7, 1920, 1080, 166666))).isEmpty());
    byte[] valid = join(videoInterface(), usbFrame(5, 640, 480, 166666));
    assertEquals(1, UsbImageSource.descriptorSizes(valid).size());
    assertTrue(
        UsbImageSource.descriptorSizes(java.util.Arrays.copyOf(valid, valid.length - 1)).isEmpty());
    assertTrue(UsbImageSource.descriptorSizes(new byte[] {0, 4}).isEmpty());
    assertTrue(
        UsbImageSource.descriptorSizes(join(videoInterface(), usbFrame(5, 0, 480, 166666)))
            .isEmpty());
  }

  @Test
  public void usbDescriptorParserSupportsContinuousIntervalsWithoutExpandingRange() {
    byte[] frame = java.util.Arrays.copyOf(usbFrame(7, 1920, 1080, 333333), 38);
    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put(0, (byte) 38);
    b.put(25, (byte) 0);
    b.putInt(26, 166666);
    b.putInt(30, 1000000);
    b.putInt(34, 1);
    var chosen =
        UsbImageSource.chooseSize(UsbImageSource.descriptorSizes(join(videoInterface(), frame)));
    assertEquals(10, chosen.fps);
    assertTrue(chosen.fpsList.size() <= 3);
  }
}
