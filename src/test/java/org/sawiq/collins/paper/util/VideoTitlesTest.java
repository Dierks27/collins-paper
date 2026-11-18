package org.sawiq.collins.paper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoTitlesTest {

    @Test
    void readsTheTitleFromYtDlpJson() {
        String json = "{\"id\":\"dQw4w9WgXcQ\",\"title\":\"Rick Astley - Never Gonna Give You Up\",\"duration\":213}";
        assertEquals("Rick Astley - Never Gonna Give You Up", VideoTitles.fromYtDlpJson(json));
    }

    @Test
    void handlesEscapesAndUnicode() {
        String json = "{\"title\":\"Caf\\u00e9 \\\"live\\\" \\ud83c\\udfb5 \\u0432\\u0438\\u0434\\u0435\\u043e\"}";
        assertEquals("Café \"live\" 🎵 видео", VideoTitles.fromYtDlpJson(json));
    }

    @Test
    void fallsBackToFulltitleAndToleratesStrayTextBeforeTheJson() {
        assertEquals("Stream", VideoTitles.fromYtDlpJson("WARNING: something\n{\"title\":\"  \",\"fulltitle\":\"Stream\"}"));
    }

    @Test
    void noTitle() {
        assertNull(VideoTitles.fromYtDlpJson("{\"duration\":10}"));
        assertNull(VideoTitles.fromYtDlpJson("not json"));
        assertNull(VideoTitles.fromYtDlpJson("{\"title\": {\"nested\": 1}}"));
        assertNull(VideoTitles.fromYtDlpJson(""));
        assertNull(VideoTitles.fromYtDlpJson(null));
    }

    @Test
    void cleanCollapsesWhitespaceAndControlCharacters() {
        assertEquals("a b c", VideoTitles.clean("  a\n\tb \u0000  c "));
        assertNull(VideoTitles.clean(" \n "));
        assertNull(VideoTitles.clean(null));
    }

    @Test
    void cleanNeutralisesColourCodes() {
        String s = VideoTitles.clean("&cRed §lbold & Tom & Jerry &#ff0000");
        assertFalse(s.contains("§"));
        assertFalse(s.matches("(?s).*&[0-9a-fk-orx#].*"), s);
        assertTrue(s.contains("Tom & Jerry"));
    }

    @Test
    void cleanCutsLongTitles() {
        String s = VideoTitles.clean("x".repeat(200));
        assertEquals(VideoTitles.MAX_LENGTH, s.length());
        assertTrue(s.endsWith("..."));
        String emoji = VideoTitles.clean("🎵".repeat(100));
        assertEquals(VideoTitles.MAX_LENGTH, emoji.codePointCount(0, emoji.length()));
    }
}
