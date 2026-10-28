package io.github.meetingassistant.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class FunAsrProvider implements SpeechProvider {
  private final OkHttpClient client;
  private final Request request;
  private final String model, taskId = UUID.randomUUID().toString();
  private final AtomicBoolean done = new AtomicBoolean();
  private volatile WebSocket socket;
  private volatile boolean ready, finishing;
  private Listener listener;

  public FunAsrProvider(
      OkHttpClient client, ProviderConfig config, ProviderConfig.Credentials keys) {
    this(client, validatedRequest(config.asrUrl, keys.asrKey), config.asrModel);
  }

  private static Request validatedRequest(String url, String key) {
    ProviderConfig.validateUrl(url, "wss");
    return new Request.Builder().url(url).header("Authorization", "Bearer " + key).build();
  }

  // Transport injection for local protocol tests; production uses the TLS-validating constructor.
  FunAsrProvider(OkHttpClient client, Request request, String model) {
    this.client = client;
    this.request = request;
    this.model = model;
  }

  @Override
  public void start(Listener listener) {
    this.listener = listener;
    socket =
        client.newWebSocket(
            request,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket ws, Response response) {
                if (done.get()) {
                  ws.cancel();
                  return;
                }
                socket = ws;
                JsonObject payload = new JsonObject();
                payload.addProperty("task_group", "audio");
                payload.addProperty("task", "asr");
                payload.addProperty("function", "recognition");
                payload.addProperty("model", model);
                JsonObject parameters = new JsonObject();
                parameters.addProperty("format", "pcm");
                parameters.addProperty("sample_rate", 16000);
                parameters.addProperty("heartbeat", true);
                payload.add("parameters", parameters);
                payload.add("input", new JsonObject());
                if (!ws.send(command("run-task", payload))) fail("语音启动指令发送失败");
              }

              @Override
              public void onMessage(WebSocket ws, String value) {
                if (done.get()) return;
                try {
                  JsonObject root = JsonParser.parseString(value).getAsJsonObject();
                  JsonObject header = root.getAsJsonObject("header");
                  if (!taskId.equals(header.get("task_id").getAsString())) return;
                  switch (header.get("event").getAsString()) {
                    case "task-started":
                      if (!ready) {
                        ready = true;
                        listener.ready();
                      }
                      break;
                    case "result-generated":
                      JsonObject sentence =
                          root.getAsJsonObject("payload")
                              .getAsJsonObject("output")
                              .getAsJsonObject("sentence");
                      if (sentence == null || bool(sentence, "heartbeat")) break;
                      int id = sentence.get("sentence_id").getAsInt();
                      if (id <= 0) break;
                      listener.segment(
                          new Segment(
                              id,
                              sentence.get("text").getAsString(),
                              bool(sentence, "sentence_end"),
                              time(sentence, "begin_time"),
                              time(sentence, "end_time")));
                      break;
                    case "task-finished":
                      if (done.compareAndSet(false, true)) {
                        ws.close(1000, "complete");
                        listener.completed();
                      }
                      break;
                    case "task-failed":
                      fail(
                          AsrErrors.describe(
                              header.has("error_code")
                                  ? header.get("error_code").getAsString()
                                  : "",
                              header.has("error_message")
                                  ? header.get("error_message").getAsString()
                                  : ""));
                      break;
                    default:
                      break;
                  }
                } catch (RuntimeException e) {
                  fail("语音服务返回了无法解析的数据");
                }
              }

              @Override
              public void onFailure(WebSocket ws, Throwable t, Response response) {
                fail(
                    response == null
                        ? NetworkErrors.describe("语音服务", t)
                        : "语音连接失败（HTTP " + response.code() + "）");
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null);
                if (!done.get()) fail("语音服务提前关闭，未完成定稿");
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                if (!done.get()) fail("语音连接已关闭，未完成定稿");
              }
            });
  }

  private static boolean bool(JsonObject obj, String key) {
    return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
  }

  private static long time(JsonObject obj, String key) {
    return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : -1;
  }

  private String command(String action, JsonObject payload) {
    JsonObject h = new JsonObject();
    h.addProperty("action", action);
    h.addProperty("task_id", taskId);
    h.addProperty("streaming", "duplex");
    JsonObject root = new JsonObject();
    root.add("header", h);
    root.add("payload", payload);
    return root.toString();
  }

  @Override
  public void sendAudio(byte[] pcm) {
    WebSocket ws = socket;
    if (done.get()) return;
    if (!ready || finishing || ws == null) {
      fail("语音任务尚未准备好接收音频");
      return;
    }
    if (ws.queueSize() > 1024 * 1024 || !ws.send(ByteString.of(pcm))) fail("网络发送积压，已停止聆听，请检查网络");
  }

  @Override
  public void finish() {
    if (done.get() || finishing) return;
    finishing = true;
    JsonObject payload = new JsonObject();
    payload.add("input", new JsonObject());
    if (socket == null || !socket.send(command("finish-task", payload))) fail("语音结束指令发送失败");
  }

  private void fail(String message) {
    if (done.compareAndSet(false, true)) {
      if (socket != null) socket.cancel();
      listener.failed(message);
    }
  }

  @Override
  public void cancel() {
    done.set(true);
    WebSocket ws = socket;
    if (ws != null) ws.cancel();
  }
}
