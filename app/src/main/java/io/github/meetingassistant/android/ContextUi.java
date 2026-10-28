package io.github.meetingassistant.android;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.function.Consumer;

/** Context notices do not take focus or stop listening. Details/settings are explicit dialogs. */
final class ContextUi {
  private final Activity activity;
  private final MeetingApp app;
  private final Consumer<String> newMeeting;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private AlertDialog dialog;
  private LinearLayout banner;
  private String owner = "";

  ContextUi(Activity a, MeetingApp app, Consumer<String> create) {
    activity = a;
    this.app = app;
    newMeeting = create;
  }

  boolean showing() {
    return dialog != null && dialog.isShowing();
  }

  private String str(int id) {
    return activity.getString(id);
  }

  private int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    v.setPadding(dp(16), dp(8), dp(16), dp(8));
    return v;
  }

  private TextView text(String s) {
    TextView v = new TextView(activity);
    v.setText(s);
    v.setTextSize(14);
    AppTheme.text(v, AppTheme.TEXT);
    v.setPadding(0, dp(6), 0, dp(6));
    return v;
  }

  private Button button(int label, Runnable action) {
    Button b = UiControls.button(activity, str(label), false, action);
    return b;
  }

  void report(LinearLayout root, ContextReport report) {
    if (banner != null && (!owner.equals(app.meetings.currentId()) || banner.getParent() != root))
      clearBanner();
    if (!app.session.consumeContextNotice()) return;
    clearBanner();
    owner = app.meetings.currentId();
    String meeting = owner;
    banner = new LinearLayout(activity);
    banner.setGravity(Gravity.CENTER_VERTICAL);
    banner.setPadding(dp(6), 0, dp(6), 0);
    AppTheme.background(banner, AppTheme.PANEL);
    banner.setId(R.id.context_banner);
    TextView message =
        text(
            str(
                report.longMeeting
                    ? R.string.context_length_notice
                    : R.string.context_slow_notice));
    message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    banner.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
    banner.addView(
        button(
            R.string.context_new,
            () -> {
              clearBanner();
              if (meeting.equals(app.meetings.currentId())) newMeeting.accept("");
            }));
    banner.addView(button(R.string.context_continue, this::clearBanner));
    root.addView(banner, new LinearLayout.LayoutParams(-1, -2));
    handler.postDelayed(this::clearBanner, 8000);
  }

  void clearBanner() {
    if (banner != null && banner.getParent() instanceof android.view.ViewGroup p)
      p.removeView(banner);
    banner = null;
    handler.removeCallbacksAndMessages(null);
  }

  void details() {
    if (showing()) return;
    ContextReport r = app.session.snapshot().context;
    LinearLayout content = column();
    content.addView(
        text(activity.getString(R.string.context_metrics, r.input, r.budget, r.before)));
    String state =
        str(
            r.organizing
                ? R.string.context_organizing
                : r.organizeFailed
                    ? R.string.context_organize_failed
                    : r.summarized ? R.string.context_organized : R.string.context_recent);
    content.addView(text(state));
    if (r.limited) content.addView(text(str(R.string.context_partial)));
    content.addView(
        text(
            activity.getString(
                R.string.context_timing,
                r.firstMs < 0
                    ? "—"
                    : String.format(java.util.Locale.ROOT, "%.1f", r.firstMs / 1000.0),
                r.totalMs < 0
                    ? "—"
                    : String.format(java.util.Locale.ROOT, "%.1f", r.totalMs / 1000.0))));
    content.addView(text(str(R.string.context_slow_help)));
    String carry = app.session.contextCarry();
    CheckBox keep = new CheckBox(activity);
    keep.setText(str(R.string.context_carry));
    AppTheme.text(keep, AppTheme.TEXT);
    keep.setEnabled(!carry.isEmpty());
    keep.setId(R.id.context_carry);
    content.addView(keep);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(content);
    dialog =
        AppTheme.alert(activity)
            .setTitle(str(R.string.context_title))
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(
                R.string.context_new, (d, w) -> newMeeting.accept(keep.isChecked() ? carry : ""))
            .create();
    dialog.show();
  }

  void settings() {
    if (showing()) return;
    ContextSettings original = app.settings.context;
    LinearLayout content = column();
    content.addView(text(str(R.string.context_settings_help)));
    int[] labels = {
      R.string.context_input,
      R.string.context_window,
      R.string.context_output,
      R.string.context_percent,
      R.string.context_slow,
      R.string.context_cooldown
    };
    int[] ids = {
      R.id.context_input,
      R.id.context_window,
      R.id.context_output,
      R.id.context_percent,
      R.id.context_slow,
      R.id.context_cooldown
    };
    int[] values = {
      original.inputBudget,
      original.modelWindow,
      original.outputBudget,
      original.organizePercent,
      original.slowSeconds,
      original.cooldownMinutes
    };
    EditText[] fields = new EditText[6];
    for (int i = 0; i < fields.length; i++) {
      content.addView(text(str(labels[i])));
      EditText f = new EditText(activity);
      fields[i] = f;
      f.setId(ids[i]);
      AppTheme.text(f, AppTheme.TEXT);
      f.setSingleLine(true);
      f.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
      f.setText(String.format(java.util.Locale.ROOT, "%d", values[i]));
      content.addView(f);
    }
    TextView error = text("");
    AppTheme.text(error, AppTheme.ERROR);
    content.addView(error);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(content);
    dialog =
        AppTheme.alert(activity)
            .setTitle(str(R.string.context_title))
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              try {
                ContextSettings options = new ContextSettings();
                options.inputBudget = Integer.parseInt(fields[0].getText().toString());
                options.modelWindow = Integer.parseInt(fields[1].getText().toString());
                options.outputBudget = Integer.parseInt(fields[2].getText().toString());
                options.organizePercent = Integer.parseInt(fields[3].getText().toString());
                options.slowSeconds = Integer.parseInt(fields[4].getText().toString());
                options.cooldownMinutes = Integer.parseInt(fields[5].getText().toString());
                options.validate();
                app.settings.context = options;
                try {
                  app.repository.save(app.settings);
                } catch (RuntimeException e) {
                  app.settings.context = original;
                  throw e;
                }
                app.session.configureContext(options);
                dialog.dismiss();
              } catch (IllegalArgumentException e) {
                error.setText(str(R.string.context_settings_invalid));
              } catch (IllegalStateException e) {
                error.setText(str(R.string.context_save_failed));
              }
            });
  }

  void close() {
    clearBanner();
    if (dialog != null) dialog.dismiss();
    dialog = null;
  }
}
