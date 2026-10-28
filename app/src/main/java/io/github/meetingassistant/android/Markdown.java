package io.github.meetingassistant.android;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small safe Markdown renderer: no HTML, links or executable content. */
final class Markdown {
  static CharSequence render(Context context, String source) {
    AppTheme.Palette palette = AppTheme.colors(context);
    SpannableStringBuilder out = new SpannableStringBuilder();
    boolean code = false;
    for (String line : source.split("\n", -1)) {
      if (line.trim().startsWith("```")) {
        code = !code;
        continue;
      }
      int start = out.length();
      if (code) {
        out.append(line).append('\n');
        span(out, new TypefaceSpan("monospace"), start, out.length());
        span(out, new AppTheme.ColorSpan(palette, AppTheme.PANEL, true), start, out.length());
        span(out, new AppTheme.ColorSpan(palette, AppTheme.TEXT, false), start, out.length());
        continue;
      }
      if (line.matches("^#{1,6} .*")) {
        out.append(line.replaceFirst("^#{1,6} +", "")).append('\n');
        span(out, new StyleSpan(Typeface.BOLD), start, out.length());
        span(out, new RelativeSizeSpan(1.12f), start, out.length());
      } else {
        out.append(line).append('\n');
        styleInline(out, start, out.length(), Pattern.compile("\\*\\*(.+?)\\*\\*"), false);
        styleInline(out, start, out.length(), Pattern.compile("`([^`]+)`"), true);
      }
    }
    return out;
  }

  private static void styleInline(
      SpannableStringBuilder text, int start, int end, Pattern pattern, boolean mono) {
    Matcher m = pattern.matcher(text.subSequence(start, end).toString());
    int removed = 0;
    while (m.find()) {
      int a = start + m.start() - removed, b = start + m.end() - removed, trim = mono ? 1 : 2;
      text.delete(b - trim, b);
      text.delete(a, a + trim);
      span(
          text,
          mono ? new TypefaceSpan("monospace") : new StyleSpan(Typeface.BOLD),
          a,
          b - 2 * trim);
      removed += 2 * trim;
    }
  }

  private static void span(SpannableStringBuilder s, Object span, int a, int b) {
    if (b > a) s.setSpan(span, a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
}
