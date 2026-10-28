package io.github.meetingassistant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;

/** SSE event parsing independent of network chunk boundaries. Never emits reasoning_content. */
public final class SseAnswers {
  public static boolean read(Reader reader, Consumer<String> delta) throws IOException {
    BufferedReader in = new BufferedReader(reader);
    StringBuilder data = new StringBuilder();
    boolean truncated = false, hasAnswer = false, done = false;
    String line;
    int total = 0;
    while ((line = in.readLine()) != null) {
      total += line.length();
      if (total > 2_000_000) throw new IOException("stream too large");
      if (line.startsWith("\uFEFF")) line = line.substring(1);
      if (!line.isEmpty()) {
        if (line.startsWith("data:")) {
          if (data.length() > 0) data.append('\n');
          String part = line.substring(5);
          data.append(part.startsWith(" ") ? part.substring(1) : part);
        }
        continue;
      }
      if (data.length() == 0) continue;
      String value = data.toString();
      data.setLength(0);
      if (value.trim().equals("[DONE]")) {
        done = true;
        break;
      }
      try {
        JsonObject root = JsonParser.parseString(value).getAsJsonObject();
        if (root.has("error")) throw new IOException("provider error");
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) continue;
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
          String reason = choice.get("finish_reason").getAsString();
          if (reason.equals("length")) truncated = true;
          else if (!reason.equals("stop")) throw new IOException("unsupported completion");
        }
        JsonObject d = choice.getAsJsonObject("delta");
        if (d != null && d.has("content") && !d.get("content").isJsonNull()) {
          String text = d.get("content").getAsString();
          if (!text.isEmpty()) {
            hasAnswer = true;
            delta.accept(text);
          }
        }
      } catch (RuntimeException e) {
        throw new IOException("invalid stream");
      }
    }
    if (!done || !hasAnswer) throw new IOException("incomplete stream");
    return truncated;
  }

  private SseAnswers() {}
}
