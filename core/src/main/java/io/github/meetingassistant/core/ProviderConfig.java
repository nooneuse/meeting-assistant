package io.github.meetingassistant.core;

import java.net.URI;

/** Public configuration only. Credentials must never be serialized with this object. */
public final class ProviderConfig {
  public int outputTokens = 4096;
  public String asrUrl = "";
  public String asrModel = "fun-asr-realtime-2026-02-28";
  public String textBaseUrl = "";
  public String textModel = "deepseek-v4.1-flash-1017";

  public ProviderConfig copy() {
    ProviderConfig c = new ProviderConfig();
    c.outputTokens = outputTokens;
    c.asrUrl = asrUrl;
    c.asrModel = asrModel;
    c.textBaseUrl = textBaseUrl;
    c.textModel = textModel;
    return c;
  }

  public void validate() {
    validateUrl(asrUrl, "wss");
    validateUrl(textBaseUrl, "https");
    if (asrModel == null
        || asrModel.trim().isEmpty()
        || textModel == null
        || textModel.trim().isEmpty()) throw new IllegalArgumentException("请填写两个模型名称");
  }

  public void validateText() {
    validateUrl(textBaseUrl, "https");
    if (textModel == null || textModel.trim().isEmpty())
      throw new IllegalArgumentException("请填写文字模型名称");
  }

  public static void validateUrl(String value, String scheme) {
    try {
      URI uri = URI.create(value);
      if (!scheme.equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("请填写有效的 " + scheme.toUpperCase() + " 地址（不含账号、查询串或片段）");
    }
  }

  public static final class Credentials {
    public final String asrKey, textKey;

    public Credentials(String asrKey, String textKey) {
      this.asrKey = asrKey == null ? "" : asrKey.trim();
      this.textKey = textKey == null ? "" : textKey.trim();
    }

    public void validate() {
      if (asrKey.isEmpty() || textKey.isEmpty())
        throw new IllegalArgumentException("请在设置中填写两个 API Key");
      if (asrKey.contains("\n")
          || textKey.contains("\n")
          || asrKey.contains("\r")
          || textKey.contains("\r")) throw new IllegalArgumentException("API Key 不能包含换行");
    }

    public void validateText() {
      if (textKey.isEmpty() || textKey.contains("\n") || textKey.contains("\r"))
        throw new IllegalArgumentException("请在设置中填写有效的文字 API Key");
    }

    @Override
    public String toString() {
      return "Credentials[redacted]";
    }
  }
}
