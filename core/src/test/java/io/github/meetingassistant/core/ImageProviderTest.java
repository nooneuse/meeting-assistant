package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Request;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class ImageProviderTest {
  @Test
  public void encodesLocalFrameWithoutPublicUploadAndRequestsLayout() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      JsonObject message = new JsonObject();
      message.addProperty("content", ImageSessionTest.OCR);
      JsonObject choice = new JsonObject();
      choice.addProperty("finish_reason", "stop");
      choice.add("message", message);
      JsonArray choices = new JsonArray();
      choices.add(choice);
      JsonObject response = new JsonObject();
      response.add("choices", choices);
      server.enqueue(
          new MockResponse().setBody(response.toString()).setChunkedBody(response.toString(), 3));
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<String> result = new AtomicReference<>(), error = new AtomicReference<>();
      OpenAiImageProvider provider =
          new OpenAiImageProvider(
              NetworkClients.create(),
              new Request.Builder().url(server.url("/v1/chat/completions")),
              "qwen3.5-ocr");
      provider.recognize(
          new byte[] {1, 2, 3},
          new ImageUnderstandingProvider.Listener() {
            public void completed(String s) {
              result.set(s);
              done.countDown();
            }

            public void failed(String e) {
              error.set(e);
              done.countDown();
            }
          });
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertNull(error.get());
      assertNotNull(result.get());
      JsonObject payload =
          JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
      assertEquals("qwen3.5-ocr", payload.get("model").getAsString());
      assertFalse(payload.get("stream").getAsBoolean());
      String encoded =
          payload
              .getAsJsonArray("messages")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("content")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("image_url")
              .get("url")
              .getAsString();
      assertEquals("data:image/jpeg;base64,AQID", encoded);
      assertTrue(payload.toString().contains("role_inference"));
    }
  }

  @Test
  public void responseErrorsNeverExposeRemoteBodiesOrFollowRedirects() throws Exception {
    for (int code : new int[] {401, 429, 302})
      try (MockWebServer server = new MockWebServer()) {
        server.enqueue(
            new MockResponse()
                .setResponseCode(code)
                .setHeader("Location", "https://other.example/")
                .setBody("private remote body"));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        OpenAiImageProvider provider =
            new OpenAiImageProvider(
                NetworkClients.create(), new Request.Builder().url(server.url("/chat")), "model");
        provider.recognize(
            new byte[] {1},
            new ImageUnderstandingProvider.Listener() {
              public void completed(String s) {
                done.countDown();
              }

              public void failed(String e) {
                error.set(e);
                done.countDown();
              }
            });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertFalse(error.get().contains("private"));
        assertEquals(1, server.getRequestCount());
      }
  }

  @Test
  public void truncatedOrPlainTextOutputIsNotAcceptedAsStructuredEvidence() throws Exception {
    for (String choice :
        new String[] {
          "{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}",
          "{\"finish_reason\":\"stop\",\"message\":{\"content\":\"plain text\"}}"
        })
      try (MockWebServer server = new MockWebServer()) {
        server.enqueue(new MockResponse().setBody("{\"choices\":[" + choice + "]}"));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        new OpenAiImageProvider(
                NetworkClients.create(), new Request.Builder().url(server.url("/chat")), "model")
            .recognize(
                new byte[] {1},
                new ImageUnderstandingProvider.Listener() {
                  public void completed(String s) {
                    done.countDown();
                  }

                  public void failed(String e) {
                    error.set(e);
                    done.countDown();
                  }
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(error.get());
      }
  }
}
