package io.github.meetingassistant.android;

/** Device adapter. It never knows about meetings, OCR providers or microphone ownership. */
interface ImageSource {
  record Device(String id, Kind kind) {}

  enum Kind {
    BACK,
    FRONT,
    EXTERNAL,
    USB
  }

  record Frame(byte[] bytes, int width, int height, int rotation, boolean nv21) {}

  interface StateListener {
    void ready();

    void failed(String safeMessage);
  }

  interface Capture {
    void frame(Frame frame);

    void failed(String safeMessage);
  }

  void open(StateListener listener);

  void capture(Capture callback);

  void close();
}
