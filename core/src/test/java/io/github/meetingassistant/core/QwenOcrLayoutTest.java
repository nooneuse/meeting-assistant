package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.*;
import org.junit.Test;

public class QwenOcrLayoutTest {
  @Test
  public void convertsActualQwenRotatedLineFormatAndKeepsRoleUnknown() {
    String raw =
        "```json\n"
            + "[{\"rotate_rect\":[356,132,47,370,90],\"text\":\"Merge Two Sorted Linked"
            + " Lists\"},{\"rotate_rect\":[820,922,31,75,90],\"text\":\"Run code\"}]\n"
            + "```";
    JsonObject layout = JsonParser.parseString(QwenOcrLayout.convert(raw)).getAsJsonObject();
    JsonArray blocks = layout.getAsJsonArray("blocks");
    assertEquals(2, blocks.size());
    JsonArray title = blocks.get(0).getAsJsonObject().getAsJsonArray("box");
    assertEquals(.171, title.get(0).getAsDouble(), .00001);
    assertEquals(.1085, title.get(1).getAsDouble(), .00001);
    JsonArray button = blocks.get(1).getAsJsonObject().getAsJsonArray("box");
    assertEquals(.7825, button.get(0).getAsDouble(), .00001);
    assertEquals(.9065, button.get(1).getAsDouble(), .00001);
    assertTrue(
        blocks.get(1).getAsJsonObject().get("role_inference").getAsString().startsWith("Unknown"));
    assertTrue(layout.getAsJsonArray("uncertainties").size() > 0);
  }

  @Test
  public void neverGuessesUnitsOrAcceptsPlainOcrText() {
    for (String value :
        new String[] {
          "plain text",
          "[]",
          "[{\"rotate_rect\":[1400,200,40,200,90],\"text\":\"x\"}]",
          "[{\"rotate_rect\":[200,200,0,30,0],\"text\":\"x\"}]"
        }) assertThrows(IllegalArgumentException.class, () -> QwenOcrLayout.convert(value));
  }

  @Test
  public void clipsBoundaryAndHandlesNonRightAngle() {
    JsonArray box =
        JsonParser.parseString(
                QwenOcrLayout.convert("[{\"rotate_rect\":[5,5,40,20,45],\"text\":\"x\"}]"))
            .getAsJsonObject()
            .getAsJsonArray("blocks")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("box");
    assertEquals(0, box.get(0).getAsDouble(), 0);
    assertEquals(0, box.get(1).getAsDouble(), 0);
    assertTrue(box.get(2).getAsDouble() > 0);
    assertTrue(box.get(3).getAsDouble() > 0);
  }
}
