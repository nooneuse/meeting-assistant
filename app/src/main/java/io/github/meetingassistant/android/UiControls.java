package io.github.meetingassistant.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared theme-aware controls with native switch/radio semantics and 48dp interaction targets. */
final class UiControls {
  static final int BACKGROUND = AppTheme.SURFACE;
  static final int TEXT = AppTheme.TEXT,
      MUTED = AppTheme.MUTED,
      PANEL = AppTheme.PANEL,
      ACCENT = AppTheme.PRIMARY;

  static int dp(Context c, int n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable shape(Context c, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(AppTheme.color(c, color));
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static RippleDrawable ripple(Context c, Drawable background, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(AppTheme.alpha(AppTheme.color(c, TEXT), .12f)),
        background,
        mask(c, radius));
  }

  private static GradientDrawable mask(Context c, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(Color.WHITE);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static Button button(Context c, String label, boolean primary, Runnable action) {
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);

    b.setMinWidth(dp(c, 48));
    b.setMinimumWidth(dp(c, 48));
    b.setMinHeight(dp(c, 48));
    b.setMinimumHeight(dp(c, 48));
    b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
    AppTheme.bind(
        b,
        p -> {
          b.setTextColor(AppTheme.foreground(p, primary ? AppTheme.ON_PRIMARY : TEXT));
          b.setBackgroundTintList(null);
          b.setBackground(ripple(c, shape(c, primary ? ACCENT : PANEL, 24), 24));
        });
    b.setOnClickListener(v -> action.run());
    return b;
  }

  static ImageButton icon(Context c, int resource, String description, Runnable action) {
    ImageButton b = new ImageButton(c);
    b.setImageResource(resource);
    b.setContentDescription(description);
    b.setTooltipText(description);
    b.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
    AppTheme.bind(
        b,
        p -> {
          b.setImageTintList(AppTheme.foreground(p, TEXT));
          b.setBackground(ripple(c, null, 24));
        });
    b.setOnClickListener(v -> action.run());
    b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
    return b;
  }

  static LinearLayout status(
      Context c, TextView label, int refreshId, String description, Runnable action) {
    LinearLayout row = new LinearLayout(c);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton refresh =
        icon(c, io.github.meetingassistant.R.drawable.ic_refresh, description, action);
    refresh.setId(refreshId);
    row.addView(refresh);
    return row;
  }

  /** A flat audio level track that blends into every page and drawer background. */
  static ProgressBar audioMeter(Context c) {
    ProgressBar meter = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
    AppTheme.bind(
        meter,
        p -> {
          LayerDrawable drawable =
              new LayerDrawable(
                  new Drawable[] {
                    shape(c, BACKGROUND, 4),
                    new ClipDrawable(shape(c, ACCENT, 4), Gravity.START, ClipDrawable.HORIZONTAL)
                  });
          drawable.setId(0, android.R.id.background);
          drawable.setId(1, android.R.id.progress);
          meter.setProgressDrawable(drawable);
          meter.setProgressBackgroundTintList(ColorStateList.valueOf(p.color(BACKGROUND)));
          meter.setProgressTintList(ColorStateList.valueOf(p.color(ACCENT)));
        });
    meter.setIndeterminate(false);
    meter.setPadding(0, dp(c, 4), 0, dp(c, 4));
    meter.setMax(100);
    return meter;
  }

  @SuppressLint(
      "UseSwitchCompatOrMaterialCode") // Explicitly themed native track/thumb, no XML inflation.
  static Switch toggle(Context c, String label) {
    Switch s = new Switch(c);
    s.setText(label);
    s.setTextSize(15);

    s.setShowText(false);
    s.setGravity(Gravity.CENTER_VERTICAL);
    s.setMinHeight(dp(c, 56));
    s.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
    s.setSwitchMinWidth(dp(c, 52));
    s.setSwitchPadding(dp(c, 16));
    s.setSplitTrack(false);
    AppTheme.bind(
        s,
        p -> {
          s.setTextColor(p.color(TEXT));
          StateListDrawable track = new StateListDrawable();
          GradientDrawable on = shape(c, ACCENT, 16);
          on.setSize(dp(c, 52), dp(c, 32));
          GradientDrawable off = shape(c, PANEL, 16);
          off.setStroke(dp(c, 1), p.color(MUTED));
          off.setSize(dp(c, 52), dp(c, 32));
          track.addState(new int[] {android.R.attr.state_checked}, on);
          track.addState(new int[] {}, off);
          StateListDrawable thumb = new StateListDrawable();
          for (boolean checked : new boolean[] {true, false}) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(p.color(checked ? AppTheme.ON_PRIMARY : TEXT));
            d.setSize(dp(c, 24), dp(c, 24));
            // Switch stretches the thumb to the track height; retain a circular 24dp center.
            thumb.addState(
                checked ? new int[] {android.R.attr.state_checked} : new int[] {},
                new InsetDrawable(d, 0, dp(c, 4), 0, dp(c, 4)));
          }
          s.setTrackDrawable(track);
          s.setThumbDrawable(thumb);
          s.setBackground(ripple(c, null, 24));
        });
    return s;
  }

  static RadioButton radio(
      Context c, String label, boolean checked, boolean enabled, Runnable action) {
    RadioButton b = new RadioButton(c);
    b.setId(View.generateViewId());
    b.setText(label);
    b.setTextSize(14);
    b.setCompoundDrawablePadding(dp(c, 12));
    b.setGravity(Gravity.CENTER_VERTICAL);
    b.setMinHeight(dp(c, 52));
    b.setPadding(0, dp(c, 10), dp(c, 14), dp(c, 10));
    AppTheme.bind(
        b,
        p -> {
          b.setTextColor(
              new ColorStateList(
                  new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                  new int[] {
                    AppTheme.alpha(p.color(TEXT), .38f),
                    p.color(AppTheme.ON_SELECTED),
                    p.color(TEXT)
                  }));
          b.setButtonDrawable(new InsetDrawable(new RadioMark(c, p), dp(c, 12), 0, dp(c, 8), 0));
          b.setButtonTintList(null);
          StateListDrawable background = new StateListDrawable();
          GradientDrawable selected = shape(c, AppTheme.SELECTED, 16);
          selected.setStroke(dp(c, 1), p.color(ACCENT));
          background.addState(new int[] {android.R.attr.state_checked}, selected);
          background.addState(new int[] {}, shape(c, PANEL, 16));
          b.setBackground(ripple(c, background, 16));
        });
    b.setChecked(checked);
    b.setEnabled(enabled);
    RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(-1, -2);
    lp.bottomMargin = dp(c, 6);
    b.setLayoutParams(lp);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private static final class RadioMark extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private final AppTheme.Palette palette;
    private boolean checked, enabled = true;

    RadioMark(Context c, AppTheme.Palette palette) {
      this.palette = palette;
      size = dp(c, 24);
    }

    public boolean isStateful() {
      return true;
    }

    protected boolean onStateChange(int[] states) {
      checked = false;
      enabled = false;
      for (int s : states) {
        checked |= s == android.R.attr.state_checked;
        enabled |= s == android.R.attr.state_enabled;
      }
      invalidateSelf();
      return true;
    }

    public void draw(Canvas canvas) {
      Rect b = getBounds();
      float cx = b.exactCenterX(),
          cy = b.exactCenterY(),
          r = Math.min(b.width(), b.height()) * .38f;
      paint.setColor(
          !enabled
              ? AppTheme.alpha(palette.color(TEXT), .38f)
              : palette.color(checked ? ACCENT : MUTED));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(size / 12f);
      canvas.drawCircle(cx, cy, r, paint);
      if (checked) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, r * .52f, paint);
      }
    }

    public int getIntrinsicWidth() {
      return size;
    }

    public int getIntrinsicHeight() {
      return size;
    }

    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  private UiControls() {}
}
