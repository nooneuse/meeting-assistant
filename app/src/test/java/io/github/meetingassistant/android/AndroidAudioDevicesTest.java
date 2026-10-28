package io.github.meetingassistant.android;

import static org.junit.Assert.*;

import android.media.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestMeetingApp.class, sdk = 35)
public class AndroidAudioDevicesTest {
  AudioDeviceInfo device(int type) {
    AudioDeviceInfo d = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
    if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
      org.robolectric.util.ReflectionHelpers.setField(
          org.robolectric.util.ReflectionHelpers.getField(d, "mPort"), "mType", 0x80000004);
    return d;
  }

  @Test
  public void outputOnlyBluetoothAndNonMicrophoneRoutesAreExcluded() {
    var app = RuntimeEnvironment.getApplication();
    var manager = app.getSystemService(AudioManager.class);
    var shadow = Shadows.shadowOf(manager);
    shadow.setInputDevices(
        List.of(
            device(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            device(AudioDeviceInfo.TYPE_USB_DEVICE),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            device(AudioDeviceInfo.TYPE_FM_TUNER)));
    var devices = new AndroidAudioDevices(app).list();
    assertEquals(2, devices.size());
    assertEquals("builtin", devices.get(0).key());
    assertEquals(AudioInputController.Kind.USB, devices.get(1).kind());
  }

  @Test
  public void communicationOnlyLeHeadsetCanBeSelectedBeforeItsInputAppears() {
    var app = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
    var manager = app.getSystemService(AudioManager.class);
    Shadows.shadowOf(manager)
        .setAvailableCommunicationDevices(List.of(device(AudioDeviceInfo.TYPE_BLE_HEADSET)));
    var devices = new AndroidAudioDevices(app).list();
    assertEquals(1, devices.size());
    assertEquals(AudioInputController.Kind.BLUETOOTH, devices.get(0).kind());
    assertTrue(devices.get(0).key().startsWith("bt:"));
  }

  @Test
  public void identicalUsbIdentitiesAreMarkedAmbiguousWithoutSavingTransientIds() {
    var app = RuntimeEnvironment.getApplication();
    var manager = app.getSystemService(AudioManager.class);
    Shadows.shadowOf(manager)
        .setInputDevices(
            List.of(
                device(AudioDeviceInfo.TYPE_USB_DEVICE), device(AudioDeviceInfo.TYPE_USB_DEVICE)));
    var devices = new AndroidAudioDevices(app).list();
    assertEquals(1, devices.size());
    assertTrue(devices.get(0).ambiguous());
    assertEquals(71, devices.get(0).key().length());
  }

  @Test
  public void deniedBluetoothPermissionStillAllowsOtherSources() {
    var app = RuntimeEnvironment.getApplication();
    var manager = app.getSystemService(AudioManager.class);
    Shadows.shadowOf(manager)
        .setInputDevices(
            List.of(
                device(AudioDeviceInfo.TYPE_USB_DEVICE),
                device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));
    Shadows.shadowOf(manager)
        .setAvailableCommunicationDevices(List.of(device(AudioDeviceInfo.TYPE_BLE_HEADSET)));
    var devices = new AndroidAudioDevices(app).list();
    assertEquals(1, devices.size());
    assertEquals(AudioInputController.Kind.USB, devices.get(0).kind());
  }

  AudioDeviceInfo bluetooth(String address) {
    AudioDeviceInfo d = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    Object port = org.robolectric.util.ReflectionHelpers.getField(d, "mPort");
    org.robolectric.util.ReflectionHelpers.setField(port, "mAddress", address);
    return d;
  }

  @Test
  public void privacyMaskedAndFullBluetoothAddressKeepSameSourceAfterPermissionRefresh() {
    AudioDeviceInfo masked = bluetooth("XX:XX:XX:XX:12:34"), full = bluetooth("AA:BB:CC:DD:12:34");
    assertEquals(AndroidAudioDevices.key(masked), AndroidAudioDevices.key(full));
    assertNotEquals(
        AndroidAudioDevices.key(masked), AndroidAudioDevices.key(bluetooth("AA:BB:CC:DD:56:78")));
    var app = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
    var manager = app.getSystemService(AudioManager.class);
    Shadows.shadowOf(manager).setAvailableCommunicationDevices(List.of(masked));
    var catalog = new AndroidAudioDevices(app);
    String selected = catalog.list().get(0).key();
    Shadows.shadowOf(manager).setInputDevices(List.of(full));
    Shadows.shadowOf(manager).setAvailableCommunicationDevices(List.of(full));
    assertEquals(1, catalog.list().size());
    assertEquals(selected, catalog.list().get(0).key());
    assertFalse(catalog.list().get(0).ambiguous());
  }

  @Test
  public void indistinguishableCommunicationOnlyHeadsetsAreRejected() {
    var app = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
    var manager = app.getSystemService(AudioManager.class);
    Shadows.shadowOf(manager)
        .setAvailableCommunicationDevices(
            List.of(bluetooth("AA:BB:CC:DD:12:34"), bluetooth("FF:EE:DD:CC:12:34")));
    var devices = new AndroidAudioDevices(app).list();
    assertEquals(1, devices.size());
    assertTrue(devices.get(0).ambiguous());
  }
}
