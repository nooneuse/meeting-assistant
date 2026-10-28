package io.github.meetingassistant.android;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.BaseInputConnection;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import io.github.meetingassistant.BuildConfig;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.ProviderConfig;
import io.github.meetingassistant.core.SessionEngine;
import io.github.meetingassistant.core.UserSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Foreground-only UI. Session ownership belongs to the Application, not this Activity. */
public final class MainActivity extends Activity {
  private static final int BG = AppTheme.SURFACE,
      PANEL = AppTheme.PANEL,
      TEXT = AppTheme.TEXT,
      MUTED = AppTheme.MUTED,
      ACCENT = AppTheme.PRIMARY;
  private static final int TOP_BAR_DP = 56, BOTTOM_BAR_DP = 60;
  private MeetingApp app;
  private LinearLayout root, body;
  private TextView preference, answer, transcript, answerHeading;
  private ScrollView answerPane, transcriptPane;
  private Button record, raw, retry, cancel, auto;
  private ImageButton awake, meetingsButton, historyButton, imageButton, captureImage;
  private ImageInputUi imageUi;
  private AudioInputUi audioUi;
  private AudioInputController.State renderedAudioState;
  private ContextUi contextUi;
  private TopicCollectionsUi topicCollections;
  private boolean cameraPermissionPending;
  private android.app.Dialog drawerDialog;
  private AlertDialog managedDialog;
  private LinearLayout drawerList;
  private SessionEngine observedSession;
  private Runnable meetingChanges;
  private long bindingVersion;
  private String displayedStorageError = "";
  private FrameLayout toolbar, controlsBar;
  private boolean barsShown = true, menuDialogOpen, selectingText;
  private boolean touching, tapCandidate, trackingAnswerScroll;
  private boolean windowFocused = true;
  private float touchX, touchY;
  private long touchStarted;
  private ValueAnimator barsAnimator;
  private final Runnable hideBars =
      () -> {
        if (canHideBars()) setBarsShown(false, true);
      };
  private SessionEngine.Snapshot snapshot;
  private boolean settingsScreen, foreground, scrollProgrammatic, modelDirty;
  private java.util.List<EditText> modelForm = java.util.List.of();

  @SuppressLint("UseSwitchCompatOrMaterialCode") // Created and styled exclusively by UiControls.
  private Switch modelFormat;

  private ScrollView settingsScroll;

  private static final class ModelDraft {
    final String[] values;
    final int[] start, end;
    final boolean format, dirty;
    final int scroll, focus;

    ModelDraft(MainActivity screen) {
      values = new String[screen.modelForm.size()];
      start = new int[values.length];
      end = new int[values.length];
      int focused = -1;
      for (int i = 0; i < values.length; i++) {
        EditText field = screen.modelForm.get(i);
        values[i] = field.getText().toString();
        start[i] = field.getSelectionStart();
        end[i] = field.getSelectionEnd();
        if (field.hasFocus()) focused = i;
      }
      format = screen.modelFormat.isChecked();
      dirty = screen.modelDirty;
      scroll = screen.settingsScroll.getScrollY();
      focus = focused;
    }
  }

  private int layoutMode = -1;
  private String lastAnswer = "", lastTranscript = "";
  private String captureAction;
  private AlertDialog captureDialog, preferenceDialog;
  private Toast currentToast;
  private boolean stateHint;
  private LocalizedMessages localizedMessages;
  private Runnable removeBackCallback;
  private LinearLayout keyList;
  private java.util.function.Consumer<SessionEngine.Snapshot> observer;
  private volatile SessionEngine.Snapshot pendingSnapshot;
  private final java.util.concurrent.atomic.AtomicBoolean uiScheduled =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Set<Integer> consumedKeys = new HashSet<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (foreground && !settingsScreen && snapshot != null) updateAnswerHeading();
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  protected void attachBaseContext(android.content.Context base) {
    super.attachBaseContext(UiLocale.wrap(base));
  }

  @Override
  public void onCreate(Bundle savedState) {
    AppTheme.prepare(this);
    super.onCreate(savedState);
    AppTheme.start(this);
    localizedMessages = new LocalizedMessages(this);
    app = (MeetingApp) getApplication();
    contextUi = new ContextUi(this, app, carry -> requestMeetingSwitch(null, carry));
    topicCollections =
        new TopicCollectionsUi(
            this,
            app,
            id -> {
              if (drawerDialog != null) drawerDialog.dismiss();
              requestMeetingSwitch(id);
            },
            this::meetingActions,
            this::requestCollectionSwitch);
    audioUi = new AudioInputUi(this, app, () -> scheduleBarsHide(4000));
    imageUi =
        new ImageInputUi(
            this,
            app,
            this::enableVideo,
            audioUi,
            () -> {
              refreshVideo();
              scheduleBarsHide(4000);
            });
    app.video.observe(
        () -> {
          if (isDestroyed()) return;
          refreshVideo();
          imageUi.refresh();
          updateAnswerHeading();
        });
    app.audio.observe(
        () -> {
          if (isDestroyed()) return;
          audioUi.refresh();
          if (renderedAudioState != app.audio.state()) {
            renderedAudioState = app.audio.state();
            updateAnswerHeading();
          }
        });
    barsShown = savedState == null || savedState.getBoolean("barsShown", true);
    // A restored Activity should retain the user's reading mode, rather than treating the
    // existing LISTENING state as a new recording and expanding the toolbar again.
    if (savedState != null) snapshot = app.session.snapshot();
    settingsScreen = savedState != null && savedState.getBoolean("settings", false);
    if (settingsScreen) {
      showSettings();
      if (getLastNonConfigurationInstance() instanceof ModelDraft draft) restoreModelDraft(draft);
    } else showMain();
    bindSession();
    if (savedState != null && savedState.getBoolean("imageSettings", false))
      imageUi.showSettings(savedState.getBoolean("imageReturnDrawer", false));
    if (savedState != null && savedState.getBoolean("audioSettings", false))
      audioUi.showSettings(
          savedState.getBoolean("audioReturnDrawer", false), () -> imageUi.showDrawer());
    meetingChanges =
        () -> {
          if (isDestroyed()) return;
          if (observedSession != app.session) {
            snapshot = null;
            barsShown = true;
            bindSession();
            if (!settingsScreen) showMain();
          }
          if (drawerDialog != null && drawerDialog.isShowing()) populateDrawer();
          if (!app.meetings.error().isEmpty()
              && !app.meetings.error().equals(displayedStorageError)) {
            displayedStorageError = app.meetings.error();
            message(displayedStorageError);
          }
        };
    app.meetingObserver = meetingChanges;
    if (!app.startupError.isEmpty()) {
      message(app.startupError);
      app.startupError = "";
    }
  }

  private void bindSession() {
    if (observedSession != null && observer != null) observedSession.detach(observer);
    observedSession = app.session;
    SessionEngine owner = observedSession;
    String meetingId = app.meetings.currentId();
    long binding = ++bindingVersion;
    uiScheduled.set(false);
    observer =
        s -> {
          if (app.session != owner || !meetingId.equals(app.meetings.currentId())) return;
          pendingSnapshot = s;
          if (uiScheduled.compareAndSet(false, true))
            handler.postDelayed(
                () -> {
                  if (binding != bindingVersion) return;
                  uiScheduled.set(false);
                  if (!isDestroyed()
                      && app.session == owner
                      && meetingId.equals(app.meetings.currentId())) {
                    if (!settingsScreen) render(pendingSnapshot);
                    else snapshot = pendingSnapshot;
                  }
                },
                32);
        };
    owner.observe(observer);
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return settingsScreen && !modelForm.isEmpty() ? new ModelDraft(this) : null;
  }

  private void restoreModelDraft(ModelDraft draft) {
    if (draft.values.length != modelForm.size()) return;
    for (int i = 0; i < draft.values.length; i++) {
      EditText field = modelForm.get(i);
      field.setText(draft.values[i]);
      if (draft.start[i] >= 0 && draft.end[i] >= 0)
        field.setSelection(
            Math.min(draft.start[i], field.length()), Math.min(draft.end[i], field.length()));
    }
    modelFormat.setChecked(draft.format);
    modelDirty = draft.dirty;
    if (draft.focus >= 0) modelForm.get(draft.focus).requestFocus();
    settingsScroll.post(() -> settingsScroll.scrollTo(0, draft.scroll));
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    saveScroll();
    out.putBoolean("settings", settingsScreen);
    out.putBoolean("barsShown", barsShown);
    out.putBoolean("audioSettings", audioUi != null && audioUi.settingsShowing());
    out.putBoolean("audioReturnDrawer", audioUi != null && audioUi.returnsToDrawer());
    out.putBoolean("imageSettings", imageUi != null && imageUi.settingsShowing());
    out.putBoolean("imageReturnDrawer", imageUi != null && imageUi.returnsToDrawer());
    super.onSaveInstanceState(out);
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    AppTheme.refresh(this);
  }

  @Override
  protected void onResume() {
    super.onResume();
    AppTheme.refresh(this);
  }

  @Override
  protected void onStart() {
    super.onStart();
    foreground = true;
    applyAwake();
    handler.post(ticker);
    scheduleBarsHide(4000);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    windowFocused = hasFocus;
    if (hasFocus) scheduleBarsHide(4000);
    else {
      handler.removeCallbacks(hideBars);
      // Permission/dialog windows may consume the gesture's UP event.
      touching = false;
      tapCandidate = false;
      trackingAnswerScroll = false;
    }
  }

  @Override
  protected void onStop() {
    foreground = false;
    cancelToast();
    handler.removeCallbacks(ticker);
    handler.removeCallbacks(hideBars);
    touching = false;
    stopBarsAnimation();
    if (!settingsScreen) setBarsShown(barsShown, false);
    consumedKeys.clear();
    if (!isChangingConfigurations()) {
      app.session.background();
      app.video.setEnabled(false);
      app.audio.background();
    }
    app.video.preview(null);
    saveScroll();
    app.saveMeetingView();
    app.meetings.flush();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    cancelToast();
    if (removeBackCallback != null) removeBackCallback.run();
    saveScroll();
    if (observedSession != null) observedSession.detach(observer);
    if (app.meetingObserver == meetingChanges) app.meetingObserver = null;
    handler.removeCallbacksAndMessages(null);
    if (imageUi != null) imageUi.destroy();
    if (audioUi != null) audioUi.destroy();
    app.audio.observe(null);
    if (contextUi != null) contextUi.close();
    if (topicCollections != null) topicCollections.destroy();
    app.video.observe(null);
    if (captureDialog != null) captureDialog.dismiss();
    if (preferenceDialog != null) preferenceDialog.dismiss();
    if (drawerDialog != null) drawerDialog.dismiss();
    if (managedDialog != null) managedDialog.dismiss();
    super.onDestroy();
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    AppTheme.text(t, color);
    t.setIncludeFontPadding(false);
    return t;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private GradientDrawable surface(int color, int radius) {
    return UiControls.shape(this, color, radius);
  }

  private Button button(String title, boolean primary, Runnable action) {
    return UiControls.button(this, title, primary, action);
  }

  private void space(LinearLayout l, int n) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(dp(n), dp(n)));
  }

  private ImageButton iconButton(int icon, String description, Runnable action) {
    ImageButton b = UiControls.icon(this, icon, description, action);
    AppTheme.bind(
        b,
        p -> {
          b.setImageTintList(AppTheme.foreground(p, TEXT));
          b.setBackground(UiControls.ripple(this, surface(PANEL, 24), 24));
        });
    return b;
  }

  private ImageButton inlineIconButton(int icon, String description, Runnable action) {
    return UiControls.icon(this, icon, description, action);
  }

  private void install(LinearLayout view) {
    // Clearing a toggle hint must not cut short deletion feedback during a meeting switch.
    if (stateHint) cancelToast();
    root = view;
    AppTheme.background(root, BG);
    root.setPadding(dp(12), dp(8), dp(12), dp(8));
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int left, top, right, bottom;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = i.left;
            top = i.top;
            right = i.right;
            bottom = i.bottom;
          } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
          }
          v.setPadding(left + dp(12), top + dp(8), right + dp(12), bottom + dp(8));
          return insets;
        });
    root.requestApplyInsets();
  }

  private void showMain() {
    modelForm = java.util.List.of();
    modelFormat = null;
    settingsScroll = null;
    settingsScreen = false;
    updateBackNavigation();
    modelDirty = false;
    layoutMode = -1;
    lastAnswer = "";
    lastTranscript = "";
    LinearLayout page = column();
    // Keep a focus target in touch mode so the first hardware navigation key is
    // available to meeting shortcuts instead of being consumed to establish focus.
    page.setFocusableInTouchMode(true);
    install(page);
    LinearLayout header = row();
    meetingsButton = iconButton(R.drawable.ic_menu, getString(R.string.meetings), this::showDrawer);
    meetingsButton.setId(R.id.meetings_button);
    header.addView(meetingsButton);
    space(header, 8);
    LinearLayout info = column();
    info.setGravity(Gravity.CENTER_VERTICAL);
    info.setPadding(0, 0, dp(8), 0);
    info.setOnClickListener(v -> showPreferenceChooser());
    preference = text("", 18, ACCENT);
    preference.setId(R.id.meeting_preference);
    preference.setSingleLine(true);
    preference.setEllipsize(TextUtils.TruncateAt.END);
    preference.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    preference.setOnClickListener(v -> showPreferenceChooser());
    preference.setTooltipText(getString(R.string.choose_preference));
    info.addView(preference, new LinearLayout.LayoutParams(-1, -1));
    header.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
    imageButton =
        inlineIconButton(
            R.drawable.ic_inputs,
            getString(R.string.input_sources),
            () -> {
              if (drawerDialog != null) drawerDialog.dismiss();
              showBarsTemporarily(4000);
              imageUi.showDrawer();
            });
    imageButton.setId(R.id.image_input_button);
    awake =
        inlineIconButton(
            R.drawable.ic_bulb_off, getString(R.string.awake_enable), () -> setAwake(!app.awake));
    header.addView(awake);
    awake.setId(R.id.awake_button);
    header.addView(imageButton);
    toolbar = new FrameLayout(this);
    toolbar.setId(R.id.meeting_toolbar);
    toolbar.addView(header, new FrameLayout.LayoutParams(-1, dp(TOP_BAR_DP)));
    page.addView(toolbar, new LinearLayout.LayoutParams(-1, barsShown ? dp(TOP_BAR_DP) : 0));
    toolbar.setVisibility(barsShown ? View.VISIBLE : View.GONE);
    body = new LinearLayout(this);
    body.setId(R.id.meeting_content);
    page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout controls = row();
    controls.setPadding(0, dp(8), 0, 0);
    auto =
        button(getString(R.string.auto_label), false, () -> setAutomatic(!app.settings.automatic));
    auto.setId(R.id.auto_button);
    auto.setSingleLine(true);
    auto.setPadding(dp(4), dp(8), dp(4), dp(8));
    controls.addView(auto, new LinearLayout.LayoutParams(dp(64), dp(52)));
    space(controls, 8);
    record = button(getString(R.string.start_listening), true, this::toggleRecord);
    controls.addView(record, new LinearLayout.LayoutParams(0, dp(52), 2));
    space(controls, 8);
    captureImage =
        inlineIconButton(
            R.drawable.ic_camera, getString(R.string.image_capture), this::captureImage);
    captureImage.setId(R.id.image_capture_button);
    controls.addView(captureImage, new LinearLayout.LayoutParams(dp(48), dp(52)));
    raw = button(getString(R.string.transcript), false, this::toggleTranscript);
    raw.setId(R.id.transcript_toggle);
    controls.addView(raw, new LinearLayout.LayoutParams(0, dp(52), 1));
    retry =
        button(
            getString(R.string.retry),
            false,
            () -> {
              try {
                app.session.retry(app.settings.provider, app.repository.textKeys());
              } catch (IllegalStateException e) {
                message(e.getMessage());
              }
            });
    controls.addView(retry);
    cancel = button(getString(R.string.cancel), false, () -> app.session.cancel());
    controls.addView(cancel);
    controlsBar = new FrameLayout(this);
    controlsBar.setId(R.id.meeting_controls);
    controlsBar.addView(controls, new FrameLayout.LayoutParams(-1, dp(BOTTOM_BAR_DP)));
    page.addView(controlsBar, new LinearLayout.LayoutParams(-1, barsShown ? dp(BOTTOM_BAR_DP) : 0));
    controlsBar.setVisibility(barsShown ? View.VISIBLE : View.GONE);
    body.addOnLayoutChangeListener(
        (v, a, b, c, d, e, f, g, h) -> {
          int w = c - a;
          // Use expanded bar heights so their animation never switches pane modes.
          int hh =
              root.getHeight()
                  - root.getPaddingTop()
                  - root.getPaddingBottom()
                  - dp(BOTTOM_BAR_DP)
                  - dp(TOP_BAR_DP);
          int mode = w >= dp(700) && hh >= dp(300) ? 2 : w > hh * 1.5 && hh < dp(330) ? 1 : 0;
          if (mode != layoutMode) {
            saveScroll();
            layoutMode = mode;
            body.post(this::buildPanes);
          }
        });
    buildPanes();
    app.session.deferRevisions(!app.followAnswer);
    render(app.session.snapshot());
    applyAwake();
    scheduleBarsHide(4000);
    page.requestFocus();
  }

  private void showDrawer() {
    if (imageUi != null) imageUi.closeDrawer();
    if (drawerDialog != null && drawerDialog.isShowing()) return;
    menuDialogOpen = true;
    showBarsTemporarily(4000);
    drawerDialog = AppTheme.page(this);
    drawerDialog.setCanceledOnTouchOutside(true);
    LinearLayout sheet = column();
    sheet.setPadding(dp(12), dp(16), dp(12), dp(16));
    AppTheme.background(sheet, BG);
    Button create =
        button(
            getString(R.string.new_meeting_action),
            true,
            () -> {
              drawerDialog.dismiss();
              requestMeetingSwitch(null);
            });
    create.setId(R.id.drawer_new_meeting);
    LinearLayout creation = row();
    creation.addView(create, new LinearLayout.LayoutParams(0, dp(48), 1));
    ImageButton collectionCreate =
        inlineIconButton(
            R.drawable.ic_create_folder,
            getString(R.string.new_collection),
            topicCollections::create);
    collectionCreate.setId(R.id.drawer_new_collection);
    creation.addView(collectionCreate);
    sheet.addView(creation);
    topicCollections.opened();
    ScrollView scroll = new ScrollView(this);
    drawerList = column();
    scroll.addView(drawerList);
    sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Button settings =
        button(
            getString(R.string.settings),
            false,
            () -> {
              if (app.imageJobs.busy()
                  || app.meetings.summaries().stream().anyMatch(r -> r.busy())) {
                message(getString(R.string.wait_tasks));
                return;
              }
              drawerDialog.dismiss();
              saveScroll();
              app.saveMeetingView();
              showSettings();
            });
    settings.setId(R.id.drawer_settings);
    sheet.addView(settings, new LinearLayout.LayoutParams(-1, dp(48)));
    sheet.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(dp(12) + i.left, dp(16) + i.top, dp(12) + i.right, dp(16) + i.bottom);
          } else
            v.setPadding(
                dp(12) + insets.getSystemWindowInsetLeft(),
                dp(16) + insets.getSystemWindowInsetTop(),
                dp(12) + insets.getSystemWindowInsetRight(),
                dp(16) + insets.getSystemWindowInsetBottom());
          return insets;
        });
    drawerDialog.setContentView(sheet);
    drawerDialog.setOnDismissListener(
        d -> {
          menuDialogOpen = false;
          drawerList = null;
          scheduleBarsHide(4000);
        });
    drawerDialog.setOnKeyListener(
        (d, code, event) -> {
          if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) drawerDialog.dismiss();
            return true;
          }
          return app.settings.actionFor(code, event.getRepeatCount(), false, false) != null;
        });
    drawerDialog.show();
    android.view.Window w = drawerDialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(
          new android.graphics.drawable.ColorDrawable(AppTheme.color(this, BG)));
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setDimAmount(.5f);
      w.setGravity(Gravity.START | Gravity.TOP);
      int available =
          root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
      w.setLayout(
          Math.min(dp(360), (int) (available * .86f)), WindowManager.LayoutParams.MATCH_PARENT);
    }
    populateDrawer();
  }

  private void populateDrawer() {
    if (drawerList != null) topicCollections.render(drawerList);
  }

  private void requestMeetingSwitch(String id) {
    requestMeetingSwitch(id, "");
  }

  private void requestMeetingSwitch(String id, String carry) {
    if (app.meetings.navigating()) {
      message(getString(R.string.switching_wait));
      return;
    }
    if (id != null && id.equals(app.meetings.currentId())) return;
    Runnable navigate =
        () -> {
          try {
            saveScroll();
            app.saveMeetingView();
            String target =
                id == null
                    ? app.meetings.create(app.settings.snapshot(), app.settings.automatic, carry)
                    : id;
            app.meetings.switchTo(target);
          } catch (IllegalStateException | IllegalArgumentException e) {
            message(e.getMessage());
          }
        };
    confirmNavigation(navigate);
  }

  private void requestCollectionSwitch(String id, boolean restore) {
    if (app.meetings.navigating() || app.meetings.structureBusy()) {
      message(getString(R.string.switching_wait));
      return;
    }
    confirmNavigation(
        () -> {
          try {
            saveScroll();
            app.saveMeetingView();
            String target =
                app.meetings.collectionTarget(
                    id, restore, app.settings.snapshot(), app.settings.automatic);
            app.meetings.switchTo(target);
          } catch (IllegalArgumentException | IllegalStateException e) {
            message(e.getMessage());
          }
        });
  }

  private void confirmNavigation(Runnable navigate) {
    if (app.session.saveState().speechActive()) {
      menuDialogOpen = true;
      AlertDialog dialog =
          AppTheme.alert(this)
              .setTitle(getString(R.string.stop_switch_title))
              .setMessage(getString(R.string.stop_switch_detail))
              .setNegativeButton(getString(R.string.cancel), null)
              .setPositiveButton(getString(R.string.stop_switch), (d, w) -> navigate.run())
              .create();
      dialog.setOnDismissListener(
          d -> {
            menuDialogOpen = false;
            scheduleBarsHide(4000);
          });
      managedDialog = dialog;
      dialog.show();
    } else navigate.run();
  }

  /** Matches the sidebar order: recently updated first, without wrapping at either end. */
  private void switchAdjacentMeeting(int direction) {
    if (app.meetings.navigating()) {
      message(getString(R.string.switching_wait));
      return;
    }
    java.util.List<io.github.meetingassistant.core.MeetingManager.Summary> meetings =
        app.meetings.collectionTopics(app.meetings.creationCollection());
    if (meetings.isEmpty()) {
      message(getString(R.string.no_meetings_to_switch));
      return;
    }
    String current = app.meetings.currentId();
    int index = -1;
    for (int i = 0; i < meetings.size(); i++) {
      if (meetings.get(i).id.equals(current)) {
        index = i;
        break;
      }
    }
    // A waiting draft is not a history row; enter the latest saved meeting if one exists.
    int target = index < 0 ? (direction > 0 ? 0 : meetings.size() - 1) : index + direction;
    if (target < 0 || target >= meetings.size()) {
      message(getString(R.string.meeting_list_edge));
      return;
    }
    requestMeetingSwitch(meetings.get(target).id);
  }

  private void switchAdjacentCollection(int direction) {
    java.util.List<io.github.meetingassistant.core.TopicCatalog.Collection> collections =
        app.meetings.catalog().ordered();
    int index = -1;
    for (int i = 0; i < collections.size(); i++)
      if (collections.get(i).id.equals(app.meetings.selectedCollection())) index = i;
    int target = index < 0 ? (direction > 0 ? 0 : collections.size() - 1) : index + direction;
    if (target < 0 || target >= collections.size()) {
      message(getString(R.string.collection_list_edge));
      return;
    }
    requestCollectionSwitch(collections.get(target).id, true);
  }

  private void meetingActions(String id) {
    io.github.meetingassistant.core.MeetingRecord r = app.meetings.get(id);
    if (r == null) return;
    AlertDialog actions =
        AppTheme.alert(this)
            .setTitle(meetingTitle(r.title, !r.customTitle && !r.autoNamed, r.createdAt))
            .setItems(
                new String[] {
                  getString(R.string.rename),
                  getString(R.string.delete_meeting),
                  getString(R.string.move_to_collection)
                },
                (d, index) -> {
                  if (index == 0) {
                    EditText name = new EditText(this);
                    name.setText(
                        meetingTitle(r.title, !r.customTitle && !r.autoNamed, r.createdAt));
                    name.setSingleLine(true);
                    AlertDialog edit =
                        AppTheme.alert(this)
                            .setTitle(getString(R.string.rename_meeting))
                            .setView(name)
                            .setNegativeButton(getString(R.string.cancel), null)
                            .setPositiveButton(getString(R.string.save), null)
                            .create();
                    edit.setOnShowListener(
                        dd ->
                            edit.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                    v -> {
                                      try {
                                        app.meetings.rename(id, name.getText().toString());
                                        edit.dismiss();
                                      } catch (IllegalArgumentException | IllegalStateException e) {
                                        message(e.getMessage());
                                      }
                                    }));
                    managedDialog = edit;
                    edit.show();
                  } else if (index == 2) {
                    topicCollections.moveTopic(id);
                  } else {
                    managedDialog =
                        AppTheme.danger(this)
                            .setTitle(
                                getString(
                                    R.string.delete_named,
                                    meetingTitle(
                                        r.title, !r.customTitle && !r.autoNamed, r.createdAt)))
                            .setMessage(getString(R.string.delete_meeting_detail))
                            .setNegativeButton(getString(R.string.cancel), null)
                            .setPositiveButton(
                                getString(R.string.delete),
                                (dd, w) -> {
                                  saveScroll();
                                  app.saveMeetingView();
                                  try {
                                    app.meetings.delete(
                                        id,
                                        success ->
                                            handler.post(
                                                () -> {
                                                  message(
                                                      success
                                                          ? getString(R.string.meeting_deleted)
                                                          : app.meetings.error());
                                                }));
                                  } catch (IllegalStateException e) {
                                    message(e.getMessage());
                                  }
                                })
                            .create();
                    managedDialog.show();
                  }
                })
            .create();
    managedDialog = actions;
    actions.show();
  }

  private void showAnswerHistory() {
    java.util.List<io.github.meetingassistant.core.MeetingRecord.Round> all =
        app.meetings.answers();
    if (all.isEmpty()) return;
    String[] labels = new String[all.size()];
    for (int i = 0; i < all.size(); i++)
      labels[i] =
          getString(
              R.string.answer_number,
              i + 1,
              historicalPreference(all.get(i)),
              all.get(i).answerComplete ? "" : getString(R.string.incomplete_suffix));
    menuDialogOpen = true;
    AlertDialog chooser =
        AppTheme.alert(this)
            .setTitle(getString(R.string.answer_history))
            .setItems(
                labels,
                (d, index) -> {
                  io.github.meetingassistant.core.MeetingRecord.Round r = all.get(index);
                  TextView content = text("", 17, TEXT);
                  content.setTextIsSelectable(true);
                  content.setPadding(dp(20), dp(12), dp(20), dp(20));
                  content.setText(
                      Markdown.render(
                          this,
                          getString(
                              R.string.historical_answer,
                              historicalPreference(r),
                              r.answer,
                              r.complete ? "" : getString(R.string.incomplete_parenthesis),
                              r.transcript(getString(R.string.partial_marker)))));
                  ScrollView scroll = new ScrollView(this);
                  scroll.addView(content);
                  AlertDialog detail =
                      AppTheme.alert(this)
                          .setTitle(labels[index])
                          .setView(scroll)
                          .setPositiveButton(getString(R.string.close), null)
                          .setNeutralButton(
                              getString(R.string.copy_answer),
                              (dd, w) -> copyText(getString(R.string.answer_history), r.answer))
                          .create();
                  detail.setOnDismissListener(
                      dd -> {
                        menuDialogOpen = false;
                        scheduleBarsHide(4000);
                      });
                  managedDialog = detail;
                  detail.show();
                })
            .setNegativeButton(getString(R.string.close), null)
            .create();
    chooser.setOnDismissListener(
        d -> {
          if (managedDialog == null || !managedDialog.isShowing()) {
            menuDialogOpen = false;
            scheduleBarsHide(4000);
          }
        });
    managedDialog = chooser;
    chooser.show();
  }

  private boolean isTranscriptShown() {
    return app.transcriptVisibilityChosen ? app.transcriptExpanded : layoutMode == 2;
  }

  private void toggleTranscript() {
    saveScroll();
    app.transcriptExpanded = !isTranscriptShown();
    app.transcriptVisibilityChosen = true;
    buildPanes();
    app.saveMeetingView();
  }

  private void buildPanes() {
    if (body == null || settingsScreen) return;
    body.removeAllViews();
    boolean showRaw = isTranscriptShown();
    boolean dual = showRaw && (layoutMode == 2 || layoutMode == 1);
    body.setOrientation(dual ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    answer = text("", 17, TEXT);
    answer.setTextIsSelectable(true);
    answer.setLineSpacing(dp(2), 1.05f);
    answer.setPadding(dp(14), dp(12), dp(14), dp(18));
    transcript = text("", 14, TEXT);
    transcript.setTextIsSelectable(true);
    transcript.setLineSpacing(dp(2), 1.05f);
    transcript.setPadding(dp(14), dp(12), dp(14), dp(18));
    answerPane = new ScrollView(this);
    answerPane.setFillViewport(true);
    answerPane.addView(answer, new ScrollView.LayoutParams(-1, -2));
    answerPane.setOnScrollChangeListener(
        (v, x, y, ox, oy) -> {
          if ((touching || trackingAnswerScroll) && !scrollProgrammatic) updateAnswerReading();
        });
    transcriptPane = new ScrollView(this);
    transcriptPane.setFillViewport(true);
    transcriptPane.addView(transcript, new ScrollView.LayoutParams(-1, -2));
    transcriptPane.setOnScrollChangeListener(
        (v, x, y, ox, oy) -> {
          if (scrollProgrammatic) return;
          int max = Math.max(0, transcript.getHeight() - transcriptPane.getHeight());
          app.followTranscript = y >= max - dp(20);
          app.transcriptScroll = y;
        });
    LinearLayout answerPanel = panel("", answerPane),
        rawPanel = panel(getString(R.string.transcript), transcriptPane);
    AppTheme.background(answerPanel, BG);
    answerHeading = (TextView) answerPanel.getChildAt(0);
    answerHeading.setId(R.id.meeting_status);
    answerHeading.setTextSize(14);
    answerHeading.setPadding(dp(14), dp(10), dp(14), dp(4));
    answerHeading.setOnClickListener(
        v -> {
          app.session.applyPendingAnswer();
          app.session.deferRevisions(selectingText || !app.followAnswer);
        });
    answerPanel.removeView(answerHeading);
    LinearLayout answerHeader = row();
    answerHeader.addView(answerHeading, new LinearLayout.LayoutParams(0, -2, 1));
    historyButton =
        iconButton(
            R.drawable.ic_history, getString(R.string.answer_history), this::showAnswerHistory);
    historyButton.setId(R.id.answer_history);
    answerHeader.addView(historyButton);
    ImageButton imageData =
        inlineIconButton(
            R.drawable.ic_video, getString(R.string.image_evidence), this::showImageEvidence);
    imageData.setId(R.id.image_evidence_button);
    imageData.setVisibility(app.session.imageCount() == 0 ? View.GONE : View.VISIBLE);
    answerHeader.addView(imageData);
    ImageButton contextInfo =
        inlineIconButton(
            R.drawable.ic_context, getString(R.string.context_title), contextUi::details);
    contextInfo.setId(R.id.context_info);
    contextInfo.setVisibility(View.GONE);
    answerHeader.addView(contextInfo);
    ImageButton copyAnswer =
        inlineIconButton(R.drawable.ic_copy, getString(R.string.copy_answer), this::copyAnswer);
    copyAnswer.setId(R.id.copy_answer);
    answerHeader.addView(copyAnswer);
    answerPanel.addView(answerHeader, 0);
    TextView rawHeading = (TextView) rawPanel.getChildAt(0);
    rawHeading.setPadding(dp(14), dp(10), dp(14), dp(10));
    rawPanel.removeView(rawHeading);
    LinearLayout rawHeader = row();
    rawHeader.addView(rawHeading, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton copyTranscript =
        inlineIconButton(
            R.drawable.ic_copy,
            getString(R.string.copy_transcript),
            () -> copyText(getString(R.string.clipboard_transcript), meetingTranscript()));
    copyTranscript.setId(R.id.copy_transcript);
    rawHeader.addView(copyTranscript);
    rawPanel.addView(rawHeader, 0);
    answerPanel.setId(R.id.answer_panel);
    rawPanel.setId(R.id.transcript_panel);
    if (dual) {
      body.addView(rawPanel, new LinearLayout.LayoutParams(0, -1, 1));
      space(body, 8);
      body.addView(answerPanel, new LinearLayout.LayoutParams(0, -1, 1.8f));
    } else {
      body.addView(answerPanel, new LinearLayout.LayoutParams(-1, 0, 1));
      if (showRaw) {
        space(body, 8);
        body.addView(rawPanel, new LinearLayout.LayoutParams(-1, 0, .65f));
      }
    }
    lastAnswer = "";
    lastTranscript = "";
    if (snapshot != null) render(snapshot);
    answerPane.post(() -> programmaticAnswer(app.answerScroll));
    transcriptPane.post(
        () ->
            programmaticTranscript(
                app.followTranscript ? Integer.MAX_VALUE : app.transcriptScroll));
    updateTranscriptToggle();
  }

  private LinearLayout panel(String title, ScrollView pane) {
    LinearLayout p = column();
    AppTheme.bind(p, palette -> p.setBackground(surface(PANEL, 14)));
    TextView label = text(title, 12, MUTED);
    label.setPadding(dp(14), dp(10), dp(14), 0);
    p.addView(label);
    p.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));
    return p;
  }

  private void saveScroll() {
    if (!settingsScreen && answerPane != null) {
      app.answerScroll = answerPane.getScrollY();
      if (transcriptPane != null && body.findViewById(R.id.transcript_panel) != null)
        app.transcriptScroll = transcriptPane.getScrollY();
    }
  }

  private void programmaticAnswer(int y) {
    scrollProgrammatic = true;
    answerPane.scrollTo(0, y);
    scrollProgrammatic = false;
  }

  private void programmaticTranscript(int y) {
    if (transcriptPane == null) return;
    scrollProgrammatic = true;
    transcriptPane.scrollTo(0, y);
    scrollProgrammatic = false;
  }

  private void render(SessionEngine.Snapshot s) {
    if (app.meetings.currentEngine() != app.session) return;
    SessionEngine.State previous = snapshot == null ? null : snapshot.state;
    snapshot = s;
    if (settingsScreen || record == null) return;
    refreshVideo();
    updatePreference();
    updateAutomaticButton();
    updateAnswerHeading();
    View contextInfo = findViewById(R.id.context_info);
    if (contextInfo != null)
      contextInfo.setVisibility(s.context.visible() ? View.VISIBLE : View.GONE);
    contextUi.report(root, s.context);
    View imageData = findViewById(R.id.image_evidence_button);
    if (imageData != null)
      imageData.setVisibility(app.session.imageCount() == 0 ? View.GONE : View.VISIBLE);
    if (historyButton != null)
      historyButton.setVisibility(app.meetings.answerCount() > 1 ? View.VISIBLE : View.GONE);
    if (s.state != previous) {
      if (s.state == SessionEngine.State.CONNECTING) {
        showBarsTemporarily(4000);
      } else if (s.state == SessionEngine.State.LISTENING) {
        showBarsTemporarily(1000);
      } else if (s.state == SessionEngine.State.ERROR) {
        showBarsTemporarily(4000);
      } else scheduleBarsHide(4000);
    }
    boolean listening = s.state == SessionEngine.State.LISTENING;
    record.setText(
        listening
            ? getString(R.string.stop_listening)
            : s.state == SessionEngine.State.CONNECTING
                ? getString(R.string.cancel_connection)
                : !app.session.canStartListening()
                    ? getString(R.string.please_wait)
                    : getString(R.string.start_listening));
    record.setEnabled(
        listening || s.state == SessionEngine.State.CONNECTING || app.session.canStartListening());
    record.setAlpha(record.isEnabled() ? 1 : .55f);
    retry.setVisibility(s.retryable ? View.VISIBLE : View.GONE);
    cancel.setVisibility(
        s.state == SessionEngine.State.FINALIZING || s.state == SessionEngine.State.ANALYZING
            ? View.VISIBLE
            : View.GONE);
    String displayedAnswer = app.meetings.answer();
    if (answer != null && !lastAnswer.equals(displayedAnswer)) {
      lastAnswer = displayedAnswer;
      int y = answerPane.getScrollY();
      answer.setText(
          displayedAnswer.isEmpty()
              ? (s.analysisState == SessionEngine.AnalysisState.GENERATING
                  ? getString(R.string.analyzing_transcript)
                  : getString(
                      app.video.enabled()
                          ? R.string.image_answer_placeholder
                          : R.string.answer_placeholder))
              : Markdown.render(this, displayedAnswer));
      answerPane.post(() -> programmaticAnswer(y));
    } else if (answer != null && displayedAnswer.isEmpty())
      answer.setText(
          s.analysisState == SessionEngine.AnalysisState.GENERATING
              ? getString(R.string.analyzing_transcript)
              : getString(
                  app.video.enabled()
                      ? R.string.image_answer_placeholder
                      : R.string.answer_placeholder));
    String fullTranscript = meetingTranscript();
    if (transcript != null && !lastTranscript.equals(fullTranscript)) {
      lastTranscript = fullTranscript;
      int y = transcriptPane.getScrollY();
      boolean follow = app.followTranscript;
      transcript.setText(
          fullTranscript.isEmpty() ? getString(R.string.transcript_placeholder) : fullTranscript);
      transcriptPane.post(() -> programmaticTranscript(follow ? Integer.MAX_VALUE : y));
    } else if (transcript != null && fullTranscript.isEmpty())
      transcript.setText(getString(R.string.transcript_placeholder));
  }

  private void updatePreference() {
    if (preference == null) return;
    UserSettings.Profile selected = app.settings.snapshot();
    String next = preferenceName(selected.id, selected.name);
    boolean current = snapshot != null && (snapshot.busy() || !snapshot.transcript.isEmpty());
    String frozen = current ? preferenceName(snapshot.preferenceId, snapshot.preference) : next;
    preference.setText(
        current && !next.equals(frozen)
            ? getString(
                snapshot.automatic
                    ? R.string.preference_next_update
                    : R.string.preference_this_round,
                frozen,
                next)
            : frozen);
    preference.setContentDescription(
        getString(R.string.choose_preference_description, preference.getText()));
  }

  private void showPreferenceChooser() {
    menuDialogOpen = true;
    showBarsTemporarily(4000);
    ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
    ids.add("");
    names.add(getString(R.string.no_preference));
    for (UserSettings.Profile p : app.settings.profiles) {
      ids.add(p.id);
      names.add(p.name);
    }
    AlertDialog dialog =
        AppTheme.alert(this)
            .setTitle(getString(R.string.choose_preference))
            .setSingleChoiceItems(
                names.toArray(new String[0]),
                Math.max(0, ids.indexOf(app.settings.selectedId)),
                (d, which) -> {
                  selectPreference(ids.get(which));
                  d.dismiss();
                })
            .setNegativeButton(getString(R.string.cancel), null)
            .create();
    dialog.setOnDismissListener(
        d -> {
          menuDialogOpen = false;
          scheduleBarsHide(4000);
        });
    preferenceDialog = dialog;
    dialog.show();
  }

  private void selectPreference(String id) {
    app.settings.selectedId = id;
    persist();
    app.session.selectPreference(app.settings.snapshot());
    updatePreference();
  }

  private void setAutomatic(boolean on) {
    app.settings.automatic = on;
    persist();
    app.session.setAutomatic(on);
    updateAutomaticButton();
    showBarsTemporarily(4000);
    showToast(
        on ? getString(R.string.automatic_segmentation) : getString(R.string.manual_segmentation),
        Toast.LENGTH_SHORT);
  }

  private void setAwake(boolean on) {
    app.awake = on;
    applyAwake();
    showBarsTemporarily(4000);
    showToast(
        on ? getString(R.string.screen_stays_awake) : getString(R.string.normal_screen_timeout),
        Toast.LENGTH_SHORT);
  }

  private void cancelToast() {
    if (currentToast != null) currentToast.cancel();
    currentToast = null;
    stateHint = false;
  }

  private void showToast(String text, int duration) {
    cancelToast();
    stateHint = duration == Toast.LENGTH_SHORT;
    currentToast = Toast.makeText(this, localizedMessages.notice(text), duration);
    currentToast.show();
  }

  private void updateAutomaticButton() {
    if (auto == null) return;
    boolean on = app.settings.automatic;
    auto.setSelected(on);
    auto.setText(on ? "✓ " + getString(R.string.auto_label) : getString(R.string.auto_label));
    AppTheme.bind(
        auto,
        p -> {
          boolean enabled = app.settings.automatic;
          auto.setTextColor(p.color(enabled ? AppTheme.ON_AUTO : TEXT));
          auto.setBackgroundTintList(null);
          auto.setBackground(
              UiControls.ripple(this, surface(enabled ? AppTheme.AUTO : PANEL, 12), 12));
        });
    auto.setContentDescription(
        on
            ? getString(R.string.auto_enabled_description)
            : getString(R.string.auto_disabled_description));
  }

  private void updateAnswerHeading() {
    if (answerHeading == null || snapshot == null) return;
    String detail =
        snapshot.hasUpdate ? getString(R.string.answer_update) : snapshot.analysisStatus;
    if (detail.isEmpty() && snapshot.coveredMs > 0) {
      long sec = snapshot.coveredMs / 1000;
      detail =
          String.format(
              java.util.Locale.ROOT, getString(R.string.covered_until), sec / 60, sec % 60);
    }
    String state =
        localizedMessages.notice(snapshot.status).replace("正在聆听", getString(R.string.listening));
    if (snapshot.state == SessionEngine.State.LISTENING) {
      if (app.audio.state() == AudioInputController.State.OPENING)
        state = getString(R.string.audio_opening);
      else if (app.audio.state() == AudioInputController.State.SWITCHING)
        state = getString(R.string.audio_switching);
    }
    if (app.session.imagePendingCount() > 0) state += " · " + getString(R.string.image_recognizing);
    String imageError = app.session.latestImageError();
    if (!imageError.isEmpty()) state += " · " + localizedMessages.notice(imageError);
    if (app.video.state() == ImageInputController.State.ERROR)
      state += " · " + localizedMessages.notice(app.video.error());
    if (!app.meetings.error().isEmpty())
      state += " · " + localizedMessages.notice(app.meetings.error());
    if (app.meetings.navigating()) state += getString(R.string.upcoming_switch);
    if (snapshot.state == SessionEngine.State.LISTENING && snapshot.startedAt > 0) {
      long sec = Math.max(0, (System.currentTimeMillis() - snapshot.startedAt) / 1000);
      state += String.format(java.util.Locale.ROOT, " · %02d:%02d", sec / 60, sec % 60);
    }
    // The speech state remains visible while automatic analysis is running or has failed.
    if (snapshot.state == SessionEngine.State.ANALYZING && detail.equals("更新中")) detail = "";
    answerHeading.setText(
        String.format(
            java.util.Locale.ROOT,
            "%s%s",
            state,
            detail.isEmpty() ? "" : " · " + localizedMessages.notice(detail)));
    boolean error =
        snapshot.state == SessionEngine.State.ERROR
            || snapshot.analysisState == SessionEngine.AnalysisState.ERROR
            || app.video.state() == ImageInputController.State.ERROR
            || !imageError.isEmpty()
            || !app.meetings.error().isEmpty();
    AppTheme.text(answerHeading, error ? AppTheme.ERROR : snapshot.hasUpdate ? ACCENT : MUTED);
    answerHeading.setClickable(snapshot.hasUpdate);
  }

  private void updateAnswerReading() {
    if (answerPane == null || answer == null) return;
    int max = Math.max(0, answer.getHeight() - answerPane.getHeight());
    app.followAnswer = answerPane.getScrollY() >= max - dp(20);
    app.session.deferRevisions(selectingText || !app.followAnswer);
  }

  private String preferenceName(String id, String name) {
    return id.isEmpty() ? getString(R.string.no_preference_short) : name;
  }

  private String historicalPreference(io.github.meetingassistant.core.MeetingRecord.Round round) {
    return round.preferenceSnapshot != null && round.preferenceSnapshot.id.isEmpty()
        ? getString(R.string.no_preference)
        : round.preference;
  }

  private String meetingTitle(String title, boolean generated, long createdAt) {
    return generated
        ? getString(
            R.string.new_meeting_title,
            new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(createdAt)))
        : title;
  }

  private String meetingTranscript() {
    return app.meetings.transcript(
        true,
        getString(R.string.partial_marker),
        getString(R.string.incomplete_round_marker),
        getString(R.string.round_label),
        getString(R.string.round_incomplete_suffix));
  }

  private boolean canHideBars() {
    AccessibilityManager accessibility =
        (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
    return foreground
        && !settingsScreen
        && !touching
        && windowFocused
        && !menuDialogOpen
        && !modalShowing()
        && !selectingText
        && (accessibility == null || !accessibility.isTouchExplorationEnabled())
        && snapshot != null; // Status/errors remain visible outside the collapsing bars.
  }

  private void scheduleBarsHide(long delay) {
    handler.removeCallbacks(hideBars);
    if (barsShown && canHideBars()) handler.postDelayed(hideBars, delay);
  }

  private void showBarsTemporarily(long delay) {
    if (settingsScreen) return;
    setBarsShown(true, true);
    scheduleBarsHide(delay);
  }

  private void stopBarsAnimation() {
    ValueAnimator old = barsAnimator;
    barsAnimator = null;
    if (old != null) old.cancel();
  }

  private void setBarsShown(boolean shown, boolean animate) {
    handler.removeCallbacks(hideBars);
    if (toolbar == null || settingsScreen) return;
    int target = shown ? dp(TOP_BAR_DP) : 0;
    if (barsShown == shown && (barsAnimator != null || toolbar.getLayoutParams().height == target))
      return;
    stopBarsAnimation();
    barsShown = shown;
    saveScroll();
    int answerY = app.answerScroll, transcriptY = app.transcriptScroll;
    int start = toolbar.getVisibility() == View.GONE ? 0 : toolbar.getLayoutParams().height;
    toolbar.setVisibility(View.VISIBLE);
    controlsBar.setVisibility(View.VISIBLE);
    toolbar.setImportantForAccessibility(
        shown
            ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    controlsBar.setImportantForAccessibility(
        shown
            ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    if (!shown && (toolbar.hasFocus() || controlsBar.hasFocus())) root.requestFocus();
    if (!animate || start == target) {
      resizeBars(target);
      toolbar.setVisibility(shown ? View.VISIBLE : View.GONE);
      controlsBar.setVisibility(shown ? View.VISIBLE : View.GONE);
      return;
    }
    ValueAnimator animator = ValueAnimator.ofInt(start, target);
    barsAnimator = animator;
    animator.setDuration(200);
    animator.addUpdateListener(a -> resizeBars((Integer) a.getAnimatedValue()));
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (barsAnimator != animation) return;
            barsAnimator = null;
            toolbar.setVisibility(shown ? View.VISIBLE : View.GONE);
            controlsBar.setVisibility(shown ? View.VISIBLE : View.GONE);
            // ScrollViews can clamp their offset as the viewport grows; retain the reading position
            // wherever the new scroll range permits. Do not rebuild text or restart the session.
            answerPane.post(
                () -> {
                  if (!settingsScreen) {
                    programmaticAnswer(answerY);
                    programmaticTranscript(transcriptY);
                  }
                });
          }
        });
    animator.start();
  }

  private void resizeBars(int height) {
    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) toolbar.getLayoutParams();
    params.height = height;
    toolbar.setLayoutParams(params);
    float fraction = Math.min(1f, height / (float) dp(TOP_BAR_DP));
    toolbar.setAlpha(fraction);
    LinearLayout.LayoutParams bottom = (LinearLayout.LayoutParams) controlsBar.getLayoutParams();
    bottom.height = Math.round(dp(BOTTOM_BAR_DP) * fraction);
    controlsBar.setLayoutParams(bottom);
    controlsBar.setAlpha(fraction);
  }

  private boolean isInside(View view, float x, float y) {
    int[] xy = new int[2];
    view.getLocationOnScreen(xy);
    return x >= xy[0] && x < xy[0] + view.getWidth() && y >= xy[1] && y < xy[1] + view.getHeight();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    boolean main = !settingsScreen;
    int action = event.getActionMasked();
    if (main && action == MotionEvent.ACTION_DOWN) {
      touching = true;
      tapCandidate = true;
      touchX = event.getX();
      touchY = event.getY();
      touchStarted = event.getEventTime();
      trackingAnswerScroll =
          answerPane != null && isInside(answerPane, event.getRawX(), event.getRawY());
      handler.removeCallbacks(hideBars);
      // Pause at the current height so targets cannot move underneath a held finger.
      stopBarsAnimation();
    } else if (main && action == MotionEvent.ACTION_MOVE) {
      int slop = ViewConfiguration.get(this).getScaledTouchSlop();
      if (Math.abs(event.getX() - touchX) > slop || Math.abs(event.getY() - touchY) > slop)
        tapCandidate = false;
    } else if (action == MotionEvent.ACTION_POINTER_DOWN) tapCandidate = false;
    // Let the original target handle the complete event first; never consume a content/button tap.
    boolean handled = super.dispatchTouchEvent(event);
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      touching = false;
      if (main
          && !settingsScreen
          && action == MotionEvent.ACTION_UP
          && tapCandidate
          && event.getEventTime() - touchStarted < ViewConfiguration.getLongPressTimeout())
        showBarsTemporarily(4000);
      else if (!settingsScreen) {
        setBarsShown(barsShown, true);
        scheduleBarsHide(4000);
      }
    }
    return handled;
  }

  @Override
  public void onActionModeStarted(ActionMode mode) {
    super.onActionModeStarted(mode);
    selectingText = true;
    app.session.deferRevisions(true);
    handler.removeCallbacks(hideBars);
  }

  @Override
  public void onActionModeFinished(ActionMode mode) {
    super.onActionModeFinished(mode);
    selectingText = false;
    app.session.deferRevisions(!app.followAnswer);
    scheduleBarsHide(4000);
  }

  void imageNotice(String text) {
    if (!foreground || isDestroyed()) return;
    showToast(localizedMessages.notice(text), Toast.LENGTH_SHORT);
  }

  private void updateTranscriptToggle() {
    if (raw == null) return;
    boolean shown = isTranscriptShown();
    String full = getString(shown ? R.string.hide_transcript : R.string.transcript);
    boolean compact = app.video.enabled() && getResources().getConfiguration().screenWidthDp < 500;
    raw.setText(compact ? getString(R.string.transcript_compact) : full);
    raw.setContentDescription(full);
    AppTheme.text(raw, shown ? ACCENT : TEXT);
  }

  private void refreshVideo() {
    updateTranscriptToggle();
    if (captureImage != null) {
      captureImage.setVisibility(app.video.enabled() ? View.VISIBLE : View.GONE);
      captureImage.setEnabled(app.video.state() == ImageInputController.State.READY);
      captureImage.setAlpha(captureImage.isEnabled() ? 1 : .5f);
    }
    if (imageButton != null)
      AppTheme.bind(
          imageButton,
          p -> {
            imageButton.setImageTintList(
                AppTheme.foreground(p, app.video.enabled() ? ACCENT : TEXT));
            imageButton.setBackground(UiControls.ripple(this, null, 24));
          });
  }

  private void persistImageSelection() {
    try {
      app.repository.save(app.settings);
    } catch (IllegalStateException e) {
      imageNotice(e.getMessage());
    }
  }

  private void enableVideo() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      cameraPermissionPending = true;
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 101);
      return;
    }
    app.video.setEnabled(true);
    persistImageSelection();
    refreshVideo();
  }

  private void captureImage() {
    if (!app.video.enabled()) {
      imageNotice(getString(R.string.image_enable_first));
      return;
    }
    if (app.video.state() != ImageInputController.State.READY || app.video.capturing()) {
      imageNotice(getString(R.string.image_source_not_ready));
      return;
    }
    if (app.imageJobs.full()) {
      imageNotice(getString(R.string.image_queue_full));
      return;
    }
    try {
      if (!app.meetings.available() || app.meetings.navigating())
        throw new IllegalStateException(getString(R.string.switching_wait));
      app.settings.image.validate();
      String imageKey = app.repository.imageKey();
      io.github.meetingassistant.core.OpenAiImageProvider.validateKey(imageKey);
      app.settings.provider.validateText();
      ProviderConfig.Credentials keys = app.repository.textKeys();
      keys.validateText();
      SessionEngine owner = app.session;
      String meetingId = app.meetings.currentId();
      UserSettings.Profile profile = app.settings.snapshot();
      ProviderConfig textConfig = app.settings.provider.copy();
      io.github.meetingassistant.core.ImageSettings imageConfig =
          new com.google.gson.Gson()
              .fromJson(
                  new com.google.gson.Gson().toJson(app.settings.image),
                  io.github.meetingassistant.core.ImageSettings.class);
      app.video.capture(
          prepared -> {
            if (!app.meetings.acceptsImage(meetingId, owner)) {
              imageNotice(getString(R.string.notice_meeting_missing));
              return;
            }
            prepared.evidence().preference = profile;
            boolean accepted =
                app.imageJobs.submit(
                    owner,
                    prepared.evidence(),
                    prepared.jpeg(),
                    () ->
                        new io.github.meetingassistant.core.OpenAiImageProvider(
                            io.github.meetingassistant.core.NetworkClients.create(),
                            imageConfig,
                            imageKey),
                    textConfig,
                    keys);
            imageNotice(getString(accepted ? R.string.image_captured : R.string.image_queue_full));
          },
          this::imageNotice);
    } catch (IllegalArgumentException | IllegalStateException e) {
      imageNotice(e.getMessage());
    }
  }

  private void showImageEvidence() {
    java.util.List<io.github.meetingassistant.core.ImageEvidence> images = app.session.images();
    if (images.isEmpty()) {
      imageNotice(getString(R.string.image_no_evidence));
      return;
    }
    SessionEngine owner = app.session;
    String[] labels = new String[images.size()];
    for (int i = 0; i < images.size(); i++) {
      var image = images.get(i);
      labels[i] =
          "#"
              + (i + 1)
              + " · "
              + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                  .format(new java.util.Date(image.capturedAt))
              + " · "
              + getString(image.included ? R.string.image_included : R.string.image_excluded);
    }
    managedDialog =
        AppTheme.alert(this)
            .setTitle(R.string.image_evidence)
            .setItems(
                labels,
                (d, which) -> {
                  var image = images.get(which);
                  String content =
                      image.structure.isEmpty()
                          ? localizedMessages.notice(
                              image.error.isEmpty()
                                  ? getString(R.string.image_recognizing)
                                  : image.error)
                          : image.structure;
                  TextView value = text(content, 14, TEXT);
                  value.setTextIsSelectable(true);
                  value.setPadding(dp(16), dp(12), dp(16), dp(12));
                  ScrollView scroll = new ScrollView(this);
                  scroll.addView(value);
                  AlertDialog.Builder detail =
                      AppTheme.alert(this)
                          .setTitle("#" + (which + 1))
                          .setView(scroll)
                          .setNegativeButton(R.string.back, null);
                  if (image.state == io.github.meetingassistant.core.ImageEvidence.State.READY) {
                    detail.setPositiveButton(
                        image.included ? R.string.image_exclude : R.string.image_include,
                        (dialog, w) -> {
                          try {
                            owner.includeImage(
                                image.id,
                                !image.included,
                                app.settings.provider,
                                app.repository.textKeys());
                          } catch (IllegalStateException e) {
                            imageNotice(e.getMessage());
                          }
                        });
                    detail.setNeutralButton(
                        R.string.image_reanalyze,
                        (dialog, w) -> {
                          try {
                            owner.requestImageAnalysis(
                                app.settings.provider,
                                app.repository.textKeys(),
                                app.settings.snapshot());
                          } catch (IllegalStateException e) {
                            imageNotice(e.getMessage());
                          }
                        });
                  } else if (image.state
                          == io.github.meetingassistant.core.ImageEvidence.State.QUEUED
                      || image.state
                          == io.github.meetingassistant.core.ImageEvidence.State.RECOGNIZING)
                    detail.setPositiveButton(
                        R.string.cancel, (dialog, w) -> owner.cancelImage(image.id));
                  managedDialog = detail.create();
                  managedDialog.show();
                })
            .setNegativeButton(R.string.back, null)
            .create();
    managedDialog.show();
  }

  private void toggleRecord() {
    SessionEngine.Snapshot s = app.session.snapshot();
    if (s.state == SessionEngine.State.LISTENING || s.state == SessionEngine.State.CONNECTING) {
      app.session.stop();
      return;
    }
    if (!app.session.canStartListening()) {
      message(getString(R.string.task_not_finished));
      return;
    }
    if (app.audio.active()) {
      message(getString(R.string.audio_in_use));
      return;
    }
    audioUi.permission(this::startListening, app.audio.bluetoothSelected());
  }

  boolean audioPermissionCanRun() {
    return foreground && !isDestroyed() && !isFinishing();
  }

  private void startListening() {
    try {
      if (!app.meetings.available()) throw new IllegalStateException(app.meetings.error());
      if (app.meetings.currentEngine() != app.session)
        throw new IllegalStateException(getString(R.string.switching_wait));
      if (app.meetings.summaries().stream()
          .anyMatch(r -> !r.id.equals(app.meetings.currentId()) && r.speechActive()))
        throw new IllegalStateException(getString(R.string.other_listening));
      app.settings.provider.validate();
      ProviderConfig.Credentials keys = app.repository.keys();
      keys.validate();
      app.answerScroll = 0;
      app.transcriptScroll = 0;
      app.followTranscript = true;
      app.followAnswer = true;
      answerPane.scrollTo(0, 0);
      programmaticTranscript(0);
      app.session.setAutomatic(app.settings.automatic);
      app.session.start(app.settings.provider, keys, app.settings.snapshot());
    } catch (IllegalArgumentException | IllegalStateException e) {
      message(e.getMessage());
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 102) {
      audioUi.permissionResult(permissions, grants);
      return;
    }
    if (request == 100
        && grants.length > 0
        && grants[0] == PackageManager.PERMISSION_GRANTED
        && foreground
        && !settingsScreen) startListening();
    else if (request == 100) message(getString(R.string.microphone_permission));
    if (request == 101) {
      boolean requested = cameraPermissionPending;
      cameraPermissionPending = false;
      if (requested
          && grants.length > 0
          && grants[0] == PackageManager.PERMISSION_GRANTED
          && foreground) {
        app.video.setEnabled(true);
        persistImageSelection();
      } else imageNotice(getString(R.string.image_permission));
      imageUi.refresh();
      refreshVideo();
    }
  }

  private void applyAwake() {
    if (app.awake && foreground)
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (awake != null) {
      awake.setImageResource(app.awake ? R.drawable.ic_bulb_on : R.drawable.ic_bulb_off);
      AppTheme.bind(
          awake,
          p -> {
            awake.setImageTintList(AppTheme.foreground(p, app.awake ? ACCENT : TEXT));
            awake.setBackground(UiControls.ripple(this, null, 24));
          });
      awake.setSelected(app.awake);
      String description =
          app.awake ? getString(R.string.awake_disable) : getString(R.string.awake_enable);
      awake.setContentDescription(description);
      awake.setTooltipText(description);
    }
  }

  private void copyAnswer() {
    String value = app.meetings.answer();
    if (value.isEmpty()) {
      message(getString(R.string.no_answer));
      return;
    }
    copyText(getString(R.string.clipboard_answer), value);
  }

  private void copyText(String label, String value) {
    if (value.isEmpty()) {
      message(getString(R.string.nothing_to_copy));
      return;
    }
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText(label, value));
    message(getString(R.string.copied));
  }

  private void message(String text) {
    showToast(text, Toast.LENGTH_LONG);
  }

  private void persist() {
    try {
      app.persist();
    } catch (IllegalStateException e) {
      message(e.getMessage());
    }
  }

  private void showSettings() {
    handler.removeCallbacks(hideBars);
    stopBarsAnimation();
    settingsScreen = true;
    updateBackNavigation();
    consumedKeys.clear();
    LinearLayout page = column();
    install(page);
    LinearLayout heading = row();
    ImageButton back =
        inlineIconButton(R.drawable.ic_back, getString(R.string.back), this::leaveSettings);
    back.setId(R.id.settings_back);
    heading.addView(back);
    space(heading, 12);
    heading.addView(text(getString(R.string.settings), 21, TEXT));
    page.addView(heading);
    ScrollView scroll = new ScrollView(this);
    settingsScroll = scroll;
    LinearLayout content = column();
    content.setPadding(0, dp(12), 0, dp(24));
    scroll.addView(content);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout models =
        section(content, R.id.settings_models, "settings_models", R.string.model_connections);
    models.addView(text(getString(R.string.provider_summary), 13, MUTED));
    EditText asrUrl =
        field(
            models,
            getString(R.string.asr_url),
            app.settings.provider.asrUrl,
            getString(R.string.asr_url_hint),
            false,
            false);
    EditText asrModel =
        field(
            models,
            getString(R.string.asr_model),
            app.settings.provider.asrModel,
            "",
            false,
            false);
    EditText asrKey =
        field(
            models,
            getString(R.string.asr_key),
            "",
            app.repository.hasKey("asr")
                ? getString(R.string.key_saved_hint)
                : getString(R.string.key_input_hint),
            true,
            false);
    EditText textUrl =
        field(
            models,
            getString(R.string.text_url),
            app.settings.provider.textBaseUrl,
            getString(R.string.text_url_hint),
            false,
            false);
    EditText textModel =
        field(
            models,
            getString(R.string.text_model),
            app.settings.provider.textModel,
            "",
            false,
            false);
    EditText textKey =
        field(
            models,
            getString(R.string.text_key),
            "",
            app.repository.hasKey("text")
                ? getString(R.string.key_saved_hint)
                : getString(R.string.key_input_hint),
            true,
            false);
    LinearLayout imageModels =
        section(
            models,
            R.id.settings_image_model,
            "settings_image_model",
            R.string.image_model_connection);
    imageModels.addView(text(getString(R.string.image_provider_help), 13, MUTED));
    EditText imageUrl =
        field(
            imageModels,
            getString(R.string.image_url),
            app.settings.image.baseUrl,
            "",
            false,
            false);
    imageUrl.setId(R.id.image_model_url);
    EditText imageModel =
        field(
            imageModels,
            getString(R.string.image_model),
            app.settings.image.model,
            "",
            false,
            false);
    imageModel.setId(R.id.image_model_name);
    EditText imageKey =
        field(
            imageModels,
            getString(R.string.image_key),
            "",
            getString(
                app.repository.hasKey("image") ? R.string.key_saved_hint : R.string.key_input_hint),
            true,
            false);
    imageKey.setId(R.id.image_model_key);
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    Switch imageFormat = UiControls.toggle(this, getString(R.string.image_qwen_format));
    imageFormat.setId(R.id.image_model_format);
    imageFormat.setChecked(app.settings.image.qwen35Coordinates);
    imageModels.addView(imageFormat);
    modelForm =
        java.util.List.of(
            asrUrl, asrModel, asrKey, textUrl, textModel, textKey, imageUrl, imageModel, imageKey);
    modelFormat = imageFormat;
    android.text.TextWatcher dirty =
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            modelDirty = true;
          }

          public void afterTextChanged(android.text.Editable e) {}
        };
    for (EditText e :
        new EditText[] {
          asrUrl, asrModel, asrKey, textUrl, textModel, textKey, imageUrl, imageModel, imageKey
        }) e.addTextChangedListener(dirty);
    imageFormat.setOnCheckedChangeListener((v, on) -> modelDirty = true);
    Button save =
        button(
            getString(R.string.save_models),
            true,
            () -> {
              if (app.imageJobs.busy()
                  || app.meetings.summaries().stream().anyMatch(r -> r.busy())) {
                message(getString(R.string.wait_tasks));
                return;
              }
              ProviderConfig c = new ProviderConfig();
              c.asrUrl = asrUrl.getText().toString().trim();
              c.asrModel = asrModel.getText().toString().trim();
              c.textBaseUrl = textUrl.getText().toString().trim();
              c.textModel = textModel.getText().toString().trim();
              try {
                if (!c.asrUrl.isEmpty()) {
                  ProviderConfig.validateUrl(c.asrUrl, "wss");
                  if (c.asrModel.isEmpty())
                    throw new IllegalArgumentException(
                        getString(R.string.notice_model_names_required));
                }
                if (!c.textBaseUrl.isEmpty()) c.validateText();
                new ProviderConfig.Credentials(
                        asrKey.getText().toString().isEmpty()
                            ? "saved"
                            : asrKey.getText().toString(),
                        textKey.getText().toString().isEmpty()
                            ? "saved"
                            : textKey.getText().toString())
                    .validate();
                io.github.meetingassistant.core.ImageSettings image =
                    new com.google.gson.Gson()
                        .fromJson(
                            new com.google.gson.Gson().toJson(app.settings.image),
                            io.github.meetingassistant.core.ImageSettings.class);
                image.baseUrl = imageUrl.getText().toString().trim();
                image.model = imageModel.getText().toString().trim();
                image.qwen35Coordinates = imageFormat.isChecked();
                if (!image.baseUrl.isEmpty()) image.validate();
                String suppliedImage = imageKey.getText().toString();
                if (!suppliedImage.isEmpty())
                  io.github.meetingassistant.core.OpenAiImageProvider.validateKey(suppliedImage);
                ProviderConfig old = app.settings.provider;
                var oldImage = app.settings.image;
                app.settings.provider = c;
                app.settings.image = image;
                try {
                  app.repository.saveModels(
                      app.settings,
                      asrKey.getText().toString(),
                      textKey.getText().toString(),
                      suppliedImage);
                } catch (RuntimeException e) {
                  app.settings.provider = old;
                  app.settings.image = oldImage;
                  throw e;
                }
                app.video.settings(image);
                imageKey.setText("");
                imageKey.setHint(
                    getString(
                        app.repository.hasKey("image")
                            ? R.string.key_saved_hint
                            : R.string.key_input_hint));
                asrKey.setText("");
                textKey.setText("");
                asrKey.setHint(
                    app.repository.hasKey("asr")
                        ? getString(R.string.key_saved_hint)
                        : getString(R.string.key_input_hint));
                textKey.setHint(
                    app.repository.hasKey("text")
                        ? getString(R.string.key_saved_hint)
                        : getString(R.string.key_input_hint));
                modelDirty = false;
                message(getString(R.string.models_saved));
              } catch (IllegalArgumentException | IllegalStateException e) {
                message(e.getMessage());
              }
            });
    save.setId(R.id.models_save);
    models.addView(save);
    space(models, 8);
    models.addView(
        button(
            getString(R.string.clear_saved_keys),
            false,
            () ->
                AppTheme.danger(this)
                    .setTitle(getString(R.string.clear_keys_title))
                    .setMessage(getString(R.string.clear_keys_detail))
                    .setNegativeButton(getString(R.string.back), null)
                    .setPositiveButton(
                        getString(R.string.clear),
                        (d, w) -> {
                          try {
                            app.repository.clearKeys();
                            asrKey.setText("");
                            textKey.setText("");
                            imageKey.setText("");
                            imageKey.setHint(getString(R.string.key_input_hint));
                            asrKey.setHint(getString(R.string.key_input_hint));
                            textKey.setHint(getString(R.string.key_input_hint));
                            message(getString(R.string.keys_cleared));
                          } catch (IllegalStateException e) {
                            message(e.getMessage());
                          }
                        })
                    .show()));
    LinearLayout audioOptions =
        section(content, R.id.settings_audio, "settings_audio", R.string.audio_input);
    Button audioSettings =
        button(
            getString(R.string.audio_settings),
            false,
            () -> {
              if (modelDirty) {
                message(getString(R.string.image_save_first));
                return;
              }
              audioUi.showSettings(false, null);
            });
    audioSettings.setId(R.id.audio_settings);
    audioOptions.addView(audioSettings);
    LinearLayout videoOptions =
        section(content, R.id.settings_video, "settings_video", R.string.image_input);
    videoOptions.addView(
        button(
            getString(R.string.image_settings),
            false,
            () -> {
              if (modelDirty) {
                message(getString(R.string.image_save_first));
                return;
              }
              imageUi.showSettings(false);
            }));
    LinearLayout contextOptions =
        section(content, R.id.settings_context, "settings_context", R.string.context_title);
    Button contextSettings =
        button(getString(R.string.context_settings), false, contextUi::settings);
    contextSettings.setId(R.id.context_settings);
    contextOptions.addView(contextSettings);
    LinearLayout listening =
        section(content, R.id.settings_listening, "settings_listening", R.string.listening_mode);
    CheckBox automatic = new CheckBox(this);
    automatic.setText(getString(R.string.automatic_mode));
    AppTheme.text(automatic, TEXT);
    automatic.setChecked(app.settings.automatic);
    automatic.setOnCheckedChangeListener((v, on) -> setAutomatic(on));
    listening.addView(automatic);
    LinearLayout preferenceOptions =
        section(
            content,
            R.id.settings_preferences,
            "settings_preferences",
            R.string.response_preferences);
    LinearLayout profiles = column();
    preferenceOptions.addView(profiles);
    populateProfiles(profiles);
    LinearLayout keyboard =
        section(content, R.id.settings_keyboard, "settings_keyboard", R.string.external_keys);
    CheckBox enabled = new CheckBox(this);
    enabled.setText(getString(R.string.enable_shortcuts));
    AppTheme.text(enabled, TEXT);
    enabled.setChecked(app.settings.keyboardEnabled);
    enabled.setOnCheckedChangeListener(
        (v, on) -> {
          app.settings.keyboardEnabled = on;
          persist();
        });
    keyboard.addView(enabled);
    TextView hint = text(getString(R.string.shortcuts_help), 13, MUTED);
    keyboard.addView(hint);
    LinearLayout keys = column();
    keyList = keys;
    keyboard.addView(keys);
    populateKeys(keys);
    keyboard.addView(
        button(
            getString(R.string.reset_shortcuts),
            false,
            () ->
                AppTheme.alert(this)
                    .setTitle(getString(R.string.reset_shortcuts_title))
                    .setMessage(getString(R.string.reset_shortcuts_detail))
                    .setNegativeButton(getString(R.string.back), null)
                    .setPositiveButton(
                        getString(R.string.reset),
                        (d, w) -> {
                          app.settings.resetBindings();
                          persist();
                          populateKeys(keys);
                        })
                    .show()));
    LinearLayout privacy =
        section(content, R.id.settings_privacy, "settings_privacy", R.string.data_privacy);
    privacy.addView(text(getString(R.string.privacy_detail), 13, MUTED));
    LinearLayout about = section(content, R.id.settings_about, "settings_about", R.string.about);
    aboutValue(about, R.string.about_name, R.id.about_name, getString(R.string.app_name));
    aboutValue(about, R.string.about_version, R.id.about_version, BuildConfig.VERSION_NAME);
    aboutValue(
        about, R.string.about_license, R.id.about_license, getString(R.string.project_license));
    TextView project =
        aboutValue(
            about, R.string.about_project, R.id.about_project, getString(R.string.project_url));
    AppTheme.text(project, ACCENT);
    project.setMinHeight(dp(48));
    project.setGravity(Gravity.CENTER_VERTICAL);
    project.setFocusable(true);
    project.setContentDescription(getString(R.string.about_project) + ": " + project.getText());
    project.setOnClickListener(
        v -> {
          try {
            startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url))));
          } catch (ActivityNotFoundException | SecurityException e) {
            showToast(getString(R.string.about_no_browser), Toast.LENGTH_SHORT);
          }
        });
    modelDirty = false;
  }

  private TextView aboutValue(LinearLayout parent, int label, int id, String value) {
    TextView caption = text(getString(label), 13, MUTED);
    caption.setPadding(0, dp(12), 0, dp(4));
    parent.addView(caption);
    TextView field = text(value, 15, TEXT);
    field.setId(id);
    field.setTextIsSelectable(true);
    parent.addView(field);
    return field;
  }

  private void leaveSettings() {
    if (modelDirty) {
      AppTheme.alert(this)
          .setTitle(getString(R.string.discard_model_title))
          .setNegativeButton(getString(R.string.keep_editing), null)
          .setPositiveButton(getString(R.string.discard), (d, w) -> showMain())
          .show();
    } else showMain();
  }

  private LinearLayout section(LinearLayout parent, int id, String key, int title) {
    SettingsSection section = new SettingsSection(this, key, getString(title));
    section.setId(id);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(8);
    parent.addView(section, lp);
    return section.content();
  }

  private EditText field(
      LinearLayout parent, String label, String value, String hint, boolean secret, boolean multi) {
    TextView t = text(label, 13, MUTED);
    t.setPadding(0, dp(12), 0, dp(4));
    parent.addView(t);
    EditText e = new EditText(this);
    AppTheme.text(e, TEXT);
    e.setHintTextColor(AppTheme.color(this, MUTED));
    e.setTextSize(15);
    e.setSingleLine(!multi);
    e.setInputType(
        secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : multi
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    if (multi) {
      e.setGravity(Gravity.TOP);
      e.setMinLines(5);
    }
    e.setText(value);
    e.setHint(hint);
    e.setPadding(dp(12), dp(10), dp(12), dp(10));
    AppTheme.bind(
        e,
        p -> {
          e.setTextColor(p.color(TEXT));
          e.setHintTextColor(p.color(MUTED));
          e.setHighlightColor(AppTheme.alpha(p.color(ACCENT), .25f));
          e.setBackgroundTintList(null);
          e.setBackground(surface(PANEL, 10));
        });
    e.setSaveEnabled(false);
    if (secret) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private void populateProfiles(LinearLayout list) {
    list.removeAllViews();
    list.addView(
        button(
            (app.settings.selectedId.isEmpty() ? "✓ " : "") + getString(R.string.no_preference),
            false,
            () -> {
              app.settings.selectedId = "";
              persist();
              populateProfiles(list);
            }));
    space(list, 6);
    for (UserSettings.Profile p : new ArrayList<>(app.settings.profiles)) {
      LinearLayout item = row();
      Button select =
          button(
              (p.id.equals(app.settings.selectedId) ? "✓ " : "") + p.name,
              false,
              () -> {
                app.settings.selectedId = p.id;
                persist();
                populateProfiles(list);
              });
      item.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
      space(item, 6);
      item.addView(button(getString(R.string.edit), false, () -> editProfile(p, list)));
      list.addView(item);
      space(list, 6);
    }
    list.addView(button(getString(R.string.add_preference), false, () -> editProfile(null, list)));
  }

  private void editProfile(UserSettings.Profile profile, LinearLayout list) {
    LinearLayout form = column();
    form.setPadding(dp(20), dp(8), dp(20), 0);
    EditText name =
        field(
            form,
            getString(R.string.name),
            profile == null ? "" : profile.name,
            getString(R.string.profile_name_hint),
            false,
            false);
    EditText prompt =
        field(
            form,
            getString(R.string.profile_prompt),
            profile == null ? "" : profile.prompt,
            getString(R.string.profile_prompt_hint),
            false,
            true);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    AlertDialog dialog =
        AppTheme.alert(this)
            .setTitle(
                profile == null
                    ? getString(R.string.add_preference)
                    : getString(R.string.edit_preference))
            .setView(scroll)
            .setPositiveButton(getString(R.string.save_and_use), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .setNeutralButton(profile == null ? "" : getString(R.string.delete), null)
            .create();
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    try {
                      app.settings.saveProfile(
                          profile == null ? "" : profile.id,
                          name.getText().toString(),
                          prompt.getText().toString());
                      persist();
                      populateProfiles(list);
                      dialog.dismiss();
                      showSettingsPreservingModelWarning();
                    } catch (IllegalArgumentException e) {
                      message(e.getMessage());
                    }
                  });
          dialog
              .getButton(AlertDialog.BUTTON_NEGATIVE)
              .setOnClickListener(
                  v -> {
                    boolean changed =
                        profile == null
                            ? !name.getText().toString().isEmpty()
                                || !prompt.getText().toString().isEmpty()
                            : !name.getText().toString().equals(profile.name)
                                || !prompt.getText().toString().equals(profile.prompt);
                    if (changed)
                      AppTheme.alert(this)
                          .setTitle(getString(R.string.discard_preference_title))
                          .setNegativeButton(getString(R.string.keep_editing), null)
                          .setPositiveButton(
                              getString(R.string.discard), (dd, w) -> dialog.dismiss())
                          .show();
                    else dialog.dismiss();
                  });
          if (profile != null)
            AppTheme.text(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), AppTheme.ERROR);
          if (profile == null)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
          else
            dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(
                    v ->
                        AppTheme.danger(this)
                            .setTitle(getString(R.string.delete_named, profile.name))
                            .setNegativeButton(getString(R.string.back), null)
                            .setPositiveButton(
                                getString(R.string.delete),
                                (dd, w) -> {
                                  UserSettings.Deleted deleted = app.settings.delete(profile.id);
                                  persist();
                                  populateProfiles(list);
                                  dialog.dismiss();
                                  showSettingsPreservingModelWarning();
                                  AppTheme.alert(this)
                                      .setMessage(getString(R.string.preference_deleted))
                                      .setNegativeButton(getString(R.string.done), null)
                                      .setPositiveButton(
                                          getString(R.string.undo_delete),
                                          (ddd, ww) -> {
                                            app.settings.undo(deleted);
                                            persist();
                                            showSettingsPreservingModelWarning();
                                          })
                                      .show();
                                })
                            .show());
        });
    dialog.setCanceledOnTouchOutside(false);
    dialog.setCancelable(false);
    dialog.show();
  }

  private void showSettingsPreservingModelWarning() {
    if (keyList != null) populateKeys(keyList);
  }

  private Map<String, String> actionNames() {
    Map<String, String> names = new LinkedHashMap<>();
    names.put(UserSettings.RECORD, getString(R.string.shortcut_record));
    names.put(UserSettings.AUTO, getString(R.string.shortcut_auto));
    names.put(UserSettings.IMAGE_CAPTURE, getString(R.string.image_capture));
    names.put(UserSettings.IMAGE_TOGGLE, getString(R.string.image_toggle));
    names.put(UserSettings.MEETING_NEW, getString(R.string.shortcut_meeting_new));
    names.put(UserSettings.MEETING_PREVIOUS, getString(R.string.shortcut_meeting_previous));
    names.put(UserSettings.MEETING_NEXT, getString(R.string.shortcut_meeting_next));
    names.put(UserSettings.COLLECTION_PREVIOUS, getString(R.string.shortcut_collection_previous));
    names.put(UserSettings.COLLECTION_NEXT, getString(R.string.shortcut_collection_next));
    names.put(UserSettings.TRANSCRIPT_TOGGLE, getString(R.string.shortcut_transcript_toggle));
    names.put(UserSettings.ANSWER_UP, getString(R.string.shortcut_answer_up));
    names.put(UserSettings.ANSWER_DOWN, getString(R.string.shortcut_answer_down));
    names.put(UserSettings.TRANSCRIPT_UP, getString(R.string.shortcut_transcript_up));
    names.put(UserSettings.TRANSCRIPT_DOWN, getString(R.string.shortcut_transcript_down));
    names.put(UserSettings.AWAKE, getString(R.string.shortcut_awake));
    names.put(UserSettings.NONE, getString(R.string.no_preference));
    for (UserSettings.Profile p : app.settings.profiles)
      names.put(UserSettings.profileAction(p.id), getString(R.string.shortcut_preference, p.name));
    return names;
  }

  private void populateKeys(LinearLayout list) {
    list.removeAllViews();
    for (Map.Entry<String, String> e : actionNames().entrySet()) {
      Integer key = app.settings.bindings.get(e.getKey());
      Button b =
          button(
              e.getValue()
                  + "   "
                  + (key == null
                      ? getString(R.string.unbound)
                      : KeyEvent.keyCodeToString(key).replace("KEYCODE_", "")),
              false,
              () -> capture(e.getKey(), e.getValue(), list));
      list.addView(b, new LinearLayout.LayoutParams(-1, -2));
      space(list, 6);
    }
  }

  private void capture(String action, String label, LinearLayout keys) {
    captureAction = action;
    captureDialog =
        AppTheme.alert(this)
            .setTitle(label)
            .setMessage(getString(R.string.capture_key_help))
            .setNegativeButton(getString(R.string.cancel), null)
            .setNeutralButton(
                getString(R.string.clear_binding),
                (d, w) -> {
                  app.settings.bind(action, null);
                  persist();
                  populateKeys(keys);
                })
            .create();
    captureDialog.setOnKeyListener(
        (dialog, keyCode, event) -> {
          if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            captureDialog.dismiss();
            return true;
          }
          if (keyCode == KeyEvent.KEYCODE_BACK) return false;
          if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return true;
          try {
            app.settings.bind(action, keyCode);
            persist();
            populateKeys(keys);
            captureDialog.dismiss();
          } catch (IllegalArgumentException e) {
            message(e.getMessage());
          }
          return true;
        });
    captureDialog.setOnDismissListener(d -> captureAction = null);
    captureDialog.show();
  }

  private boolean modalShowing() {
    return contextUi != null && contextUi.showing()
        || imageUi != null && imageUi.showing()
        || audioUi != null && audioUi.showing()
        || drawerDialog != null && drawerDialog.isShowing()
        || preferenceDialog != null && preferenceDialog.isShowing()
        || managedDialog != null && managedDialog.isShowing();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_UP && consumedKeys.remove(event.getKeyCode()))
      return true;
    if (!foreground || settingsScreen || modalShowing() || captureAction != null)
      return super.dispatchKeyEvent(event);
    View focus = getCurrentFocus();
    boolean editing = focus instanceof EditText;
    boolean composing =
        editing && BaseInputConnection.getComposingSpanStart(((EditText) focus).getText()) >= 0;
    if (event.getAction() != KeyEvent.ACTION_DOWN
        || (event.getMetaState()
                & (KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK))
            != 0) return super.dispatchKeyEvent(event);
    String action =
        app.settings.actionFor(event.getKeyCode(), event.getRepeatCount(), editing, composing);
    if (action == null) return super.dispatchKeyEvent(event);
    consumedKeys.add(event.getKeyCode());
    switch (action) {
      case UserSettings.RECORD:
        toggleRecord();
        break;
      case UserSettings.MEETING_NEW:
        requestMeetingSwitch(null);
        break;
      case UserSettings.COLLECTION_PREVIOUS:
        switchAdjacentCollection(-1);
        break;
      case UserSettings.COLLECTION_NEXT:
        switchAdjacentCollection(1);
        break;
      case UserSettings.MEETING_PREVIOUS:
        switchAdjacentMeeting(-1);
        break;
      case UserSettings.MEETING_NEXT:
        switchAdjacentMeeting(1);
        break;
      case UserSettings.TRANSCRIPT_TOGGLE:
        toggleTranscript();
        break;
      case UserSettings.IMAGE_CAPTURE:
        captureImage();
        break;
      case UserSettings.IMAGE_TOGGLE:
        if (app.video.enabled()) {
          app.video.setEnabled(false);
          app.audio.background();
          imageNotice(getString(R.string.video_disabled));
        } else enableVideo();
        break;
      case UserSettings.AUTO:
        setAutomatic(!app.settings.automatic);
        break;
      case UserSettings.AWAKE:
        setAwake(!app.awake);
        break;
      case UserSettings.ANSWER_UP:
        app.followAnswer = false;
        app.session.deferRevisions(true);
        answerPane.smoothScrollBy(0, -dp(80));
        break;
      case UserSettings.ANSWER_DOWN:
        answerPane.smoothScrollBy(0, dp(80));
        handler.postDelayed(this::updateAnswerReading, 300);
        break;
      case UserSettings.TRANSCRIPT_UP:
        scrollTranscript(-1);
        break;
      case UserSettings.TRANSCRIPT_DOWN:
        scrollTranscript(1);
        break;
      case UserSettings.NONE:
        selectPreference("");
        break;
      default:
        if (action.startsWith("profile:")) {
          String id = action.substring(8);
          if (app.settings.find(id) != null) {
            selectPreference(id);
          }
        }
        break;
    }
    if (action.equals(UserSettings.NONE) || action.startsWith("profile:"))
      showBarsTemporarily(4000);
    return true;
  }

  private void scrollTranscript(int direction) {
    if (!isTranscriptShown()) {
      saveScroll();
      app.transcriptExpanded = true;
      app.transcriptVisibilityChosen = true;
      buildPanes();
    }
    transcriptPane.post(
        () -> {
          app.followTranscript = false;
          transcriptPane.smoothScrollBy(0, (int) (direction * transcriptPane.getHeight() * .8));
        });
  }

  private void updateBackNavigation() {
    if (Build.VERSION.SDK_INT < 33) return;
    if (removeBackCallback != null) {
      removeBackCallback.run();
      removeBackCallback = null;
    }
    // Intercept only the settings page. Main-screen back remains system navigation.
    if (settingsScreen) {
      android.window.OnBackInvokedCallback callback = this::leaveSettings;
      android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
      dispatcher.registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
      removeBackCallback = () -> dispatcher.unregisterOnBackInvokedCallback(callback);
    }
  }

  @Override
  public void onBackPressed() {
    if (settingsScreen) leaveSettings();
    else super.onBackPressed();
  }
}
