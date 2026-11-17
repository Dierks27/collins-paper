package org.sawiq.collins.paper.panel;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.panel.PanelGeometry.Panel;
import org.sawiq.collins.paper.store.ScreenStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The screen panel: a thin {@link BlockDisplay} over each face of every
 * screen, so a screen reads as a switched-off TV whether or not something is
 * playing and whether or not the player has the client mod. The client mod's
 * video draws on top of it (see {@link PanelGeometry}).
 *
 * <p>Panels are never saved with the world ({@code setPersistent(false)}):
 * they vanish when their chunk unloads and are spawned again when it loads,
 * so the screens in {@code screens.yml} are the only source of truth. Every
 * panel carries the screen's name and its side in its persistent data, and
 * one routine, {@link #reconcile}, makes a chunk hold exactly the panels it
 * should: it removes tagged panels that do not belong there (deleted screen,
 * duplicate, panels turned off) and spawns the ones that are missing.</p>
 *
 * <p>Folia: everything that touches entities runs on the region thread that
 * owns the chunk, via the region scheduler.</p>
 *
 * <p>Everything about how a panel looks is in this class, so a later switch
 * to an {@code ItemDisplay} with a custom item model only changes
 * {@link #spawn}.</p>
 */
public final class PanelManager implements Listener {

    /** {@link #reconcile} argument: rebuild every panel in the chunk. */
    private static final String ALL = "*";

    /** How far away panels are drawn, as a multiple of the normal display-entity distance. */
    private static final float VIEW_RANGE = 4f;

    /** Settings from {@code screens.panel} in config.yml. */
    public record Settings(boolean enabled, BlockData block, double depth, double offset, int brightness) {}

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final NamespacedKey screenKey;
    private final NamespacedKey sideKey;
    private volatile Settings settings;

    public PanelManager(JavaPlugin plugin, ScreenStore store, ConfigurationSection config) {
        this.plugin = plugin;
        this.store = store;
        this.screenKey = new NamespacedKey(plugin, "panel_screen");
        this.sideKey = new NamespacedKey(plugin, "panel_side");
        this.settings = readSettings(config);
    }

    public Settings settings() {
        return settings;
    }

    // ==================== Settings ====================

    /** Reads {@code screens.panel}; anything unusable falls back to the default with a console warning. */
    public Settings readSettings(ConfigurationSection c) {
        boolean enabled = c == null || c.getBoolean("enabled", true);
        String blockName = c == null ? "BLACK_CONCRETE" : c.getString("block", "BLACK_CONCRETE");
        double depth = c == null ? 0.004 : c.getDouble("depth", 0.004);
        double offset = c == null ? 0.001 : c.getDouble("offset", 0.001);
        int brightness = c == null ? -1 : c.getInt("brightness", -1);

        BlockData block = blockData(blockName);

        if (!(depth > 0 && depth <= 0.5)) {
            plugin.getLogger().warning("screens.panel.depth " + depth + " is not between 0 and 0.5; using 0.004.");
            depth = 0.004;
        }
        if (!(offset >= 0 && offset <= 0.5)) {
            plugin.getLogger().warning("screens.panel.offset " + offset + " is not between 0 and 0.5; using 0.001.");
            offset = 0.001;
        }
        if (PanelGeometry.coversVideo(offset, depth)) {
            plugin.getLogger().warning("screens.panel: offset + depth = " + (offset + depth) + " reaches past "
                    + PanelGeometry.VIDEO_PLANE + " blocks, where the client mod draws the video, so the panel"
                    + " will hide the video. Keep offset + depth below " + PanelGeometry.VIDEO_PLANE + ".");
        }
        if (brightness < -1 || brightness > 15) {
            plugin.getLogger().warning("screens.panel.brightness " + brightness + " is not -1 or 0..15; using -1.");
            brightness = -1;
        }
        return new Settings(enabled, block, depth, offset, brightness);
    }

    /**
     * The block the panel is drawn as. A carpet is drawn as its full block
     * (white carpet as white wool, moss carpet as moss), which has the same
     * texture: a carpet's model is only 1/16 of a block tall, so stretching
     * it would not cover the screen.
     */
    private BlockData blockData(String name) {
        Material m = name == null ? null : Material.matchMaterial(name.trim());
        if (m != null && m.name().endsWith("_CARPET")) {
            String base = m.name().substring(0, m.name().length() - "_CARPET".length());
            Material full = Material.matchMaterial(base + "_WOOL");
            if (full == null) full = Material.matchMaterial(base + "_BLOCK");
            if (full != null) m = full;
        }
        if (m == null || !m.isBlock() || m.isAir()) {
            plugin.getLogger().warning("screens.panel.block '" + name + "' is not a block; using BLACK_CONCRETE.");
            m = Material.BLACK_CONCRETE;
        }
        return m.createBlockData();
    }

    /** Re-reads the settings and rebuilds the panels of one screen, or of all screens when {@code screenName} is null. */
    public void refresh(ConfigurationSection config, String screenName) {
        this.settings = readSettings(config);
        Collection<Screen> screens = screenName == null ? store.all() : listOf(store.get(screenName));
        Set<String> chunks = new HashSet<>();
        for (Screen s : screens) {
            for (Panel p : PanelGeometry.panels(s, settings.offset(), settings.depth())) {
                if (chunks.add(s.world() + ":" + chunkOf(p.anchorBlockX()) + ":" + chunkOf(p.anchorBlockZ()))) {
                    schedule(s.world(), chunkOf(p.anchorBlockX()), chunkOf(p.anchorBlockZ()),
                            screenName == null ? ALL : s.name());
                }
            }
        }
    }

    private static List<Screen> listOf(Screen s) {
        return s == null ? List.of() : List.of(s);
    }

    // ==================== Screen lifecycle ====================

    /** A screen was created or redefined: give it its panels. */
    public void screenChanged(Screen screen) {
        scheduleChunksOf(screen, screen.name());
    }

    /** A screen was removed (or is about to be redefined elsewhere): take its panels away. */
    public void screenRemoved(Screen oldScreen) {
        scheduleChunksOf(oldScreen, oldScreen.name());
    }

    /** At startup: panels for every screen in a loaded chunk, and cleanup of any left over. */
    public void spawnAll() {
        for (Screen s : store.all()) scheduleChunksOf(s, null);
    }

    private void scheduleChunksOf(Screen s, String forceScreen) {
        Set<Long> done = new HashSet<>();
        for (Panel p : PanelGeometry.panels(s, settings.offset(), settings.depth())) {
            int cx = chunkOf(p.anchorBlockX()), cz = chunkOf(p.anchorBlockZ());
            if (done.add(((long) cx << 32) ^ (cz & 0xffffffffL))) schedule(s.world(), cx, cz, forceScreen);
        }
    }

    private void schedule(String worldName, int cx, int cz, String forceScreen) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        Bukkit.getRegionScheduler().execute(plugin, world, cx, cz,
                () -> reconcile(world, cx, cz, forceScreen == null || forceScreen.equals(ALL)
                        ? forceScreen : forceScreen.toLowerCase(Locale.ROOT)));
    }

    /**
     * On plugin disable: remove the panels in loaded chunks, so a reload does
     * not leave them behind. Best effort: on Folia this thread may not own
     * those chunks, and {@link #reconcile} cleans up after a reload anyway.
     */
    public void removeAll() {
        for (World world : Bukkit.getWorlds()) {
            try {
                for (BlockDisplay d : world.getEntitiesByClass(BlockDisplay.class)) {
                    if (isPanel(d)) d.remove();
                }
            } catch (Throwable ignored) {
                // Not this thread's region (Folia).
            }
        }
    }

    // ==================== Chunks ====================

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        if (anchoredIn(c.getWorld().getName(), c.getX(), c.getZ())) {
            schedule(c.getWorld().getName(), c.getX(), c.getZ(), null);
        }
    }

    /**
     * Entities are loaded separately from chunks. Panels are never saved, so
     * normally none come back from disk; this catches any that do (an older
     * build, an edited world) and anything else that no longer belongs.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (isPanel(entity)) {
                Chunk c = e.getChunk();
                schedule(c.getWorld().getName(), c.getX(), c.getZ(), null);
                return;
            }
        }
    }

    private boolean anchoredIn(String world, int cx, int cz) {
        for (Screen s : store.all()) {
            if (!world.equals(s.world())) continue;
            for (Panel p : PanelGeometry.panels(s, settings.offset(), settings.depth())) {
                if (chunkOf(p.anchorBlockX()) == cx && chunkOf(p.anchorBlockZ()) == cz) return true;
            }
        }
        return false;
    }

    /**
     * Makes one chunk hold exactly the panels it should. Runs on the chunk's
     * region thread.
     *
     * @param forceScreen panels of this screen (lower case) are rebuilt even
     *                    when present, because its settings or shape changed
     */
    private void reconcile(World world, int cx, int cz, String forceScreen) {
        if (!world.isChunkLoaded(cx, cz)) return; // unloaded: its panels are already gone
        Settings st = settings;

        // The panels that belong in this chunk, keyed "screen|SIDE".
        List<String> expectedKeys = new ArrayList<>();
        List<Panel> expectedPanels = new ArrayList<>();
        if (st.enabled()) {
            for (Screen s : store.all()) {
                if (!world.getName().equals(s.world())) continue;
                for (Panel p : PanelGeometry.panels(s, st.offset(), st.depth())) {
                    if (chunkOf(p.anchorBlockX()) == cx && chunkOf(p.anchorBlockZ()) == cz) {
                        expectedKeys.add(key(s.name(), p.side().name()));
                        expectedPanels.add(p);
                    }
                }
            }
        }

        Set<String> present = new HashSet<>();
        for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
            if (!isPanel(entity)) continue;
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            String screen = pdc.get(screenKey, PersistentDataType.STRING);
            String k = key(screen, pdc.get(sideKey, PersistentDataType.STRING));
            boolean keep = expectedKeys.contains(k)
                    && !present.contains(k)
                    && (forceScreen == null || !(forceScreen.equals(ALL) || forceScreen.equals(screen)));
            if (keep) present.add(k);
            else entity.remove();
        }

        for (int i = 0; i < expectedKeys.size(); i++) {
            if (!present.contains(expectedKeys.get(i))) {
                String screen = expectedKeys.get(i).substring(0, expectedKeys.get(i).indexOf('|'));
                spawn(world, screen, expectedPanels.get(i), st);
            }
        }
    }

    private void spawn(World world, String screenName, Panel p, Settings st) {
        Location at = new Location(world, p.x(), p.y(), p.z()); // yaw and pitch 0: no rotation
        world.spawn(at, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(screenKey, PersistentDataType.STRING, screenName);
            d.getPersistentDataContainer().set(sideKey, PersistentDataType.STRING, p.side().name());
            d.setBlock(st.block());
            d.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(),
                    new Vector3f((float) p.sizeX(), (float) p.sizeY(), (float) p.sizeZ()),
                    new AxisAngle4f()));
            if (st.brightness() >= 0) {
                d.setBrightness(new Display.Brightness(st.brightness(), st.brightness()));
            }
            d.setViewRange(VIEW_RANGE);
            // Culling box 0 = never culled; the entity sits at a corner of the
            // panel, so a culling box around it would hide most of a big screen.
            d.setDisplayWidth(0f);
            d.setDisplayHeight(0f);
        });
    }

    private boolean isPanel(Entity entity) {
        return entity instanceof BlockDisplay
                && entity.getPersistentDataContainer().has(screenKey, PersistentDataType.STRING);
    }

    private static String key(String screen, String side) {
        return (screen == null ? "" : screen.toLowerCase(Locale.ROOT)) + "|" + side;
    }

    private static int chunkOf(int block) {
        return block >> 4;
    }
}
