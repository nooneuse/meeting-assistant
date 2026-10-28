package io.github.meetingassistant.core;

/** Provider-independent, serializable limits. Zero modelWindow means unknown. */
public final class ContextSettings {
  public int inputBudget = 12000, modelWindow, outputBudget = 4096;
  public int organizePercent = 75, slowSeconds = 10, cooldownMinutes = 10;

  public int budget() {
    return modelWindow == 0
        ? inputBudget
        : Math.min(inputBudget, modelWindow - outputBudget - 1024);
  }

  public void validate() {
    if (inputBudget < 2048
        || inputBudget > 128000
        || modelWindow < 0
        || modelWindow > 2000000
        || outputBudget < 256
        || outputBudget > 32768
        || organizePercent < 50
        || organizePercent > 90
        || slowSeconds < 1
        || slowSeconds > 120
        || cooldownMinutes < 1
        || cooldownMinutes > 240
        || budget() < 2048) throw new IllegalArgumentException("上下文设置超出范围，或模型窗口不足以预留输出空间");
  }

  public ContextSettings copy() {
    ContextSettings c = new ContextSettings();
    c.inputBudget = inputBudget;
    c.modelWindow = modelWindow;
    c.outputBudget = outputBudget;
    c.organizePercent = organizePercent;
    c.slowSeconds = slowSeconds;
    c.cooldownMinutes = cooldownMinutes;
    return c;
  }
}
