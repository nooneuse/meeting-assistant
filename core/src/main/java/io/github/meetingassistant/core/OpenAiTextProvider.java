package io.github.meetingassistant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Chat Completions adapter. Works with Alibaba-hosted DeepSeek without an OpenAI SDK. */
public final class OpenAiTextProvider implements TextProvider {
  private final OkHttpClient client;
  private final String model;
  private int outputTokens = 4096;
  private final Request.Builder request;
  private final AtomicBoolean canceled = new AtomicBoolean();
  private volatile Call call;

  public OpenAiTextProvider(
      OkHttpClient client, ProviderConfig config, ProviderConfig.Credentials keys) {
    this(client, validated(config.textBaseUrl, keys.textKey), config.textModel);
    outputTokens = Math.max(256, Math.min(32768, config.outputTokens));
  }

  private static Request.Builder validated(String base, String key) {
    ProviderConfig.validateUrl(base, "https");
    return new Request.Builder()
        .url(base.replaceAll("/+$", "") + "/chat/completions")
        .header("Authorization", "Bearer " + key);
  }

  OpenAiTextProvider(OkHttpClient client, Request.Builder request, String model) {
    this.client = client;
    this.request = request;
    this.model = model;
  }

  @Override
  public void analyze(JsonArray messages, Listener listener) {
    JsonObject payload = new JsonObject();
    payload.addProperty("model", model);
    payload.add("messages", messages);
    payload.addProperty("stream", true);
    payload.addProperty("max_tokens", outputTokens);
    Request req =
        request
            .header("Accept", "text/event-stream")
            .post(
                RequestBody.create(
                    payload.toString(), MediaType.get("application/json; charset=utf-8")))
            .build();
    call = client.newCall(req);
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            if (!canceled.get()) listener.failed(NetworkErrors.describe("文字服务", e));
          }

          @Override
          public void onResponse(Call call, Response response) {
            try (Response r = response) {
              if (canceled.get()) return;
              if (!r.isSuccessful()) {
                if (r.body() != null) {
                  String error = r.peekBody(8192).string();
                  try {
                    com.google.gson.JsonObject root =
                        com.google.gson.JsonParser.parseString(error).getAsJsonObject();
                    String code =
                        root.has("error")
                            ? root.getAsJsonObject("error").get("code").getAsString()
                            : "";
                    if (code.equals("context_length_exceeded")
                        || code.equals("max_context_length_exceeded")) {
                      listener.contextLimit();
                      return;
                    }
                  } catch (RuntimeException ignored) {
                  }
                }
                listener.failed("文字服务请求失败（HTTP " + r.code() + "），请检查权限、额度与模型");
                return;
              }
              ResponseBody body = r.body();
              if (body == null) throw new IOException("empty body");
              boolean truncated =
                  SseAnswers.read(
                      body.charStream(),
                      s -> {
                        if (!canceled.get()) listener.delta(s);
                      });
              if (!canceled.get()) listener.completed(truncated);
            } catch (IOException | RuntimeException e) {
              if (!canceled.get()) listener.failed("文字响应未完整结束，已保留收到的内容，可重试");
            }
          }
        });
  }

  @Override
  public void cancel() {
    canceled.set(true);
    Call c = call;
    if (c != null) c.cancel();
  }
}
