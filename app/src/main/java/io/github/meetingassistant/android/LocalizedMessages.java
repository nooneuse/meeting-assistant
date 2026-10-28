package io.github.meetingassistant.android;

import android.content.Context;
import android.content.res.TypedArray;
import io.github.meetingassistant.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localizes only owned notices, including statuses saved by previous Chinese-only versions. Meeting
 * content and user names must never be sent through this adapter.
 */
final class LocalizedMessages {
  private static final Pattern SLOT = Pattern.compile("%(\\d+)\\$s");
  private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
  private final Context context;
  private final boolean chinese;
  private final Map<String, String> exact = new HashMap<>();
  private final List<Rule> rules = new ArrayList<>();

  private record Rule(Pattern pattern, String english, int[] slots) {}

  LocalizedMessages(Context context) {
    this.context = context;
    chinese =
        context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");
    Context zh = UiLocale.withLocale(context, UiLocale.CHINESE);
    Context en = UiLocale.withLocale(context, Locale.ENGLISH);
    TypedArray notices = context.getResources().obtainTypedArray(R.array.owned_notices);
    try {
      for (int i = 0; i < notices.length(); i++) {
        int id = notices.getResourceId(i, 0);
        String source = zh.getString(id);
        String target = chinese ? source : en.getString(id);
        register(source, target);
        // Translate fixed legacy status/error text without rewriting user topic names.
        if (source.contains("议题")) register(source.replace("议题", "会议"), target);
      }
    } finally {
      notices.recycle();
    }
  }

  private void register(String source, String english) {
    Matcher matcher = SLOT.matcher(source);
    StringBuilder regex = new StringBuilder();
    List<Integer> slots = new ArrayList<>();
    int end = 0;
    while (matcher.find()) {
      regex.append(Pattern.quote(source.substring(end, matcher.start()))).append("(.*?)");
      slots.add(Integer.parseInt(matcher.group(1)) - 1);
      end = matcher.end();
    }
    if (slots.isEmpty()) exact.put(source, english);
    else {
      regex.append(Pattern.quote(source.substring(end)));
      rules.add(
          new Rule(
              Pattern.compile(regex.toString(), Pattern.DOTALL),
              english,
              slots.stream().mapToInt(Integer::intValue).toArray()));
    }
  }

  String notice(String source) {
    if (source == null) return context.getString(R.string.unexpected_error);
    if (source.isEmpty()) return source;
    String translated = exact.get(source);
    if (translated != null) return translated;
    for (Rule rule : rules) {
      Matcher matcher = rule.pattern.matcher(source);
      if (!matcher.matches()) continue;
      String[] args = new String[java.util.Arrays.stream(rule.slots).max().orElse(-1) + 1];
      for (int i = 0; i < rule.slots.length; i++) {
        String arg = matcher.group(i + 1);
        if (arg.matches("（[A-Z][A-Z0-9_]{0,47}）"))
          arg = " (" + arg.substring(1, arg.length() - 1) + ")";
        args[rule.slots[i]] = exact.getOrDefault(arg, arg);
      }
      return String.format(Locale.ENGLISH, rule.english, (Object[]) args);
    }
    return !chinese && HAN.matcher(source).find()
        ? context.getString(R.string.unexpected_error)
        : source;
  }
}
