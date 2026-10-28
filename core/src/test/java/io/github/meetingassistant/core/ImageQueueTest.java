package io.github.meetingassistant.core;

import static org.junit.Assert.*;

import java.util.concurrent.*;
import org.junit.Test;

public class ImageQueueTest {
  static class Held implements ImageUnderstandingProvider {
    volatile Listener listener;
    volatile boolean cancelled;
    final CountDownLatch entered = new CountDownLatch(1);

    public void recognize(byte[] pixels, Listener listener) {
      this.listener = listener;
      entered.countDown();
    }

    public void cancel() {
      cancelled = true;
    }
  }

  @Test
  public void boundsAcceptedFramesKeepsFifoAndDoesNotDropEvidence() throws Exception {
    SessionEngineTest.FakeFactory f = new SessionEngineTest.FakeFactory();
    ProviderConfig config = new ProviderConfig();
    config.textBaseUrl = "https://example.org/v1";
    ProviderConfig.Credentials keys = new ProviderConfig.Credentials("", "test");
    try (SessionEngine owner = new SessionEngine(f);
        ImageWorkQueue queue = new ImageWorkQueue()) {
      Held a = new Held(), b = new Held(), c = new Held();
      assertTrue(queue.submit(owner, new ImageEvidence(), new byte[] {1}, () -> a, config, keys));
      assertTrue(a.entered.await(3, TimeUnit.SECONDS));
      assertTrue(queue.submit(owner, new ImageEvidence(), new byte[] {2}, () -> b, config, keys));
      assertTrue(queue.submit(owner, new ImageEvidence(), new byte[] {3}, () -> c, config, keys));
      assertFalse(
          queue.submit(owner, new ImageEvidence(), new byte[] {4}, Held::new, config, keys));
      assertEquals(3, owner.imageCount());
      assertTrue(queue.full());
      assertNull(b.listener);
      a.listener.completed(ImageSessionTest.OCR);
      assertTrue(b.entered.await(3, TimeUnit.SECONDS));
      assertTrue(a.cancelled);
      b.listener.failed("图像识别超时，请重新抓拍");
      assertTrue(c.entered.await(3, TimeUnit.SECONDS));
      c.listener.completed(ImageSessionTest.OCR);
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (queue.busy() && System.nanoTime() < end) Thread.yield();
      assertFalse(queue.busy());
      assertEquals(3, owner.imageCount());
      assertEquals(ImageEvidence.State.ERROR, owner.images().get(1).state);
    }
  }

  @Test
  public void deletionCancelsInFlightOcrAndPreventsLateAnalysis() throws Exception {
    SessionEngineTest.FakeFactory f = new SessionEngineTest.FakeFactory();
    ProviderConfig config = new ProviderConfig();
    config.textBaseUrl = "https://example.org/v1";
    try (SessionEngine owner = new SessionEngine(f);
        ImageWorkQueue queue = new ImageWorkQueue()) {
      Held held = new Held();
      assertTrue(
          queue.submit(
              owner,
              new ImageEvidence(),
              new byte[] {1},
              () -> held,
              config,
              new ProviderConfig.Credentials("", "test")));
      assertTrue(held.entered.await(3, TimeUnit.SECONDS));
      owner.close();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!held.cancelled && System.nanoTime() < end) Thread.yield();
      assertTrue(held.cancelled);
      held.listener.completed(ImageSessionTest.OCR);
      assertEquals(0, f.text.calls);
      assertEquals("", owner.snapshot().answer);
    }
  }
}
