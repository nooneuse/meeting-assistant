package io.github.meetingassistant.core;

import java.util.*;

/** Encrypted folder metadata. No model context or credentials live at collection scope. */
public final class TopicCatalog {
  public static final String DEFAULT = "default";
  public int version = 1;
  public String selectedCollection = "";
  public List<Collection> collections = new ArrayList<>();
  public Set<String> expanded = new LinkedHashSet<>();
  public Map<String, String> remembered = new LinkedHashMap<>();

  public TopicCatalog() {
    Collection c = new Collection();
    c.id = DEFAULT;
    collections.add(c);
    expanded.add(DEFAULT);
  }

  public static final class Collection {
    public String id = UUID.randomUUID().toString(), name = "";
    public long createdAt = System.currentTimeMillis(), updatedAt = createdAt;
  }

  public Collection find(String id) {
    for (Collection c : collections) if (c.id.equals(id)) return c;
    return null;
  }

  public List<Collection> ordered() {
    List<Collection> out = new ArrayList<>(collections);
    out.sort(
        Comparator.comparing((Collection c) -> DEFAULT.equals(c.id))
            .thenComparing(Comparator.comparingLong((Collection c) -> c.updatedAt).reversed())
            .thenComparing(c -> c.id));
    return out;
  }

  public void validate() {
    if (version != 1
        || collections == null
        || expanded == null
        || remembered == null
        || selectedCollection == null) throw new IllegalStateException("议题集记录损坏，原数据已保留");
    Set<String> ids = new HashSet<>();
    for (Collection c : collections)
      if (c == null
          || c.id == null
          || c.id.isEmpty()
          || c.name == null
          || !ids.add(c.id)
          || c.createdAt < 0
          || c.updatedAt < 0) throw new IllegalStateException("议题集记录损坏，原数据已保留");
    if (!ids.contains(DEFAULT)
        || !selectedCollection.isEmpty() && !ids.contains(selectedCollection))
      throw new IllegalStateException("议题集记录损坏，原数据已保留");
    expanded.retainAll(ids);
    remembered.keySet().retainAll(ids);
  }
}
