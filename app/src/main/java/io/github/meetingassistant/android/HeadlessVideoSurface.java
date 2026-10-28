package io.github.meetingassistant.android;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;

/** Drains the UVC driver's required preview surface without putting video on the meeting screen. */
final class HeadlessVideoSurface implements AutoCloseable {
  private android.opengl.EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private android.opengl.EGLContext context = EGL14.EGL_NO_CONTEXT;
  private android.opengl.EGLSurface surface = EGL14.EGL_NO_SURFACE;
  private SurfaceTexture texture;
  private int name;

  SurfaceTexture open(int width, int height, Handler handler) {
    try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      int[] version = new int[2];
      if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException();
      android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
      int[] count = new int[1];
      if (!EGL14.eglChooseConfig(
              display,
              new int[] {
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_NONE
              },
              0,
              configs,
              0,
              1,
              count,
              0)
          || count[0] == 0) throw new IllegalStateException();
      context =
          EGL14.eglCreateContext(
              display,
              configs[0],
              EGL14.EGL_NO_CONTEXT,
              new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
              0);
      surface =
          EGL14.eglCreatePbufferSurface(
              display,
              configs[0],
              new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE},
              0);
      if (context == EGL14.EGL_NO_CONTEXT
          || surface == EGL14.EGL_NO_SURFACE
          || !EGL14.eglMakeCurrent(display, surface, surface, context))
        throw new IllegalStateException();
      int[] names = new int[1];
      GLES20.glGenTextures(1, names, 0);
      name = names[0];
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name);
      GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      texture = new SurfaceTexture(name);
      texture.setDefaultBufferSize(width, height);
      texture.setOnFrameAvailableListener(
          t -> {
            if (texture != null)
              try {
                t.updateTexImage();
              } catch (RuntimeException ignored) {
              }
          },
          handler);
      return texture;
    } catch (RuntimeException e) {
      close();
      throw e;
    }
  }

  public void close() {
    if (texture != null) {
      texture.setOnFrameAvailableListener(null);
      texture.release();
      texture = null;
    }
    if (display != EGL14.EGL_NO_DISPLAY) {
      if (name != 0) {
        GLES20.glDeleteTextures(1, new int[] {name}, 0);
        name = 0;
      }
      EGL14.eglMakeCurrent(
          display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
      EGL14.eglTerminate(display);
      EGL14.eglReleaseThread();
      display = EGL14.EGL_NO_DISPLAY;
      context = EGL14.EGL_NO_CONTEXT;
      surface = EGL14.EGL_NO_SURFACE;
    }
  }
}
