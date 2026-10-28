package io.github.meetingassistant.android;

import android.app.Instrumentation;
import android.os.Bundle;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** On-device upgrade audit: only hashes are kept in private cache; no user payload is exported. */
final class TopicMigrationCheck {
  private static File file(Instrumentation test) {
    return new File(test.getTargetContext().getCacheDir(), "topic-migration-baseline.json");
  }

  static void baseline(Instrumentation test) throws Exception {
    JsonObject result = snapshot(test, false);
    try (Writer out =
        new OutputStreamWriter(new FileOutputStream(file(test)), StandardCharsets.UTF_8)) {
      new Gson().toJson(result, out);
    }
    report(test, "baseline_record_count", result.getAsJsonObject("records").size());
  }

  static void verify(Instrumentation test) throws Exception {
    JsonObject before;
    try (Reader in =
        new InputStreamReader(new FileInputStream(file(test)), StandardCharsets.UTF_8)) {
      before = JsonParser.parseReader(in).getAsJsonObject();
    }
    JsonObject after = snapshot(test, true);
    if (!before.equals(after))
      throw new AssertionError("Upgrade changed record hashes or selected ID; no content exported");
    report(test, "upgrade_preserved_record_count", after.getAsJsonObject("records").size());
    if (!file(test).delete()) throw new AssertionError("Cannot remove private migration hashes");
  }

  private static JsonObject snapshot(Instrumentation test, boolean upgraded) throws Exception {
    MeetingRepository repo = new MeetingRepository(test.getTargetContext());
    try {
      JsonObject result = new JsonObject(), records = new JsonObject();
      result.addProperty("selected", repo.selected());
      // Reflection-free serialization remains compatible with the previous target APK's record
      // class.
      for (Object record : repo.load()) {
        JsonObject json = new Gson().toJsonTree(record).getAsJsonObject();
        if (upgraded
            && (json.get("version").getAsInt() != 3
                || !"default".equals(json.get("collectionId").getAsString())))
          throw new AssertionError("Legacy record did not enter default collection");
        String id = json.get("id").getAsString();
        json.remove("version");
        json.remove("collectionId");
        byte[] digest =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical(json).getBytes(StandardCharsets.UTF_8));
        records.addProperty(
            id, android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP));
      }
      result.add("records", records);
      return result;
    } finally {
      repo.close();
    }
  }

  private static String canonical(JsonElement value) {
    if (value.isJsonObject()) {
      TreeMap<String, String> fields = new TreeMap<>();
      for (var e : value.getAsJsonObject().entrySet())
        fields.put(e.getKey(), canonical(e.getValue()));
      return fields.toString();
    }
    if (value.isJsonArray()) {
      List<String> fields = new ArrayList<>();
      for (JsonElement e : value.getAsJsonArray()) fields.add(canonical(e));
      return fields.toString();
    }
    return value.toString();
  }

  private static void report(Instrumentation test, String key, int count) {
    Bundle result = new Bundle();
    result.putInt(key, count);
    test.sendStatus(0, result);
  }
}
