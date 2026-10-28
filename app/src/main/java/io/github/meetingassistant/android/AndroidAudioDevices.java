package io.github.meetingassistant.android;

import android.annotation.SuppressLint;
import android.content.*;
import android.media.*;
import android.os.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.AudioInputSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** System UAC inputs and Bluetooth communication routes; never claims raw USB audio interfaces. */
@SuppressLint(
    "MissingPermission") // Checked on each UI entry; revocation is handled as a safe failure.
final class AndroidAudioDevices implements AudioInputController.Devices {
  private final Context context;
  private final AudioManager manager;
  private android.bluetooth.BluetoothHeadset legacyHeadset;
  private Runnable deviceChanged;
  private final Handler main = new Handler(Looper.getMainLooper());

  AndroidAudioDevices(Context context) {
    this.context = context.getApplicationContext();
    manager = context.getSystemService(AudioManager.class);
    if (Build.VERSION.SDK_INT < 31) {
      android.bluetooth.BluetoothManager bluetooth =
          context.getSystemService(android.bluetooth.BluetoothManager.class);
      android.bluetooth.BluetoothAdapter adapter =
          bluetooth == null ? null : bluetooth.getAdapter();
      if (adapter != null)
        adapter.getProfileProxy(
            this.context,
            new android.bluetooth.BluetoothProfile.ServiceListener() {
              public void onServiceConnected(
                  int profile, android.bluetooth.BluetoothProfile proxy) {
                legacyHeadset = (android.bluetooth.BluetoothHeadset) proxy;
                if (deviceChanged != null) deviceChanged.run();
              }

              public void onServiceDisconnected(int profile) {
                legacyHeadset = null;
                if (deviceChanged != null) deviceChanged.run();
              }
            },
            android.bluetooth.BluetoothProfile.HEADSET);
    }
  }

  static boolean bluetooth(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        || Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET;
  }

  static String key(AudioDeviceInfo d) {
    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return AudioInputSettings.BUILTIN;
    try {
      return identity(
          d.getType(),
          d.getProductName().toString(),
          Build.VERSION.SDK_INT >= 28 ? d.getAddress() : "");
    } catch (SecurityException e) {
      throw new AudioInputController.InputFailure(R.string.audio_bluetooth_permission);
    }
  }

  private static String identity(int type, String name, String address) {
    try {
      // AudioPolicy can cache a privacy-masked MAC after BLUETOOTH_CONNECT is granted.
      // Android's masked form retains the suffix; use the same name + suffix on both sides.
      // Detect collisions in BOTH input and communication catalogs rather than guessing.
      String locator = address;
      if (bluetooth(type) && address.matches("(?i)(?:[0-9a-fx]{2}:){5}[0-9a-f]{2}"))
        locator = address.substring(address.length() - 5).toUpperCase(Locale.ROOT);
      String identity = type + "|" + name + "|" + locator;
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(bluetooth(type) ? "bt:" : "device:");
      for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private AudioInputController.Device describe(AudioDeviceInfo d, int communication) {
    int type = d.getType();
    AudioInputController.Kind kind =
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            ? AudioInputController.Kind.BUILTIN
            : bluetooth(type)
                ? AudioInputController.Kind.BLUETOOTH
                : type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    ? AudioInputController.Kind.USB
                    : AudioInputController.Kind.OTHER;
    String name =
        kind == AudioInputController.Kind.BUILTIN
            ? UiLocale.wrap(context).getString(R.string.audio_builtin)
            : d.getProductName().toString();
    return new AudioInputController.Device(key(d), name, kind, d.getId(), communication, false);
  }

  private boolean bluetoothPermission() {
    return Build.VERSION.SDK_INT < 31
        || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  public List<AudioInputController.Device> list() {
    Map<String, AudioInputController.Device> result = new LinkedHashMap<>();
    Map<String, Integer> count = new HashMap<>(), communicationCount = new HashMap<>();
    List<AudioDeviceInfo> comm = List.of();
    if (Build.VERSION.SDK_INT >= 31) {
      try {
        comm = manager.getAvailableCommunicationDevices();
      } catch (SecurityException ignored) {
      }
    }
    for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
      // Remote submix, echo reference and tuners are not microphone inputs supported by this app.
      int t = d.getType();
      if (bluetooth(t) && !bluetoothPermission()) continue;
      if (!(t == AudioDeviceInfo.TYPE_BUILTIN_MIC
          || bluetooth(t)
          || t == AudioDeviceInfo.TYPE_USB_DEVICE
          || t == AudioDeviceInfo.TYPE_USB_HEADSET
          || t == AudioDeviceInfo.TYPE_USB_ACCESSORY
          || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
          || t == AudioDeviceInfo.TYPE_LINE_ANALOG
          || t == AudioDeviceInfo.TYPE_LINE_DIGITAL)) continue;
      int communication = -1;
      for (AudioDeviceInfo c : comm)
        if (bluetooth(c.getType()) && key(c).equals(key(d))) {
          communication = c.getId();
          break;
        }
      AudioInputController.Device item = describe(d, communication);
      count.merge(item.key(), 1, Integer::sum);
      result.putIfAbsent(item.key(), item);
    }
    // A Bluetooth mic may become an input only after its communication profile is activated.
    for (AudioDeviceInfo c : comm) {
      if (!bluetooth(c.getType()) || !bluetoothPermission()) continue;
      AudioInputController.Device item = describe(c, c.getId());
      communicationCount.merge(item.key(), 1, Integer::sum);
      result.putIfAbsent(item.key(), item);
    }
    if (Build.VERSION.SDK_INT < 31 && legacyHeadset != null) {
      try {
        for (android.bluetooth.BluetoothDevice d : legacyHeadset.getConnectedDevices()) {
          String name =
              d.getName() == null
                  ? UiLocale.wrap(context).getString(R.string.audio_bluetooth)
                  : d.getName();
          String key =
              identity(
                  AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                  name,
                  Build.VERSION.SDK_INT >= 28 ? d.getAddress() : "");
          communicationCount.merge(key, 1, Integer::sum);
          result.putIfAbsent(
              key,
              new AudioInputController.Device(
                  key, name, AudioInputController.Kind.BLUETOOTH, -1, -1, false));
        }
      } catch (SecurityException ignored) {
      }
    }
    List<AudioInputController.Device> out = new ArrayList<>();
    for (AudioInputController.Device d : result.values())
      out.add(
          new AudioInputController.Device(
              d.key(),
              d.name(),
              d.kind(),
              d.routeId(),
              d.communicationId(),
              d.kind() != AudioInputController.Kind.BUILTIN
                  && (count.getOrDefault(d.key(), 0) > 1
                      || communicationCount.getOrDefault(d.key(), 0) > 1)));
    return out;
  }

  public void observe(Runnable changed) {
    deviceChanged = changed;
    manager.registerAudioDeviceCallback(
        new AudioDeviceCallback() {
          public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            changed.run();
          }

          public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            changed.run();
          }
        },
        main);
  }

  private AudioDeviceInfo input(String key) {
    for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
      if (key(d).equals(key)) return d;
    return null;
  }

  private static void check(BooleanSupplier canceled) {
    if (canceled.getAsBoolean())
      throw new AudioInputController.InputFailure(R.string.audio_canceled);
  }

  public AudioInputController.Connection open(
      AudioInputController.Device device, BooleanSupplier canceled) {
    BluetoothRoute route = null;
    AudioRecord recorder = null;
    try {
      check(canceled);
      if (device.kind() == AudioInputController.Kind.BLUETOOTH) {
        route = new BluetoothRoute(device);
        route.open(canceled);
      }
      check(canceled);
      AudioDeviceInfo preferred = input(device.key());
      if (preferred == null) throw new AudioInputController.InputFailure(R.string.audio_missing);
      int chosenRate = 0, chosenChannels = 0;
      for (int[] format :
          new int[][] {{16000, 1}, {48000, 1}, {44100, 1}, {48000, 2}, {44100, 2}, {8000, 1}}) {
        int mask = format[1] == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        int min = AudioRecord.getMinBufferSize(format[0], mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) continue;
        try {
          recorder =
              new AudioRecord.Builder()
                  .setAudioSource(
                      device.kind() == AudioInputController.Kind.BLUETOOTH
                          ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                          : MediaRecorder.AudioSource.VOICE_RECOGNITION)
                  .setAudioFormat(
                      new AudioFormat.Builder()
                          .setSampleRate(format[0])
                          .setChannelMask(mask)
                          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                          .build())
                  .setBufferSizeInBytes(Math.max(min * 2, format[0] * format[1] * 2 / 2))
                  .build();
          if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
            throw new IllegalStateException();
          chosenRate = format[0];
          chosenChannels = format[1];
          break;
        } catch (RuntimeException e) {
          if (recorder != null) recorder.release();
          recorder = null;
        }
      }
      if (recorder == null) throw new AudioInputController.InputFailure(R.string.audio_format);
      if (!recorder.setPreferredDevice(preferred))
        throw new AudioInputController.InputFailure(R.string.audio_route_failed);
      check(canceled);
      recorder.startRecording();
      if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new AudioInputController.InputFailure(R.string.audio_failed);
      return new Capture(recorder, route, chosenRate, chosenChannels);
    } catch (RuntimeException ex) {
      if (recorder != null) {
        try {
          recorder.stop();
        } catch (RuntimeException ignored) {
        }
        recorder.release();
      }
      if (route != null) route.close();
      throw ex;
    }
  }

  private final class Capture implements AudioInputController.Connection {
    private final AudioRecord recorder;
    private final BluetoothRoute route;
    private final short[] frame;
    private final PcmNormalizer normalizer;
    private volatile boolean interrupted;

    Capture(AudioRecord recorder, BluetoothRoute route, int rate, int channels) {
      this.recorder = recorder;
      this.route = route;
      frame = new short[rate / 10 * channels];
      normalizer = new PcmNormalizer(rate, channels);
    }

    public byte[] read() {
      int count = recorder.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
      if (count < 0 && !interrupted)
        throw new AudioInputController.InputFailure(R.string.audio_failed);
      if (Build.VERSION.SDK_INT >= 29 && !interrupted) {
        AudioRecordingConfiguration c = recorder.getActiveRecordingConfiguration();
        if (c != null && c.isClientSilenced())
          throw new AudioInputController.InputFailure(R.string.audio_silenced);
      }
      return count > 0 ? normalizer.convert(frame, count) : new byte[0];
    }

    public AudioInputController.Device actual() {
      AudioDeviceInfo d = recorder.getRoutedDevice();
      return d == null ? null : describe(d, -1);
    }

    public void interrupt() {
      interrupted = true;
      try {
        recorder.stop();
      } catch (IllegalStateException ignored) {
      }
    }

    public void close() {
      interrupt();
      recorder.release();
      if (route != null) route.close();
    }
  }

  private final class BluetoothRoute {
    final AudioInputController.Device device;
    final int previousMode;
    boolean selected, scoStarted, registered, modeSet;
    volatile boolean scoConnected;
    BroadcastReceiver receiver;

    BluetoothRoute(AudioInputController.Device device) {
      this.device = device;
      previousMode = manager.getMode();
    }

    void open(BooleanSupplier canceled) {
      if (Build.VERSION.SDK_INT >= 31
          && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
              != android.content.pm.PackageManager.PERMISSION_GRANTED)
        throw new AudioInputController.InputFailure(R.string.audio_bluetooth_permission);
      manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
      modeSet = true;
      long deadline = SystemClock.elapsedRealtime() + 30000;
      if (Build.VERSION.SDK_INT >= 31) {
        AudioDeviceInfo target = null;
        for (AudioDeviceInfo d : manager.getAvailableCommunicationDevices())
          if (d.getId() == device.communicationId() || key(d).equals(device.key())) {
            target = d;
            break;
          }
        if (target == null || !manager.setCommunicationDevice(target))
          throw new AudioInputController.InputFailure(R.string.audio_route_failed);
        selected = true;
        while (true) {
          check(canceled);
          AudioDeviceInfo active = manager.getCommunicationDevice();
          if (active != null && key(active).equals(device.key()) && input(device.key()) != null)
            break;
          waitRoute(deadline);
        }
      } else {
        receiver =
            new BroadcastReceiver() {
              public void onReceive(Context c, Intent intent) {
                scoConnected =
                    intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        == AudioManager.SCO_AUDIO_STATE_CONNECTED;
              }
            };
        context.registerReceiver(
            receiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        registered = true;
        manager.startBluetoothSco();
        scoStarted = true;
        manager.setBluetoothScoOn(true);
        while (!scoConnected || input(device.key()) == null) {
          check(canceled);
          waitRoute(deadline);
        }
      }
    }

    void waitRoute(long deadline) {
      if (SystemClock.elapsedRealtime() > deadline)
        throw new AudioInputController.InputFailure(R.string.audio_bluetooth_timeout);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AudioInputController.InputFailure(R.string.audio_canceled);
      }
    }

    void close() {
      try {
        if (Build.VERSION.SDK_INT >= 31 && selected) manager.clearCommunicationDevice();
        if (Build.VERSION.SDK_INT < 31 && scoStarted) {
          manager.setBluetoothScoOn(false);
          manager.stopBluetoothSco();
        }
      } catch (RuntimeException ignored) {
      }
      if (registered) {
        context.unregisterReceiver(receiver);
        registered = false;
      }
      if (modeSet && manager.getMode() == AudioManager.MODE_IN_COMMUNICATION)
        manager.setMode(previousMode);
    }
  }
}
