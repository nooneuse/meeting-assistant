package io.github.meetingassistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persisted allowlist, deliberately independent of provider settings. */
public final class MeetingRecord {
  public int version = 3;
  public String collectionId = TopicCatalog.DEFAULT;
  public String id = UUID.randomUUID().toString(), title = "", roundId = "";
  public boolean customTitle, autoNamed, draft;
  public long createdAt = System.currentTimeMillis(), updatedAt = createdAt;
  public SessionEngine.SavedState current = new SessionEngine.SavedState();
  public List<Round> rounds = new ArrayList<>();
  public ViewState view = new ViewState();

  public static final class ViewState {
    public int answerScroll, transcriptScroll;
    public boolean transcriptExpanded,
        transcriptVisibilityChosen,
        followTranscript = true,
        followAnswer = true;
  }

  public static final class Round {
    public String id, transcript, answer, preference;
    public String contextRoundId = "";
    public long at;
    public UserSettings.Profile preferenceSnapshot;
    public List<SpeechProvider.Segment> segments;
    public List<Transcript.Notice> audioNotices;
    public List<String> imageIds;
    public boolean complete, answerComplete;

    public Round() {}

    public Round(String id, SessionEngine.SavedState s) {
      this.id = id;
      contextRoundId = s.contextRoundId;
      transcript = s.transcript(true);
      segments = new ArrayList<>(s.segments);
      audioNotices = new ArrayList<>(s.audioNotices);
      answer = s.pendingAnswer == null ? s.answer : s.pendingAnswer;
      imageIds = new ArrayList<>(s.pendingAnswer == null ? s.answerImages : s.pendingImages);
      UserSettings.Profile actual =
          s.pendingAnswer != null ? s.pendingPreference : s.answerPreference;
      if (actual == null) actual = s.preference;
      preference = actual.name;
      preferenceSnapshot = new UserSettings.Profile(actual.id, actual.name, actual.prompt);
      at = s.startedAt;
      complete = s.finalized;
      answerComplete = s.analysis == SessionEngine.AnalysisState.DONE;
    }

    public String transcript(String partialMarker) {
      if (segments == null) return transcript;
      SessionEngine.SavedState text = new SessionEngine.SavedState();
      text.segments = segments;
      text.audioNotices = audioNotices;
      return text.transcript(true, partialMarker);
    }
  }
}
