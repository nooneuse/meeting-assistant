package io.github.meetingassistant.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.google.gson.Gson;
import io.github.meetingassistant.core.ProviderConfig;
import io.github.meetingassistant.core.UserSettings;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials are encrypted separately from the serializable public settings. */
final class SettingsRepository {
  private static final String ALIAS = "meeting-assistant-credentials-v1";
  private final SharedPreferences prefs;
  private final Context localized;
  private final Gson gson = new Gson();

  SettingsRepository(Context context) {
    this(context, "settings-v1");
  }

  SettingsRepository(Context context, String preferencesName) {
    localized = UiLocale.wrap(context);
    prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
  }

  UserSettings load() {
    String json = prefs.getString("settings", null);
    if (json == null) {
      UserSettings s = UserSettings.initial();
      s.find("algorithm").name =
          localized.getString(io.github.meetingassistant.R.string.default_algorithm_name);
      s.find("algorithm").prompt =
          localized.getString(io.github.meetingassistant.R.string.default_algorithm_prompt);
      s.find("finance").name =
          localized.getString(io.github.meetingassistant.R.string.default_finance_name);
      s.find("finance").prompt =
          localized.getString(io.github.meetingassistant.R.string.default_finance_prompt);
      save(s);
      return s;
    }
    try {
      UserSettings s = gson.fromJson(json, UserSettings.class);
      if (s == null || s.provider == null || s.profiles == null || s.bindings == null)
        throw new IllegalStateException();
      if (s.find(s.selectedId) == null) s.selectedId = "";
      if (s.audio == null) s.audio = new io.github.meetingassistant.core.AudioInputSettings();
      s.audio.repair();
      if (s.image == null) s.image = new io.github.meetingassistant.core.ImageSettings();
      if (s.context == null) s.context = new io.github.meetingassistant.core.ContextSettings();
      try {
        s.context.validate();
      } catch (IllegalArgumentException invalid) {
        s.context = new io.github.meetingassistant.core.ContextSettings();
      }
      return s;
    } catch (RuntimeException e) {
      throw new IllegalStateException("本地设置损坏，请清除应用数据后重新配置");
    }
  }

  void save(UserSettings settings) {
    if (!prefs.edit().putString("settings", gson.toJson(settings)).commit())
      throw new IllegalStateException("本地设置保存失败");
  }

  ProviderConfig.Credentials keys() {
    try {
      return new ProviderConfig.Credentials(
          decrypt(prefs.getString("asr-key", "")), decrypt(prefs.getString("text-key", "")));
    } catch (Exception e) {
      throw new IllegalStateException("无法读取加密密钥，请在设置中清除后重新输入");
    }
  }

  boolean hasKey(String which) {
    return !prefs.getString(which + "-key", "").isEmpty();
  }

  String imageKey() {
    try {
      return decrypt(prefs.getString("image-key", ""));
    } catch (Exception e) {
      throw new IllegalStateException("无法读取加密密钥，请在设置中清除后重新输入");
    }
  }

  ProviderConfig.Credentials textKeys() {
    try {
      return new ProviderConfig.Credentials("", decrypt(prefs.getString("text-key", "")));
    } catch (Exception e) {
      throw new IllegalStateException("无法读取加密密钥，请在设置中清除后重新输入");
    }
  }

  void saveImageSettings(UserSettings settings, String newKey) {
    saveModels(settings, "", "", newKey);
  }

  void saveModels(UserSettings settings, String newAsrKey, String newTextKey) {
    saveModels(settings, newAsrKey, newTextKey, "");
  }

  /** All settings and supplied credentials commit together; blank secrets retain saved keys. */
  void saveModels(UserSettings settings, String newAsrKey, String newTextKey, String newImageKey) {
    try {
      SharedPreferences.Editor editor = prefs.edit();
      if (!newAsrKey.trim().isEmpty()) editor.putString("asr-key", encrypt(newAsrKey.trim()));
      if (!newTextKey.trim().isEmpty()) editor.putString("text-key", encrypt(newTextKey.trim()));
      if (!newImageKey.trim().isEmpty()) editor.putString("image-key", encrypt(newImageKey.trim()));
      editor.putString("settings", gson.toJson(settings));
      if (!editor.commit()) throw new IllegalStateException();
    } catch (Exception e) {
      throw new IllegalStateException("模型设置或加密密钥保存失败");
    }
  }

  void clearKeys() {
    if (!prefs.edit().remove("asr-key").remove("text-key").remove("image-key").commit())
      throw new IllegalStateException("密钥清除失败");
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }

  private String encrypt(String text) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(
            cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
  }

  private String decrypt(String blob) throws Exception {
    if (blob.isEmpty()) return "";
    String[] parts = blob.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }
}
