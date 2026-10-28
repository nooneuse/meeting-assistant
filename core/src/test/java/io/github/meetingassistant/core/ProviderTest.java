package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.junit.Test;

public class ProviderTest {
  static String event(String content) {
    return "data: " + content + "\n\n";
  }

  static String chunk(String content) {
    return event("{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}");
  }

  @Test
  public void sseIgnoresReasoningUsageAndComments() throws Exception {
    StringBuilder result = new StringBuilder();
    String stream =
        ":keepalive\n\n"
            + event("{\"choices\":[{\"delta\":{\"reasoning_content\":\"internal\"}}]}")
            + chunk("你好")
            + event("{\"choices\":[],\"usage\":{}}")
            + event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
            + event("[DONE]");
    assertFalse(SseAnswers.read(new StringReader(stream), result::append));
    assertEquals("你好", result.toString());
  }

  @Test
  public void sseDetectsTruncationAndDisconnect() throws Exception {
    assertTrue(
        SseAnswers.read(
            new StringReader(
                chunk("半句")
                    + event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}")
                    + event("[DONE]")),
            s -> {}));
    assertThrows(
        java.io.IOException.class, () -> SseAnswers.read(new StringReader(chunk("未完成")), s -> {}));
    assertThrows(
        java.io.IOException.class,
        () -> SseAnswers.read(new StringReader(event("[DONE]")), s -> {}));
  }

  @Test
  public void httpStreamsSplitUtf8AndUsesConfiguredModel() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setChunkedBody(chunk("合并链表") + event("[DONE]"), 1));
      AtomicReference<String> error = new AtomicReference<>();
      StringBuilder answer = new StringBuilder();
      CountDownLatch done = new CountDownLatch(1);
      OpenAiTextProvider provider =
          new OpenAiTextProvider(
              NetworkClients.create(),
              new Request.Builder()
                  .url(server.url("/v1/chat/completions"))
                  .header("Authorization", "Bearer test-only"),
              "selected-model");
      provider.analyze(
          new Prompts().build("题目", UserSettings.initial().snapshot()),
          new TextProvider.Listener() {
            public void delta(String s) {
              answer.append(s);
            }

            public void completed(boolean t) {
              done.countDown();
            }

            public void failed(String e) {
              error.set(e);
              done.countDown();
            }
          });
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertNull(error.get());
      assertEquals("合并链表", answer.toString());
      RecordedRequest req = server.takeRequest();
      JsonObject body = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
      assertEquals("selected-model", body.get("model").getAsString());
      assertTrue(body.get("stream").getAsBoolean());
    }
  }

  @Test
  public void httpErrorsDoNotExposeServerBodyOrFollowRedirects() throws Exception {
    for (int code : new int[] {401, 429, 302})
      try (MockWebServer server = new MockWebServer()) {
        server.enqueue(
            new MockResponse()
                .setResponseCode(code)
                .setHeader("Location", "https://other.example/")
                .setBody("sensitive upstream text"));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        OpenAiTextProvider p =
            new OpenAiTextProvider(
                NetworkClients.create(), new Request.Builder().url(server.url("/chat")), "model");
        p.analyze(
            new com.google.gson.JsonArray(),
            new TextProvider.Listener() {
              public void delta(String s) {}

              public void completed(boolean t) {
                done.countDown();
              }

              public void failed(String e) {
                error.set(e);
                done.countDown();
              }
            });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertTrue(error.get().contains(Integer.toString(code)));
        assertFalse(error.get().contains("sensitive"));
        assertEquals(1, server.getRequestCount());
      }
  }

  @Test
  public void websocketProtocolWaitsForReadyAndUsesOneTaskId() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      CountDownLatch ready = new CountDownLatch(1), complete = new CountDownLatch(1);
      AtomicReference<String> task = new AtomicReference<>(), error = new AtomicReference<>();
      AtomicInteger frames = new AtomicInteger(), segments = new AtomicInteger();
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                      JsonObject h = root.getAsJsonObject("header");
                      String action = h.get("action").getAsString();
                      if (action.equals("run-task")) {
                        task.set(h.get("task_id").getAsString());
                        assertEquals(
                            16000,
                            root.getAsJsonObject("payload")
                                .getAsJsonObject("parameters")
                                .get("sample_rate")
                                .getAsInt());
                        ws.send(serverEvent(task.get(), "task-started", "{}"));
                      } else {
                        assertEquals(task.get(), h.get("task_id").getAsString());
                        assertEquals(1, frames.get());
                        ws.send(
                            serverEvent(
                                task.get(),
                                "result-generated",
                                "{\"output\":{\"sentence\":{\"heartbeat\":true,\"sentence_id\":0}}}"));
                        ws.send(
                            serverEvent(
                                task.get(),
                                "result-generated",
                                "{\"output\":{\"sentence\":{\"sentence_id\":1,\"text\":\"最终原文\",\"sentence_end\":true,\"begin_time\":0,\"end_time\":100}}}"));
                        ws.send(serverEvent(task.get(), "task-finished", "{}"));
                      }
                    }

                    @Override
                    public void onMessage(WebSocket ws, ByteString bytes) {
                      assertNotNull(task.get());
                      assertEquals(3200, bytes.size());
                      frames.incrementAndGet();
                    }
                  }));
      FunAsrProvider provider =
          new FunAsrProvider(
              NetworkClients.create(),
              new Request.Builder().url(server.url("/asr")).build(),
              "test-fun-asr");
      provider.start(
          new SpeechProvider.Listener() {
            public void ready() {
              ready.countDown();
            }

            public void segment(SpeechProvider.Segment s) {
              assertEquals("最终原文", s.text);
              assertTrue(s.isFinal);
              segments.incrementAndGet();
            }

            public void completed() {
              complete.countDown();
            }

            public void failed(String e) {
              error.set(e);
              complete.countDown();
            }
          });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      provider.sendAudio(new byte[3200]);
      provider.finish();
      provider.finish();
      assertTrue(complete.await(5, TimeUnit.SECONDS));
      assertNull(error.get());
      assertEquals(1, segments.get());
    }
  }

  static String serverEvent(String id, String event, String payload) {
    return "{\"header\":{\"task_id\":\""
        + id
        + "\",\"event\":\""
        + event
        + "\"},\"payload\":"
        + payload
        + "}";
  }
}
