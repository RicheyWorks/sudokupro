package com.xai.sudokupro.model.api;

/**
 * Wire representation of the caller's relationship to today's daily puzzle,
 * shared by the server and the desktop client.
 *
 * @param date        the puzzle's UTC date (ISO-8601, e.g. "2026-07-16")
 * @param difficulty  numeric difficulty of today's puzzle (1-4 scale)
 * @param joined      caller has a game in progress (or finished) for today
 * @param completed   caller has solved today's puzzle
 * @param streakDays  consecutive daily puzzles completed, including today if done
 */
public record DailyStatus(
    String date,
    int difficulty,
    boolean joined,
    boolean completed,
    int streakDays
) {}
