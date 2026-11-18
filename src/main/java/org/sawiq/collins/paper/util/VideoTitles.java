package org.sawiq.collins.paper.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Video titles: reading them from yt-dlp, and making any title (from a site
 * or typed by a player) safe to show. No Bukkit types, so it can be
 * unit-tested.
 */
public final class VideoTitles {

    /** Longest title kept, in characters. */
    public static final int MAX_LENGTH = 64;

    private VideoTitles() {}

    /**
     * The title in yt-dlp's {@code --dump-single-json} output, cleaned, or
     * null when there is none. Live streams report the stream title.
     */
    public static String fromYtDlpJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            // yt-dlp prints exactly one JSON object; tolerate stray text before it.
            int start = json.indexOf('{');
            if (start < 0) return null;
            JsonElement root = JsonParser.parseString(json.substring(start));
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            for (String key : new String[]{"title", "fulltitle"}) {
                JsonElement e = o.get(key);
                if (e != null && e.isJsonPrimitive()) {
                    String t = clean(e.getAsString());
                    if (t != null) return t;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Makes a title safe to put in chat and on items: control characters
     * become spaces, runs of spaces become one, Minecraft colour codes
     * ({@code §x} and {@code &x}) are neutralised so a title cannot recolour
     * or garble the text around it, and it is cut to {@link #MAX_LENGTH}.
     * Returns null when nothing is left.
     */
    public static String clean(String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') continue;                      // the section sign starts a colour code
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        // "&a" etc. would be turned into colours by the plugin's messages:
        // put an invisible separator between the & and the code letter.
        String s = b.toString().replaceAll("(?i)&([0-9a-fk-orx#])", "&​$1");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > MAX_LENGTH) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_LENGTH - 3)).trim() + "...";
        }
        return s;
    }
}
