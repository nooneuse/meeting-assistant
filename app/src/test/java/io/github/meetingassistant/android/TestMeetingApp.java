package io.github.meetingassistant.android;

import io.github.meetingassistant.core.MeetingManager;

/** JVM UI tests use an explicit in-memory store; production always uses the encrypted store. */
public class TestMeetingApp extends MeetingApp {
  @Override
  protected MeetingManager.Store createMeetingStore() {
    return new MeetingManager.MemoryStore();
  }
}
