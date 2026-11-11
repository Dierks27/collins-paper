package org.sawiq.collins.paper.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which addresses a screen may play.
 *
 * <p>A player's game fetches the video itself, from whatever host the
 * address names, as soon as they come near a playing screen. So with
 * {@code security.restrictHosts} on, an address is playable only when its
 * host is on the allow-list ({@code security.allowedHosts}), or when a
 * player holding {@link #BYPASS_PERMISSION} put it there, which approves
 * that exact address for everyone.</p>
 *
 * <p>No Bukkit types here, so the rules can be unit-tested on their own.</p>
 */
public final class UrlPolicy {

    /** Holders may put any address on a screen that the basic URL checks accept. */
    public static final String BYPASS_PERMISSION = "collins.url.any";

    private final boolean restricted;
    private final List<String> allowedHosts = new ArrayList<>();
    private final Set<String> approvedUrls = ConcurrentHashMap.newKeySet();

    public UrlPolicy(boolean restricted, Collection<String> allowedHosts) {
        this.restricted = restricted;
        if (allowedHosts != null) {
            for (String host : allowedHosts) {
                String h = normalizeHost(host);
                if (h != null) this.allowedHosts.add(h);
            }
        }
    }

    public boolean restricted() {
        return restricted;
    }

    /**
     * True when the address's host is a listed host or a subdomain of one.
     * Only the host part counts: a listed name elsewhere in the address
     * (path, query, the part before an {@code @}) matches nothing.
     */
    public boolean hostAllowed(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    /**
     * May this address be sent to players? Always true with the
     * restriction off, and for "no address at all".
     */
    public boolean mayPlay(String url) {
        if (!restricted || url == null || url.isBlank()) return true;
        return hostAllowed(url) || approvedUrls.contains(url.trim());
    }

    /** Records that a holder of the bypass permission put this address on a screen. */
    public void approve(String url) {
        if (url != null && !url.isBlank()) approvedUrls.add(url.trim());
    }

    public Set<String> approvedUrls() {
        return approvedUrls;
    }

    /** The lower-cased host of an http(s) address without a trailing dot, or null. */
    static String hostOf(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            return normalizeHost(uri.getHost());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }
}
