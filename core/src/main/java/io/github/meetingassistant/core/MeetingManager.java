package io.github.meetingassistant.core;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Per-meeting session ownership. Disk work never executes in a speech/model callback. */
public final class MeetingManager implements AutoCloseable {
  public interface Store {
    List<MeetingRecord> load();

    default TopicCatalog catalog() {
      return null;
    }

    default void saveCatalog(TopicCatalog catalog) {}

    /** Production and MemoryStore override this with atomic rollback. */
    default void structure(
        List<MeetingRecord> upserts, List<String> deletes, TopicCatalog catalog, String selected) {
      for (MeetingRecord r : upserts) save(r);
      for (String id : deletes) delete(id);
      saveCatalog(catalog);
      if (selected != null) select(selected);
    }

    String selected();

    void save(MeetingRecord record);

    void select(String id);

    void delete(String id);

    default void replaceAndDelete(String id, MeetingRecord replacement) {
      save(replacement);
      delete(id);
      select(replacement.id);
    }

    default void deleteAndSelect(String id, String selectedId) {
      delete(id);
      select(selectedId);
    }
  }

  public static class MemoryStore implements Store {
    private final Map<String, MeetingRecord> records = new LinkedHashMap<>();
    private String selected = "";
    private final Gson gson = new Gson();
    private TopicCatalog catalog;

    public synchronized TopicCatalog catalog() {
      return catalog == null ? null : gson.fromJson(gson.toJson(catalog), TopicCatalog.class);
    }

    public synchronized void saveCatalog(TopicCatalog c) {
      catalog = gson.fromJson(gson.toJson(c), TopicCatalog.class);
    }

    public synchronized void structure(
        List<MeetingRecord> upserts, List<String> deletes, TopicCatalog catalog, String selected) {
      Map<String, MeetingRecord> backup = new LinkedHashMap<>(records);
      String oldSelected = this.selected;
      TopicCatalog oldCatalog = this.catalog;
      try {
        Store.super.structure(upserts, deletes, catalog, selected);
      } catch (RuntimeException e) {
        records.clear();
        records.putAll(backup);
        this.selected = oldSelected;
        this.catalog = oldCatalog;
        throw e;
      }
    }

    public synchronized List<MeetingRecord> load() {
      List<MeetingRecord> out = new ArrayList<>();
      for (MeetingRecord r : records.values()) out.add(copy(r));
      return out;
    }

    private MeetingRecord copy(MeetingRecord r) {
      return gson.fromJson(gson.toJson(r), MeetingRecord.class);
    }

    public synchronized String selected() {
      return selected;
    }

    public synchronized void save(MeetingRecord r) {
      records.put(r.id, copy(r));
    }

    public synchronized void select(String id) {
      selected = id;
    }

    public synchronized void delete(String id) {
      records.remove(id);
    }

    @Override
    public synchronized void replaceAndDelete(String id, MeetingRecord replacement) {
      Map<String, MeetingRecord> backup = new LinkedHashMap<>(records);
      String before = selected;
      try {
        Store.super.replaceAndDelete(id, replacement);
      } catch (RuntimeException e) {
        records.clear();
        records.putAll(backup);
        selected = before;
        throw e;
      }
    }

    @Override
    public synchronized void deleteAndSelect(String id, String selectedId) {
      Map<String, MeetingRecord> backup = new LinkedHashMap<>(records);
      String before = selected;
      try {
        Store.super.deleteAndSelect(id, selectedId);
      } catch (RuntimeException e) {
        records.clear();
        records.putAll(backup);
        selected = before;
        throw e;
      }
    }
  }

  private static final class Entry {
    MeetingRecord record;
    SessionEngine engine;
    Consumer<SessionEngine.Snapshot> listener;
    long saveVersion, scheduledAt;
    java.util.concurrent.ScheduledFuture<?> scheduled;
    boolean deleting;

    Entry(MeetingRecord r) {
      record = r;
    }
  }

  private final Object lock = new Object();
  private final Store store;
  private final Supplier<SessionEngine> factory;
  private final Gson gson = new Gson();
  private final Map<String, Entry> entries = new LinkedHashMap<>();
  private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService io =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "meeting-store");
            t.setDaemon(true);
            return t;
          });
  private String currentId = "", pendingTarget, pendingSource;
  private String error = "";
  private boolean closed, unavailable;
  private TopicCatalog catalog = new TopicCatalog();
  private boolean structural;

  public MeetingManager(
      Store store,
      Supplier<SessionEngine> factory,
      UserSettings.Profile profile,
      boolean automatic) {
    this.store = store;
    this.factory = factory;
    try {
      TopicCatalog loadedCatalog = store.catalog();
      boolean migrating = loadedCatalog == null;
      if (!migrating) {
        loadedCatalog.validate();
        catalog = loadedCatalog;
      }
      for (MeetingRecord r : store.load()) {
        if ((r.version != 1 && r.version != 2 && r.version != 3)
            || r.id == null
            || r.id.isEmpty()
            || r.current == null
            || r.rounds == null
            || r.view == null) throw new IllegalStateException();
        if (r.current.state == null
            || r.current.analysis == null
            || r.current.segments == null
            || r.current.history == null
            || r.current.images == null
            || r.current.answerImages == null
            || r.current.pendingImages == null
            || r.current.preference == null
            || r.current.selected == null
            || r.current.answer == null
            || r.current.status == null
            || r.current.analysisStatus == null
            || r.current.finalText == null
            || r.title == null) throw new IllegalStateException();
        for (SpeechProvider.Segment segment : r.current.segments)
          if (segment == null || segment.text == null) throw new IllegalStateException();
        for (ImageEvidence image : r.current.images)
          if (image == null
              || image.id == null
              || image.state == null
              || image.structure == null
              || image.error == null
              || image.preference == null) throw new IllegalStateException();
        if (r.current.busy()) {
          r.current.state = SessionEngine.State.ERROR;
          r.current.analysis =
              r.current.finalized
                  ? SessionEngine.AnalysisState.ERROR
                  : SessionEngine.AnalysisState.IDLE;
          r.current.status = "上次任务已中断 · 可继续会议";
          r.current.analysisStatus = "未恢复录音或自动重试";
        }
        if (migrating && r.version >= 3 && !TopicCatalog.DEFAULT.equals(r.collectionId))
          throw new IllegalStateException("议题归属损坏，原数据已保留");
        if (migrating || r.version < 3) r.collectionId = TopicCatalog.DEFAULT;
        if (r.collectionId == null || catalog.find(r.collectionId) == null)
          throw new IllegalStateException("议题归属损坏，原数据已保留");
        r.version = 3;
        entries.put(r.id, new Entry(r));
      }
      currentId = store.selected();
      if (!entries.containsKey(currentId))
        currentId =
            entries.values().stream()
                .max(
                    Comparator.comparing((Entry e) -> !e.record.draft)
                        .thenComparingLong(e -> e.record.updatedAt))
                .map(e -> e.record.id)
                .orElse("");
      if (!currentId.isEmpty() && !entries.get(currentId).record.draft) {
        catalog.selectedCollection = entries.get(currentId).record.collectionId;
        catalog.remembered.put(catalog.selectedCollection, currentId);
        catalog.expanded.add(catalog.selectedCollection);
      }
      if (migrating) {
        for (Entry entry : entries.values()) {
          TopicCatalog.Collection c = catalog.find(entry.record.collectionId);
          c.updatedAt = Math.max(c.updatedAt, entry.record.updatedAt);
        }
        store.structure(
            entries.values().stream()
                .map(e -> copy(e.record))
                .collect(java.util.stream.Collectors.toList()),
            List.of(),
            copyCatalog(catalog),
            currentId.isEmpty() ? null : currentId);
      }
    } catch (RuntimeException e) {
      unavailable = true;
      error = "会议记录读取失败，原数据已保留；请重启应用重试";
    }
    if (currentId.isEmpty()) {
      MeetingRecord r = blank(profile, automatic);
      r.draft = true;
      entries.put(r.id, new Entry(r));
      currentId = r.id;
      if (!unavailable) {
        saveSoon(r.id, 0);
        selectOnDisk(r.id);
      }
    }
    currentEngine();
    for (String id : new ArrayList<>(entries.keySet())) saveSoon(id, 0);
  }

  private MeetingRecord blank(UserSettings.Profile p, boolean automatic) {
    MeetingRecord r = new MeetingRecord();
    r.collectionId =
        catalog.selectedCollection.isEmpty() ? TopicCatalog.DEFAULT : catalog.selectedCollection;
    r.title =
        "新议题 "
            + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(r.createdAt));
    r.current.selected = new UserSettings.Profile(p.id, p.name, p.prompt);
    r.current.automatic = automatic;
    return r;
  }

  private MeetingRecord copy(MeetingRecord r) {
    return gson.fromJson(gson.toJson(r), MeetingRecord.class);
  }

  public String currentId() {
    synchronized (lock) {
      return currentId;
    }
  }

  public String error() {
    synchronized (lock) {
      return error;
    }
  }

  public boolean available() {
    synchronized (lock) {
      return !unavailable && !closed;
    }
  }

  public boolean navigating() {
    synchronized (lock) {
      return pendingTarget != null;
    }
  }

  public void observe(Runnable listener) {
    observers.addIfAbsent(listener);
  }

  public void detach(Runnable listener) {
    observers.remove(listener);
  }

  private void changed() {
    for (Runnable r : observers) r.run();
  }

  public MeetingRecord current() {
    return get(currentId());
  }

  public MeetingRecord get(String id) {
    synchronized (lock) {
      Entry e = entries.get(id);
      return e == null ? null : copy(e.record);
    }
  }

  public static final class Summary {
    public final String id, title, collectionId;
    public final long updatedAt, createdAt;
    public final boolean defaultTitle;
    public final SessionEngine.State state;

    Summary(MeetingRecord r) {
      id = r.id;
      title = r.title;
      collectionId = r.collectionId;
      updatedAt = r.updatedAt;
      createdAt = r.createdAt;
      defaultTitle = !r.customTitle && !r.autoNamed;
      state = r.current.state;
    }

    public boolean speechActive() {
      return state == SessionEngine.State.CONNECTING
          || state == SessionEngine.State.LISTENING
          || state == SessionEngine.State.FINALIZING;
    }

    public boolean busy() {
      return speechActive() || state == SessionEngine.State.ANALYZING;
    }
  }

  public List<Summary> summaries() {
    synchronized (lock) {
      List<Summary> out = new ArrayList<>();
      for (Entry e : entries.values()) if (!e.record.draft) out.add(new Summary(e.record));
      out.sort(
          Comparator.comparingLong((Summary s) -> s.updatedAt).reversed().thenComparing(s -> s.id));
      return out;
    }
  }

  public List<MeetingRecord> list() {
    synchronized (lock) {
      List<MeetingRecord> out = new ArrayList<>();
      for (Entry e : entries.values()) if (!e.record.draft) out.add(copy(e.record));
      out.sort(
          Comparator.comparingLong((MeetingRecord r) -> r.updatedAt)
              .reversed()
              .thenComparing(r -> r.id));
      return out;
    }
  }

  public SessionEngine currentEngine() {
    SessionEngine e = engine(currentId());
    if (!navigating()) e.resumeContextWork();
    return e;
  }

  public boolean acceptsImage(String id, SessionEngine owner) {
    synchronized (lock) {
      Entry entry = entries.get(id);
      return !closed && entry != null && !entry.deleting && entry.engine == owner;
    }
  }

  private SessionEngine engine(String id) {
    Entry e;
    boolean created = false;
    synchronized (lock) {
      e = entries.get(id);
      if (e == null) throw new IllegalArgumentException("会议不存在");
      if (e.engine == null) {
        e.engine = factory.get();
        e.engine.restore(e.record.current);
        e.engine.loadContextArchive(e.record.rounds);
        // Restore normalization is not a user edit and must not bump folder dates.
        e.record.current = e.engine.saveState();
        created = true;
      }
    }
    if (created) {
      SessionEngine owned = e.engine;
      e.listener = s -> onSnapshot(id, owned);
      owned.observe(e.listener);
    }
    return e.engine;
  }

  private void onSnapshot(String id, SessionEngine owned) {
    SessionEngine.SavedState s = owned.saveState();
    boolean switchNow = false;
    long delay;
    synchronized (lock) {
      Entry e = entries.get(id);
      if (closed || e == null || e.deleting || e.engine != owned) return;
      SessionEngine.SavedState before = e.record.current;
      if (s.state == SessionEngine.State.CONNECTING
          || s.startedAt > 0
          || !s.segments.isEmpty()
          || !s.answer.isEmpty()
          || !s.images.isEmpty()) e.record.draft = false;
      if (s.state == SessionEngine.State.CONNECTING
          && before.state != SessionEngine.State.CONNECTING) {
        if (!before.segments.isEmpty() || !before.answer.isEmpty() && !before.imageAnalysisRunning)
          e.record.rounds.add(new MeetingRecord.Round(e.record.roundId, before));
        e.record.roundId = UUID.randomUUID().toString();
      }
      boolean stableChanged = !before.stableText().equals(s.stableText());
      boolean imagesChanged = before.imageStateVersion != s.imageStateVersion;
      boolean contentChanged =
          !before.transcript(false).equals(s.transcript(false))
              || !before.answer.equals(s.answer)
              || before.state != s.state
              || before.analysis != s.analysis
              || !java.util.Objects.equals(before.pendingAnswer, s.pendingAnswer);
      e.record.current = s;
      long previousModified = e.record.updatedAt;
      if (imagesChanged
          || stableChanged
          || before.state != s.state
          || before.analysis != s.analysis
          || !java.util.Objects.equals(before.pendingAnswer, s.pendingAnswer)
          || !s.busy() && contentChanged) e.record.updatedAt = System.currentTimeMillis();
      if (!e.record.customTitle
          && !e.record.autoNamed
          && (e.record.title.startsWith("新会议 ") || e.record.title.startsWith("新议题 "))
          && !s.stableText().trim().isEmpty()) {
        String first = s.stableText().trim().split("[\\n。！？!?]", 2)[0];
        if (!first.isEmpty()) {
          e.record.title = first.substring(0, Math.min(24, first.length()));
          e.record.autoNamed = true;
        }
      }
      if (!e.record.customTitle && !e.record.autoNamed && !s.images.isEmpty()) {
        for (ImageEvidence image : s.images)
          if (image.state == ImageEvidence.State.READY && !image.text().trim().isEmpty()) {
            String first = image.text().trim().split("[\\n。！？!?]", 2)[0];
            e.record.title = first.substring(0, Math.min(24, first.length()));
            e.record.autoNamed = true;
            break;
          }
      }
      if (previousModified != e.record.updatedAt
          || !e.record.draft && before.startedAt != s.startedAt)
        touch(e.record.collectionId, e.record.updatedAt);
      if (!e.record.draft && id.equals(currentId)) {
        catalog.selectedCollection = e.record.collectionId;
        catalog.remembered.put(e.record.collectionId, id);
      }
      delay = imagesChanged || stableChanged || !s.busy() ? 0 : 1000;
      if (id.equals(pendingSource)
          && !s.speechActive()
          && pendingTarget != null
          && entries.containsKey(pendingTarget)) {
        currentId = pendingTarget;
        selectedTopic(entries.get(currentId).record);
        pendingTarget = null;
        pendingSource = null;
        switchNow = true;
      }
    }
    saveSoon(id, delay);
    if (switchNow) selectOnDisk(currentId());
    changed();
  }

  public String create(UserSettings.Profile p, boolean automatic) {
    requireAvailable();
    return create(p, automatic, "");
  }

  public String create(UserSettings.Profile p, boolean automatic, String carry) {
    requireNavigationReady();
    if (ContextMemory.estimate(carry) > 1000) throw new IllegalArgumentException("延续摘录过长");
    MeetingRecord r = blank(p, automatic);
    r.current.contextMemory.carry = carry;
    synchronized (lock) {
      entries.put(r.id, new Entry(r));
      touch(r.collectionId, r.updatedAt);
    }
    saveSoon(r.id, 0);
    engine(r.id);
    changed();
    return r.id;
  }

  /** Caller confirms stopping beforehand. Returns true once selected, false while ASR drains. */
  public boolean switchTo(String id) {
    requireNavigationReady();
    synchronized (lock) {
      if (entries.get(id) == null || entries.get(id).deleting)
        throw new IllegalStateException("会议不存在或正在删除");
    }
    engine(id);
    String source = currentId();
    if (source.equals(id)) {
      synchronized (lock) {
        selectedTopic(entries.get(id).record);
      }
      selectOnDisk(id);
      changed();
      return true;
    }
    SessionEngine old = engine(source);
    old.suspendContextWork();
    boolean speech = old.saveState().speechActive();
    synchronized (lock) {
      if (speech) {
        pendingSource = source;
        pendingTarget = id;
      } else {
        currentId = id;
        selectedTopic(entries.get(id).record);
        pendingSource = null;
        pendingTarget = null;
      }
    }
    if (speech) {
      old.stop();
      onSnapshot(source, old);
      return currentId().equals(id);
    }
    selectOnDisk(id);
    discardDraft(source);
    changed();
    return true;
  }

  private void discardDraft(String id) {
    Entry removed;
    synchronized (lock) {
      Entry e = entries.get(id);
      if (id.equals(currentId) || e == null || !e.record.draft || e.deleting) return;
      removed = entries.remove(id);
      if (removed.scheduled != null) removed.scheduled.cancel(false);
    }
    if (removed.engine != null) {
      removed.engine.detach(removed.listener);
      removed.engine.close();
    }
    io.execute(
        () -> {
          try {
            store.delete(id);
          } catch (RuntimeException e) {
            failed("会议草稿清理失败，请重启应用重试");
          }
        });
  }

  public void cancelNavigation() {
    synchronized (lock) {
      pendingTarget = null;
      pendingSource = null;
    }
    changed();
  }

  public void rename(String id, String name) {
    requireAvailable();
    String title = name.trim();
    if (title.isEmpty() || title.length() > 80)
      throw new IllegalArgumentException("会议名称应为 1–80 字符");
    synchronized (lock) {
      Entry e = entries.get(id);
      if (e == null) return;
      e.record.title = title;
      e.record.draft = false;
      e.record.customTitle = true;
      e.record.updatedAt = System.currentTimeMillis();
      touch(e.record.collectionId, e.record.updatedAt);
      if (id.equals(currentId)) selectedTopic(e.record);
    }
    saveSoon(id, 0);
    changed();
  }

  public void saveView(MeetingRecord.ViewState view) {
    String id = currentId();
    synchronized (lock) {
      Entry e = entries.get(id);
      if (e == null || e.deleting) return;
      e.record.view = gson.fromJson(gson.toJson(view), MeetingRecord.ViewState.class);
    }
    saveSoon(id, 0);
  }

  private TopicCatalog copyCatalog(TopicCatalog c) {
    return gson.fromJson(gson.toJson(c), TopicCatalog.class);
  }

  public TopicCatalog catalog() {
    synchronized (lock) {
      return copyCatalog(catalog);
    }
  }

  public String selectedCollection() {
    synchronized (lock) {
      return catalog.selectedCollection;
    }
  }

  public String creationCollection() {
    String selected = selectedCollection();
    return selected.isEmpty() ? TopicCatalog.DEFAULT : selected;
  }

  public List<Summary> collectionTopics(String collection) {
    return summaries().stream()
        .filter(s -> s.collectionId.equals(collection))
        .collect(java.util.stream.Collectors.toList());
  }

  public boolean structureBusy() {
    synchronized (lock) {
      return structural;
    }
  }

  private void touch(String id, long at) {
    TopicCatalog.Collection c = catalog.find(id);
    if (c != null) c.updatedAt = Math.max(c.updatedAt, at);
  }

  private void selectedTopic(MeetingRecord r) {
    catalog.selectedCollection = r.collectionId;
    catalog.expanded.add(r.collectionId);
    if (!r.draft) catalog.remembered.put(r.collectionId, r.id);
  }

  private void requireNavigationReady() {
    requireAvailable();
    synchronized (lock) {
      if (structural || pendingTarget != null) throw new IllegalStateException("议题操作进行中，请稍候");
    }
  }

  public void setExpanded(String id, boolean expanded) {
    requireAvailable();
    if (structureBusy()) return;
    synchronized (lock) {
      if (catalog.find(id) == null) return;
      if (expanded) catalog.expanded.add(id);
      else catalog.expanded.remove(id);
    }
    selectOnDisk(currentId());
    changed();
  }

  /** Caller invokes only after the stop-navigation confirmation. No input starts here. */
  public String collectionTarget(
      String id, boolean restore, UserSettings.Profile p, boolean automatic) {
    requireNavigationReady();
    synchronized (lock) {
      if (catalog.find(id) == null) throw new IllegalArgumentException("议题集不存在");
      String remembered = catalog.remembered.get(id);
      Entry prior = entries.get(remembered);
      if (restore
          && prior != null
          && !prior.deleting
          && !prior.record.draft
          && prior.record.collectionId.equals(id)) return remembered;
      if (entries.get(currentId).record.draft
          && entries.get(currentId).record.collectionId.equals(id)) return currentId;
      MeetingRecord r = blank(p, automatic);
      r.collectionId = id;
      r.draft = true;
      entries.put(r.id, new Entry(r));
      saveSoon(r.id, 0);
      return r.id;
    }
  }

  private static String collectionName(String name) {
    String value = name.trim();
    if (value.isEmpty() || value.length() > 80)
      throw new IllegalArgumentException("议题集名称应为 1–80 字符");
    return value;
  }

  private record StructurePlan(
      List<MeetingRecord> upserts, List<String> deletes, TopicCatalog catalog, String selected) {}

  /** Prepare and publish under the short memory lock; encryption/SQLite never hold it. */
  private void structureOperation(
      Supplier<StructurePlan> prepare,
      Consumer<StructurePlan> publish,
      Consumer<Boolean> completion) {
    requireNavigationReady();
    synchronized (lock) {
      structural = true;
    }
    io.execute(
        () -> {
          boolean success = false;
          try {
            StructurePlan plan;
            synchronized (lock) {
              plan = prepare.get();
            }
            store.structure(plan.upserts, plan.deletes, plan.catalog, plan.selected);
            synchronized (lock) {
              publish.accept(plan);
              TopicCatalog next = plan.catalog;
              for (TopicCatalog.Collection c : next.collections) {
                TopicCatalog.Collection live = catalog.find(c.id);
                if (live != null) c.updatedAt = Math.max(c.updatedAt, live.updatedAt);
              }
              catalog = next;
              catalog
                  .remembered
                  .entrySet()
                  .removeIf(
                      item -> {
                        Entry e = entries.get(item.getValue());
                        return e == null
                            || e.record.draft
                            || !e.record.collectionId.equals(item.getKey());
                      });
              Entry current = entries.get(currentId);
              if (current != null && !current.record.draft) {
                catalog.selectedCollection = current.record.collectionId;
                catalog.remembered.put(current.record.collectionId, currentId);
              }
            }
            success = true;
            recovered("议题集操作失败");
          } catch (RuntimeException e) {
            failed("议题集操作失败，原数据已保留，请重试");
          } finally {
            synchronized (lock) {
              structural = false;
            }
            changed();
            if (success) selectOnDisk(currentId());
            completion.accept(success);
          }
        });
  }

  public void createCollection(String name, Consumer<String> completion) {
    String value = collectionName(name);
    TopicCatalog.Collection c = new TopicCatalog.Collection();
    c.name = value;
    structureOperation(
        () -> {
          TopicCatalog next = copyCatalog(catalog);
          next.collections.add(c);
          next.expanded.add(c.id);
          return new StructurePlan(List.of(), List.of(), next, null);
        },
        plan -> {},
        success -> completion.accept(success ? c.id : null));
  }

  public void renameCollection(String id, String name, Consumer<Boolean> completion) {
    String value = collectionName(name);
    structureOperation(
        () -> {
          TopicCatalog next = copyCatalog(catalog);
          TopicCatalog.Collection c = next.find(id);
          if (c == null) throw new IllegalArgumentException("议题集不存在");
          c.name = value;
          c.updatedAt = System.currentTimeMillis();
          return new StructurePlan(List.of(), List.of(), next, null);
        },
        plan -> {},
        completion);
  }

  public void moveTopic(String id, String destination, Consumer<Boolean> completion) {
    structureOperation(
        () -> {
          Entry e = entries.get(id);
          if (e == null || e.deleting || catalog.find(destination) == null)
            throw new IllegalArgumentException("议题集不存在");
          MeetingRecord r = copy(e.record);
          TopicCatalog next = copyCatalog(catalog);
          long at = System.currentTimeMillis();
          next.find(r.collectionId).updatedAt = at;
          next.find(destination).updatedAt = at;
          next.remembered.values().removeIf(id::equals);
          r.collectionId = destination;
          r.updatedAt = at;
          if (id.equals(currentId)) {
            next.selectedCollection = destination;
            next.expanded.add(destination);
            if (!r.draft) next.remembered.put(destination, id);
          }
          return new StructurePlan(List.of(r), List.of(), next, null);
        },
        plan -> {
          MeetingRecord moved = plan.upserts.get(0);
          Entry e = entries.get(id);
          e.record.collectionId = moved.collectionId;
          e.record.updatedAt = Math.max(e.record.updatedAt, moved.updatedAt);
          // Retain any audio/OCR/answer snapshots that arrived while SQLite was writing.
        },
        completion);
  }

  public void mergeCollection(String source, String destination, Consumer<Boolean> completion) {
    if (TopicCatalog.DEFAULT.equals(source) || source.equals(destination))
      throw new IllegalArgumentException("默认议题集不能删除");
    structureOperation(
        () -> {
          TopicCatalog next = copyCatalog(catalog);
          if (next.find(source) == null || next.find(destination) == null)
            throw new IllegalArgumentException("议题集不存在");
          long at = System.currentTimeMillis();
          List<MeetingRecord> moved = new ArrayList<>();
          for (Entry e : entries.values())
            if (e.record.collectionId.equals(source)) {
              if (e.deleting) throw new IllegalStateException();
              MeetingRecord r = copy(e.record);
              r.collectionId = destination;
              moved.add(r);
              if (r.id.equals(currentId) && !r.draft) next.remembered.put(destination, r.id);
            }
          next.collections.removeIf(c -> c.id.equals(source));
          next.expanded.remove(source);
          next.remembered.remove(source);
          next.find(destination).updatedAt = at;
          if (next.selectedCollection.equals(source)) {
            next.selectedCollection = destination;
            next.expanded.add(destination);
          }
          return new StructurePlan(moved, List.of(), next, null);
        },
        plan -> {
          for (MeetingRecord r : plan.upserts) entries.get(r.id).record.collectionId = destination;
        },
        completion);
  }

  public void deleteCollection(String id, boolean clearOnly, Consumer<Boolean> completion) {
    requireNavigationReady();
    if (TopicCatalog.DEFAULT.equals(id) && !clearOnly)
      throw new IllegalArgumentException("默认议题集不能删除");
    List<Entry> removed = new ArrayList<>();
    synchronized (lock) {
      if (catalog.find(id) == null) throw new IllegalArgumentException("议题集不存在");
      for (Entry e : entries.values())
        if (e.record.collectionId.equals(id)) {
          if (e.deleting) throw new IllegalStateException("议题操作进行中，请稍候");
          removed.add(e);
        }
      for (Entry e : removed) e.deleting = true;
    }
    for (Entry e : removed) if (e.engine != null) e.engine.cancel();
    structureOperation(
        () -> {
          TopicCatalog next = copyCatalog(catalog);
          next.remembered.remove(id);
          if (!clearOnly) {
            next.collections.removeIf(c -> c.id.equals(id));
            next.expanded.remove(id);
          } else next.find(id).updatedAt = System.currentTimeMillis();
          boolean selectedRemoved = removed.stream().anyMatch(e -> e.record.id.equals(currentId));
          String fallback = null;
          MeetingRecord replacement = null;
          if (selectedRemoved || next.selectedCollection.equals(id)) {
            String parent = clearOnly ? id : TopicCatalog.DEFAULT;
            String remembered = next.remembered.get(parent);
            Entry previous = entries.get(remembered);
            if (previous != null && !previous.deleting) fallback = remembered;
            else {
              MeetingRecord current = entries.get(currentId).record;
              replacement = blank(current.current.selected, current.current.automatic);
              replacement.collectionId = parent;
              replacement.draft = true;
              fallback = replacement.id;
            }
            next.selectedCollection = parent;
            next.expanded.add(parent);
          }
          return new StructurePlan(
              replacement == null ? List.of() : List.of(replacement),
              removed.stream().map(e -> e.record.id).collect(java.util.stream.Collectors.toList()),
              next,
              fallback);
        },
        plan -> {
          for (Entry e : removed) {
            entries.remove(e.record.id);
            if (e.scheduled != null) e.scheduled.cancel(false);
          }
          for (MeetingRecord r : plan.upserts) entries.put(r.id, new Entry(r));
          if (plan.selected != null) currentId = plan.selected;
        },
        success -> {
          if (success)
            for (Entry e : removed) {
              if (e.engine != null) {
                e.engine.detach(e.listener);
                e.engine.close();
              }
            }
          else {
            synchronized (lock) {
              for (Entry e : removed) e.deleting = false;
            }
            for (Entry e : removed) if (e.engine != null) onSnapshot(e.record.id, e.engine);
          }
          completion.accept(success);
        });
  }

  public String transcript(boolean annotate) {
    return transcript(annotate, "[未定稿] ", "[本轮未完整定稿]\n", "第 %1$d 轮", " · 未完整定稿");
  }

  public String transcript(
      boolean annotate,
      String partialMarker,
      String incompleteMarker,
      String roundLabel,
      String incompleteSuffix) {
    synchronized (lock) {
      MeetingRecord r = entries.get(currentId).record;
      StringBuilder out = new StringBuilder();
      int n = 1;
      for (MeetingRecord.Round round : r.rounds)
        appendRound(
            out,
            n++,
            round.transcript(partialMarker),
            round.complete,
            roundLabel,
            incompleteSuffix);
      String current = r.current.transcript(annotate, partialMarker);
      if (r.rounds.isEmpty())
        return annotate
                && !r.current.finalized
                && r.current.state == SessionEngine.State.ERROR
                && !current.isEmpty()
            ? incompleteMarker + current
            : current;
      appendRound(out, n, current, r.current.finalized, roundLabel, incompleteSuffix);
      return out.toString();
    }
  }

  private static void appendRound(
      StringBuilder out,
      int number,
      String text,
      boolean complete,
      String roundLabel,
      String incompleteSuffix) {
    if (text.isEmpty()) return;
    if (out.length() > 0) out.append("\n\n");
    out.append(String.format(java.util.Locale.ROOT, roundLabel, number));
    if (!complete) out.append(incompleteSuffix);
    out.append('\n').append(text);
  }

  public String answer() {
    synchronized (lock) {
      MeetingRecord r = entries.get(currentId).record;
      if (!r.current.answer.isEmpty()) return r.current.answer;
      for (int i = r.rounds.size() - 1; i >= 0; i--)
        if (!r.rounds.get(i).answer.isEmpty()) return r.rounds.get(i).answer;
      return "";
    }
  }

  public int answerCount() {
    synchronized (lock) {
      MeetingRecord r = entries.get(currentId).record;
      int n = r.current.answer.isEmpty() ? 0 : 1;
      for (MeetingRecord.Round round : r.rounds) if (!round.answer.isEmpty()) n++;
      return n;
    }
  }

  public List<MeetingRecord.Round> answers() {
    synchronized (lock) {
      MeetingRecord r = entries.get(currentId).record;
      List<MeetingRecord.Round> all = new ArrayList<>();
      for (MeetingRecord.Round round : r.rounds)
        if (!round.answer.isEmpty())
          all.add(gson.fromJson(gson.toJson(round), MeetingRecord.Round.class));
      if (!r.current.answer.isEmpty()) all.add(new MeetingRecord.Round(r.roundId, r.current));
      return all;
    }
  }

  /** Cancellation precedes deletion; queued writes cannot resurrect a tombstoned entry. */
  public void delete(String id, Consumer<Boolean> completion) {
    requireNavigationReady();
    SessionEngine owned;
    synchronized (lock) {
      Entry e = entries.get(id);
      if (e == null || e.deleting) {
        completion.accept(false);
        return;
      }
      e.deleting = true;
      owned = e.engine;
    }
    if (owned != null) owned.cancel();
    io.execute(
        () -> {
          try {
            MeetingRecord replacement = null;
            String fallback = null;
            synchronized (lock) {
              Entry target = entries.get(id);
              if (currentId.equals(id)) {
                fallback =
                    entries.values().stream()
                        .filter(
                            e ->
                                !e.record.id.equals(id)
                                    && !e.deleting
                                    && !e.record.draft
                                    && e.record.collectionId.equals(target.record.collectionId))
                        .max(Comparator.comparingLong(e -> e.record.updatedAt))
                        .map(e -> e.record.id)
                        .orElse(null);
                if (fallback == null) {
                  replacement =
                      blank(target.record.current.selected, target.record.current.automatic);
                  replacement.draft = true;
                  replacement.collectionId = target.record.collectionId;
                  fallback = replacement.id;
                }
              }
            }
            // Selection and deletion commit together; the final empty draft is not history.
            TopicCatalog next;
            synchronized (lock) {
              next = copyCatalog(catalog);
              next.remembered.values().removeIf(id::equals);
              TopicCatalog.Collection parent = next.find(entries.get(id).record.collectionId);
              parent.updatedAt = System.currentTimeMillis();
              if (fallback != null) {
                MeetingRecord dest =
                    replacement == null ? entries.get(fallback).record : replacement;
                next.selectedCollection = dest.collectionId;
                if (!dest.draft) next.remembered.put(dest.collectionId, dest.id);
              }
            }
            MeetingRecord selectedRecord = replacement;
            if (fallback != null && selectedRecord == null) {
              synchronized (lock) {
                selectedRecord = copy(entries.get(fallback).record);
              }
            }
            store.structure(
                selectedRecord == null ? List.of() : List.of(selectedRecord),
                List.of(id),
                next,
                fallback);
            synchronized (lock) {
              for (TopicCatalog.Collection c : next.collections) {
                TopicCatalog.Collection live = catalog.find(c.id);
                if (live != null) c.updatedAt = Math.max(c.updatedAt, live.updatedAt);
              }
              catalog = next;
            }
            Entry removed;
            boolean selectedReplacement = false;
            synchronized (lock) {
              removed = entries.remove(id);
              if (replacement != null) entries.put(replacement.id, new Entry(replacement));
              if (currentId.equals(id)) {
                currentId = fallback;
                selectedReplacement = true;
              }
              selectedTopic(entries.get(currentId).record);
              if (id.equals(pendingTarget) || id.equals(pendingSource)) {
                pendingSource = null;
                pendingTarget = null;
              }
            }
            if (removed != null && removed.engine != null) {
              removed.engine.detach(removed.listener);
              removed.engine.close();
            }
            if (selectedReplacement) {
              engine(fallback);
              selectOnDisk(fallback);
            } else if (replacement != null) discardDraft(replacement.id);
            recovered("会议删除失败");
            changed();
            completion.accept(true);
          } catch (RuntimeException e) {
            synchronized (lock) {
              Entry target = entries.get(id);
              if (target != null) target.deleting = false;
            }
            if (owned != null) onSnapshot(id, owned);
            failed("会议删除失败，记录已保留，请重试");
            completion.accept(false);
          }
        });
  }

  private void requireAvailable() {
    if (!available()) throw new IllegalStateException(error().isEmpty() ? "会议存储不可用" : error());
  }

  private void selectOnDisk(String id) {
    if (unavailable) return;
    io.execute(
        () -> {
          try {
            TopicCatalog snapshot;
            synchronized (lock) {
              snapshot = copyCatalog(catalog);
            }
            store.structure(List.of(), List.of(), snapshot, id);
            recovered("会议选择保存失败");
          } catch (RuntimeException e) {
            failed("会议选择保存失败，请重试");
          }
        });
  }

  private void saveSoon(String id, long delay) {
    synchronized (lock) {
      Entry e = entries.get(id);
      if (closed || unavailable || e == null || e.deleting) return;
      long due = System.nanoTime() / 1_000_000 + delay;
      if (e.scheduled != null && !e.scheduled.isDone()) {
        if (due >= e.scheduledAt) return;
        e.scheduled.cancel(false);
      }
      long version = ++e.saveVersion;
      e.scheduledAt = due;
      e.scheduled =
          io.schedule(
              () -> {
                MeetingRecord r;
                synchronized (lock) {
                  Entry now = entries.get(id);
                  if (closed || now == null || now.deleting || now.saveVersion != version) return;
                  now.scheduled = null;
                  r = copy(now.record);
                }
                try {
                  TopicCatalog snapshot;
                  synchronized (lock) {
                    snapshot = copyCatalog(catalog);
                  }
                  store.structure(List.of(r), List.of(), snapshot, null);
                  recovered("会议记录保存失败");
                } catch (RuntimeException failure) {
                  failed("会议记录保存失败，请检查设备存储并重试");
                }
              },
              delay,
              TimeUnit.MILLISECONDS);
    }
  }

  private void recovered(String prefix) {
    boolean update = false;
    synchronized (lock) {
      if (error.startsWith(prefix)) {
        error = "";
        update = true;
      }
    }
    if (update) changed();
  }

  private void failed(String message) {
    synchronized (lock) {
      error = message;
    }
    changed();
  }

  public void flush() {
    io.execute(
        () -> {
          if (unavailable) return;
          java.util.List<String> ids;
          synchronized (lock) {
            ids = new ArrayList<>(entries.keySet());
          }
          for (String id : ids) {
            MeetingRecord r;
            synchronized (lock) {
              Entry e = entries.get(id);
              if (e == null || e.deleting) continue;
              r = copy(e.record);
            }
            try {
              TopicCatalog snapshot;
              synchronized (lock) {
                snapshot = copyCatalog(catalog);
              }
              store.structure(List.of(r), List.of(), snapshot, null);
            } catch (RuntimeException e) {
              failed("会议记录保存失败，请检查设备存储并重试");
            }
          }
        });
  }

  public void awaitWrites() {
    try {
      flush();
      io.submit(() -> {}).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("会议保存未完成");
    }
  }

  @Override
  public void close() {
    List<Entry> all;
    synchronized (lock) {
      if (closed) return;
      all = new ArrayList<>(entries.values());
    }
    for (Entry e : all)
      if (e.engine != null) {
        if (e.listener != null) e.engine.detach(e.listener);
        e.engine.close();
      }
    awaitWrites();
    synchronized (lock) {
      closed = true;
    }
    io.shutdownNow();
    observers.clear();
  }
}
