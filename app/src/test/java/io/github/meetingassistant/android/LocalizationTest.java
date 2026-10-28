package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.AsrErrors;
import io.github.meetingassistant.core.NetworkErrors;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35, qualifiers = "en-rUS")
public class LocalizationTest {
  @Test
  public void localePolicyAcceptsSimplifiedChineseOnly() {
    for (String tag : new String[] {"zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-TW"})
      assertEquals(tag, "zh", UiLocale.choose(Locale.forLanguageTag(tag)).getLanguage());
    for (String tag :
        new String[] {
          "en-US", "fr-FR", "ja-JP", "ar", "zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN"
        }) assertEquals(tag, Locale.ENGLISH, UiLocale.choose(Locale.forLanguageTag(tag)));
  }

  @Test
  public void unsupportedFirstLanguageDoesNotFallBackToSecondaryChinese() {
    Context base = RuntimeEnvironment.getApplication();
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.setLocales(new LocaleList(Locale.FRENCH, Locale.SIMPLIFIED_CHINESE));
    Context ui =
        UiLocale.wrap(
            base.createConfigurationContext(config),
            new LocaleList(Locale.FRENCH, Locale.SIMPLIFIED_CHINESE));
    assertEquals("Settings", ui.getString(R.string.settings));
    assertEquals(Locale.ENGLISH, ui.getResources().getConfiguration().getLocales().get(0));
  }

  @Test
  public void englishResourcesCoverAllStringsWithoutChineseUiText() throws Exception {
    Context base = RuntimeEnvironment.getApplication();
    Context en = UiLocale.withLocale(base, Locale.ENGLISH);
    Context zh = UiLocale.withLocale(base, UiLocale.CHINESE);
    int count = 0;
    for (java.lang.reflect.Field field : R.string.class.getFields()) {
      String text = en.getString(field.getInt(null));
      assertFalse(field.getName(), text.matches("(?s).*[\\p{IsHan}].*"));
      assertFalse(field.getName(), text.isEmpty());
      assertFalse(field.getName(), zh.getString(field.getInt(null)).isEmpty());
      count++;
    }
    assertTrue(count > 150);
  }

  @Test
  public void oldSavedStatusesAndSanitizedProviderErrorsRenderInEnglish() {
    Context en = UiLocale.withLocale(RuntimeEnvironment.getApplication(), Locale.ENGLISH);
    LocalizedMessages text = new LocalizedMessages(en);
    assertEquals("Ready", text.notice("准备就绪"));
    assertEquals("Listening · Automatic analysis", text.notice("正在聆听 · 自动分析"));
    assertEquals(
        "Previous task interrupted · You can continue this topic",
        text.notice("上次任务已中断 · 可继续会议"));
    assertEquals("Speech connection failed (HTTP 403)", text.notice("语音连接失败（HTTP 403）"));
    assertEquals(
        "Enter a valid WSS URL without credentials, a query or a fragment.",
        text.notice("请填写有效的 WSS 地址（不含账号、查询串或片段）"));
    assertEquals(
        "Speech task timed out (TASK_TIMEOUT). Check audio transmission and network.",
        text.notice(AsrErrors.describe("TASK_TIMEOUT", "timeout")));
    assertEquals(
        "Could not resolve the Speech service address. Check the URL and network.",
        text.notice(NetworkErrors.describe("语音服务", new java.net.UnknownHostException())));
    assertEquals("English message", text.notice("English message"));
    assertEquals(en.getString(R.string.unexpected_error), text.notice("未知的内部错误"));
  }

  @Test
  public void firstInstallDefaultsUseUiLanguageButExistingProfilesAreNotRewritten() {
    Context base = RuntimeEnvironment.getApplication();
    SettingsRepository settings = new SettingsRepository(base, "locale-defaults");
    var original = settings.load();
    assertEquals("Algorithm discussion", original.find("algorithm").name);
    assertEquals("Finance meeting", original.find("finance").name);
    String algorithmPrompt = original.find("algorithm").prompt;
    RuntimeEnvironment.setQualifiers("zh-rCN");
    var switched = new SettingsRepository(base, "locale-defaults").load();
    assertEquals("Algorithm discussion", switched.find("algorithm").name);
    assertEquals("Finance meeting", switched.find("finance").name);
    assertEquals(algorithmPrompt, switched.find("algorithm").prompt);
    var chinese = new SettingsRepository(base, "locale-chinese-defaults").load();
    assertEquals("算法讨论", chinese.find("algorithm").name);
    assertEquals("金融会议", chinese.find("finance").name);
    RuntimeEnvironment.setQualifiers("en-rUS");
    assertEquals(
        "算法讨论",
        new SettingsRepository(base, "locale-chinese-defaults").load().find("algorithm").name);
    original.find("algorithm").name = "我的自定义名称";
    original.find("algorithm").prompt = "保留用户提示词";
    settings.save(original);
    Context zh = UiLocale.withLocale(base, UiLocale.CHINESE);
    var restored = new SettingsRepository(zh, "locale-defaults").load();
    assertEquals("我的自定义名称", restored.find("algorithm").name);
    assertEquals("保留用户提示词", restored.find("algorithm").prompt);
  }
}
