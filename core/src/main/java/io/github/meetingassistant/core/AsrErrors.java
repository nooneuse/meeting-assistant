package io.github.meetingassistant.core;

import java.util.Locale;

/** Classifies server error text without displaying or logging the original message. */
public final class AsrErrors {
  public static String describe(String code, String remoteMessage) {
    String suffix = code != null && code.matches("[A-Z][A-Z0-9_]{0,47}") ? "（" + code + "）" : "";
    String message = remoteMessage == null ? "" : remoteMessage.toLowerCase(Locale.ROOT);
    if (message.contains("timeout") || message.contains("timed out"))
      return "语音任务超时" + suffix + "，请检查音频发送与网络";
    if (message.contains("no speech")
        || message.contains("no valid audio")
        || message.contains("empty audio")) return "未收到有效语音" + suffix + "，请检查麦克风和音量";
    if (message.contains("audio")
        || message.contains("sample_rate")
        || message.contains("sample rate")
        || message.contains("format")
        || message.contains("decode")) return "语音音频处理失败" + suffix + "，请检查音频格式与发送状态";
    if (message.contains("quota")
        || message.contains("balance")
        || message.contains("auth")
        || message.contains("permission")) return "语音模型权限或额度不可用" + suffix;
    return "语音任务失败" + suffix + "，请检查模型权限、余额或网络";
  }

  private AsrErrors() {}
}
