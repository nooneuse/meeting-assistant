package io.github.meetingassistant.android;

import android.app.Application;
import io.github.meetingassistant.core.AudioSource;
import io.github.meetingassistant.core.FunAsrProvider;
import io.github.meetingassistant.core.NetworkClients;
import io.github.meetingassistant.core.OpenAiTextProvider;
import io.github.meetingassistant.core.ProviderConfig;
import io.github.meetingassistant.core.SessionEngine;
import io.github.meetingassistant.core.SpeechProvider;
import io.github.meetingassistant.core.TextProvider;
import io.github.meetingassistant.core.UserSettings;
import okhttp3.OkHttpClient;

public class MeetingApp extends Application {
  SettingsRepository repository;
  UserSettings settings;
  SessionEngine session;
  ImageInputController video;
  AudioInputController audio;
  final io.github.meetingassistant.core.ImageWorkQueue imageJobs =
      new io.github.meetingassistant.core.ImageWorkQueue();
  io.github.meetingassistant.core.MeetingManager meetings;
  private String activeMeetingId = "";
  Runnable meetingObserver;
  private Runnable managerListener;
  private final java.util.concurrent.atomic.AtomicBoolean notifying =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  boolean awake,
      transcriptExpanded,
      transcriptVisibilityChosen,
      followTranscript = true,
      followAnswer = true;
  int answerScroll, transcriptScroll;
  String startupError = "";

  @Override
  public void onCreate() {
    super.onCreate();
    repository = new SettingsRepository(this);
    try {
      settings = repository.load();
    } catch (IllegalStateException e) {
      settings = new UserSettings();
      startupError = e.getMessage();
    }
    OkHttpClient client = NetworkClients.create();
    video = createImageInput();
    audio = createAudioInput();
    java.util.function.Supplier<SessionEngine> factory =
        () -> {
          SessionEngine engine =
              new SessionEngine(
                  new SessionEngine.Factory() {
                    @Override
                    public SpeechProvider speech(ProviderConfig c, ProviderConfig.Credentials k) {
                      return new FunAsrProvider(client, c, k);
                    }

                    @Override
                    public TextProvider text(ProviderConfig c, ProviderConfig.Credentials k) {
                      return new OpenAiTextProvider(client, c, k);
                    }

                    @Override
                    public AudioSource audio() {
                      return audio.source();
                    }
                  });
          engine.configureContext(settings.context);
          return engine;
        };
    bindMeetings(
        new io.github.meetingassistant.core.MeetingManager(
            createMeetingStore(), factory, settings.snapshot(), settings.automatic));
    if (!meetings.error().isEmpty()) startupError = meetings.error();
  }

  void bindMeetings(io.github.meetingassistant.core.MeetingManager replacement) {
    if (meetings != null && managerListener != null) meetings.detach(managerListener);
    meetings = replacement;
    activeMeetingId = "";
    notifying.set(false);
    synchronizeMeeting();
    managerListener =
        () -> {
          if (meetings != replacement) return;
          if (notifying.compareAndSet(false, true))
            main.postDelayed(
                () -> {
                  if (meetings != replacement) return;
                  notifying.set(false);
                  synchronizeMeeting();
                  if (meetingObserver != null) meetingObserver.run();
                },
                50);
        };
    meetings.observe(managerListener);
  }

  protected io.github.meetingassistant.core.MeetingManager.Store createMeetingStore() {
    return new MeetingRepository(this);
  }

  protected AudioInputController createAudioInput() {
    return new AudioInputController(this, settings.audio);
  }

  protected ImageInputController createImageInput() {
    return new ImageInputController(this, settings.image);
  }

  void synchronizeMeeting() {
    String id = meetings.currentId();
    if (id.equals(activeMeetingId)) return;
    activeMeetingId = id;
    session = meetings.currentEngine();
    session.configureContext(settings.context);
    io.github.meetingassistant.core.MeetingRecord r = meetings.current();
    settings.selectedId = settings.find(r.current.selected.id) == null ? "" : r.current.selected.id;
    settings.automatic = r.current.automatic;
    answerScroll = r.view.answerScroll;
    transcriptScroll = r.view.transcriptScroll;
    transcriptExpanded = r.view.transcriptExpanded;
    transcriptVisibilityChosen = r.view.transcriptVisibilityChosen;
    followTranscript = r.view.followTranscript;
    followAnswer = r.view.followAnswer;
  }

  void saveMeetingView() {
    if (!activeMeetingId.equals(meetings.currentId())) return;
    io.github.meetingassistant.core.MeetingRecord.ViewState v =
        new io.github.meetingassistant.core.MeetingRecord.ViewState();
    v.answerScroll = answerScroll;
    v.transcriptScroll = transcriptScroll;
    v.transcriptExpanded = transcriptExpanded;
    v.transcriptVisibilityChosen = transcriptVisibilityChosen;
    v.followTranscript = followTranscript;
    v.followAnswer = followAnswer;
    meetings.saveView(v);
  }

  void persist() {
    repository.save(settings);
    session.configureContext(settings.context);
    session.setAutomatic(settings.automatic);
    session.selectPreference(settings.snapshot());
  }
}
