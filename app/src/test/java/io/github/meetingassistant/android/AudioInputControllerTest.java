package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35)
public class AudioInputControllerTest {
  static final AudioInputController.Device BUILTIN =
      new AudioInputController.Device(
          "builtin", "Built-in", AudioInputController.Kind.BUILTIN, 1, -1, false);
  static final AudioInputController.Device USB =
      new AudioInputController.Device(
          "device:usb", "Capture audio", AudioInputController.Kind.USB, 2, -1, false);
  static final AudioInputController.Device BT =
      new AudioInputController.Device(
          "bt:headset", "Headset", AudioInputController.Kind.BLUETOOTH, 3, 4, false);

  static class Hardware implements AudioInputController.Devices {
    final List<AudioInputController.Device> list =
        new CopyOnWriteArrayList<>(List.of(BUILTIN, USB, BT));
    final AtomicInteger opens = new AtomicInteger(),
        closes = new AtomicInteger(),
        live = new AtomicInteger(),
        maximum = new AtomicInteger();
    volatile Capture latest;
    volatile boolean blockOpen, refuse;
    volatile Runnable changed;

    public List<AudioInputController.Device> list() {
      return new ArrayList<>(list);
    }

    public void observe(Runnable r) {
      changed = r;
    }

    public AudioInputController.Connection open(
        AudioInputController.Device d, BooleanSupplier canceled) {
      opens.incrementAndGet();
      while (blockOpen && !canceled.getAsBoolean()) pause();
      if (canceled.getAsBoolean())
        throw new AudioInputController.InputFailure(R.string.audio_canceled);
      if (refuse) throw new AudioInputController.InputFailure(R.string.audio_route_failed);
      int n = live.incrementAndGet();
      maximum.accumulateAndGet(n, Math::max);
      latest = new Capture(this, d);
      return latest;
    }
  }

  static class Capture implements AudioInputController.Connection {
    final Hardware hardware;
    final AudioInputController.Device selected;
    volatile AudioInputController.Device route;
    volatile boolean interrupted, closed;

    Capture(Hardware h, AudioInputController.Device d) {
      hardware = h;
      selected = route = d;
    }

    public byte[] read() {
      pause();
      return interrupted ? new byte[0] : new byte[3200];
    }

    public AudioInputController.Device actual() {
      return route;
    }

    public void interrupt() {
      interrupted = true;
    }

    public void close() {
      assertFalse("double release", closed);
      closed = true;
      hardware.closes.incrementAndGet();
      hardware.live.decrementAndGet();
    }
  }

  static class Sink implements AudioSource.Listener {
    AtomicInteger frames = new AtomicInteger(), notes = new AtomicInteger();
    volatile String failed = "";
    volatile CountDownLatch block;

    public void audio(byte[] pcm) {
      frames.incrementAndGet();
      if (block != null)
        try {
          block.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
    }

    public void failed(String s) {
      failed = s;
    }

    public void discontinuity(String s) {
      notes.incrementAndGet();
    }
  }

  Hardware hardware;
  AudioInputController controller;
  AudioSource source;
  Sink sink;

  @Before
  public void setup() {
    hardware = new Hardware();
    controller =
        new AudioInputController(
            RuntimeEnvironment.getApplication(), new AudioInputSettings(), hardware);
    sink = new Sink();
  }

  @After
  public void close() {
    if (sink.block != null) sink.block.countDown();
    if (source != null) source.cancel();
    controller.stopTest();
    await(() -> !controller.active());
  }

  static void pause() {
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static void await(BooleanSupplier done) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!done.getAsBoolean() && System.nanoTime() < end) pause();
    assertTrue("timed out", done.getAsBoolean());
  }

  void start() {
    source = controller.source();
    source.start(sink);
    await(() -> sink.frames.get() > 1);
  }

  @Test
  public void selectingSourceDoesNotOpenItAndSourceIsSharedAcrossMeetings() {
    controller.select(USB.key());
    assertEquals(0, hardware.opens.get());
    start();
    assertEquals(USB, hardware.latest.selected);
  }

  @Test
  public void liveSwitchReleasesBeforeOpeningAndKeepsSingleDeliveryStream() {
    start();
    Capture first = hardware.latest;
    int before = sink.frames.get();
    controller.select(USB.key());
    await(() -> hardware.opens.get() == 2 && sink.frames.get() > before + 2);
    assertTrue(first.closed);
    assertEquals(1, hardware.maximum.get());
    assertEquals(1, sink.notes.get());
    assertEquals(USB, hardware.latest.selected);
    assertTrue(sink.failed.isEmpty());
  }

  @Test
  public void stopDrainsPendingFramesBeforeItsCallbackAndReleasesDevice() {
    start();
    AtomicBoolean drained = new AtomicBoolean();
    source.stop(
        () -> {
          assertEquals(0, hardware.live.get());
          drained.set(true);
        });
    await(drained::get);
    int n = sink.frames.get();
    pause();
    assertEquals(n, sink.frames.get());
    assertFalse(controller.active());
  }

  @Test
  public void actualRouteMismatchStopsInsteadOfForwardingFallbackAudio() {
    controller.select(USB.key());
    start();
    hardware.latest.route = BUILTIN;
    await(() -> !sink.failed.isEmpty());
    assertEquals(AudioInputController.State.ERROR, controller.state());
    assertEquals(1, hardware.opens.get());
    assertEquals(USB.key(), controller.selected());
    assertEquals(0, hardware.live.get());
  }

  @Test
  public void reconnectDoesNotResumeRecording() {
    start();
    hardware.latest.route = USB;
    await(() -> !controller.active());
    int n = hardware.opens.get();
    hardware.changed.run();
    pause();
    assertEquals(n, hardware.opens.get());
    assertFalse(controller.active());
  }

  @Test
  public void missingPreferredDeviceNeverFallsBack() {
    controller.select(USB.key());
    hardware.list.remove(USB);
    source = controller.source();
    source.start(sink);
    await(() -> !sink.failed.isEmpty());
    assertEquals(0, hardware.opens.get());
    assertEquals(0, sink.frames.get());
  }

  @Test
  public void ambiguousDevicesCannotBeChosen() {
    hardware.list.add(
        new AudioInputController.Device(
            "duplicate", "Same", AudioInputController.Kind.USB, 9, -1, true));
    assertThrows(AudioInputController.InputFailure.class, () -> controller.select("duplicate"));
    assertEquals("builtin", controller.selected());
  }

  @Test
  public void refusedRouteFailsWithoutStartingAnotherInput() {
    hardware.refuse = true;
    source = controller.source();
    source.start(sink);
    await(() -> !sink.failed.isEmpty());
    assertEquals(0, sink.frames.get());
    assertEquals(1, hardware.opens.get());
  }

  @Test
  public void cancellationDuringBluetoothSetupDoesNotLeaveOwnershipOrCallListener() {
    controller.select(BT.key());
    hardware.blockOpen = true;
    source = controller.source();
    source.start(sink);
    await(() -> hardware.opens.get() > 0);
    source.cancel();
    await(() -> !controller.active());
    assertEquals(0, sink.frames.get());
    assertEquals("", sink.failed);
    assertEquals(0, hardware.live.get());
  }

  @Test
  public void rapidSwitchBackRebuildsAnInterruptedRecorder() {
    start();
    hardware.blockOpen = true;
    controller.select(USB.key());
    await(() -> hardware.opens.get() >= 2);
    controller.select(BUILTIN.key());
    hardware.blockOpen = false;
    await(
        () ->
            hardware.opens.get() >= 3
                && controller.state() == AudioInputController.State.CAPTURING);
    assertEquals(BUILTIN, hardware.latest.selected);
    assertEquals(1, hardware.maximum.get());
    assertTrue(sink.failed.isEmpty());
  }

  @Test
  public void slowConsumerHasBoundedBacklogAndReportsFailure() {
    sink.block = new CountDownLatch(1);
    source = controller.source();
    source.start(sink);
    await(() -> hardware.latest != null && hardware.latest.closed);
    sink.block.countDown();
    await(() -> !sink.failed.isEmpty());
    assertFalse(controller.active());
    assertEquals(1, hardware.maximum.get());
  }

  @Test
  public void localTestCannotCompeteWithListeningAndStopsOnBackground() {
    controller.test();
    await(() -> controller.state() == AudioInputController.State.CAPTURING);
    assertTrue(controller.testing());
    assertThrows(AudioInputController.InputFailure.class, () -> controller.source().start(sink));
    controller.background();
    await(() -> !controller.active());
    assertEquals(0, sink.frames.get());
    assertEquals(0, hardware.live.get());
  }

  @Test
  public void hotUnplugNotificationStopsEvenIfDriverStillReportsOldRoute() {
    controller.select(USB.key());
    start();
    hardware.list.remove(USB);
    hardware.changed.run();
    await(() -> !sink.failed.isEmpty());
    assertFalse(controller.active());
    assertEquals(1, hardware.opens.get());
  }

  @Test
  public void unverifiedRouteFramesAreDiscardedUntilTimeout() {
    hardware.blockOpen = true;
    source = controller.source();
    source.start(sink);
    await(() -> hardware.opens.get() > 0);
    hardware.blockOpen = false;
    await(() -> hardware.latest != null);
    hardware.latest.route = null;
    int before = sink.frames.get();
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5));
    await(() -> !sink.failed.isEmpty());
    assertTrue(sink.frames.get() <= before + 1);
  }
}
