package io.github.meetingassistant.android;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

/** Only the first configured language decides the UI; unsupported languages use English. */
final class UiLocale {
  static final Locale CHINESE = Locale.forLanguageTag("zh-Hans");

  static Locale choose(Locale primary) {
    if (!primary.getLanguage().equals("zh")) return Locale.ENGLISH;
    if (!primary.getScript().isEmpty())
      return primary.getScript().equals("Hans") ? CHINESE : Locale.ENGLISH;
    return switch (primary.getCountry()) {
      case "TW", "HK", "MO" -> Locale.ENGLISH;
      default -> CHINESE;
    };
  }

  static Context wrap(Context base) {
    LocaleList languages;
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      android.app.LocaleManager manager = base.getSystemService(android.app.LocaleManager.class);
      languages = manager.getSystemLocales();
    } else languages = android.content.res.Resources.getSystem().getConfiguration().getLocales();
    return wrap(base, languages);
  }

  static Context wrap(Context base, LocaleList languages) {
    return withLocale(base, choose(languages.isEmpty() ? Locale.ENGLISH : languages.get(0)));
  }

  static Context withLocale(Context base, Locale locale) {
    Configuration config = new Configuration();
    config.setLocales(new LocaleList(locale));
    config.setLayoutDirection(locale);
    return base.createConfigurationContext(config);
  }

  private UiLocale() {}
}
