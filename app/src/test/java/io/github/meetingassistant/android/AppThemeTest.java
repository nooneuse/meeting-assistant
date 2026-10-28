package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.app.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.ColorUtils;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

@RunWith(RobolectricTestRunner.class)
@Config(
    application = TestMeetingApp.class,
    sdk = 35,
    qualifiers = "zh-rCN-w393dp-h852dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppThemeTest {
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
    app.imageJobs.close();
  }

  void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
  }

  View root() {
    return ((ViewGroup) screen.findViewById(android.R.id.content)).getChildAt(0);
  }

  void night(boolean dark) {
    Configuration next = new Configuration(app.getResources().getConfiguration());
    next.uiMode =
        (next.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
    lifecycle.configurationChange(next);
    screen = lifecycle.get();
    idle();
  }

  void settings() {
    screen.findViewById(R.id.meetings_button).performClick();
    idle();
    ShadowDialog.getLatestDialog().findViewById(R.id.drawer_settings).performClick();
    idle();
  }

  int background(View view) {
    return ((ColorDrawable) view.getBackground()).getColor();
  }

  void contrast(AppTheme.Palette p) {
    for (int[] pair :
        new int[][] {
          {AppTheme.TEXT, AppTheme.SURFACE},
          {AppTheme.TEXT, AppTheme.PANEL},
          {AppTheme.MUTED, AppTheme.SURFACE},
          {AppTheme.ON_PRIMARY, AppTheme.PRIMARY},
          {AppTheme.ON_SELECTED, AppTheme.SELECTED},
          {AppTheme.ON_AUTO, AppTheme.AUTO},
          {AppTheme.ERROR, AppTheme.SURFACE}
        })
      assertTrue(
          "Insufficient contrast for role " + pair[0],
          ColorUtils.calculateContrast(p.color(pair[0]), p.color(pair[1])) >= 4.5);
  }

  @Test
  public void dayNightUpdatesExistingViewsSystemBarsAndAutoWithoutChangingItsState()
      throws Exception {
    View original = root();
    MainActivity activity = screen;
    Button auto = screen.findViewById(R.id.auto_button);
    auto.performClick();
    idle();
    int light = background(root());
    assertTrue(auto.getText().toString().startsWith("✓"));
    contrast(AppTheme.colors(screen));
    capture("theme-light");
    night(true);
    assertSame(activity, screen);
    assertSame(original, root());
    assertSame(auto, screen.findViewById(R.id.auto_button));
    assertTrue(AppTheme.colors(screen).dark);
    assertNotEquals(light, background(root()));
    assertTrue(app.settings.automatic);
    assertTrue(auto.isSelected());
    assertEquals(AppTheme.color(screen, AppTheme.ON_AUTO), auto.getCurrentTextColor());
    assertEquals(
        0,
        screen.getWindow().getDecorView().getWindowInsetsController().getSystemBarsAppearance()
            & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    Button transcript = screen.findViewById(R.id.transcript_toggle);
    assertEquals(
        AppTheme.color(screen, AppTheme.PANEL),
        ((GradientDrawable) ((RippleDrawable) transcript.getBackground()).getDrawable(0))
            .getColor()
            .getDefaultColor());
    assertEquals(AppTheme.color(screen, AppTheme.TEXT), transcript.getCurrentTextColor());
    contrast(AppTheme.colors(screen));
    capture("theme-dark");
    night(false);
    assertFalse(AppTheme.colors(screen).dark);
    assertEquals(light, background(root()));
  }

  @Test
  public void switchingWhileEditingKeepsSameFieldsFocusUnsavedSecretAndExpandedSection() {
    settings();
    SettingsSection models = screen.findViewById(R.id.settings_models);
    models.setExpanded(true);
    ((SettingsSection) screen.findViewById(R.id.settings_image_model)).setExpanded(true);
    EditText field = screen.findViewById(R.id.image_model_key);
    field.setText("synthetic-unsaved-secret");
    field.requestFocus();
    field.setSelection(3, 8);
    night(true);
    assertSame(field, screen.findViewById(R.id.image_model_key));
    assertEquals("synthetic-unsaved-secret", field.getText().toString());
    assertTrue(field.hasFocus());
    assertEquals(3, field.getSelectionStart());
    assertEquals(8, field.getSelectionEnd());
    assertTrue(models.expanded());
    assertTrue(((SettingsSection) screen.findViewById(R.id.settings_image_model)).expanded());
    assertFalse(field.isSaveEnabled());
    assertFalse(app.repository.imageKey().equals("synthetic-unsaved-secret"));
    screen.findViewById(R.id.settings_back).performClick();
    idle();
    assertNotNull(ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE));
  }

  @Test
  public void configurationRecreationRetainsModelDraftInMemoryWithoutWritingBundle() {
    settings();
    ((SettingsSection) screen.findViewById(R.id.settings_models)).setExpanded(true);
    ((SettingsSection) screen.findViewById(R.id.settings_image_model)).setExpanded(true);
    EditText field = screen.findViewById(R.id.image_model_key);
    field.setText("synthetic-memory-only");
    Bundle state = new Bundle();
    screen.onSaveInstanceState(state);
    assertFalse(state.toString().contains("synthetic-memory-only"));
    lifecycle.recreate();
    screen = lifecycle.get();
    idle();
    assertEquals(
        "synthetic-memory-only",
        ((EditText) screen.findViewById(R.id.image_model_key)).getText().toString());
    assertFalse(app.repository.imageKey().equals("synthetic-memory-only"));
  }

  @Test
  public void openDrawerAndNativeDialogRecolorWithoutBeingDismissed() {
    screen.findViewById(R.id.image_input_button).performClick();
    idle();
    Dialog drawer = ShadowDialog.getLatestDialog();
    RadioGroup sources = drawer.findViewById(R.id.image_sources);
    int checked = sources.getCheckedRadioButtonId();
    ProgressBar meter = drawer.findViewById(R.id.audio_level);
    night(true);
    assertTrue(drawer.isShowing());
    assertSame(sources, drawer.findViewById(R.id.image_sources));
    assertEquals(checked, sources.getCheckedRadioButtonId());
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE), background(drawer.getWindow().getDecorView()));
    assertEquals(
        AppTheme.color(screen, AppTheme.SURFACE),
        meter.getProgressBackgroundTintList().getDefaultColor());
    drawer.dismiss();
    idle();
    AlertDialog dialog =
        AppTheme.danger(screen)
            .setTitle("Fixture")
            .setMessage("Theme text")
            .setPositiveButton("OK", null)
            .create();
    dialog.show();
    idle();
    night(false);
    assertTrue(dialog.isShowing());
    assertEquals(
        AppTheme.color(screen, AppTheme.ERROR),
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());
    dialog.dismiss();
  }

  @Test
  public void markdownSpansAndReadingPositionUpdateInPlace() {
    SessionEngine.SavedState state = new SessionEngine.SavedState();
    state.state = SessionEngine.State.DONE;
    state.answer = "```cpp\n" + "int answer = 42;\n".repeat(120) + "```";
    app.session.restore(state);
    idle();
    for (int i = 0; i < 3; i++) {
      root()
          .measure(
              View.MeasureSpec.makeMeasureSpec(393, 1073741824),
              View.MeasureSpec.makeMeasureSpec(852, 1073741824));
      root().layout(0, 0, 393, 852);
      idle();
    }
    LinearLayout panel = screen.findViewById(R.id.answer_panel);
    ScrollView pane = (ScrollView) panel.getChildAt(1);
    TextView text = (TextView) pane.getChildAt(0);
    app.followAnswer = false;
    app.answerScroll = 400;
    pane.scrollTo(0, 400);
    assertEquals(400, pane.getScrollY());
    CharSequence original = text.getText();
    night(true);
    assertSame(original, text.getText());
    assertEquals(400, pane.getScrollY());
    AppTheme.ColorSpan[] spans =
        ((Spanned) text.getText()).getSpans(0, text.length(), AppTheme.ColorSpan.class);
    assertTrue(spans.length >= 2);
    TextPaint paint = new TextPaint();
    for (AppTheme.ColorSpan span : spans) span.updateDrawState(paint);
    assertEquals(AppTheme.color(screen, AppTheme.TEXT), paint.getColor());
    assertEquals(AppTheme.color(screen, AppTheme.PANEL), paint.bgColor);
  }

  @Test
  public void listeningAndPendingOcrContinueAcrossThemeChangeWithNoRepeatedAnalysis()
      throws Exception {
    AtomicReference<SpeechProvider.Listener> speech = new AtomicReference<>();
    AtomicReference<AudioSource.Listener> audio = new AtomicReference<>();
    AtomicInteger starts = new AtomicInteger(),
        pcm = new AtomicInteger(),
        canceled = new AtomicInteger(),
        requests = new AtomicInteger();
    BlockingQueue<TextProvider.Listener> answers = new LinkedBlockingQueue<>();
    app.meetings.close();
    app.bindMeetings(
        new MeetingManager(
            new MeetingManager.MemoryStore(),
            () ->
                new SessionEngine(
                    new SessionEngine.Factory() {
                      public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                        return new SpeechProvider() {
                          public void start(Listener listener) {
                            speech.set(listener);
                          }

                          public void sendAudio(byte[] bytes) {
                            pcm.incrementAndGet();
                          }

                          public void finish() {}

                          public void cancel() {
                            canceled.incrementAndGet();
                          }
                        };
                      }

                      public AudioSource audio() {
                        return new AudioSource() {
                          public void start(Listener listener) {
                            starts.incrementAndGet();
                            audio.set(listener);
                          }

                          public void stop(Runnable done) {
                            done.run();
                          }

                          public void cancel() {
                            canceled.incrementAndGet();
                          }
                        };
                      }

                      public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                        return new TextProvider() {
                          public void analyze(
                              com.google.gson.JsonArray messages, Listener listener) {
                            requests.incrementAndGet();
                            answers.add(listener);
                          }

                          public void cancel() {}
                        };
                      }
                    }),
            app.settings.snapshot(),
            false));
    idle();
    ProviderConfig config = new ProviderConfig();
    config.asrUrl = "wss://example.org/asr";
    config.textBaseUrl = "https://example.org/v1";
    ProviderConfig.Credentials credentials = new ProviderConfig.Credentials("fixture", "fixture");
    app.session.start(config, credentials, app.settings.snapshot());
    speech.get().ready();
    audio.get().audio(new byte[320]);
    ImageEvidence image = new ImageEvidence();
    assertTrue(app.session.acceptImage(image, canceled::incrementAndGet));
    SessionEngine engine = app.session;
    String topic = app.meetings.currentId();
    night(true);
    assertSame(engine, app.session);
    assertEquals(topic, app.meetings.currentId());
    audio.get().audio(new byte[320]);
    speech.get().segment(new SpeechProvider.Segment(1, "语音继续完整接收", true, 0, 500));
    app.session.imageRecognized(
        image.id,
        "{\"blocks\":[{\"id\":\"b1\",\"text\":\"算法问题\",\"box\":[0,0,1,1],\"order\":0,\"role_inference\":\"question\"}],\"uncertainties\":[]}",
        config,
        credentials,
        app.settings.selected());
    TextProvider.Listener answer = answers.poll(2, TimeUnit.SECONDS);
    assertNotNull(answer);
    answer.delta("主题切换后继续回答");
    night(false);
    answer.completed(false);
    idle();
    assertEquals(1, starts.get());
    assertEquals(2, pcm.get());
    assertEquals(0, canceled.get());
    assertEquals(1, requests.get());
    assertEquals(SessionEngine.State.LISTENING, engine.snapshot().state);
    assertTrue(engine.snapshot().transcript.contains("语音继续完整接收"));
    assertEquals(ImageEvidence.State.READY, engine.images().get(0).state);
    assertTrue(engine.snapshot().answer.contains("主题切换后继续回答"));
  }

  @Test
  @Config(sdk = 26)
  public void android8UsesReadableFallbackWithoutDynamicColors() {
    assertFalse(AppTheme.colors(screen).dark);
    contrast(AppTheme.colors(screen));
    night(true);
    assertTrue(AppTheme.colors(screen).dark);
    contrast(AppTheme.colors(screen));
    assertEquals(AppTheme.color(screen, AppTheme.SURFACE), background(root()));
  }

  @Test
  public void alternativePalettesRecolorRetainedControlsAndRemainReadable() {
    View original = root();
    Button auto = screen.findViewById(R.id.auto_button);
    auto.performClick();
    idle();
    int previous = auto.getCurrentTextColor();
    for (int style :
        new int[] {
          com.google.android.material.R.style.Theme_Material3_Light_NoActionBar,
          com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar
        }) {
      android.view.ContextThemeWrapper context =
          new android.view.ContextThemeWrapper(screen, style);
      AppTheme.Palette palette = new AppTheme.Palette(context);
      contrast(palette);
      AppTheme.apply(screen, palette);
      assertSame(original, root());
      assertEquals(palette.color(AppTheme.ON_AUTO), auto.getCurrentTextColor());
    }
    assertNotEquals(previous, auto.getCurrentTextColor());
  }

  @Test
  @Config(sdk = 31)
  public void supportedAndroid12ReadsSystemAccentTonesInBothModes() {
    ShadowBuild.setManufacturer("Google");
    assertTrue(com.google.android.material.color.DynamicColors.isDynamicColorAvailable());
    AppTheme.refresh(screen);
    assertEquals(
        screen.getColor(android.R.color.system_accent1_600),
        AppTheme.color(screen, AppTheme.PRIMARY));
    night(true);
    assertEquals(
        screen.getColor(android.R.color.system_accent1_200),
        AppTheme.color(screen, AppTheme.PRIMARY));
    contrast(AppTheme.colors(screen));
  }

  void capture(String name) throws Exception {
    View view = root();
    int w = screen.getResources().getConfiguration().screenWidthDp,
        h = screen.getResources().getConfiguration().screenHeightDp;
    view.measure(
        View.MeasureSpec.makeMeasureSpec(w, 1073741824),
        View.MeasureSpec.makeMeasureSpec(h, 1073741824));
    view.layout(0, 0, w, h);
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File out = new File(System.getProperty("meeting.preview.dir"), name + ".png");
    out.getParentFile().mkdirs();
    try (FileOutputStream stream = new FileOutputStream(out)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
    }
    bitmap.recycle();
  }
}
