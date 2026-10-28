package io.github.meetingassistant.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class UserSettings {
  public int version = 1;
  public ProviderConfig provider = new ProviderConfig();
  public ImageSettings image = new ImageSettings();
  public AudioInputSettings audio = new AudioInputSettings();
  public ContextSettings context = new ContextSettings();
  public List<Profile> profiles = new ArrayList<>();
  public String selectedId = "";
  public boolean keyboardEnabled = true;
  public boolean automatic = false;
  public Map<String, Integer> bindings = new LinkedHashMap<>();

  public static final String ANSWER_UP = "answer.up",
      ANSWER_DOWN = "answer.down",
      RECORD = "record.toggle",
      AWAKE = "awake.toggle",
      AUTO = "auto.toggle",
      MEETING_NEW = "meeting.new",
      MEETING_PREVIOUS = "meeting.previous",
      MEETING_NEXT = "meeting.next",
      COLLECTION_PREVIOUS = "collection.previous",
      COLLECTION_NEXT = "collection.next",
      TRANSCRIPT_TOGGLE = "transcript.toggle",
      IMAGE_CAPTURE = "image.capture",
      IMAGE_TOGGLE = "image.toggle",
      NONE = "profile.none",
      TRANSCRIPT_UP = "transcript.up",
      TRANSCRIPT_DOWN = "transcript.down";

  public static UserSettings initial() {
    UserSettings s = new UserSettings();
    s.profiles.add(
        new Profile("algorithm", "算法讨论", "算法讨论，主要目标是回答题目，语言偏好是 C++。先给思路，再给代码、复杂度和边界条件。"));
    s.profiles.add(
        new Profile(
            "finance", "金融会议", "金融会议，主要目标是提出质疑，重点围绕负债情况。优先列出需要核实的问题，区分已知事实与信息缺口，不臆测未提及的数据。"));
    s.selectedId = "algorithm";
    s.resetBindings();
    return s;
  }

  public void resetBindings() {
    bindings.clear();
    // Android KeyEvent codes, kept here to allow JVM-only tests.
    bindings.put(ANSWER_UP, 19);
    bindings.put(ANSWER_DOWN, 20);
    bindings.put(RECORD, 62);
    bindings.put(AWAKE, 40);
    bindings.put(NONE, 7);
    bindings.put(TRANSCRIPT_UP, 92);
    bindings.put(TRANSCRIPT_DOWN, 93);
    if (find("algorithm") != null) bindings.put(profileAction("algorithm"), 8);
    if (find("finance") != null) bindings.put(profileAction("finance"), 9);
  }

  public static String profileAction(String id) {
    return "profile:" + id;
  }

  public Profile find(String id) {
    for (Profile p : profiles) if (p.id.equals(id)) return p;
    return null;
  }

  public Profile selected() {
    return find(selectedId);
  }

  public Profile snapshot() {
    Profile p = selected();
    return p == null ? new Profile("", "不使用回答偏好", "") : new Profile(p.id, p.name, p.prompt);
  }

  public Profile saveProfile(String id, String name, String prompt) {
    if (name.trim().isEmpty() || prompt.trim().isEmpty())
      throw new IllegalArgumentException("名称和回答偏好正文不能为空");
    if (name.length() > 60 || prompt.length() > 6000)
      throw new IllegalArgumentException("名称最多 60 字符，正文最多 6000 字符");
    Profile p = find(id);
    if (p == null) {
      p = new Profile(UUID.randomUUID().toString(), name.trim(), prompt.trim());
      profiles.add(p);
    } else {
      p.name = name.trim();
      p.prompt = prompt.trim();
    }
    selectedId = p.id;
    return p;
  }

  public Deleted delete(String id) {
    Profile p = find(id);
    if (p == null) return null;
    int index = profiles.indexOf(p);
    profiles.remove(p);
    if (id.equals(selectedId)) selectedId = "";
    return new Deleted(p, index, bindings.remove(profileAction(id)));
  }

  public void undo(Deleted d) {
    if (d == null || find(d.profile.id) != null) return;
    profiles.add(Math.min(d.index, profiles.size()), d.profile);
    if (d.key != null && !bindings.containsValue(d.key))
      bindings.put(profileAction(d.profile.id), d.key);
  }

  public String conflict(String action, int key) {
    for (Map.Entry<String, Integer> e : bindings.entrySet())
      if (!e.getKey().equals(action) && e.getValue() == key) return e.getKey();
    return null;
  }

  public void bind(String action, Integer key) {
    if (key == null) {
      bindings.remove(action);
      return;
    }
    if (key <= 0 || key == 3 || key == 4 || key == 26 || key == 111)
      throw new IllegalArgumentException("该系统键无法绑定，请选择其他按键");
    if (conflict(action, key) != null) throw new IllegalArgumentException("按键已被其他功能使用，请先清除原绑定");
    bindings.put(action, key);
  }

  public String actionFor(int key, int repeat, boolean editing, boolean composing) {
    if (!keyboardEnabled || editing || composing) return null;
    for (Map.Entry<String, Integer> e : bindings.entrySet())
      if (e.getValue() == key) {
        boolean scroll =
            e.getKey().equals(ANSWER_UP)
                || e.getKey().equals(ANSWER_DOWN)
                || e.getKey().equals(TRANSCRIPT_UP)
                || e.getKey().equals(TRANSCRIPT_DOWN);
        return repeat == 0 || scroll ? e.getKey() : "consumed";
      }
    return null;
  }

  public static final class Profile {
    public String id, name, prompt;

    public Profile(String id, String name, String prompt) {
      this.id = id;
      this.name = name;
      this.prompt = prompt;
    }
  }

  public static final class Deleted {
    public final Profile profile;
    public final int index;
    public final Integer key;

    Deleted(Profile p, int i, Integer k) {
      profile = p;
      index = i;
      key = k;
    }
  }
}
