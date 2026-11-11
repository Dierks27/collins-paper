package org.sawiq.collins.paper.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlPolicyTest {

    /** The list shipped in config.yml. */
    private static final List<String> SHIPPED = List.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
            "youtube-nocookie.com", "twitch.tv", "www.twitch.tv", "m.twitch.tv");

    private final UrlPolicy policy = new UrlPolicy(true, SHIPPED);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://youtube.com/watch?v=dQw4w9WgXcQ",          // exact
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",      // listed subdomain
            "https://gaming.youtube.com/x",                     // unlisted subdomain of a listed domain
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/x",
            "https://www.twitch.tv/somechannel",
            "http://m.twitch.tv/somechannel",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=x",                // upper case
            "https://YouTube.Com/watch?v=x",
            "https://www.youtube.com./watch?v=x",               // trailing dot
            "https://www.youtube.com:443/watch?v=x",            // port
            "  https://youtu.be/x  ",                           // surrounding spaces
    })
    void allowed(String url) {
        assertTrue(policy.hostAllowed(url), url);
        assertTrue(policy.mayPlay(url), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/video.mp4",                    // direct file
            "https://rutube.ru/video/abc/",
            "https://vk.com/video-1_2",
            "https://youtube.com.evil.example/x",               // look-alike suffix
            "https://evil.example/?youtube.com",                // name in the query
            "https://evil.example/youtube.com/watch",           // name in the path
            "https://evil.example/#youtube.com",
            "https://notyoutube.com/x",                         // "contains", no dot boundary
            "https://youtube.com@evil.example/",                // @ trick
            "https://youtube.com:pw@evil.example/",
            "https://evil.example\\@youtube.com/",              // backslash trick (not a valid URI)
            "https://xn--yutube-wqf.com/x",                     // punycode look-alike
            "https://уoutube.com/x",                       // Cyrillic "у" (IDN homograph)
            "https://youtube.co/x",
            "https://tv/x",
            "ftp://youtube.com/x",                              // not http(s)
            "youtube.com/watch?v=x",                            // no scheme
            "//youtube.com/x",
            "https:///youtube.com",                             // empty host
            "",
    })
    void refused(String url) {
        assertFalse(policy.hostAllowed(url), url);
    }

    @Test
    void blankAddressIsNotAHostButMayAlwaysPlay() {
        assertFalse(policy.hostAllowed(""));
        assertTrue(policy.mayPlay(""));
        assertTrue(policy.mayPlay(null));
    }

    @Test
    void unlistedAddressPlaysOnlyOnceABypassHolderApprovedIt() {
        String mp4 = "https://example.com/video.mp4";
        assertFalse(policy.mayPlay(mp4));

        policy.approve(mp4);
        assertTrue(policy.mayPlay(mp4));
        // Approval is for that exact address, not for its host.
        assertFalse(policy.mayPlay("https://example.com/other.mp4"));
    }

    @Test
    void restrictionOffBehavesAsBefore() {
        UrlPolicy off = new UrlPolicy(false, SHIPPED);
        assertTrue(off.mayPlay("https://example.com/video.mp4"));
        assertTrue(off.mayPlay("https://rutube.ru/video/abc/"));
    }

    @Test
    void listEntriesAreNormalisedAndAnEmptyListAllowsNothing() {
        UrlPolicy sloppy = new UrlPolicy(true, List.of(" YouTube.COM. ", ""));
        assertTrue(sloppy.hostAllowed("https://www.youtube.com/x"));
        assertFalse(sloppy.hostAllowed("https://twitch.tv/x"));

        UrlPolicy empty = new UrlPolicy(true, List.of());
        assertFalse(empty.mayPlay("https://www.youtube.com/x"));
    }
}
