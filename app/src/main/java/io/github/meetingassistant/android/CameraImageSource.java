package io.github.meetingassistant.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.WindowManager;
import java.util.*;

/**
 * Camera2 capture stays inside this app; a small YUV stream drives AF/AE without a visible view.
 */
final class CameraImageSource implements ImageSource {
  private final Context context;
  private final String id;
  private final HandlerThread thread = new HandlerThread("meeting-camera");
  private Handler handler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader still, meter;
  private StateListener listener;
  private Capture pending;
  private boolean closed;
  private int rotation;
  private int sensorOrientation;
  private boolean front;
  private Integer focusMode;

  private int currentRotation() {
    int display =
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRotation() * 90;
    return Math.floorMod(sensorOrientation + (front ? display : -display), 360);
  }

  CameraImageSource(Context context, String id) {
    this.context = context;
    this.id = id;
  }

  static List<Device> devices(Context context) {
    List<Device> result = new ArrayList<>();
    CameraManager manager = context.getSystemService(CameraManager.class);
    if (manager == null) return result;
    try {
      for (String id : manager.getCameraIdList()) {
        Integer facing =
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        Kind kind =
            facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT
                ? Kind.FRONT
                : facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                    ? Kind.BACK
                    : Kind.EXTERNAL;
        result.add(new Device("camera:" + id, kind));
      }
    } catch (CameraAccessException | RuntimeException ignored) {
    }
    result.sort(
        Comparator.comparing(d -> d.kind() == Kind.BACK ? 0 : d.kind() == Kind.FRONT ? 1 : 2));
    return result;
  }

  @SuppressLint(
      "MissingPermission") // Controller checks runtime permission before creating a source.
  public void open(StateListener listener) {
    this.listener = listener;
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(
        () -> {
          if (closed) return;
          try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map =
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException();
            Size size = choose(map.getOutputSizes(ImageFormat.JPEG), 8_000_000);
            Size small = choose(map.getOutputSizes(ImageFormat.YUV_420_888), 640 * 480);
            Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            sensorOrientation = sensor == null ? 0 : sensor;
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            int[] focusModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (focusModes != null)
              for (int mode : focusModes)
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) focusMode = mode;
            still = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            still.setOnImageAvailableListener(
                reader -> {
                  try (Image image = reader.acquireLatestImage()) {
                    if (image == null || pending == null || closed) return;
                    java.nio.ByteBuffer b = image.getPlanes()[0].getBuffer();
                    if (b.remaining() > 8 * 1024 * 1024) throw new IllegalStateException();
                    byte[] data = new byte[b.remaining()];
                    b.get(data);
                    Capture cb = pending;
                    pending = null;
                    cb.frame(new Frame(data, image.getWidth(), image.getHeight(), rotation, false));
                  } catch (RuntimeException e) {
                    captureError("图像抓取失败，请重试");
                  }
                },
                handler);
            meter =
                ImageReader.newInstance(
                    small.getWidth(), small.getHeight(), ImageFormat.YUV_420_888, 2);
            meter.setOnImageAvailableListener(
                reader -> {
                  try (Image image = reader.acquireLatestImage()) {}
                },
                handler);
            manager.openCamera(
                id,
                new CameraDevice.StateCallback() {
                  public void onOpened(CameraDevice device) {
                    if (closed) {
                      device.close();
                      return;
                    }
                    camera = device;
                    createSession();
                  }

                  public void onDisconnected(CameraDevice device) {
                    device.close();
                    fail("图像来源已断开");
                  }

                  public void onError(CameraDevice device, int error) {
                    device.close();
                    fail("无法打开图像来源，请检查权限、占用和隐私开关");
                  }
                },
                handler);
          } catch (CameraAccessException | RuntimeException e) {
            fail("无法打开图像来源，请检查权限、占用和隐私开关");
          }
        });
  }

  static Size choose(Size[] sizes, long budget) {
    if (sizes == null || sizes.length == 0) throw new IllegalArgumentException();
    return Arrays.stream(sizes)
        .filter(s -> (long) s.getWidth() * s.getHeight() <= budget)
        .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
        .orElseGet(
            () ->
                Arrays.stream(sizes)
                    .min(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                    .orElseThrow(IllegalArgumentException::new));
  }

  @SuppressWarnings("deprecation")
  private void createSession() {
    try {
      camera.createCaptureSession(
          Arrays.asList(still.getSurface(), meter.getSurface()),
          new CameraCaptureSession.StateCallback() {
            public void onConfigured(CameraCaptureSession configured) {
              if (closed) {
                configured.close();
                return;
              }
              session = configured;
              try {
                CaptureRequest.Builder request =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(meter.getSurface());
                if (focusMode != null) request.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                session.setRepeatingRequest(request.build(), null, handler);
                listener.ready();
              } catch (CameraAccessException | RuntimeException e) {
                fail("图像来源准备失败，请重新开启");
              }
            }

            public void onConfigureFailed(CameraCaptureSession session) {
              fail("图像来源不支持当前采集格式");
            }
          },
          handler);
    } catch (CameraAccessException | RuntimeException e) {
      fail("图像来源准备失败，请重新开启");
    }
  }

  public void capture(Capture callback) {
    if (handler == null) {
      callback.failed("图像来源尚未就绪");
      return;
    }
    handler.post(
        () -> {
          if (closed || session == null || pending != null) {
            callback.failed("图像来源尚未就绪");
            return;
          }
          pending = callback;
          rotation = currentRotation();
          try {
            CaptureRequest.Builder request =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(still.getSurface());
            if (focusMode != null) request.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.JPEG_ORIENTATION, 0);
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            session.capture(
                request.build(),
                new CameraCaptureSession.CaptureCallback() {
                  public void onCaptureFailed(
                      CameraCaptureSession session,
                      CaptureRequest request,
                      CaptureFailure failure) {
                    captureError("图像抓取失败，请重试");
                  }
                },
                handler);
          } catch (CameraAccessException | RuntimeException e) {
            captureError("图像抓取失败，请重试");
          }
          handler.postDelayed(
              () -> {
                if (pending == callback) captureError("图像抓取超时，请检查来源");
              },
              8000);
        });
  }

  private void captureError(String error) {
    Capture cb = pending;
    pending = null;
    if (cb != null) cb.failed(error);
  }

  private void fail(String error) {
    if (!closed) {
      captureError(error);
      listener.failed(error);
    }
  }

  public void close() {
    closed = true;
    if (handler != null)
      handler.post(
          () -> {
            captureError("图像抓取已取消");
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (still != null) still.close();
            if (meter != null) meter.close();
            thread.quitSafely();
          });
  }
}
