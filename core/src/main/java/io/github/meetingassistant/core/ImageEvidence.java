package io.github.meetingassistant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/** Persisted OCR evidence, never pixels, URLs, credentials or provider response bodies. */
public final class ImageEvidence {
  private static final com.google.gson.Gson JSON = new com.google.gson.Gson();

  public enum State {
    QUEUED,
    RECOGNIZING,
    READY,
    ERROR,
    INTERRUPTED,
    CANCELLED
  }

  public String id = UUID.randomUUID().toString();
  public String source = "", error = "", structure = "", transcriptAnchor = "";
  public long capturedAt = System.currentTimeMillis(), audioEndMs;
  public int width, height, originalWidth, originalHeight, rotation;
  public float cropLeft, cropTop, cropRight = 1, cropBottom = 1;
  public boolean included = true;
  public State state = State.QUEUED;
  public UserSettings.Profile preference = new UserSettings.Profile("", "", "");

  public ImageEvidence copy() {
    return JSON.fromJson(JSON.toJson(this), ImageEvidence.class);
  }

  /** Accept only the documented schema; malformed or incomplete OCR is never silently flattened. */
  public static String validateStructure(String value) {
    if (value == null || value.length() > 48000) throw new IllegalArgumentException("图像识别结果无效，请重拍");
    String s = value.trim();
    if (s.startsWith("```")) {
      int line = s.indexOf('\n'), end = s.lastIndexOf("```");
      if (line < 0 || end <= line) throw new IllegalArgumentException("图像识别结果无效，请重拍");
      s = s.substring(line + 1, end).trim();
    }
    try {
      JsonObject root = JsonParser.parseString(s).getAsJsonObject();
      if (!root.has("blocks")
          || !root.get("blocks").isJsonArray()
          || !root.has("uncertainties")
          || !root.get("uncertainties").isJsonArray()) throw new IllegalArgumentException();
      JsonArray blocks = root.getAsJsonArray("blocks");
      if (blocks.size() == 0 || blocks.size() > 300) throw new IllegalArgumentException();
      java.util.Set<String> ids = new java.util.HashSet<>();
      boolean content = false;
      for (var el : blocks) {
        JsonObject b = el.getAsJsonObject();
        String id = b.get("id").getAsString();
        if (id.trim().isEmpty() || id.length() > 32 || !ids.add(id))
          throw new IllegalArgumentException();
        String text = b.get("text").getAsString();
        if (text.length() > 24000) throw new IllegalArgumentException();
        content |= !text.trim().isEmpty();
        JsonArray box = b.getAsJsonArray("box");
        if (box.size() != 4) throw new IllegalArgumentException();
        double[] xy = new double[4];
        for (int i = 0; i < 4; i++) {
          xy[i] = box.get(i).getAsDouble();
          if (!Double.isFinite(xy[i]) || xy[i] < 0 || xy[i] > 1)
            throw new IllegalArgumentException();
        }
        if (xy[0] >= xy[2] || xy[1] >= xy[3]) throw new IllegalArgumentException();
        b.get("role_inference").getAsString();
        if (b.get("order").getAsInt() < 0) throw new IllegalArgumentException();
      }
      if (!content) throw new IllegalArgumentException();
      // An explicit allowlist prevents arbitrary extra provider fields entering archives/prompts.
      JsonObject clean = new JsonObject();
      JsonArray safe = new JsonArray();
      for (var el : blocks) {
        JsonObject b = el.getAsJsonObject(), out = new JsonObject();
        for (String k : new String[] {"id", "text", "box", "order", "role_inference"})
          out.add(k, b.get(k));
        safe.add(out);
      }
      clean.add("blocks", safe);
      JsonArray uncertain = root.getAsJsonArray("uncertainties");
      if (uncertain.size() > 64) throw new IllegalArgumentException();
      for (var item : uncertain)
        if (!item.isJsonPrimitive()
            || !item.getAsJsonPrimitive().isString()
            || item.getAsString().length() > 1024) throw new IllegalArgumentException();
      clean.add("uncertainties", uncertain);
      return clean.toString();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("图像识别结果无效，请重拍");
    }
  }

  public String text() {
    if (structure.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (var block : JsonParser.parseString(structure).getAsJsonObject().getAsJsonArray("blocks")) {
      if (out.length() > 0) out.append('\n');
      out.append(block.getAsJsonObject().get("text").getAsString());
    }
    return out.toString();
  }
}
