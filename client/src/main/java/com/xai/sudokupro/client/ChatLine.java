package com.xai.sudokupro.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Renders one chat line for display.
 *
 * <p>Chat used to be labelled twice. The desktop client built
 * {@code "[12:04:31] ann: hi"} and sent <em>that string</em> as the chat payload;
 * the server relays chat with the sender in the envelope's {@code from} field, and
 * the receiving client prepends it again — so every peer read
 * {@code "ann: [12:04:31] ann: hi"}. The wire now carries the message and only the
 * message; the name and the clock are added here, once, at the point of display.
 */
public final class ChatLine {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ChatLine() { }

    /** {@code "[HH:mm:ss] speaker: text"}. */
    public static String render(LocalTime at, String speaker, String text) {
        return "[" + CLOCK.format(at) + "] " + speaker + ": " + text;
    }

    /** {@link #render} at the current local time. */
    public static String renderNow(String speaker, String text) {
        return render(LocalTime.now(), speaker, text);
    }
}
