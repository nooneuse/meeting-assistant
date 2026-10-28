package io.github.meetingassistant.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import io.github.meetingassistant.core.ImageEvidence;
import io.github.meetingassistant.core.ImageSettings;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Main-thread device ownership plus a separate bounded (one frame) image conversion worker. */
class ImageInputController {
  enum State {
    OFF,
    OPENING,
    READY,
    ERROR
  }

  record Prepared(byte[] jpeg, ImageEvidence evidence) {}

  interface Sources {
    List<ImageSource.Device> list();

    ImageSource create(String id);
  }

  private final Context context;
  private final Sources sources;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "meeting-image-convert");
            t.setDaemon(true);
            return t;
          });
  private ImageSource source;
  private ImageSettings settings;
  private Consumer<Bitmap> preview;
  private Runnable observer;
  private State state = State.OFF;
  private String error = "";
  private long generation;
  private boolean capturing;
  int previewWidth, previewHeight, previewRotation;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (preview == null || state != State.READY) return;
          if (!capturing) take(true, result -> {}, message -> {});
          main.postDelayed(this, 1000);
        }
      };

  ImageInputController(Context context, ImageSettings settings) {
    this(
        context,
        settings,
        new Sources() {
          public List<ImageSource.Device> list() {
            List<ImageSource.Device> devices = CameraImageSource.devices(context);
            devices.addAll(UsbImageSource.devices(context));
            return devices;
          }

          public ImageSource create(String id) {
            if (id.startsWith("camera:")) return new CameraImageSource(context, id.substring(7));
            if (id.startsWith("usb:")) return new UsbImageSource(context, id.substring(4));
            throw new IllegalArgumentException();
          }
        });
  }

  ImageInputController(Context context, ImageSettings settings, Sources sources) {
    this.context = context;
    this.settings = settings;
    this.sources = sources;
  }

  State state() {
    return state;
  }

  String error() {
    return error;
  }

  boolean enabled() {
    return state != State.OFF;
  }

  boolean capturing() {
    return capturing;
  }

  void observe(Runnable observer) {
    this.observer = observer;
  }

  void settings(ImageSettings settings) {
    this.settings = settings;
  }

  List<ImageSource.Device> devices() {
    try {
      return sources.list();
    } catch (RuntimeException e) {
      return new ArrayList<>();
    }
  }

  private void changed() {
    if (observer != null) observer.run();
  }

  void setEnabled(boolean on) {
    generation++;
    capturing = false;
    if (source != null) {
      source.close();
      source = null;
    }
    main.removeCallbacks(poll);
    error = "";
    if (!on) {
      state = State.OFF;
      changed();
      return;
    }
    state = State.OPENING;
    changed();
    if (context.checkSelfPermission(Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) {
      fail("未获得图像来源权限，请重新开启");
      return;
    }
    List<ImageSource.Device> devices = devices();
    if (settings.sourceId.isEmpty() && !devices.isEmpty()) settings.sourceId = devices.get(0).id();
    if (devices.stream().noneMatch(d -> d.id().equals(settings.sourceId))) {
      fail("图像来源不可用，请重新选择");
      return;
    }
    long ticket = generation;
    try {
      source = sources.create(settings.sourceId);
      source.open(
          new ImageSource.StateListener() {
            public void ready() {
              main.post(
                  () -> {
                    if (ticket != generation || state != State.OPENING) return;
                    state = State.READY;
                    changed();
                    if (preview != null) main.post(poll);
                  });
            }

            public void failed(String message) {
              main.post(
                  () -> {
                    if (ticket == generation) fail(message);
                  });
            }
          });
      main.postDelayed(
          () -> {
            if (ticket == generation && state == State.OPENING) fail("图像来源准备超时，请重新开启");
          },
          20000);
    } catch (RuntimeException | LinkageError e) {
      fail("图像来源准备失败，请重新开启");
    }
  }

  void select(String id) {
    settings.sourceId = id;
    if (enabled()) setEnabled(true);
    else changed();
  }

  private void fail(String message) {
    generation++;
    capturing = false;
    if (source != null) {
      source.close();
      source = null;
    }
    state = State.ERROR;
    error = message;
    main.removeCallbacks(poll);
    changed();
  }

  void preview(Consumer<Bitmap> listener) {
    preview = listener;
    main.removeCallbacks(poll);
    if (preview != null && state == State.READY) main.post(poll);
  }

  void capture(Consumer<Prepared> completed, Consumer<String> failed) {
    take(false, completed, failed);
  }

  private void take(boolean forPreview, Consumer<Prepared> completed, Consumer<String> failed) {
    if (state != State.READY || source == null || capturing) {
      failed.accept("图像来源尚未就绪");
      return;
    }
    capturing = true;
    long ticket = generation;
    ImageSettings crop =
        new com.google.gson.Gson()
            .fromJson(new com.google.gson.Gson().toJson(settings), ImageSettings.class);
    String sourceId = settings.sourceId;
    source.capture(
        new ImageSource.Capture() {
          public void frame(ImageSource.Frame frame) {
            long capturedAt = System.currentTimeMillis();
            worker.execute(
                () -> {
                  Bitmap bitmap = null;
                  try {
                    bitmap = decode(frame);
                    if (frame.rotation() != 0) {
                      Matrix m = new Matrix();
                      m.postRotate(frame.rotation());
                      Bitmap rotated =
                          Bitmap.createBitmap(
                              bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                      if (rotated != bitmap) bitmap.recycle();
                      bitmap = rotated;
                    }
                    ImageEvidence evidence = new ImageEvidence();
                    evidence.capturedAt = capturedAt;
                    evidence.source = sourceId.startsWith("usb:") ? "USB" : "Camera";
                    evidence.rotation = frame.rotation();
                    evidence.originalWidth = bitmap.getWidth();
                    evidence.originalHeight = bitmap.getHeight();
                    if (!forPreview
                        && crop.cropSource.equals(sourceId)
                        && crop.cropWidth == bitmap.getWidth()
                        && crop.cropHeight == bitmap.getHeight()
                        && crop.cropRotation == frame.rotation()) {
                      validateCrop(crop);
                      int x = (int) (bitmap.getWidth() * crop.left),
                          y = (int) (bitmap.getHeight() * crop.top);
                      int right =
                          Math.min(
                              bitmap.getWidth(), (int) Math.ceil(bitmap.getWidth() * crop.right));
                      int bottom =
                          Math.min(
                              bitmap.getHeight(),
                              (int) Math.ceil(bitmap.getHeight() * crop.bottom));
                      Bitmap area = Bitmap.createBitmap(bitmap, x, y, right - x, bottom - y);
                      if (area != bitmap) bitmap.recycle();
                      bitmap = area;
                      evidence.cropLeft = crop.left;
                      evidence.cropTop = crop.top;
                      evidence.cropRight = crop.right;
                      evidence.cropBottom = crop.bottom;
                    }
                    evidence.width = bitmap.getWidth();
                    evidence.height = bitmap.getHeight();
                    if (forPreview) {
                      int w = bitmap.getWidth(), h = bitmap.getHeight();
                      if (w > 1280 || h > 1280) {
                        float f = 1280f / Math.max(w, h);
                        Bitmap small =
                            Bitmap.createScaledBitmap(
                                bitmap,
                                Math.max(1, (int) (w * f)),
                                Math.max(1, (int) (h * f)),
                                true);
                        if (small != bitmap) bitmap.recycle();
                        bitmap = small;
                      }
                      Bitmap display = bitmap;
                      bitmap = null;
                      main.post(
                          () -> {
                            if (ticket == generation) capturing = false;
                            if (ticket == generation && preview != null && state == State.READY) {
                              previewWidth = evidence.originalWidth;
                              previewHeight = evidence.originalHeight;
                              previewRotation = evidence.rotation;
                              preview.accept(display);
                            } else display.recycle();
                          });
                    } else {
                      ByteArrayOutputStream out = new ByteArrayOutputStream();
                      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                        throw new IllegalStateException();
                      byte[] jpeg = out.toByteArray();
                      if (jpeg.length > 8 * 1024 * 1024) throw new IllegalStateException();
                      main.post(
                          () -> {
                            if (ticket == generation) capturing = false;
                            if (ticket == generation && state == State.READY)
                              completed.accept(new Prepared(jpeg, evidence));
                            else failed.accept("图像来源已变化，请重新抓拍");
                          });
                    }
                  } catch (RuntimeException | OutOfMemoryError e) {
                    main.post(
                        () -> {
                          if (ticket == generation) capturing = false;
                          failed.accept("图像转换失败，请调整来源或识别区域");
                        });
                  } finally {
                    if (bitmap != null) bitmap.recycle();
                  }
                });
          }

          public void failed(String message) {
            main.post(
                () -> {
                  if (ticket == generation) capturing = false;
                  failed.accept(message);
                });
          }
        });
  }

  static void validateCrop(ImageSettings c) {
    if (!Float.isFinite(c.left)
        || !Float.isFinite(c.top)
        || !Float.isFinite(c.right)
        || !Float.isFinite(c.bottom)
        || c.left < 0
        || c.top < 0
        || c.right > 1
        || c.bottom > 1
        || c.right - c.left < .05f
        || c.bottom - c.top < .05f) throw new IllegalArgumentException("识别区域无效，请重新选择");
  }

  static Bitmap decode(ImageSource.Frame frame) {
    byte[] bytes = frame.bytes();
    if (frame.width() <= 0
        || frame.height() <= 0
        || (long) frame.width() * frame.height() > 12_000_000) throw new IllegalArgumentException();
    if (frame.nv21()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      new YuvImage(bytes, ImageFormat.NV21, frame.width(), frame.height(), null)
          .compressToJpeg(new Rect(0, 0, frame.width(), frame.height()), 95, out);
      bytes = out.toByteArray();
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || (long) bounds.outWidth * bounds.outHeight > 12_000_000)
      throw new IllegalArgumentException();
    Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (result == null) throw new IllegalArgumentException();
    return result;
  }
}
