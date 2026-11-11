package org.sawiq.collins.paper.store;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.util.YouTubeQuality;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public final class ScreenStore {
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();

    /** Who made a screen. The name is only what they were called then, for display. */
    public record Owner(UUID id, String name) {}

    /**
     * Screen name (lower case) -> owner. Kept beside the screens instead of
     * inside {@link Screen}, so the many places that rebuild a Screen to
     * change one field cannot drop the owner. A screen without an entry has
     * no owner and is managed by admins only.
     */
    private final Map<String, Owner> owners = new ConcurrentHashMap<>();
    private final JavaPlugin plugin;
    private final File file;

    public ScreenStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "screens.yml");
    }

    public Collection<Screen> all() {
        return screens.values();
    }

    public Screen get(String name) {
        return screens.get(name.toLowerCase());
    }

    public void put(Screen screen) {
        screens.put(screen.name().toLowerCase(), screen);
    }

    public Screen remove(String name) {
        owners.remove(name.toLowerCase());
        return screens.remove(name.toLowerCase());
    }

    /** The screen's owner, or null when it has none. */
    public Owner ownerOf(String name) {
        return owners.get(name.toLowerCase());
    }

    /** Sets the screen's owner; null clears it. */
    public void setOwner(String name, Owner owner) {
        if (owner == null) owners.remove(name.toLowerCase());
        else owners.put(name.toLowerCase(), owner);
    }

    public int countOwnedBy(UUID id) {
        int n = 0;
        for (Owner o : owners.values()) {
            if (o.id().equals(id)) n++;
        }
        return n;
    }

    public void load() {
        screens.clear();
        owners.clear();
        if (!file.exists()) return;

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("screens")) return;

        for (String key : Objects.requireNonNull(cfg.getConfigurationSection("screens")).getKeys(false)) {
            String path = "screens." + key + ".";
            String name = cfg.getString(path + "name", key);
            String world = cfg.getString(path + "world", "world");
            int defaultYoutubeQuality = YouTubeQuality.sanitize(
                    plugin.getConfig().getInt("video.defaultYouTubeQuality", YouTubeQuality.DEFAULT),
                    YouTubeQuality.DEFAULT
            );

            int x1 = cfg.getInt(path + "x1");
            int y1 = cfg.getInt(path + "y1");
            int z1 = cfg.getInt(path + "z1");
            int x2 = cfg.getInt(path + "x2");
            int y2 = cfg.getInt(path + "y2");
            int z2 = cfg.getInt(path + "z2");

            int axis = cfg.getInt(path + "axis", 0);
            String url = cfg.getString(path + "mp4Url", "");
            boolean playing = cfg.getBoolean(path + "playing", false);
            boolean loop = cfg.getBoolean(path + "loop", false);
            double volumeD = cfg.getDouble(path + "volume", 1.0);
            int youtubeQuality = YouTubeQuality.sanitize(
                    cfg.getInt(path + "youtubeQuality", defaultYoutubeQuality),
                    defaultYoutubeQuality
            );

            put(new Screen(
                    name,
                    world,
                    x1, y1, z1,
                    x2, y2, z2,
                    (byte) axis,
                    url == null ? "" : url,
                    playing,
                    loop,
                    (float) volumeD,
                    youtubeQuality
            ));

            String ownerId = cfg.getString(path + "owner");
            if (ownerId != null) {
                try {
                    setOwner(name, new Owner(UUID.fromString(ownerId), cfg.getString(path + "ownerName", "?")));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("screens.yml: screen '" + name + "' has a broken owner '" + ownerId
                            + "'; treating it as having no owner.");
                }
            }
        }
    }

    public void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder");
            return;
        }

        YamlConfiguration cfg = new YamlConfiguration();
        for (Screen s : all()) {
            String key = safeKey(s.name());
            String path = "screens." + key + ".";
            cfg.set(path + "name", s.name());
            cfg.set(path + "world", s.world());
            cfg.set(path + "x1", s.x1());
            cfg.set(path + "y1", s.y1());
            cfg.set(path + "z1", s.z1());
            cfg.set(path + "x2", s.x2());
            cfg.set(path + "y2", s.y2());
            cfg.set(path + "z2", s.z2());
            cfg.set(path + "axis", (int) s.axis());
            cfg.set(path + "mp4Url", s.mp4Url());
            cfg.set(path + "playing", s.playing());
            cfg.set(path + "loop", s.loop());
            cfg.set(path + "volume", (double) s.volume());
            cfg.set(path + "youtubeQuality", s.youtubeQuality());
            Owner owner = ownerOf(s.name());
            if (owner != null) {
                cfg.set(path + "owner", owner.id().toString());
                cfg.set(path + "ownerName", owner.name());
            }
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save screens.yml: " + e.getMessage());
        }
    }

    private String safeKey(String screenName) {
        String n = screenName == null ? "" : screenName.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) n = "screen";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(n.getBytes(StandardCharsets.UTF_8));
    }
}
