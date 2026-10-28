package io.github.meetingassistant.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import io.github.meetingassistant.R;

/** Persistent disclosure section. Folding retains the same form views and their unsaved values. */
@SuppressLint(
    "ViewConstructor") // Programmatic only: a stable preference key and title are required.
final class SettingsSection extends LinearLayout {
  private final LinearLayout header, content;
  private final ImageView arrow;
  private final String key, title;
  private boolean expanded;

  SettingsSection(Context context, String key, String title) {
    super(context);
    this.key = key;
    this.title = title;
    setOrientation(VERTICAL);
    header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(12), dp(4), dp(12), dp(4));
    header.setMinimumHeight(dp(56));
    header.setBackground(UiControls.ripple(context, null, 24));
    header.setFocusable(true);
    if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
    TextView label = new TextView(context);
    label.setText(title);
    AppTheme.text(label, AppTheme.TEXT);
    label.setTextSize(17);
    label.setTypeface(null, Typeface.BOLD);
    label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    header.addView(label, new LayoutParams(0, -2, 1));
    arrow = new ImageView(context);
    arrow.setImageResource(R.drawable.ic_chevron);
    AppTheme.bind(
        arrow,
        p ->
            arrow.setImageTintList(
                android.content.res.ColorStateList.valueOf(p.color(AppTheme.TEXT))));
    arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    header.addView(arrow, new LayoutParams(dp(24), dp(24)));
    addView(header, new LayoutParams(-1, -2));
    content = new LinearLayout(context);
    content.setOrientation(VERTICAL);
    content.setPadding(dp(4), 0, dp(4), dp(12));
    addView(content, new LayoutParams(-1, -2));
    header.setOnClickListener(v -> setExpanded(!expanded));
    header.setAccessibilityDelegate(
        new AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
            info.addAction(
                expanded
                    ? AccessibilityNodeInfo.ACTION_COLLAPSE
                    : AccessibilityNodeInfo.ACTION_EXPAND);
          }

          @Override
          public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_EXPAND
                || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
              setExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND);
              return true;
            }
            return super.performAccessibilityAction(host, action, args);
          }
        });
    expanded =
        context.getSharedPreferences("settings-ui-v1", Context.MODE_PRIVATE).getBoolean(key, false);
    render();
  }

  private int dp(int n) {
    return UiControls.dp(getContext(), n);
  }

  LinearLayout content() {
    return content;
  }

  View heading() {
    return header;
  }

  boolean expanded() {
    return expanded;
  }

  void setExpanded(boolean value) {
    if (expanded == value) return;
    if (!value && content.findFocus() != null) {
      content.findFocus().clearFocus();
      header.requestFocus();
      InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
      if (input != null) input.hideSoftInputFromWindow(getWindowToken(), 0);
    }
    expanded = value;
    render();
    getContext()
        .getSharedPreferences("settings-ui-v1", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(key, value)
        .apply();
  }

  private void render() {
    content.setVisibility(expanded ? VISIBLE : GONE);
    arrow.setRotation(expanded ? 90 : 0);
    header.setContentDescription(
        getContext()
            .getString(expanded ? R.string.section_collapse : R.string.section_expand, title));
    if (Build.VERSION.SDK_INT >= 30)
      header.setStateDescription(
          getContext()
              .getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
  }
}
