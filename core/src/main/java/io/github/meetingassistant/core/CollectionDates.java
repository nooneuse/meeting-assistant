package io.github.meetingassistant.core;

import java.time.*;
import java.time.temporal.ChronoUnit;

/** Locale-independent section keys, using calendar dates so DST never shifts a section. */
public final class CollectionDates {
  private CollectionDates() {}

  public static String section(long modified, long now, ZoneId zone) {
    LocalDate date = Instant.ofEpochMilli(modified).atZone(zone).toLocalDate();
    LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    long days = ChronoUnit.DAYS.between(date, today);
    if (days <= 0) return "today";
    if (days == 1) return "yesterday";
    if (days < 7) return "week";
    return YearMonth.from(date).toString();
  }
}
