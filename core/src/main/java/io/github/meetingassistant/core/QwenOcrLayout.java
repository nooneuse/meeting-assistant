package io.github.meetingassistant.core;

import com.google.gson.*;

/** Explicit Qwen3.5 adapter. Never guesses the unit system of an arbitrary provider. */
public final class QwenOcrLayout {
  private QwenOcrLayout() {}

  public static String convert(String response) {
    try {
      String raw = response.trim();
      if (raw.startsWith("```"))
        raw = raw.substring(raw.indexOf('\n') + 1, raw.lastIndexOf("```")).trim();
      JsonArray lines = JsonParser.parseString(raw).getAsJsonArray(), blocks = new JsonArray();
      if (lines.size() == 0 || lines.size() > 300) throw new IllegalArgumentException();
      int n = 0;
      for (JsonElement element : lines) {
        JsonObject line = element.getAsJsonObject();
        JsonArray rect = line.getAsJsonArray("rotate_rect");
        if (rect.size() != 5) throw new IllegalArgumentException();
        double cx = rect.get(0).getAsDouble(),
            cy = rect.get(1).getAsDouble(),
            w = rect.get(2).getAsDouble(),
            h = rect.get(3).getAsDouble(),
            angle = rect.get(4).getAsDouble();
        for (double value : new double[] {cx, cy, w, h, angle})
          if (!Double.isFinite(value)) throw new IllegalArgumentException();
        if (cx < 0
            || cx > 1000
            || cy < 0
            || cy > 1000
            || w <= 0
            || h <= 0
            || w > 1500
            || h > 1500
            || Math.abs(angle) > 90) throw new IllegalArgumentException();
        double radians = Math.toRadians(angle),
            bw = Math.abs(w * Math.cos(radians)) + Math.abs(h * Math.sin(radians)),
            bh = Math.abs(w * Math.sin(radians)) + Math.abs(h * Math.cos(radians));
        JsonArray box = new JsonArray();
        box.add(Math.max(0, (cx - bw / 2) / 1000));
        box.add(Math.max(0, (cy - bh / 2) / 1000));
        box.add(Math.min(1, (cx + bw / 2) / 1000));
        box.add(Math.min(1, (cy + bh / 2) / 1000));
        JsonObject block = new JsonObject();
        block.addProperty("id", "B" + (n + 1));
        block.addProperty("text", line.get("text").getAsString());
        block.add("box", box);
        block.addProperty("order", n++);
        block.addProperty(
            "role_inference",
            "Unknown; infer UI role from text and geometry, not a detected control");
        blocks.add(block);
      }
      JsonObject layout = new JsonObject();
      layout.add("blocks", blocks);
      JsonArray uncertainties = new JsonArray();
      uncertainties.add(
          "Qwen3.5-OCR text positions use the explicitly requested 0..1000 coordinate grid. UI"
              + " roles and non-text controls are not detected; infer roles cautiously from text"
              + " and layout. Code indentation and table associations may require verification.");
      layout.add("uncertainties", uncertainties);
      return ImageEvidence.validateStructure(layout.toString());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("图像识别结果无效，请重拍");
    }
  }
}
