package io.github.meetingassistant.android;

import android.content.Context;
import android.hardware.usb.*;
import android.os.Handler;
import android.os.HandlerThread;
import com.serenegiant.usb.*;
import java.util.*;

/** Direct UVC adapter: video interfaces only, no audio recording, no files or library helper UI. */
final class UsbImageSource implements ImageSource {
  private final Context context;
  private final String deviceName;

  // One worker orders teardown before another source can claim the same USB interfaces.
  private static final class Worker {
    static final Handler HANDLER = create();

    private static Handler create() {
      HandlerThread thread = new HandlerThread("meeting-usb-video");
      thread.start();
      return new Handler(thread.getLooper());
    }
  }

  private Handler handler;
  private USBMonitor monitor;
  private USBMonitor.UsbControlBlock controlBlock;
  private UVCCamera camera;
  private HeadlessVideoSurface sink;
  private volatile boolean closed;
  private volatile Capture pending;
  private volatile long lastFrame;
  private StateListener listener;

  UsbImageSource(Context context, String name) {
    this.context = context;
    deviceName = name;
  }

  static List<Device> devices(Context context) {
    List<Device> result = new ArrayList<>();
    UsbManager manager = context.getSystemService(UsbManager.class);
    if (manager == null) return result;
    for (UsbDevice d : manager.getDeviceList().values()) {
      boolean video = d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO;
      for (int i = 0; i < d.getInterfaceCount(); i++)
        video |= d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO;
      if (video) result.add(new Device("usb:" + d.getDeviceName(), Kind.USB));
    }
    return result;
  }

  public void open(StateListener listener) {
    this.listener = listener;
    handler = Worker.HANDLER;
    handler.post(
        () -> {
          if (closed) return;
          try {
            monitor =
                new USBMonitor(
                    context,
                    new USBMonitor.OnDeviceConnectListener() {
                      public void onAttach(UsbDevice device) {}

                      public void onDetach(UsbDevice device) {
                        if (matches(device)) fail("图像来源已断开");
                      }

                      public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock block) {
                        if (matches(d) && !closed) fail("图像来源已断开");
                      }

                      public void onCancel(UsbDevice device) {
                        if (matches(device)) fail("未获得图像来源权限，请重新开启");
                      }

                      public void onError(UsbDevice device, USBMonitor.USBException e) {
                        fail("无法打开 USB 图像来源，请重新插拔");
                      }

                      public void onDeviceOpen(
                          UsbDevice device, USBMonitor.UsbControlBlock block, boolean fresh) {
                        if (closed || !matches(device) || camera != null) return;
                        try {
                          controlBlock = block;
                          prepareBulkVideo(block.getConnection(), device);
                          List<com.serenegiant.usb.Size> advertised =
                              descriptorSizes(block.getRawDescriptors());
                          com.serenegiant.usb.Size initial =
                              advertised.isEmpty() ? null : chooseSize(advertised);
                          camera =
                              new UVCCamera(
                                  new UVCParam(initial, UVCCamera.getRecommendedPlatformQuirks()));
                          if (camera.open(block) != 0) throw new IllegalStateException();
                          List<com.serenegiant.usb.Size> sizes = camera.getSupportedSizeList();
                          if (sizes == null || sizes.isEmpty()) throw new IllegalStateException();
                          com.serenegiant.usb.Size size = chooseSize(sizes);
                          com.serenegiant.usb.Size current = camera.getPreviewSize();
                          if (current == null
                              || current.width != size.width
                              || current.height != size.height
                              || current.type != size.type
                              || current.fps != size.fps) camera.setPreviewSize(size);
                          sink = new HeadlessVideoSurface();
                          camera.setPreviewTexture(sink.open(size.width, size.height, handler));
                          camera.setFrameCallback(
                              buffer -> {
                                if (closed) return;
                                boolean first = lastFrame == 0;
                                lastFrame = android.os.SystemClock.elapsedRealtime();
                                if (first) handler.post(listener::ready);
                                Capture cb;
                                synchronized (UsbImageSource.this) {
                                  cb = pending;
                                  pending = null;
                                }
                                if (cb != null) {
                                  int length = size.width * size.height * 3 / 2;
                                  if (buffer.remaining() < length) {
                                    cb.failed("图像帧格式无效，请检查来源");
                                    return;
                                  }
                                  byte[] data = new byte[length];
                                  buffer.get(data);
                                  cb.frame(new Frame(data, size.width, size.height, 0, true));
                                }
                              },
                              UVCCamera.PIXEL_FORMAT_NV21);
                          camera.startPreview();
                          handler.postDelayed(
                              new Runnable() {
                                public void run() {
                                  if (closed) return;
                                  if (lastFrame == 0
                                      || android.os.SystemClock.elapsedRealtime() - lastFrame
                                          > 5000) fail("USB 图像来源无视频信号，请检查连接");
                                  else handler.postDelayed(this, 2000);
                                }
                              },
                              8000);
                        } catch (RuntimeException | LinkageError e) {
                          fail("无法打开 USB 图像来源，请检查格式与设备兼容性");
                        }
                      }
                    },
                    handler);
            monitor.register();
            UsbDevice selected =
                context.getSystemService(UsbManager.class).getDeviceList().get(deviceName);
            if (selected == null) {
              fail("图像来源已断开");
              return;
            }
            monitor.requestPermission(selected);
          } catch (RuntimeException | LinkageError e) {
            fail("无法打开 USB 图像来源，请检查格式与设备兼容性");
          }
        });
  }

  private boolean matches(UsbDevice d) {
    return d != null && deviceName.equals(d.getDeviceName());
  }

  /** Normalize only video Bulk IN endpoints; never reconfigure USB audio or the whole device. */
  private static void prepareBulkVideo(UsbDeviceConnection connection, UsbDevice device) {
    if (connection == null) return;
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      UsbInterface intf = device.getInterface(i);
      if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_VIDEO
          || intf.getInterfaceSubclass() != 2
          || intf.getAlternateSetting() != 0) continue;
      List<UsbEndpoint> endpoints = new ArrayList<>();
      for (int j = 0; j < intf.getEndpointCount(); j++) {
        UsbEndpoint ep = intf.getEndpoint(j);
        if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
            && ep.getDirection() == UsbConstants.USB_DIR_IN) endpoints.add(ep);
      }
      if (endpoints.isEmpty() || !connection.claimInterface(intf, true)) continue;
      try {
        connection.setInterface(intf);
        // CLEAR_FEATURE(ENDPOINT_HALT) also resets the Bulk data toggle/FIFO.
        // Some capture cards require this even when GET_STATUS says not halted.
        for (UsbEndpoint ep : endpoints)
          connection.controlTransfer(0x02, 1, 0, ep.getAddress(), null, 0, 1000);
      } finally {
        connection.releaseInterface(intf);
      }
    }
  }

  /** Read VS frame descriptors before native open, avoiding its default maximum-size probe. */
  static List<com.serenegiant.usb.Size> descriptorSizes(byte[] raw) {
    List<com.serenegiant.usb.Size> result = new ArrayList<>();
    if (raw == null || raw.length > 1024 * 1024) return result;
    boolean video = false;
    for (int at = 0; at < raw.length; ) {
      int length = raw[at] & 255;
      if (length < 2 || length > raw.length - at) return new ArrayList<>();
      int type = raw[at + 1] & 255;
      if (type == 4) {
        if (length < 9) return new ArrayList<>();
        video = (raw[at + 5] & 255) == 14 && (raw[at + 6] & 255) == 2;
      } else if (video && type == 0x24 && length >= 26) {
        int frame = raw[at + 2] & 255;
        if (frame == 5 || frame == 7) {
          int width = u16(raw, at + 5), height = u16(raw, at + 7), count = raw[at + 25] & 255;
          if (width > 0 && height > 0 && (long) width * height <= 12_000_000) {
            List<Integer> fps = new ArrayList<>();
            long defaultInterval = u32(raw, at + 21);
            int defaultFps = defaultInterval == 0 ? 0 : (int) (10000000 / defaultInterval);
            if (count > 0 && 26 + 4 * count <= length) {
              for (int i = 0; i < count; i++) addFps(fps, u32(raw, at + 26 + 4 * i));
            } else if (count == 0 && length >= 38) {
              addFps(fps, u32(raw, at + 26));
              addFps(fps, u32(raw, at + 30));
              if (defaultFps > 0 && !fps.contains(defaultFps)) fps.add(defaultFps);
            }
            if (!fps.isEmpty())
              result.add(
                  new com.serenegiant.usb.Size(
                      frame, width, height, defaultFps > 0 ? defaultFps : fps.get(0), fps));
          }
        }
      }
      at += length;
    }
    return result;
  }

  private static int u16(byte[] raw, int at) {
    return (raw[at] & 255) | ((raw[at + 1] & 255) << 8);
  }

  private static long u32(byte[] raw, int at) {
    return (raw[at] & 255L)
        | ((raw[at + 1] & 255L) << 8)
        | ((raw[at + 2] & 255L) << 16)
        | ((raw[at + 3] & 255L) << 24);
  }

  private static void addFps(List<Integer> values, long interval) {
    if (interval > 0) {
      int fps = (int) (10000000 / interval);
      if (fps > 0 && !values.contains(fps)) values.add(fps);
    }
  }

  static com.serenegiant.usb.Size chooseSize(List<com.serenegiant.usb.Size> sizes) {
    if (sizes == null || sizes.isEmpty()) throw new IllegalArgumentException();
    Comparator<com.serenegiant.usb.Size> order =
        Comparator.comparingLong((com.serenegiant.usb.Size s) -> (long) s.width * s.height)
            .thenComparingInt(s -> s.type == UVCCamera.UVC_VS_FRAME_MJPEG ? 1 : 0);
    com.serenegiant.usb.Size selected =
        sizes.stream()
            .filter(s -> (long) s.width * s.height <= 1920L * 1080)
            .max(order)
            .orElseGet(
                () ->
                    sizes.stream()
                        .min(
                            Comparator.comparingLong(
                                    (com.serenegiant.usb.Size s) -> (long) s.width * s.height)
                                .thenComparingInt(
                                    s -> s.type == UVCCamera.UVC_VS_FRAME_MJPEG ? 0 : 1))
                        .orElseThrow(IllegalArgumentException::new))
            .clone();
    if (selected.width <= 0
        || selected.height <= 0
        || (long) selected.width * selected.height > 12_000_000)
      throw new IllegalArgumentException();
    if (selected.fpsList != null)
      selected.fps =
          selected.fpsList.stream()
              .filter(fps -> fps >= 5)
              .min(Integer::compareTo)
              .orElse(selected.fps);
    return selected;
  }

  public synchronized void capture(Capture cb) {
    if (closed
        || camera == null
        || pending != null
        || lastFrame == 0
        || android.os.SystemClock.elapsedRealtime() - lastFrame > 2000) {
      cb.failed("图像来源尚未就绪");
      return;
    }
    pending = cb;
    handler.postDelayed(
        () -> {
          synchronized (UsbImageSource.this) {
            if (pending == cb) {
              pending = null;
              cb.failed("图像抓取超时，请检查来源");
            }
          }
        },
        5000);
  }

  private void fail(String error) {
    if (!closed) listener.failed(error);
  }

  public void close() {
    if (closed) return;
    closed = true;
    synchronized (this) {
      if (pending != null) {
        pending.failed("图像抓取已取消");
        pending = null;
      }
    }
    if (handler != null)
      handler.post(
          () -> {
            if (camera != null) {
              // Native conversion may still be using the callback method ID. Join its
              // threads before clearing that ID, otherwise CheckJNI aborts the process.
              camera.stopPreview();
              camera.setFrameCallback(null, 0);
              camera.destroy();
              camera = null;
            }
            // UVCCamera owns a clone. Closing that clone removes the monitor's map
            // entry too, so keep and close the original connection explicitly.
            if (controlBlock != null) {
              controlBlock.close();
              controlBlock = null;
            }
            if (sink != null) {
              sink.close();
              sink = null;
            }
            if (monitor != null) {
              monitor.unregister();
              monitor.destroy();
              monitor = null;
            }
          });
  }
}
