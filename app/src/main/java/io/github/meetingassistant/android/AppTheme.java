package io.github.meetingassistant.android;

import android.app.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.os.Build;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import com.google.android.material.color.DynamicColors;
import io.github.meetingassistant.R;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

/** UI-only theme state. No view reconstruction, provider work, or persistent color cache. */
final class AppTheme {
  static final int SURFACE = com.google.android.material.R.attr.colorSurface;
  static final int TEXT = com.google.android.material.R.attr.colorOnSurface;
  static final int MUTED = com.google.android.material.R.attr.colorOnSurfaceVariant;
  static final int PANEL = com.google.android.material.R.attr.colorSurfaceContainer;
  static final int PRIMARY = androidx.appcompat.R.attr.colorPrimary;
  static final int ON_PRIMARY = com.google.android.material.R.attr.colorOnPrimary;
  static final int SELECTED = com.google.android.material.R.attr.colorSecondaryContainer;
  static final int ON_SELECTED = com.google.android.material.R.attr.colorOnSecondaryContainer;
  static final int AUTO = com.google.android.material.R.attr.colorTertiaryContainer;
  static final int ON_AUTO = com.google.android.material.R.attr.colorOnTertiaryContainer;
  static final int ERROR = androidx.appcompat.R.attr.colorError;
  static final int ERROR_PANEL = com.google.android.material.R.attr.colorErrorContainer;
  static final int ON_ERROR_PANEL = com.google.android.material.R.attr.colorOnErrorContainer;
  static final int OUTLINE = com.google.android.material.R.attr.colorOutlineVariant;
  private static final int[] ROLES = {
    SURFACE,
    TEXT,
    MUTED,
    PANEL,
    PRIMARY,
    ON_PRIMARY,
    SELECTED,
    ON_SELECTED,
    AUTO,
    ON_AUTO,
    ERROR,
    ERROR_PANEL,
    ON_ERROR_PANEL,
    OUTLINE
  };
  private static final Map<Activity, State> states = new WeakHashMap<>();

  static final class Palette {
    final boolean dark;
    private final int[] colors;

    Palette(Context context) {
      dark =
          (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
              == Configuration.UI_MODE_NIGHT_YES;
      colors = new int[ROLES.length];
      for (int i = 0; i < ROLES.length; i++) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(ROLES[i], value, true))
          throw new IllegalStateException("Missing theme color role");
        colors[i] = value.resourceId == 0 ? value.data : context.getColor(value.resourceId);
      }
    }

    int color(int role) {
      for (int i = 0; i < ROLES.length; i++) if (role == ROLES[i]) return colors[i];
      throw new IllegalArgumentException("Unknown theme color role");
    }

    boolean same(Palette other) {
      return other != null && dark == other.dark && Arrays.equals(colors, other.colors);
    }
  }

  private static final class WindowRef {
    final WeakReference<Window> window;
    final boolean rounded;

    WindowRef(Window window, boolean rounded) {
      this.window = new WeakReference<>(window);
      this.rounded = rounded;
    }
  }

  private static final class State {
    Palette palette;
    final List<WindowRef> windows = new ArrayList<>();
  }

  static void prepare(Activity activity) {
    activity.setTheme(R.style.AppTheme);
    android.content.res.Resources.Theme fresh = activity.getResources().newTheme();
    fresh.applyStyle(R.style.AppTheme, true);
    activity.getTheme().setTo(fresh);
    DynamicColors.applyToActivityIfAvailable(activity);
  }

  static void start(Activity activity) {
    states.computeIfAbsent(activity, a -> new State()).palette = new Palette(activity);
    track(activity, activity.getWindow(), false);
  }

  static Palette colors(Context context) {
    Activity activity = owner(context);
    State state = states.get(activity);
    return state == null || state.palette == null ? new Palette(context) : state.palette;
  }

  static int color(Context context, int role) {
    return colors(context).color(role);
  }

  static int alpha(int color, float fraction) {
    return (color & 0x00ffffff) | (Math.round(Color.alpha(color) * fraction) << 24);
  }

  static void bind(View view, Consumer<Palette> binding) {
    view.setTag(R.id.theme_binding, binding);
    binding.accept(colors(view.getContext()));
  }

  static ColorStateList foreground(Palette p, int role) {
    return new ColorStateList(
        new int[][] {{-android.R.attr.state_enabled}, {}},
        new int[] {alpha(p.color(TEXT), .38f), p.color(role)});
  }

  static void text(TextView view, int role) {
    view.setTag(R.id.theme_text_role, role);
    view.setTextColor(foreground(colors(view.getContext()), role));
  }

  static void background(View view, int role) {
    bind(view, p -> view.setBackgroundColor(p.color(role)));
  }

  static void refresh(Activity activity) {
    prepare(activity);
    State state = states.computeIfAbsent(activity, a -> new State());
    Palette next = new Palette(activity);
    if (next.same(state.palette)) return;
    apply(activity, next);
  }

  static void apply(Activity activity, Palette next) {
    State state = states.computeIfAbsent(activity, a -> new State());
    state.palette = next;
    state.windows.removeIf(ref -> ref.window.get() == null);
    for (WindowRef ref : state.windows) {
      Window window = ref.window.get();
      if (window != null) applyWindow(window, next, ref.rounded);
    }
  }

  static Dialog page(Activity activity) {
    Dialog dialog = new Dialog(activity);
    track(activity, dialog.getWindow(), false);
    return dialog;
  }

  static AlertDialog.Builder alert(Context context) {
    return alert(context, false);
  }

  static AlertDialog.Builder danger(Context context) {
    return alert(context, true);
  }

  private static AlertDialog.Builder alert(Context context, boolean danger) {
    return new AlertDialog.Builder(context) {
      @Override
      public AlertDialog create() {
        AlertDialog dialog = super.create();
        if (danger) dialog.getWindow().getDecorView().setTag(R.id.theme_danger, true);
        Activity activity = owner(context);
        if (activity != null) track(activity, dialog.getWindow(), true);
        return dialog;
      }
    };
  }

  private static Activity owner(Context context) {
    while (context instanceof ContextWrapper wrapper) {
      if (context instanceof Activity activity) return activity;
      Context base = wrapper.getBaseContext();
      if (base == context) break;
      context = base;
    }
    return context instanceof Activity activity ? activity : null;
  }

  private static void track(Activity activity, Window window, boolean rounded) {
    if (window == null) return;
    State state = states.computeIfAbsent(activity, a -> new State());
    state.windows.removeIf(ref -> ref.window.get() == null);
    state.windows.add(new WindowRef(window, rounded));
    applyWindow(window, colors(activity), rounded);
    window
        .getDecorView()
        .addOnAttachStateChangeListener(
            new View.OnAttachStateChangeListener() {
              @Override
              public void onViewAttachedToWindow(View view) {
                view.post(() -> applyWindow(window, colors(activity), rounded));
              }

              @Override
              public void onViewDetachedFromWindow(View view) {}
            });
  }

  @SuppressWarnings("deprecation")
  private static void applyWindow(Window window, Palette p, boolean rounded) {
    View decor = window.getDecorView();
    window.setBackgroundDrawable(
        rounded
            ? UiControls.shape(window.getContext(), PANEL, 28)
            : new ColorDrawable(p.color(SURFACE)));
    window.setStatusBarColor(p.color(SURFACE));
    window.setNavigationBarColor(p.color(SURFACE));
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController control = decor.getWindowInsetsController();
      if (control != null) {
        int flags =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        control.setSystemBarsAppearance(p.dark ? 0 : flags, flags);
      }
    } else {
      int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
      int current = window.getDecorView().getSystemUiVisibility();
      window.getDecorView().setSystemUiVisibility(p.dark ? current & ~flags : current | flags);
    }
    if (Boolean.TRUE.equals(decor.getTag(R.id.theme_danger))) {
      Button confirm = decor.findViewById(android.R.id.button1);
      if (confirm != null) text(confirm, ERROR);
    }
    refreshTree(window.getDecorView(), p);
  }

  @SuppressWarnings("unchecked")
  static void refreshTree(View view, Palette p) {
    if (view instanceof TextView text) {
      text.setTextColor(
          foreground(
              p, view instanceof Button && !(view instanceof CompoundButton) ? PRIMARY : TEXT));
      text.setHintTextColor(p.color(MUTED));
      text.setLinkTextColor(p.color(PRIMARY));
      text.setHighlightColor(alpha(p.color(PRIMARY), .25f));
      if (text.getText() instanceof Spanned spans) {
        for (ColorSpan span : spans.getSpans(0, spans.length(), ColorSpan.class)) span.update(p);
      }
      if (text instanceof EditText edit) {
        edit.setBackgroundTintList(
            new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {p.color(PRIMARY), p.color(OUTLINE)}));
      }
      if (text instanceof CheckedTextView checked)
        checked.setCheckMarkTintList(
            new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {p.color(PRIMARY), p.color(MUTED)}));
      if (text instanceof EditText edit && Build.VERSION.SDK_INT >= 29) {
        Drawable cursor = edit.getTextCursorDrawable();
        if (cursor != null) {
          cursor = cursor.mutate();
          cursor.setTint(p.color(PRIMARY));
          edit.setTextCursorDrawable(cursor);
        }
        tint(edit.getTextSelectHandle(), p.color(PRIMARY));
        tint(edit.getTextSelectHandleLeft(), p.color(PRIMARY));
        tint(edit.getTextSelectHandleRight(), p.color(PRIMARY));
      }
    }
    if (view instanceof CompoundButton button && !(view instanceof Switch)) {
      button.setButtonTintList(
          new ColorStateList(
              new int[][] {{android.R.attr.state_checked}, {}},
              new int[] {p.color(PRIMARY), p.color(MUTED)}));
    }
    if (view instanceof ImageView image) image.setImageTintList(foreground(p, TEXT));
    updateDrawable(view.getBackground(), p);
    Object binding = view.getTag(R.id.theme_binding);
    if (binding instanceof Consumer<?>) ((Consumer<Palette>) binding).accept(p);
    if (view instanceof TextView text && view.getTag(R.id.theme_text_role) instanceof Integer role)
      text.setTextColor(foreground(p, role));
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) refreshTree(group.getChildAt(i), p);
    view.invalidate();
  }

  private static void tint(Drawable drawable, int color) {
    if (drawable != null) drawable.mutate().setTint(color);
  }

  private static void updateDrawable(Drawable drawable, Palette p) {
    if (drawable instanceof RippleDrawable ripple)
      ripple.setColor(ColorStateList.valueOf(alpha(p.color(TEXT), .12f)));
    if (drawable instanceof LayerDrawable layers)
      for (int i = 0; i < layers.getNumberOfLayers(); i++) updateDrawable(layers.getDrawable(i), p);
    if (drawable instanceof InsetDrawable inset) updateDrawable(inset.getDrawable(), p);
  }

  static final class ColorSpan extends CharacterStyle implements UpdateAppearance {
    private final int role;
    private final boolean background;
    private int color;

    ColorSpan(Palette p, int role, boolean background) {
      this.role = role;
      this.background = background;
      update(p);
    }

    void update(Palette p) {
      color = p.color(role);
    }

    @Override
    public void updateDrawState(TextPaint paint) {
      if (background) paint.bgColor = color;
      else paint.setColor(color);
    }
  }

  private AppTheme() {}
}
