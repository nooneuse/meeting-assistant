package io.github.meetingassistant.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Speech and analysis have independent lifecycles. No network work runs on the audio callback. */
public final class SessionEngine implements AutoCloseable {
  public enum State {
    IDLE,
    CONNECTING,
    LISTENING,
    FINALIZING,
    ANALYZING,
    DONE,
    ERROR
  }

  public enum AnalysisState {
    IDLE,
    WAITING,
    GENERATING,
    DONE,
    ERROR
  }

  public interface Factory {
    SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials keys);

    TextProvider text(ProviderConfig c, ProviderConfig.Credentials keys);

    AudioSource audio();

    default TextProvider summarize(ProviderConfig c, ProviderConfig.Credentials keys) {
      return text(c, keys);
    }
  }

  interface Clockwork extends AutoCloseable {
    long now();

    void schedule(Runnable task, long delayMs);

    void execute(Runnable task);

    default void maintenance(Runnable task) {
      execute(task);
    }

    void close();
  }

  private static final class RealClockwork implements Clockwork {
    private final ScheduledExecutorService timers =
        Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "meeting-timers"));
    private final ExecutorService requests =
        Executors.newSingleThreadExecutor(r -> daemon(r, "meeting-analysis"));

    private final ExecutorService maintenance =
        Executors.newSingleThreadExecutor(r -> daemon(r, "meeting-context"));

    public void maintenance(Runnable r) {
      maintenance.execute(r);
    }

    private static Thread daemon(Runnable r, String name) {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    }

    public long now() {
      return System.nanoTime() / 1_000_000;
    }

    public void schedule(Runnable r, long delay) {
      timers.schedule(r, Math.max(0, delay), TimeUnit.MILLISECONDS);
    }

    public void execute(Runnable r) {
      requests.execute(r);
    }

    public void close() {
      timers.shutdownNow();
      requests.shutdownNow();
      maintenance.shutdownNow();
    }
  }

  public static final class Snapshot {
    public final State state;
    public final AnalysisState analysisState;
    public final String transcript, answer, status, preference, preferenceId, analysisStatus;
    public final boolean retryable, automatic, hasUpdate;
    public final long startedAt, coveredMs;
    public ContextReport context;

    Snapshot(
        State s,
        AnalysisState as,
        String t,
        String a,
        String m,
        String p,
        String pid,
        String am,
        boolean r,
        boolean auto,
        boolean update,
        long at,
        long covered) {
      state = s;
      analysisState = as;
      transcript = t;
      answer = a;
      status = m;
      preference = p;
      preferenceId = pid;
      analysisStatus = am;
      retryable = r;
      automatic = auto;
      hasUpdate = update;
      startedAt = at;
      coveredMs = covered;
    }

    public boolean busy() {
      return state == State.CONNECTING
          || state == State.LISTENING
          || state == State.FINALIZING
          || state == State.ANALYZING;
    }
  }

  /** Explicit allowlist: no provider config, credentials, requests or audio. */
  public static final class SavedState {
    public State state = State.IDLE;
    public AnalysisState analysis = AnalysisState.IDLE;
    public java.util.List<SpeechProvider.Segment> segments = new java.util.ArrayList<>();
    public java.util.List<Transcript.Notice> audioNotices = new java.util.ArrayList<>();
    public java.util.List<String> history = new java.util.ArrayList<>();
    public java.util.List<ImageEvidence> images = new java.util.ArrayList<>();
    public long imageStateVersion;
    public java.util.List<String> answerImages = new java.util.ArrayList<>(),
        pendingImages = new java.util.ArrayList<>();
    public String answer = "", status = "准备就绪", analysisStatus = "", finalText = "";
    public UserSettings.Profile answerPreference, pendingPreference, lastAnalysisPreference;
    public String pendingAnswer;
    public long pendingMs;
    public UserSettings.Profile preference = new UserSettings.Profile("", "不使用回答偏好", "");
    public UserSettings.Profile selected = new UserSettings.Profile("", "不使用回答偏好", "");
    public boolean automatic, continuousRound, finalized, remembered, imageAnalysisRunning;
    public long startedAt, coveredMs;
    public String contextRoundId = java.util.UUID.randomUUID().toString();
    public ContextMemory.State contextMemory = new ContextMemory.State();
    public ContextReport contextReport = new ContextReport();
    public long contextNoticeConsumed, contextNoticeAt;

    public String transcript(boolean annotate) {
      return transcript(annotate, "[未定稿] ");
    }

    public String transcript(boolean annotate, String partialMarker) {
      return Transcript.render(segments, audioNotices, annotate, partialMarker);
    }

    public String stableText() {
      StringBuilder out = new StringBuilder();
      for (SpeechProvider.Segment s : segments)
        if (s.isFinal && !s.text.isEmpty()) {
          if (out.length() > 0) out.append('\n');
          out.append(s.text);
        }
      return out.toString();
    }

    public boolean speechActive() {
      return state == State.CONNECTING || state == State.LISTENING || state == State.FINALIZING;
    }

    public boolean busy() {
      return speechActive() || state == State.ANALYZING;
    }
  }

  public synchronized SavedState saveState() {
    SavedState s = new SavedState();
    s.state = state;
    s.imageAnalysisRunning = active != null && active.visual;
    s.analysis = analysis;
    s.segments = transcript.segments();
    s.audioNotices = transcript.notices();
    s.history = prompts.history();
    s.contextRoundId = contextRoundId;
    s.contextMemory = ContextMemory.copy(contextMemory);
    s.contextReport = contextReport.copy();
    s.contextReport.organizing = false;
    s.contextNoticeConsumed = contextNoticeConsumed;
    s.contextNoticeAt = contextNoticeAt;
    if (archivedImageVersion != imageStateVersion) {
      archivedImages = new java.util.ArrayList<>();
      for (ImageEvidence image : images) archivedImages.add(image.copy());
      archivedImageVersion = imageStateVersion;
    }
    s.images = new java.util.ArrayList<>(archivedImages);
    s.imageStateVersion = imageStateVersion;
    s.answerImages = new java.util.ArrayList<>(answerImages);
    s.pendingImages = new java.util.ArrayList<>(pendingImages);
    s.answer = answer;
    s.answerPreference = copy(answerPreference);
    s.lastAnalysisPreference = lastAnalysisPreference == null ? null : copy(lastAnalysisPreference);
    s.pendingPreference = pendingPreference == null ? null : copy(pendingPreference);
    s.pendingAnswer = pendingAnswer;
    s.pendingMs = pendingMs;
    s.status = status;
    s.analysisStatus = analysisStatus;
    s.finalText = finalText;
    s.preference = copy(preference);
    s.selected = copy(selected);
    s.automatic = automatic;
    s.continuousRound = continuousRound;
    s.finalized = finalized;
    s.remembered = remembered;
    s.startedAt = startedAt;
    s.coveredMs = pendingAnswer == null ? coveredMs : pendingMs;
    return s;
  }

  /** Cold restore: neither microphone nor model is resumed. */
  public synchronized void restore(SavedState s) {
    ++generation;
    dispose();
    credentials = null;
    contextRoundId =
        s.contextRoundId == null ? java.util.UUID.randomUUID().toString() : s.contextRoundId;
    contextMemory = ContextMemory.copy(s.contextMemory);
    contextReport = s.contextReport == null ? new ContextReport() : s.contextReport.copy();
    contextReport.organizing = false;
    contextNoticeConsumed = s.contextNoticeConsumed;
    contextNoticeAt = s.contextNoticeAt;
    nextContextNotice =
        clock.now()
            + Math.max(
                0,
                contextOptions.cooldownMinutes * 60000L
                    - (System.currentTimeMillis() - contextNoticeAt));
    contextArchive.clear();
    if (s.history != null)
      for (int i = 0; i < s.history.size(); i++)
        if (!s.history.get(i).trim().isEmpty())
          contextArchive.add(
              new ContextMemory.Source("legacy-history:" + i, s.history.get(i), false));
    images.clear();
    ++imageStateVersion;
    if (s.images != null)
      for (ImageEvidence original : s.images) {
        ImageEvidence image = original.copy();
        if (image.state == ImageEvidence.State.QUEUED
            || image.state == ImageEvidence.State.RECOGNIZING) {
          image.state = ImageEvidence.State.INTERRUPTED;
          image.error = "图像任务已中断，请重新抓拍";
        }
        images.add(image);
      }
    java.util.List<String> restoredImages =
        s.pendingAnswer != null ? s.pendingImages : s.answerImages;
    answerImages =
        restoredImages == null
            ? new java.util.ArrayList<>()
            : new java.util.ArrayList<>(restoredImages);
    pendingImages.clear();
    imageRevision++;
    imageRequested = false;
    imageCredentials = null;
    transcript.clear();
    prompts.clear();
    for (SpeechProvider.Segment segment : s.segments) transcript.update(segment);
    transcript.restoreNotices(s.audioNotices);
    for (String h : s.history) prompts.remember(h);
    answer = s.pendingAnswer == null ? s.answer : s.pendingAnswer;
    UserSettings.Profile restoredPreference =
        s.pendingAnswer != null ? s.pendingPreference : s.answerPreference;
    answerPreference = copy(restoredPreference == null ? s.preference : restoredPreference);
    lastAnalysisPreference =
        s.lastAnalysisPreference == null ? null : copy(s.lastAnalysisPreference);
    finalText = s.finalText;
    preference = copy(s.preference);
    selected = copy(s.selected);
    automatic = s.automatic;
    continuousRound = s.continuousRound;
    finalized = s.finalized;
    remembered = s.remembered;
    startedAt = s.startedAt;
    coveredMs = s.coveredMs;
    covered = s.finalText;
    coveredPref = 0;
    coveredProfile = copy(answerPreference);
    prefVersion = 0;
    attempted = "";
    attemptedPref = -1;
    pendingAnswer = null;
    finalPassStarted = false;
    deferRevisions = false;
    state = s.state;
    analysis = s.analysis;
    status = s.status;
    analysisStatus = s.analysisStatus;
    if (s.busy()) {
      state = State.ERROR;
      analysis = finalized ? AnalysisState.ERROR : AnalysisState.IDLE;
      status = "上次任务已中断 · 可继续会议";
      analysisStatus = "未恢复录音或自动重试";
    }
    publish();
  }

  private static final class Request {
    final long round, id, prefVersion, endMs;
    final String input;
    final UserSettings.Profile profile;
    final boolean stream, visual;
    final long imageRevision;
    java.util.List<String> imageIds;
    java.util.List<ContextMemory.Source> sources;
    ContextMemory.State memory;
    ContextSettings limits;
    java.util.List<ContextMemory.HistoricalImage> historicalImages;
    com.google.gson.JsonArray images;
    int budget, retries, attempt;
    boolean continuous;
    long contextEpoch, contextCorrections;
    long dispatched = -1, first = -1;
    String buffer = "";
    boolean invalid;
    TextProvider provider;

    Request(
        long round,
        long id,
        long pv,
        long end,
        String input,
        UserSettings.Profile p,
        boolean stream,
        boolean visual,
        long imageRevision,
        java.util.List<String> imageIds) {
      this.round = round;
      this.id = id;
      prefVersion = pv;
      endMs = end;
      this.input = input;
      profile = copy(p);
      this.stream = stream;
      this.visual = visual;
      this.imageRevision = imageRevision;
      this.imageIds = imageIds;
    }
  }

  private final Factory factory;
  private final Clockwork clock;
  private final AutomaticPolicy policy = new AutomaticPolicy();
  private final Transcript transcript = new Transcript();
  private final Prompts prompts = new Prompts();
  private ContextSettings contextOptions = new ContextSettings();
  private ContextMemory.State contextMemory = new ContextMemory.State();
  private ContextReport contextReport = new ContextReport();
  private final java.util.List<ContextMemory.Source> contextArchive = new java.util.ArrayList<>();
  private String contextRoundId = java.util.UUID.randomUUID().toString(), latencyBucket = "";
  private final java.util.ArrayDeque<Boolean> slowSamples = new java.util.ArrayDeque<>();
  private long contextNoticeConsumed,
      contextNoticeAt,
      nextContextNotice,
      summaryId,
      summaryNextAt,
      contextCorrections;
  private TextProvider summaryProvider;
  private long contextEpoch;
  private int adaptiveBudget = Integer.MAX_VALUE;
  private boolean contextSuspended;

  private final java.util.List<ImageEvidence> images = new java.util.ArrayList<>();
  private java.util.List<String> answerImages = new java.util.ArrayList<>(),
      pendingImages = new java.util.ArrayList<>();
  private final java.util.Map<String, Runnable> imageCancellation = new java.util.HashMap<>();
  private long imageStateVersion, archivedImageVersion = -1;
  private java.util.List<ImageEvidence> archivedImages = new java.util.ArrayList<>();
  private long imageRevision, coveredImageRevision = -1, attemptedImageRevision = -1;
  private boolean imageRequested, closed;
  private ProviderConfig imageConfig;
  private ProviderConfig.Credentials imageCredentials;
  private UserSettings.Profile lastAnalysisPreference,
      imagePreference,
      coveredProfile = new UserSettings.Profile("", "", "");
  private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Snapshot>> observers =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  private State state = State.IDLE;
  private AnalysisState analysis = AnalysisState.IDLE;
  private String answer = "",
      status = "准备就绪",
      analysisStatus = "",
      finalText = "",
      attempted = "",
      covered = "";
  private UserSettings.Profile preference = new UserSettings.Profile("", "不使用回答偏好", ""),
      selected = copy(preference),
      answerPreference = copy(preference),
      pendingPreference;
  private ProviderConfig config;
  private ProviderConfig.Credentials credentials;
  private SpeechProvider speech;
  private AudioSource audio;
  private Request active;
  private long generation,
      requestId,
      scheduleId,
      startedAt,
      prefVersion,
      attemptedPref = -1,
      coveredPref = -1,
      coveredMs;
  private boolean automatic,
      continuousRound,
      finalized,
      remembered,
      deferRevisions,
      finalPassStarted;
  private String pendingAnswer;
  private long pendingMs;

  public SessionEngine(Factory factory) {
    this(factory, new RealClockwork());
  }

  SessionEngine(Factory factory, Clockwork clock) {
    this.factory = factory;
    this.clock = clock;
  }

  private static UserSettings.Profile copy(UserSettings.Profile p) {
    return new UserSettings.Profile(p.id, p.name, p.prompt);
  }

  public synchronized void observe(Consumer<Snapshot> o) {
    observers.addIfAbsent(o);
    o.accept(snapshot());
  }

  public synchronized void detach(Consumer<Snapshot> o) {
    observers.remove(o);
  }

  public synchronized Snapshot snapshot() {
    Snapshot result =
        new Snapshot(
            state,
            analysis,
            transcript.text(),
            answer,
            status,
            preference.name,
            preference.id,
            analysisStatus,
            analysis == AnalysisState.ERROR
                && (automatic && state == State.LISTENING
                    || finalized && state == State.ERROR
                    || !images.isEmpty() && analysis == AnalysisState.ERROR),
            automatic,
            pendingAnswer != null,
            startedAt,
            coveredMs);
    result.context = contextReport.copy();
    return result;
  }

  private void publish() {
    Snapshot s = snapshot();
    for (Consumer<Snapshot> observer : observers) observer.accept(s);
  }

  public synchronized void setAutomatic(boolean on) {
    if (automatic == on) return;
    automatic = on;
    ++scheduleId;
    if (on && snapshot().busy()) continuousRound = true;
    if (state == State.LISTENING || state == State.CONNECTING) {
      preference = copy(selected);
      if (on) {
        policy.changed(clock.now(), true, transcript.finalText());
        scheduleAnalysis();
      } else if (active == null) {
        analysis = AnalysisState.IDLE;
        analysisStatus = "";
      }
      listeningStatus();
    }
    publish();
  }

  public synchronized void selectPreference(UserSettings.Profile p) {
    if (selected.id.equals(p.id)
        && selected.name.equals(p.name)
        && selected.prompt.equals(p.prompt)) return;
    selected = copy(p);
    if (!automatic || !(state == State.LISTENING || state == State.CONNECTING)) {
      publish();
      return;
    }
    if (preference.id.equals(p.id)
        && preference.name.equals(p.name)
        && preference.prompt.equals(p.prompt)) return;
    preference = copy(p);
    ++prefVersion;
    if (active != null) active.invalid = true;
    pendingAnswer = null;
    analysisStatus = "偏好已更改，等待更新";
    policy.changed(clock.now(), true, "如何");
    scheduleAnalysis();
    publish();
  }

  public synchronized void deferRevisions(boolean defer) {
    deferRevisions = defer;
    if (!defer) applyPendingAnswer();
  }

  public synchronized void applyPendingAnswer() {
    if (pendingAnswer == null) return;
    answer = pendingAnswer;
    if (pendingPreference != null) answerPreference = copy(pendingPreference);
    coveredMs = pendingMs;
    answerImages = new java.util.ArrayList<>(pendingImages);
    pendingImages.clear();
    pendingAnswer = null;
    publish();
  }

  public synchronized boolean canStartListening() {
    boolean speechActive =
        state == State.CONNECTING || state == State.LISTENING || state == State.FINALIZING;
    return !closed && (!snapshot().busy() || active != null && active.visual && !speechActive);
  }

  public synchronized boolean start(
      ProviderConfig c, ProviderConfig.Credentials keys, UserSettings.Profile p) {
    if (!canStartListening()) return false;
    boolean keepImage = active != null && active.visual;
    try {
      c.validate();
      keys.validate();
    } catch (IllegalArgumentException e) {
      status = e.getMessage();
      if (!keepImage) state = State.ERROR;
      publish();
      return false;
    }
    remember();
    if (keepImage) {
      ++scheduleId;
      disposeSpeech();
    } else dispose();
    long round = ++generation;
    config = c.copy();
    credentials = keys;
    preference = copy(p);
    selected = copy(p);
    transcript.clear();
    contextRoundId = java.util.UUID.randomUUID().toString();
    if (!keepImage) answer = "";
    finalText = "";
    attempted = "";
    covered = "";
    analysisStatus = keepImage ? "更新中" : "";
    pendingAnswer = null;
    finalized = false;
    remembered = false;
    finalPassStarted = false;
    deferRevisions = false;
    continuousRound = automatic;
    if (!keepImage) prefVersion = 0;
    attemptedPref = -1;
    coveredPref = -1;
    coveredMs = 0;
    coveredImageRevision = -1;
    attemptedImageRevision = -1;
    if (!keepImage) answerImages.clear();
    startedAt = 0;
    analysis = keepImage ? AnalysisState.GENERATING : AnalysisState.IDLE;
    policy.reset(clock.now());
    state = State.CONNECTING;
    status = "连接语音服务中，尚未开始采集";
    publish();
    try {
      speech = factory.speech(config, keys);
      speech.start(
          new SpeechProvider.Listener() {
            public void ready() {
              onReady(round);
            }

            public void segment(SpeechProvider.Segment s) {
              onSegment(round, s);
            }

            public void completed() {
              onFinalized(round);
            }

            public void failed(String m) {
              fail(round, m);
            }
          });
      timeout(round, State.CONNECTING, 15000, "语音连接超时，请检查网络与配置");
    } catch (RuntimeException e) {
      fail(round, "无法启动语音服务，请检查配置");
    }
    return true;
  }

  private void listeningStatus() {
    if (state == State.LISTENING) status = automatic ? "正在聆听 · 自动分析" : "正在聆听 · 停止后分析";
  }

  private synchronized void onReady(long round) {
    if (round != generation || state != State.CONNECTING) return;
    state = State.LISTENING;
    listeningStatus();
    startedAt = System.currentTimeMillis();
    publish();
    try {
      audio = factory.audio();
      audio.start(
          new AudioSource.Listener() {
            public void audio(byte[] pcm) {
              onAudio(round, pcm);
            }

            public void discontinuity(String notice) {
              synchronized (SessionEngine.this) {
                if (round != generation || state != State.LISTENING) return;
                transcript.notice(notice);
                publish();
              }
            }

            public void failed(String m) {
              fail(round, m);
            }
          });
      clock.schedule(
          () -> {
            synchronized (SessionEngine.this) {
              if (round == generation && state == State.LISTENING) stop();
            }
          },
          900000);
    } catch (RuntimeException e) {
      fail(round, "无法启用麦克风，请检查权限与占用情况");
    }
  }

  private synchronized void onAudio(long round, byte[] pcm) {
    if (round == generation
        && (state == State.LISTENING || state == State.FINALIZING)
        && speech != null) speech.sendAudio(pcm);
  }

  private synchronized void onSegment(long round, SpeechProvider.Segment s) {
    if (round != generation || !(state == State.LISTENING || state == State.FINALIZING)) return;
    String before = transcript.finalText();
    transcript.update(s);
    String stable = transcript.finalText();
    boolean changed = !before.equals(stable);
    if (changed && !stable.startsWith(before)) {
      ++contextCorrections;
      cancelContextWork();
    }
    if (changed
        && active != null
        && (!stable.startsWith(active.input) || AutomaticPolicy.correction(s.text))) {
      active.invalid = true;
      if (active.visual) imageRequested = true;
      analysisStatus = "原文有纠正，等待更新";
    }
    if (changed && (AutomaticPolicy.correction(s.text) || !stable.startsWith(before)))
      pendingAnswer = null;
    policy.changed(clock.now(), changed, s.text);
    if (automatic && state == State.LISTENING) scheduleAnalysis();
    publish();
  }

  private void scheduleAnalysis() {
    if (!automatic || state != State.LISTENING || active != null) return;
    String input = transcript.finalText().trim();
    if (!AutomaticPolicy.meaningful(input) || equivalent(input, attempted, attemptedPref)) return;
    analysis = AnalysisState.WAITING;
    if (analysisStatus.isEmpty() || !analysisStatus.contains("纠正")) analysisStatus = "等待稳定段落";
    long round = generation, ticket = ++scheduleId;
    clock.schedule(
        () -> {
          synchronized (SessionEngine.this) {
            if (round != generation
                || ticket != scheduleId
                || !automatic
                || state != State.LISTENING
                || active != null) return;
            beginAnalysis(transcript.finalText().trim());
          }
        },
        policy.delay(clock.now()));
  }

  public synchronized void stop() {
    if (state == State.CONNECTING) {
      if (active != null && active.visual) {
        ++generation;
        ++scheduleId;
        disposeSpeech();
        credentials = null;
        state = State.ANALYZING;
        status = "正在生成答案…";
        publish();
      } else cancel();
      return;
    }
    if (state != State.LISTENING) return;
    ++scheduleId;
    long round = generation;
    state = State.FINALIZING;
    status = "麦克风已停止，等待原文定稿…";
    publish();
    timeout(round, State.FINALIZING, 20000, "语音收尾超时，原文可能不完整；未提交尾段分析");
    audio.stop(
        () -> {
          synchronized (SessionEngine.this) {
            if (round == generation && state == State.FINALIZING && speech != null) speech.finish();
          }
        });
  }

  private synchronized void onFinalized(long round) {
    if (round != generation) return;
    if (state == State.CONNECTING || state == State.LISTENING) {
      fail(round, "语音服务提前结束，原文可能不完整；请重新聆听");
      return;
    }
    if (state != State.FINALIZING) return;
    if (transcript.hasPending()) {
      fail(round, "部分原文未定稿，未提交尾段分析；请重新聆听");
      return;
    }
    finalText = transcript.finalText().trim();
    finalized = true;
    disposeSpeech();
    if (active == null && imageRequested) {
      state = State.ANALYZING;
      if (drainImageAnalysis()) return;
    }
    if ((finalText.isEmpty() || continuousRound && !AutomaticPolicy.meaningful(finalText))
        && active == null) {
      credentials = null;
      state = State.IDLE;
      status = "没有可分析的定稿内容，未调用文字模型";
      publish();
      return;
    }
    state = State.ANALYZING;
    status = "聆听已停止 · 等待最终答案";
    if (active == null) {
      if (!drainImageAnalysis()) finishOrAnalyze();
    } else publish();
  }

  private void finishOrAnalyze() {
    if (active != null || !finalized) return;
    if (drainImageAnalysis()) return;
    boolean same =
        equivalent(finalText, covered, coveredPref)
            && coveredImageRevision == imageRevision
            && coveredProfile.id.equals(preference.id)
            && coveredProfile.prompt.equals(preference.prompt);
    boolean failedSame =
        equivalent(finalText, attempted, attemptedPref)
            && attemptedImageRevision == imageRevision
            && analysis == AnalysisState.ERROR;
    if (!same && !failedSame && !finalPassStarted && !finalText.isEmpty()) {
      finalPassStarted = true;
      beginAnalysis(finalText);
      return;
    }
    state = failedSame || analysis == AnalysisState.ERROR ? State.ERROR : State.DONE;
    status = state == State.ERROR ? "文字分析未完成，可手动重试" : "已完成 · AI 建议请结合原文核对";
    if (state == State.DONE) remember();
    credentials = null;
    publish();
  }

  private boolean equivalent(String input, String previous, long previousPref) {
    return prefVersion == previousPref
        && (input.equals(previous)
            || !previous.isEmpty()
                && input.startsWith(previous)
                && !AutomaticPolicy.meaningful(input.substring(previous.length())));
  }

  private void beginAnalysis(String input) {
    beginAnalysis(input, false, preference);
  }

  private void beginAnalysis(String input, boolean visual, UserSettings.Profile requestProfile) {
    if (active != null || closed || input.isEmpty() && !visual) return;
    if (!visual && !automatic && !finalized) return;
    lastAnalysisPreference = copy(requestProfile);
    policy.requested(clock.now());
    ++scheduleId;
    attempted = input;
    attemptedPref = prefVersion;
    attemptedImageRevision = imageRevision;
    Request r =
        new Request(
            generation,
            ++requestId,
            prefVersion,
            transcript.finalEndMs(),
            input,
            requestProfile,
            answer.isEmpty(),
            visual,
            imageRevision,
            selectedImageIds());
    active = r;
    analysis = AnalysisState.GENERATING;
    analysisStatus = "更新中";
    if (state != State.CONNECTING && state != State.LISTENING && state != State.FINALIZING) {
      state = State.ANALYZING;
      status = "正在生成答案…";
    }
    publish();
    ProviderConfig requestConfig = (visual ? imageConfig : config).copy();
    ProviderConfig.Credentials requestKeys = visual ? imageCredentials : credentials;
    String bucket = requestConfig.textBaseUrl + "\n" + requestConfig.textModel;
    if (!bucket.equals(latencyBucket)) {
      latencyBucket = bucket;
      slowSamples.clear();
      contextReport.slow = false;
      contextReport.firstMs = contextReport.totalMs = -1;
      adaptiveBudget = Integer.MAX_VALUE;
    }
    r.contextEpoch = contextEpoch;
    r.contextCorrections = contextCorrections;
    r.continuous = continuousRound;
    r.sources = contextSources();
    r.historicalImages = new java.util.ArrayList<>();
    java.util.Set<String> recent = new java.util.HashSet<>(selectedImageIds());
    for (ImageEvidence image : images)
      if (image.included && image.state == ImageEvidence.State.READY && !recent.contains(image.id))
        r.historicalImages.add(new ContextMemory.HistoricalImage(image.id, image.structure));
    r.memory = ContextMemory.copy(contextMemory);
    r.images = selectedImageData();
    r.limits = contextOptions.copy();
    r.budget = Math.min(r.limits.budget(), adaptiveBudget);
    requestConfig.outputTokens = r.limits.outputBudget;
    clock.schedule(
        () -> {
          synchronized (SessionEngine.this) {
            if (isActive(r)) {
              if (r.first < 0 && r.dispatched >= 0) recordLatency(r, true);
              analysisFailed(r, "文字分析超时，可手动重试");
            }
          }
        },
        180000);
    submitContextRequest(r, requestConfig, requestKeys);
  }

  private void submitContextRequest(Request r, ProviderConfig c, ProviderConfig.Credentials keys) {
    int attempt = ++r.attempt;
    clock.execute(
        () -> {
          try {
            ContextMemory.Plan plan =
                ContextMemory.prepare(
                    ContextMemory.withHistoricalImages(r.sources, r.historicalImages),
                    r.memory,
                    r.profile,
                    r.images,
                    r.continuous,
                    r.visual,
                    r.limits,
                    r.budget);
            synchronized (SessionEngine.this) {
              if (!isActive(r) || r.attempt != attempt) return;
              if (r.invalid || r.prefVersion != prefVersion) {
                analysisCompleted(r, false);
                return;
              }
              contextMemory = plan.memory();
              contextReport.input = plan.input();
              contextReport.before = plan.before();
              contextReport.budget = r.budget;
              contextReport.limited = plan.limited();
              contextReport.summarized = !contextMemory.items.isEmpty();
              contextReport.longMeeting =
                  plan.before() >= r.limits.budget() * r.limits.organizePercent / 100;
              contextNotice();
              r.imageIds = new java.util.ArrayList<>(plan.imageIds());
              publish();
            }
            TextProvider provider = factory.text(c, keys);
            synchronized (SessionEngine.this) {
              if (!isActive(r) || r.attempt != attempt) {
                provider.cancel();
                return;
              }
              if (r.invalid || r.prefVersion != prefVersion) {
                provider.cancel();
                analysisCompleted(r, false);
                return;
              }
              r.provider = provider;
              r.dispatched = clock.now();
              r.first = -1;
            }
            provider.analyze(
                plan.messages(),
                new TextProvider.Listener() {
                  public void delta(String content) {
                    synchronized (SessionEngine.this) {
                      if (!isActive(r) || r.attempt != attempt) return;
                      if (r.first < 0 && !content.trim().isEmpty())
                        r.first = clock.now() - r.dispatched;
                      r.buffer += content;
                      if (r.stream && !r.invalid && r.prefVersion == prefVersion) {
                        answer = r.buffer;
                        answerPreference = copy(r.profile);
                        publish();
                      }
                    }
                  }

                  public void completed(boolean truncated) {
                    synchronized (SessionEngine.this) {
                      if (!isActive(r) || r.attempt != attempt) return;
                      if (!r.invalid && r.prefVersion == prefVersion && !r.buffer.trim().isEmpty())
                        recordLatency(r, false);
                      analysisCompleted(r, truncated);
                    }
                  }

                  public void failed(String m) {
                    synchronized (SessionEngine.this) {
                      if (isActive(r) && r.attempt == attempt) analysisFailed(r, m);
                    }
                  }

                  public void contextLimit() {
                    synchronized (SessionEngine.this) {
                      if (!isActive(r) || r.attempt != attempt) return;
                      ++r.attempt;
                      cancelProvider(r);
                      if (r.retries++ == 0 && r.budget * 3 / 4 >= 2048) {
                        r.budget = r.budget * 3 / 4;
                        adaptiveBudget = Math.min(adaptiveBudget, r.budget);
                        r.buffer = "";
                        r.first = -1;
                        r.dispatched = -1;
                        submitContextRequest(r, c, keys);
                      } else analysisFailed(r, "文字模型上下文超限，请降低输入预算或新建会议");
                    }
                  }
                });
            synchronized (SessionEngine.this) {
              if (!closed
                  && !r.invalid
                  && r.contextCorrections == contextCorrections
                  && r.contextEpoch == contextEpoch
                  && r.attempt == attempt) startContextWork(plan.job(), c, keys);
            }
          } catch (IllegalArgumentException e) {
            int size =
                ContextMemory.estimate(r.input) + ContextMemory.estimate(r.images.toString());
            synchronized (SessionEngine.this) {
              if (isActive(r) && r.attempt == attempt) {
                contextReport.before = size;
                contextReport.budget = r.budget;
                contextReport.limited = true;
                if (e.getMessage() != null
                    && (e.getMessage().startsWith("最新原文") || e.getMessage().startsWith("最新图像")))
                  contextReport.longMeeting = true;
                contextNotice();
                analysisFailed(r, e.getMessage());
              }
            }
          } catch (RuntimeException e) {
            synchronized (SessionEngine.this) {
              if (isActive(r) && r.attempt == attempt) analysisFailed(r, "无法提交文字分析，请检查配置");
            }
          }
        });
  }

  private void recordLatency(Request r, boolean timeout) {
    if (r.dispatched < 0 || r.invalid || r.prefVersion != prefVersion) return;
    contextReport.firstMs = timeout ? clock.now() - r.dispatched : r.first;
    contextReport.totalMs = clock.now() - r.dispatched;
    slowSamples.addLast(timeout || r.first > r.limits.slowSeconds * 1000L);
    while (slowSamples.size() > 3) slowSamples.removeFirst();
    contextReport.slow = slowSamples.stream().filter(Boolean::booleanValue).count() >= 2;
    contextNotice();
  }

  private void contextNotice() {
    if ((contextReport.longMeeting || contextReport.slow) && clock.now() >= nextContextNotice) {
      contextReport.notice++;
      contextNoticeAt = System.currentTimeMillis();
      nextContextNotice = clock.now() + contextOptions.cooldownMinutes * 60000L;
    }
  }

  public synchronized boolean consumeContextNotice() {
    if (contextReport.notice <= contextNoticeConsumed) return false;
    contextNoticeConsumed = contextReport.notice;
    publish();
    return true;
  }

  public synchronized void configureContext(ContextSettings options) {
    options.validate();
    boolean changed =
        !new com.google.gson.Gson()
            .toJson(contextOptions)
            .equals(new com.google.gson.Gson().toJson(options));
    if (contextOptions.inputBudget != options.inputBudget
        || contextOptions.modelWindow != options.modelWindow
        || contextOptions.outputBudget != options.outputBudget) {
      adaptiveBudget = Integer.MAX_VALUE;
      ++contextEpoch;
      cancelContextWork();
    }
    if (contextOptions.slowSeconds != options.slowSeconds) {
      slowSamples.clear();
      contextReport.slow = false;
    }
    if (contextOptions.cooldownMinutes != options.cooldownMinutes && contextNoticeAt > 0)
      nextContextNotice =
          clock.now()
              + Math.max(
                  0,
                  options.cooldownMinutes * 60000L
                      - (System.currentTimeMillis() - contextNoticeAt));
    contextOptions = options.copy();
    contextReport.budget = active == null ? options.budget() : active.budget;
    if (changed) publish();
  }

  public synchronized void suspendContextWork() {
    contextSuspended = true;
    cancelContextWork();
  }

  public synchronized void resumeContextWork() {
    contextSuspended = false;
  }

  private java.util.List<ContextMemory.Source> currentContextSources() {
    java.util.List<ContextMemory.Source> out = new java.util.ArrayList<>();
    for (SpeechProvider.Segment seg : transcript.segments())
      if (seg.isFinal && !seg.text.trim().isEmpty())
        out.add(new ContextMemory.Source(contextRoundId + ":" + seg.id, seg.text, true));
    int event = 0;
    for (Transcript.Notice n : transcript.notices())
      out.add(
          new ContextMemory.Source(
              contextRoundId + ":audio-event:" + event++,
              "[Application event, not spoken content] " + n.text(),
              true));
    return out;
  }

  private java.util.List<ContextMemory.Source> contextSources() {
    java.util.List<ContextMemory.Source> current = currentContextSources(),
        out = new java.util.ArrayList<>();
    java.util.Set<String> currentIds = new java.util.HashSet<>();
    for (ContextMemory.Source v : current) currentIds.add(v.id());
    for (ContextMemory.Source v : contextArchive) if (!currentIds.contains(v.id())) out.add(v);
    out.addAll(current);
    return out;
  }

  synchronized void loadContextArchive(java.util.List<MeetingRecord.Round> rounds) {
    if (rounds.isEmpty()) return;
    contextArchive.clear();
    int legacy = 0;
    for (MeetingRecord.Round round : rounds) {
      if (round == null) {
        contextMemory.omitted = true;
        continue;
      }
      String id =
          round.contextRoundId == null || round.contextRoundId.isEmpty()
              ? round.id
              : round.contextRoundId;
      if (id == null || id.isEmpty()) id = "legacy-round:" + (legacy++);
      String marker = round.complete ? "" : "[该轮未完整收尾，仅包含已定稿原文]\n";
      if (round.segments != null) {
        for (SpeechProvider.Segment seg : round.segments) {
          if (seg == null || seg.text == null) {
            contextMemory.omitted = true;
            continue;
          }
          if (seg.isFinal && !seg.text.trim().isEmpty())
            contextArchive.add(
                new ContextMemory.Source(id + ":" + seg.id, marker + seg.text, false));
        }
      } else if (round.transcript != null && !round.transcript.trim().isEmpty())
        contextArchive.add(new ContextMemory.Source(id + ":legacy", round.transcript, false));
      if (round.audioNotices != null) {
        int event = 0;
        for (Transcript.Notice n : round.audioNotices)
          contextArchive.add(
              new ContextMemory.Source(
                  id + ":audio-event:" + event++,
                  "[Application event, not spoken content] " + n.text(),
                  false));
      }
    }
  }

  public synchronized String contextCarry() {
    java.util.List<ContextMemory.Source> sources = currentContextSources();
    StringBuilder text = new StringBuilder();
    for (int i = sources.size() - 1; i >= 0; i--) {
      String part = "[" + sources.get(i).id() + "] " + sources.get(i).text() + "\n";
      if (ContextMemory.estimate(part) + ContextMemory.estimate(text.toString()) > 1000) continue;
      text.insert(0, part);
    }
    for (ImageEvidence image : selectedImages()) {
      for (com.google.gson.JsonElement el :
          com.google.gson.JsonParser.parseString(image.structure)
              .getAsJsonObject()
              .getAsJsonArray("blocks")) {
        com.google.gson.JsonObject block = el.getAsJsonObject();
        String part =
            "[图像 "
                + image.id
                + " 区域 "
                + block.get("id").getAsString()
                + "] "
                + block.get("text").getAsString()
                + "\n";
        if (ContextMemory.estimate(text.toString()) + ContextMemory.estimate(part) <= 1000)
          text.append(part);
      }
    }
    return text.toString().trim();
  }

  private void startContextWork(
      ContextMemory.Job job, ProviderConfig c, ProviderConfig.Credentials keys) {
    if (job == null
        || contextSuspended
        || closed
        || contextReport.organizing
        || clock.now() < summaryNextAt) return;
    long id = ++summaryId, correction = contextCorrections;
    contextReport.organizing = true;
    contextReport.organizeFailed = false;
    summaryNextAt = clock.now() + 30000;
    clock.schedule(
        () -> {
          synchronized (SessionEngine.this) {
            if (summaryId == id && contextReport.organizing) {
              cancelContextWork();
              contextReport.organizeFailed = true;
              publish();
            }
          }
        },
        60000);
    clock.maintenance(
        () -> {
          try {
            TextProvider provider = factory.summarize(c.copy(), keys);
            synchronized (SessionEngine.this) {
              if (closed || id != summaryId) {
                provider.cancel();
                return;
              }
              summaryProvider = provider;
            }
            StringBuilder buffer = new StringBuilder();
            provider.analyze(
                job.messages(),
                new TextProvider.Listener() {
                  public void delta(String text) {
                    synchronized (SessionEngine.this) {
                      if (id != summaryId) return;
                      if (buffer.length() + text.length() > 32000) {
                        failed("summary too large");
                        return;
                      }
                      buffer.append(text);
                    }
                  }

                  public void completed(boolean truncated) {
                    ContextMemory.State result;
                    try {
                      if (truncated) throw new IllegalArgumentException();
                      result = ContextMemory.finish(job, buffer.toString());
                    } catch (RuntimeException e) {
                      failed("invalid summary");
                      return;
                    }
                    synchronized (SessionEngine.this) {
                      if (closed || id != summaryId || correction != contextCorrections) return;
                      contextMemory = result;
                      contextReport.summarized = true;
                      cancelContextWork();
                      publish();
                    }
                  }

                  public void failed(String m) {
                    synchronized (SessionEngine.this) {
                      if (id != summaryId) return;
                      cancelContextWork();
                      contextReport.organizeFailed = true;
                      publish();
                    }
                  }
                });
          } catch (RuntimeException e) {
            synchronized (SessionEngine.this) {
              if (id == summaryId) {
                cancelContextWork();
                contextReport.organizeFailed = true;
                publish();
              }
            }
          }
        });
  }

  private void cancelContextWork() {
    ++summaryId;
    contextReport.organizing = false;
    TextProvider provider = summaryProvider;
    summaryProvider = null;
    if (provider != null) provider.cancel();
  }

  private void cancelProvider(Request r) {
    TextProvider provider = r.provider;
    r.provider = null;
    if (provider != null) provider.cancel();
  }

  private boolean isActive(Request r) {
    return active == r && (r.visual || generation == r.round);
  }

  private void analysisCompleted(Request r, boolean truncated) {
    if (!isActive(r)) return;
    if (!r.invalid && r.buffer.trim().isEmpty()) {
      analysisFailed(r, "文字模型未返回有效内容，可手动重试");
      return;
    }
    active = null;
    cancelProvider(r);
    if (!r.invalid && r.prefVersion == prefVersion) {
      covered = r.input;
      coveredPref = r.prefVersion;
      coveredProfile = copy(r.profile);
      coveredImageRevision = r.imageRevision;
      if (deferRevisions && !r.stream) {
        pendingAnswer = r.buffer;
        pendingPreference = copy(r.profile);
        pendingMs = r.endMs;
        pendingImages = new java.util.ArrayList<>(r.imageIds);
      } else {
        answer = r.buffer;
        answerPreference = copy(r.profile);
        coveredMs = r.endMs;
        answerImages = new java.util.ArrayList<>(r.imageIds);
        pendingAnswer = null;
      }
      analysisStatus = truncated ? "答案达到长度限制，可能不完整" : "";
    } else {
      attemptedPref = -1;
      analysisStatus = "上下文已变化，等待更新";
    }
    analysis = AnalysisState.DONE;
    if (drainImageAnalysis()) return;
    if (!imageRequested) imageCredentials = null;
    if (state == State.LISTENING) {
      scheduleAnalysis();
      publish();
    } else if (finalized) finishOrAnalyze();
    else {
      if (state == State.ANALYZING && r.visual) {
        state = State.DONE;
        status = "已完成 · AI 建议请结合原文核对";
        imageCredentials = null;
      }
      publish();
    }
  }

  private void analysisFailed(Request r, String m) {
    if (!isActive(r)) return;
    active = null;
    cancelProvider(r);
    analysis = AnalysisState.ERROR;
    analysisStatus = m;
    if (drainImageAnalysis()) return;
    if (!imageRequested) imageCredentials = null;
    if (state == State.LISTENING) {
      scheduleAnalysis();
      publish();
    } else if (finalized) finishOrAnalyze();
    else {
      if (state == State.ANALYZING && r.visual) {
        state = State.ERROR;
        status = "文字分析未完成，可手动重试";
        imageCredentials = null;
      }
      publish();
    }
  }

  public synchronized void retry(ProviderConfig c, ProviderConfig.Credentials keys) {
    if (active == null && analysis == AnalysisState.ERROR && !selectedImageIds().isEmpty()) {
      requestImageAnalysis(
          c, keys, lastAnalysisPreference == null ? selected : lastAnalysisPreference);
      return;
    }
    if (active != null
        || analysis != AnalysisState.ERROR
        || !(state == State.LISTENING || finalized && state == State.ERROR)) return;
    try {
      c.validateText();
      keys.validateText();
    } catch (IllegalArgumentException e) {
      analysisStatus = e.getMessage();
      publish();
      return;
    }
    config = c.copy();
    credentials = keys;
    beginAnalysis(finalized ? finalText : transcript.finalText().trim());
  }

  public synchronized int imageCount() {
    return images.size();
  }

  public synchronized int imagePendingCount() {
    int n = 0;
    for (ImageEvidence image : images)
      if (image.state == ImageEvidence.State.QUEUED
          || image.state == ImageEvidence.State.RECOGNIZING) ++n;
    return n;
  }

  public synchronized String latestImageError() {
    return images.isEmpty() ? "" : images.get(images.size() - 1).error;
  }

  public synchronized java.util.List<ImageEvidence> images() {
    java.util.List<ImageEvidence> out = new java.util.ArrayList<>();
    for (ImageEvidence image : images) out.add(image.copy());
    return out;
  }

  public synchronized boolean closed() {
    return closed;
  }

  public synchronized boolean acceptImage(ImageEvidence image, Runnable cancel) {
    if (closed || images.size() >= 200) return false;
    ImageEvidence copy = image.copy();
    copy.audioEndMs = transcript.finalEndMs();
    copy.transcriptAnchor = Prompts.window(transcript.finalText(), 12000);
    images.add(copy);
    ++imageStateVersion;
    imageCancellation.put(copy.id, cancel);
    publish();
    return true;
  }

  public synchronized boolean imageState(String id, ImageEvidence.State next, String safeError) {
    if (closed) return false;
    for (ImageEvidence image : images)
      if (image.id.equals(id)) {
        if (image.state == ImageEvidence.State.CANCELLED) return false;
        image.state = next;
        ++imageStateVersion;
        image.error = safeError;
        if (next != ImageEvidence.State.QUEUED && next != ImageEvidence.State.RECOGNIZING)
          imageCancellation.remove(id);
        publish();
        return true;
      }
    return false;
  }

  public synchronized void imageRecognized(
      String id,
      String structure,
      ProviderConfig c,
      ProviderConfig.Credentials keys,
      UserSettings.Profile profile) {
    if (closed) return;
    for (ImageEvidence image : images)
      if (image.id.equals(id)) {
        if (image.state == ImageEvidence.State.CANCELLED) return;
        image.structure = ImageEvidence.validateStructure(structure);
        ++imageStateVersion;
        image.state = ImageEvidence.State.READY;
        image.error = "";
        imageCancellation.remove(id);
        ++imageRevision;
        pendingAnswer = null;
        if (active != null) active.invalid = true;
        requestImageAnalysis(c, keys, automatic ? selected : profile);
        publish();
        return;
      }
  }

  public synchronized void requestImageAnalysis(
      ProviderConfig c, ProviderConfig.Credentials keys, UserSettings.Profile p) {
    if (closed) return;
    if (selectedImageIds().isEmpty() && transcript.finalText().trim().isEmpty()) {
      answer = "";
      pendingAnswer = null;
      answerImages.clear();
      pendingImages.clear();
      analysis = AnalysisState.IDLE;
      if (state == State.DONE || state == State.ANALYZING) {
        state = State.IDLE;
        status = "准备就绪";
      }
      publish();
      return;
    }
    try {
      c.validateText();
      keys.validateText();
    } catch (IllegalArgumentException e) {
      analysis = AnalysisState.ERROR;
      analysisStatus = e.getMessage();
      publish();
      return;
    }
    imageConfig = c.copy();
    imageCredentials = keys;
    imagePreference = copy(p);
    imageRequested = true;
    drainImageAnalysis();
  }

  public synchronized void includeImage(
      String id, boolean include, ProviderConfig c, ProviderConfig.Credentials keys) {
    for (ImageEvidence image : images)
      if (image.id.equals(id)) {
        image.included = include;
        ++contextCorrections;
        cancelContextWork();
        ++imageRevision;
        ++imageStateVersion;
        if (active != null) active.invalid = true;
        pendingAnswer = null;
        requestImageAnalysis(c, keys, selected);
        publish();
        return;
      }
  }

  public synchronized void cancelImage(String id) {
    Runnable cancel = imageCancellation.remove(id);
    if (cancel != null) cancel.run();
    imageState(id, ImageEvidence.State.CANCELLED, "图像任务已取消");
  }

  private void cancelAllImages() {
    for (Runnable cancel : imageCancellation.values()) cancel.run();
    imageCancellation.clear();
    imageRequested = false;
    imageCredentials = null;
  }

  private boolean drainImageAnalysis() {
    if (!imageRequested || active != null || state == State.FINALIZING || closed) return false;
    imageRequested = false;
    if (selectedImageIds().isEmpty() && transcript.finalText().trim().isEmpty()) return false;
    beginAnalysis(
        finalized ? finalText : transcript.finalText().trim(),
        true,
        automatic ? selected : imagePreference);
    return active != null;
  }

  private java.util.List<ImageEvidence> selectedImages() {
    java.util.List<ImageEvidence> result = new java.util.ArrayList<>();
    int size = 0;
    for (int i = images.size() - 1; i >= 0 && result.size() < 3; i--) {
      ImageEvidence e = images.get(i);
      if (e.state != ImageEvidence.State.READY || !e.included) continue;
      if (e.structure.length() > 48000 || size + e.structure.length() > 48000) continue;
      result.add(0, e);
      size += e.structure.length();
    }
    return result;
  }

  private java.util.List<String> selectedImageIds() {
    java.util.List<String> ids = new java.util.ArrayList<>();
    for (ImageEvidence e : selectedImages()) ids.add(e.id);
    return ids;
  }

  private com.google.gson.JsonArray selectedImageData() {
    com.google.gson.JsonArray data = new com.google.gson.JsonArray();
    for (ImageEvidence e : selectedImages()) {
      com.google.gson.JsonObject v = new com.google.gson.JsonObject();
      v.addProperty("image_id", e.id);
      v.addProperty("captured_at", e.capturedAt);
      v.addProperty("audio_end_ms_at_capture", e.audioEndMs);
      v.addProperty("transcript_at_capture", e.transcriptAnchor);
      com.google.gson.JsonObject geometry = new com.google.gson.JsonObject();
      geometry.addProperty("recognition_width", e.width);
      geometry.addProperty("recognition_height", e.height);
      geometry.addProperty("oriented_original_width", e.originalWidth);
      geometry.addProperty("oriented_original_height", e.originalHeight);
      geometry.addProperty("rotation_degrees", e.rotation);
      com.google.gson.JsonArray crop = new com.google.gson.JsonArray();
      crop.add(e.cropLeft);
      crop.add(e.cropTop);
      crop.add(e.cropRight);
      crop.add(e.cropBottom);
      geometry.add("crop_in_oriented_original", crop);
      v.add("geometry", geometry);
      v.add("layout", com.google.gson.JsonParser.parseString(e.structure));
      data.add(v);
    }
    return data;
  }

  private void remember() {
    if (!remembered && !transcript.finalText().trim().isEmpty()) {
      String rememberedText =
          finalized ? finalText : "[该轮未完整收尾，仅包含已定稿原文]\n" + transcript.finalText();
      prompts.remember(Prompts.window(rememberedText, 12000));
      java.util.Set<String> ids = new java.util.HashSet<>();
      for (ContextMemory.Source source : contextArchive) ids.add(source.id());
      for (ContextMemory.Source source : currentContextSources())
        if (ids.add(source.id()))
          contextArchive.add(
              new ContextMemory.Source(
                  source.id(), (finalized ? "" : "[该轮未完整收尾，仅包含已定稿原文]\n") + source.text(), false));
      remembered = true;
    }
  }

  public synchronized void cancel() {
    cancelContextWork();
    cancelAllImages();
    for (ImageEvidence image : images)
      if (image.state == ImageEvidence.State.QUEUED
          || image.state == ImageEvidence.State.RECOGNIZING) {
        image.state = ImageEvidence.State.CANCELLED;
        image.error = "图像任务已取消";
        ++imageStateVersion;
      }
    if (!snapshot().busy()) {
      publish();
      return;
    }
    ++generation;
    dispose();
    credentials = null;
    state = State.ERROR;
    analysis = finalized ? AnalysisState.ERROR : AnalysisState.IDLE;
    analysisStatus = finalized ? "分析已取消" : "聆听已终止，建议可能不完整";
    status = finalized ? "已取消分析，可手动重试" : "已取消聆听，原文可能不完整；未提交尾段分析";
    publish();
  }

  public synchronized void background() {
    if (state == State.CONNECTING || state == State.LISTENING || state == State.FINALIZING) {
      cancel();
      status = "应用离开前台，聆听已终止；请返回后重新开始";
      publish();
    }
  }

  public synchronized void newMeeting() {
    ++generation;
    dispose();
    credentials = null;
    prompts.clear();
    contextArchive.clear();
    contextMemory = new ContextMemory.State();
    contextReport = new ContextReport();
    contextReport.budget = contextOptions.budget();
    contextRoundId = java.util.UUID.randomUUID().toString();
    slowSamples.clear();
    contextNoticeConsumed = contextNoticeAt = nextContextNotice = 0;
    cancelAllImages();
    images.clear();
    ++imageStateVersion;
    answerImages.clear();
    pendingImages.clear();
    imageRevision++;
    transcript.clear();
    answer = "";
    finalText = "";
    pendingAnswer = null;
    finalized = false;
    remembered = false;
    coveredMs = 0;
    analysis = AnalysisState.IDLE;
    analysisStatus = "";
    state = State.IDLE;
    status = "新会议 · 上下文已清空";
    publish();
  }

  private synchronized void fail(long round, String m) {
    if (round != generation
        || !(state == State.CONNECTING || state == State.LISTENING || state == State.FINALIZING))
      return;
    boolean keepImage = active != null && active.visual;
    ++generation;
    if (keepImage) {
      ++scheduleId;
      disposeSpeech();
    } else dispose();
    credentials = null;
    state = State.ERROR;
    analysis = keepImage ? AnalysisState.GENERATING : AnalysisState.IDLE;
    analysisStatus = keepImage ? "更新中" : "听写已中断，未继续分析";
    status = m;
    publish();
  }

  private void timeout(long round, State expected, long delay, String m) {
    clock.schedule(
        () -> {
          synchronized (SessionEngine.this) {
            if (round == generation && state == expected) fail(round, m);
          }
        },
        delay);
  }

  private void disposeSpeech() {
    if (audio != null) {
      audio.cancel();
      audio = null;
    }
    if (speech != null) {
      speech.cancel();
      speech = null;
    }
  }

  private void dispose() {
    ++contextEpoch;
    cancelContextWork();
    imageRequested = false;
    imageCredentials = null;
    imageConfig = null;
    ++scheduleId;
    disposeSpeech();
    Request r = active;
    active = null;
    if (r != null) cancelProvider(r);
    pendingAnswer = null;
  }

  @Override
  public synchronized void close() {
    closed = true;
    cancelAllImages();
    ++generation;
    dispose();
    credentials = null;
    clock.close();
    observers.clear();
  }
}
