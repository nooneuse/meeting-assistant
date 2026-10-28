package io.github.meetingassistant.core;

/** Public image configuration. Runtime enablement and credentials are deliberately absent. */
public final class ImageSettings {
  public String sourceId = "";
  public String baseUrl = "";
  public String model = "qwen3.5-ocr";
  public boolean qwen35Coordinates = true;
  public String cropSource = "";
  public int cropWidth, cropHeight, cropRotation;
  public float left = 0, top = 0, right = 1, bottom = 1;

  public void validate() {
    ProviderConfig.validateUrl(baseUrl, "https");
    if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("请填写图像模型名称");
  }
}
