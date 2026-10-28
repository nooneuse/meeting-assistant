package io.github.meetingassistant.android;

import android.annotation.SuppressLint;
import android.app.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.*;

/** Right drawer and its dedicated settings page. Preview never appears on the meeting canvas. */
final class ImageInputUi {
  private final MainActivity activity;
  private final AudioInputUi audioUi;
  private final MeetingApp app;
  private final Runnable enable, changed;
  private Dialog drawer, settings;
  private LinearLayout drawerContent;
  private RadioGroup sourceList;
  private TextView stateLabel;

  @SuppressLint("UseSwitchCompatOrMaterialCode") // Created and styled exclusively by UiControls.
  private Switch switchView;

  private CropPreview preview;
  private boolean updating, returnDrawer;
  private String signature = "";
  private final LocalizedMessages messages;

  ImageInputUi(
      MainActivity activity,
      MeetingApp app,
      Runnable enable,
      AudioInputUi audioUi,
      Runnable changed) {
    this.activity = activity;
    this.audioUi = audioUi;
    this.app = app;
    this.enable = enable;
    this.changed = changed;
    messages = new LocalizedMessages(activity);
  }

  private String str(int id) {
    return activity.getString(id);
  }

  private int dp(int n) {
    return (int) (n * activity.getResources().getDisplayMetrics().density + .5f);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private TextView text(String value, int sp) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextSize(sp);
    AppTheme.text(v, AppTheme.TEXT);
    v.setPadding(0, dp(8), 0, dp(8));
    return v;
  }

  private Button button(String value, Runnable action) {
    Button button = UiControls.button(activity, value, false, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(8);
    button.setLayoutParams(lp);
    return button;
  }

  private void tell(String value) {
    activity.imageNotice(value);
  }

  boolean showing() {
    return drawer != null && drawer.isShowing() || settingsShowing();
  }

  boolean returnsToDrawer() {
    return returnDrawer;
  }

  boolean settingsShowing() {
    return settings != null && settings.isShowing();
  }

  void closeDrawer() {
    if (drawer != null) drawer.dismiss();
  }

  void destroy() {
    returnDrawer = false;
    closeDrawer();
    if (settings != null) settings.dismiss();
  }

  void showDrawer() {
    if (showing()) return;
    drawer = AppTheme.page(activity);
    drawer.setCanceledOnTouchOutside(true);
    drawerContent = column();
    drawerContent.setPadding(dp(12), dp(16), dp(12), dp(16));
    AppTheme.background(drawerContent, AppTheme.SURFACE);
    drawerContent.addView(text(str(R.string.input_sources), 20));
    audioUi.addDrawer(
        drawerContent,
        () -> {
          closeDrawer();
          audioUi.showSettings(true, this::showDrawer);
        });
    drawerContent.addView(text(str(R.string.image_input), 18));
    addControls(drawerContent);
    drawerContent.addView(
        button(
            str(R.string.image_settings),
            () -> {
              closeDrawer();
              showSettings(true);
            }));
    ScrollView drawerScroll = new ScrollView(activity);
    drawerScroll.addView(drawerContent);
    drawer.setContentView(drawerScroll);
    drawer.setOnDismissListener(
        d -> {
          drawerContent = null;
          audioUi.drawerClosed();
          changed.run();
        });
    guardKeys(drawer);
    drawer.show();
    Window w = drawer.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(new ColorDrawable(AppTheme.color(activity, AppTheme.SURFACE)));
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setDimAmount(.5f);
      w.setGravity(Gravity.END | Gravity.TOP);
      View root = activity.findViewById(android.R.id.content);
      int available =
          root.getWidth() > 0
              ? root.getWidth()
              : activity.getResources().getDisplayMetrics().widthPixels;
      w.setLayout(Math.min(dp(360), (int) (available * .86f)), -1);
    }
    applyInsets(drawerContent);
    signature = "";
    refresh();
    changed.run();
  }

  private void addControls(LinearLayout parent) {
    switchView = UiControls.toggle(activity, str(R.string.video_input));
    switchView.setId(R.id.video_enabled);
    switchView.setText(str(R.string.video_input));
    switchView.setOnCheckedChangeListener(
        (v, on) -> {
          if (updating) return;
          if (on) enable.run();
          else {
            app.video.setEnabled(false);
            tell(str(R.string.video_disabled));
          }
          refresh();
          changed.run();
        });
    parent.addView(switchView);
    stateLabel = text("", 14);
    stateLabel.setId(R.id.image_source_status);
    parent.addView(
        UiControls.status(
            activity,
            stateLabel,
            R.id.image_refresh,
            str(R.string.image_refresh_label),
            () -> {
              signature = "";
              refresh();
            }));
    parent.addView(text(str(R.string.image_source), 16));
    sourceList = new RadioGroup(activity);
    sourceList.setId(R.id.image_sources);
    parent.addView(sourceList, new LinearLayout.LayoutParams(-1, -2));
  }

  String deviceLabel(ImageSource.Device device, int ordinal) {
    int name =
        switch (device.kind()) {
          case BACK -> R.string.camera_back;
          case FRONT -> R.string.camera_front;
          case EXTERNAL -> R.string.camera_external;
          case USB -> R.string.camera_usb;
        };
    return str(name) + " " + ordinal;
  }

  String status() {
    return switch (app.video.state()) {
      case OFF -> str(R.string.video_disabled);
      case OPENING -> str(R.string.video_opening);
      case READY -> str(R.string.video_ready);
      case ERROR -> messages.notice(app.video.error());
    };
  }

  void refresh() {
    if (!showing()) return;
    audioUi.refresh();
    updating = true;
    if (switchView != null) switchView.setChecked(app.video.enabled());
    if (stateLabel != null) stateLabel.setText(status());
    java.util.List<ImageSource.Device> devices = app.video.devices();
    String next = devices.toString() + app.settings.image.sourceId;
    if (!next.equals(signature) && sourceList != null) {
      signature = next;
      sourceList.removeAllViews();
      if (devices.isEmpty()) sourceList.addView(text(str(R.string.image_no_sources), 14));
      boolean selectedFound = false;
      int index = 0;
      for (ImageSource.Device device : devices) {
        boolean selected = device.id().equals(app.settings.image.sourceId);
        selectedFound |= selected;
        RadioButton item =
            UiControls.radio(
                activity,
                deviceLabel(device, ++index),
                selected,
                true,
                () -> {
                  app.video.select(device.id());
                  try {
                    app.repository.save(app.settings);
                  } catch (IllegalStateException e) {
                    tell(messages.notice(e.getMessage()));
                  }
                  signature = "";
                  refresh();
                  changed.run();
                });
        sourceList.addView(item);
      }
      if (!selectedFound && !app.settings.image.sourceId.isEmpty())
        sourceList.addView(text(str(R.string.image_source_missing), 14));
    }
    updating = false;
  }

  void showSettings(boolean fromDrawer) {
    if (settingsShowing()) return;
    returnDrawer = fromDrawer;
    closeDrawer();
    settings = AppTheme.page(activity);
    settings.setCanceledOnTouchOutside(false);
    LinearLayout page = column();
    page.setPadding(dp(12), 0, dp(12), 0);
    AppTheme.background(page, AppTheme.SURFACE);
    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton back =
        UiControls.icon(activity, R.drawable.ic_back, str(R.string.back), this::leaveSettings);
    back.setId(R.id.image_settings_back);
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    header.addView(text(str(R.string.image_settings), 20));
    page.addView(header);
    ScrollView scroll = new ScrollView(activity);
    LinearLayout content = column();
    scroll.addView(content);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    addControls(content);
    preview = new CropPreview();
    preview.setContentDescription(str(R.string.image_preview));
    preview.setId(R.id.image_preview);
    content.addView(preview, new LinearLayout.LayoutParams(-1, dp(240)));
    content.addView(text(str(R.string.image_crop_help), 13));
    content.addView(
        button(
            str(R.string.image_crop_reset),
            () -> {
              app.settings.image.left = app.settings.image.top = 0;
              app.settings.image.right = app.settings.image.bottom = 1;
              app.settings.image.cropSource = "";
              persistCrop();
              preview.invalidate();
            }));
    content.addView(text(str(R.string.image_privacy), 13));
    settings.setContentView(page);
    settings.setOnKeyListener(
        (d, code, event) -> {
          if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) leaveSettings();
            return true;
          }
          return false;
        });
    settings.setOnDismissListener(
        d -> {
          app.video.preview(null);
          if (preview != null) preview.release();
          preview = null;
          signature = "";
          if (returnDrawer && !activity.isDestroyed() && !activity.isFinishing()) showDrawer();
          changed.run();
        });
    settings.show();
    Window w = settings.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(new ColorDrawable(AppTheme.color(activity, AppTheme.SURFACE)));
      w.setLayout(-1, -1);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    applyInsets(page);
    app.video.preview(
        bitmap -> {
          if (preview != null && settingsShowing()) preview.image(bitmap);
          else bitmap.recycle();
        });
    signature = "";
    refresh();
    changed.run();
  }

  private void leaveSettings() {
    settings.dismiss();
  }

  private void persistCrop() {
    try {
      app.repository.save(app.settings);
    } catch (IllegalStateException e) {
      tell(messages.notice(e.getMessage()));
    }
  }

  private void guardKeys(Dialog dialog) {
    dialog.setOnKeyListener(
        (d, code, event) -> {
          if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
            return true;
          }
          return app.settings.actionFor(code, event.getRepeatCount(), false, false) != null;
        });
  }

  private void applyInsets(View view) {
    int l = view.getPaddingLeft(),
        t = view.getPaddingTop(),
        r = view.getPaddingRight(),
        b = view.getPaddingBottom();
    view.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            Insets i =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom);
          } else
            v.setPadding(
                l + insets.getSystemWindowInsetLeft(),
                t + insets.getSystemWindowInsetTop(),
                r + insets.getSystemWindowInsetRight(),
                b + insets.getSystemWindowInsetBottom());
          return insets;
        });
    view.requestApplyInsets();
  }

  private final class CropPreview extends View {
    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF display = new RectF();
    private float startX, startY, endX, endY;
    private boolean dragging;

    CropPreview() {
      super(activity);
      setBackgroundColor(0xff000000);
    }

    void image(Bitmap image) {
      release();
      bitmap = image;
      invalidate();
    }

    void release() {
      if (bitmap != null) {
        bitmap.recycle();
        bitmap = null;
      }
    }

    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (bitmap == null) return;
      float scale =
          Math.min(
              (float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
      float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
      display.set(
          (getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
      canvas.drawBitmap(bitmap, null, display, paint);
      ImageSettings c = app.settings.image;
      if (dragging
          || c.cropSource.equals(c.sourceId)
              && c.cropWidth == app.video.previewWidth
              && c.cropHeight == app.video.previewHeight
              && c.cropRotation == app.video.previewRotation) {
        float l = dragging ? Math.min(startX, endX) : c.left,
            t = dragging ? Math.min(startY, endY) : c.top;
        float r = dragging ? Math.max(startX, endX) : c.right,
            b = dragging ? Math.max(startY, endY) : c.bottom;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(dp(4));
        canvas.drawRect(
            display.left + l * display.width(),
            display.top + t * display.height(),
            display.left + r * display.width(),
            display.top + b * display.height(),
            paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(dp(2));
        canvas.drawRect(
            display.left + l * display.width(),
            display.top + t * display.height(),
            display.left + r * display.width(),
            display.top + b * display.height(),
            paint);
        paint.setStyle(Paint.Style.FILL);
      }
    }

    public boolean onTouchEvent(MotionEvent event) {
      if (bitmap == null || display.width() == 0) return false;
      float x = Math.max(0, Math.min(1, (event.getX() - display.left) / display.width())),
          y = Math.max(0, Math.min(1, (event.getY() - display.top) / display.height()));
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        startX = endX = x;
        startY = endY = y;
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
      } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
        endX = x;
        endY = y;
      } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
        endX = x;
        endY = y;
        dragging = false;
        if (Math.abs(endX - startX) >= .05 && Math.abs(endY - startY) >= .05) {
          ImageSettings c = app.settings.image;
          c.left = Math.min(startX, endX);
          c.top = Math.min(startY, endY);
          c.right = Math.max(startX, endX);
          c.bottom = Math.max(startY, endY);
          c.cropSource = c.sourceId;
          c.cropWidth = app.video.previewWidth;
          c.cropHeight = app.video.previewHeight;
          c.cropRotation = app.video.previewRotation;
          persistCrop();
        }
        performClick();
        getParent().requestDisallowInterceptTouchEvent(false);
      } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = false;
      invalidate();
      return true;
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
