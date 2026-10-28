package io.github.meetingassistant.core;

/** Physical source preference; independent of the speech provider and meeting. */
public final class AudioInputSettings {
  public static final String BUILTIN = "builtin";
  public String source = BUILTIN;

  public void repair() {
    if (source == null || source.trim().isEmpty() || source.length() > 1024) source = BUILTIN;
  }
}
