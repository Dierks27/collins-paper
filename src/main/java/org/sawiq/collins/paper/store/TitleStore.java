package org.sawiq.collins.paper.store;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Playlist;
import org.sawiq.collins.paper.util.VideoTitles;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names for videos, kept in {@code titles.yml}.
 *
 * <ul>
 *   <li><b>Titles</b> come from the site (yt-dlp) and belong to the address,
 *       so the same video shows the same title on every screen.</li>
 *   <li><b>Names</b> are typed by a player and belong to one screen and one
 *       address, so naming a video on your screen never relabels it on
 *       someone else's. A name wins over the title.</li>
 * </ul>
 *
 * <p>Addresses are stored as list entries, not as YAML keys, because the
 * dots and slashes in them would be read as nested paths.</p>
 */
public final class TitleStore {

    private final JavaPlugin plugin;
    private final File file;

    /** address -> title from the site */
    private final Map<String, String> titles = new ConcurrentHashMap<>();
    /** screen name (lower case) -> address -> name a player gave it */
    private final Map<String, Map<String, String>> names = new ConcurrentHashMap<>();

    public TitleStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "titles.yml");
    }

    private static String key(String url) {
        return url == null ? "" : url.trim();
    }

    private static String screenKey(String screen) {
        return screen.toLowerCase(Locale.ROOT);
    }

    // ==================== Reading ====================

    /**
     * What to call the video at {@code url} on {@code screen}: the name a
     * player gave it there, else the site's title, else {@code fallback}
     * (for example the short label a playlist makes from the address).
     */
    public String titleFor(String screen, String url, String fallback) {
        if (url == null || url.isBlank()) return fallback;
        if (screen != null) {
            Map<String, String> forScreen = names.get(screenKey(screen));
            String named = forScreen == null ? null : forScreen.get(key(url));
            if (named != null) return named;
        }
        String title = titles.get(key(url));
        return title != null ? title : fallback;
    }

    /** The label used when there is no title at all: the playlist's short form of the address. */
    public String titleFor(String screen, String url) {
        return titleFor(screen, url, url == null || url.isBlank() ? "" : new Playlist.PlaylistEntry(0, url).title());
    }

    public boolean hasTitle(String url) {
        return titles.containsKey(key(url));
    }

    // ==================== Writing ====================

    /** A title from the site. May be called from any thread. */
    public void putTitle(String url, String title) {
        String clean = VideoTitles.clean(title);
        if (url == null || url.isBlank() || clean == null) return;
        if (clean.equals(titles.put(key(url), clean))) return; // unchanged: nothing to save
        save();
    }

    /** A name a player gave the video at {@code url} on {@code screen}; null clears it. Returns the stored name. */
    public String setName(String screen, String url, String name) {
        String clean = VideoTitles.clean(name);
        Map<String, String> forScreen = names.computeIfAbsent(screenKey(screen), k -> new ConcurrentHashMap<>());
        if (clean == null) forScreen.remove(key(url));
        else forScreen.put(key(url), clean);
        if (forScreen.isEmpty()) names.remove(screenKey(screen));
        save();
        return clean;
    }

    /** A screen was removed: forget the names given on it. */
    public void forgetScreen(String screen) {
        if (names.remove(screenKey(screen)) != null) save();
    }

    /**
     * At startup: drop titles and names of addresses no screen or playlist
     * uses any more, so the file cannot grow without bound.
     *
     * @param inUse screen name (lower case) -> addresses that screen uses
     */
    public void prune(Map<String, Set<String>> inUse) {
        Set<String> allUrls = new java.util.HashSet<>();
        inUse.values().forEach(allUrls::addAll);
        boolean changed = titles.keySet().retainAll(allUrls);
        for (var it = names.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Set<String> urls = inUse.get(e.getKey());
            if (urls == null) {
                it.remove();
                changed = true;
                continue;
            }
            changed |= e.getValue().keySet().retainAll(urls);
            if (e.getValue().isEmpty()) it.remove();
        }
        if (changed) save();
    }

    // ==================== File ====================

    public void load() {
        titles.clear();
        names.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : cfg.getMapList("titles")) {
            Object url = m.get("url"), title = m.get("title");
            String clean = title == null ? null : VideoTitles.clean(title.toString());
            if (url != null && clean != null) titles.put(key(url.toString()), clean);
        }
        for (Map<?, ?> m : cfg.getMapList("names")) {
            Object screen = m.get("screen"), url = m.get("url"), name = m.get("name");
            String clean = name == null ? null : VideoTitles.clean(name.toString());
            if (screen != null && url != null && clean != null) {
                names.computeIfAbsent(screenKey(screen.toString()), k -> new ConcurrentHashMap<>())
                        .put(key(url.toString()), clean);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        List<Map<String, String>> t = new ArrayList<>();
        new java.util.TreeMap<>(titles).forEach((url, title) -> t.add(entry("url", url, "title", title)));
        List<Map<String, String>> n = new ArrayList<>();
        new java.util.TreeMap<>(names).forEach((screen, m) ->
                new java.util.TreeMap<>(m).forEach((url, name) -> {
                    Map<String, String> e = entry("screen", screen, "url", url);
                    e.put("name", name);
                    n.add(e);
                }));
        cfg.set("titles", t);
        cfg.set("names", n);
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save titles.yml: " + e.getMessage());
        }
    }

    private static Map<String, String> entry(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
