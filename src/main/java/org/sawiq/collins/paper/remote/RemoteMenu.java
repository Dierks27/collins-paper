package org.sawiq.collins.paper.remote;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.sawiq.collins.paper.command.ScreenActions;
import org.sawiq.collins.paper.model.Playlist;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.YouTubeQuality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One open chest menu of the TV Remote. The menu is its own
 * {@link InventoryHolder}, which is how {@link RemoteController} recognises
 * its clicks. Buttons only call {@link ScreenActions}; nothing about a
 * screen is decided here.
 *
 * <p>The contents are redrawn after every click and once a second while the
 * menu is open, so it follows what the commands, other players and the end
 * of a video do to the screen.</p>
 */
final class RemoteMenu implements InventoryHolder {

    enum View { LIST, SCREEN, PLAYLIST }

    private static final int PAGE_SIZE = 45;
    private static final long CONFIRM_MS = 5000L;
    private static final int SEEK_SECONDS = 30;
    private static final java.util.Set<String> SHOWS_OWNER = java.util.Set.of("remote.list.screen", "remote.screen.info");

    /** {@code limited}: counts against the per-player action rate limit. */
    private record Button(boolean limited, Consumer<ClickType> onClick) {}

    private final RemoteController ctl;
    private final Lang lang;
    private final Player player;
    private final View view;
    /** The screen this menu is about; null for the list. */
    private final String screenName;
    private final Inventory inventory;

    private final Map<Integer, Button> buttons = new HashMap<>();
    private ItemStack[] drawing;
    private int page;
    private long confirmUntilMs;
    private boolean removeMode;
    private ScheduledTask refreshTask;

    RemoteMenu(RemoteController ctl, Player player, View view, String screenName) {
        this.ctl = ctl;
        this.lang = ctl.lang;
        this.player = player;
        this.view = view;
        this.screenName = screenName;

        String title = switch (view) {
            case LIST -> lang.raw("remote.list.title");
            case SCREEN -> lang.tr("remote.screen.title", lang.vars("name", screenName));
            case PLAYLIST -> lang.tr("remote.playlist.title", lang.vars("name", screenName));
        };
        this.inventory = Bukkit.createInventory(this, view == View.SCREEN ? 27 : 54, RemoteItem.text(title));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void open() {
        if (!render()) return;
        player.openInventory(inventory);
        refreshTask = player.getScheduler().runAtFixedRate(ctl.plugin, t -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != this) {
                t.cancel();
                return;
            }
            render();
        }, null, 20L, 20L);
    }

    void closed() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
    }

    void click(int slot, ClickType type) {
        if (!player.hasPermission(ScreenActions.USE)) {
            lang.send(player, "error.no_permission");
            ctl.later(player, player::closeInventory);
            return;
        }
        Button button = buttons.get(slot);
        if (button == null) return;
        if (button.limited() && !ctl.actions.checkRateLimit(player)) return;

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        button.onClick().accept(type);
        render();
    }

    // ==================== Drawing ====================

    /** Redraws the menu. Returns false when its screen is gone and the list was opened instead. */
    private boolean render() {
        buttons.clear();
        drawing = new ItemStack[inventory.getSize()];

        switch (view) {
            case LIST -> renderList();
            case SCREEN, PLAYLIST -> {
                Screen s = ctl.store.get(screenName);
                if (s == null || !ctl.actions.canManage(player, screenName)) {
                    // Removed (by this menu or by someone else) while we were
                    // looking at it, or handed to another owner.
                    ctl.later(player, () -> new RemoteMenu(ctl, player, View.LIST, null).open());
                    return false;
                }
                if (view == View.SCREEN) renderScreen(s);
                else renderPlaylist(s);
            }
        }

        // Only touch slots that changed, so an idle menu sends nothing.
        for (int i = 0; i < drawing.length; i++) {
            if (!Objects.equals(inventory.getItem(i), drawing[i])) inventory.setItem(i, drawing[i]);
        }
        return true;
    }

    private void renderList() {
        // Admins see every screen; everyone else the ones they can manage.
        List<Screen> screens = new ArrayList<>(ctl.store.all());
        screens.removeIf(s -> !ctl.actions.canManage(player, s.name()));
        screens.sort(Comparator.comparing(s -> s.name().toLowerCase()));
        page = clampPage(page, screens.size());

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= screens.size()) break;
            Screen s = screens.get(index);
            Material icon = switch (state(s)) {
                case "playing" -> Material.LIME_DYE;
                case "paused" -> Material.YELLOW_DYE;
                default -> Material.GRAY_DYE;
            };
            String name = s.name();
            set(i, icon, false, "remote.list.screen", screenVars(s), false,
                    c -> openLater(View.SCREEN, name));
        }

        pageButtons(screens.size());

        set(46, Material.EMERALD, false, "remote.list.new", null, false, c -> {
            ctl.later(player, player::closeInventory);
            ctl.startNewScreen(player);
        });

        // Volume and hear radius are not per screen: they apply to the whole
        // server, so only admins get the buttons.
        if (!ctl.actions.isAdmin(player)) return;
        float volume = ctl.runtime.globalVolume;
        Map<String, String> volumeVars = lang.vars("value", String.format(java.util.Locale.ROOT, "%.1f", volume));
        set(47, Material.RED_STAINED_GLASS_PANE, false, "remote.list.volume_down", volumeVars, false,
                c -> ctl.actions.setGlobalVolume(player, step(volume, -0.1f)));
        set(48, Material.NOTE_BLOCK, false, "remote.list.volume", volumeVars, false,
                c -> ctl.actions.resetGlobalVolume(player));
        set(49, Material.LIME_STAINED_GLASS_PANE, false, "remote.list.volume_up", volumeVars, false,
                c -> ctl.actions.setGlobalVolume(player, step(volume, 0.1f)));

        int radius = ctl.runtime.hearRadius;
        Map<String, String> radiusVars = lang.vars("value", radius);
        set(50, Material.RED_STAINED_GLASS_PANE, false, "remote.list.radius_down", radiusVars, false,
                c -> ctl.actions.setHearRadius(player, radius - 10));
        set(51, Material.BELL, false, "remote.list.radius", radiusVars, false,
                c -> ctl.actions.resetHearRadius(player));
        set(52, Material.LIME_STAINED_GLASS_PANE, false, "remote.list.radius_up", radiusVars, false,
                c -> ctl.actions.setHearRadius(player, radius == 1 ? 10 : radius + 10));
    }

    private void renderScreen(Screen s) {
        String name = s.name();
        String state = state(s);
        Map<String, String> vars = screenVars(s);

        set(0, Material.OAK_DOOR, false, "remote.screen.back", null, false, c -> openLater(View.LIST, null));
        set(4, Material.PAINTING, false, "remote.screen.info", vars, false, null);

        boolean confirming = System.currentTimeMillis() < confirmUntilMs;
        set(8, confirming ? Material.TNT : Material.BARRIER, confirming,
                confirming ? "remote.screen.remove_confirm" : "remote.screen.remove", vars, true, c -> {
                    if (System.currentTimeMillis() < confirmUntilMs) {
                        ctl.actions.removeScreen(player, name); // render() then falls back to the list
                    } else {
                        confirmUntilMs = System.currentTimeMillis() + CONFIRM_MS;
                    }
                });

        set(10, Material.SPECTRAL_ARROW, false, "remote.screen.seek_back", vars, true,
                c -> ctl.actions.back(player, name, SEEK_SECONDS));
        switch (state) {
            case "playing" -> set(12, Material.YELLOW_CONCRETE, false, "remote.screen.pause", vars, true,
                    c -> ctl.actions.pause(player, name));
            case "paused" -> set(12, Material.LIME_CONCRETE, false, "remote.screen.resume", vars, true,
                    c -> ctl.actions.resume(player, name));
            default -> set(12, Material.LIME_CONCRETE, false, "remote.screen.play", vars, true,
                    c -> ctl.actions.play(player, name));
        }
        if (!state.equals("stopped")) {
            set(13, Material.LIME_TERRACOTTA, false, "remote.screen.restart", vars, true,
                    c -> ctl.actions.play(player, name));
            set(14, Material.RED_CONCRETE, false, "remote.screen.stop", vars, true,
                    c -> ctl.actions.stop(player, name));
        }
        set(16, Material.ARROW, false, "remote.screen.seek_forward", vars, true,
                c -> ctl.actions.seek(player, name, SEEK_SECONDS));

        set(19, Material.NAME_TAG, false, "remote.screen.set_url", vars, false, c -> {
            ctl.later(player, player::closeInventory);
            ctl.ask(player, "remote.prompt.url", (p, url) -> {
                // Same validation as /collins seturl, then straight into playback.
                if (ctl.actions.setUrl(p, name, url)) ctl.actions.play(p, name);
            }, null);
        });
        set(21, Material.BOOKSHELF, false, "remote.screen.playlist",
                lang.vars("count", playlistSize(name)), false, c -> openLater(View.PLAYLIST, name));
        set(23, Material.REPEATER, s.loop(), s.loop() ? "remote.screen.loop_on" : "remote.screen.loop_off", vars, true,
                c -> ctl.actions.setLoop(player, name, !s.loop()));
        set(25, Material.SPYGLASS, false, "remote.screen.quality", vars, true,
                c -> ctl.actions.setQuality(player, name, nextQuality(s.youtubeQuality(), c.isRightClick() ? -1 : 1)));
    }

    private void renderPlaylist(Screen s) {
        String name = s.name();
        Playlist playlist = Playlist.get(name);
        List<Playlist.PlaylistEntry> entries = playlist == null ? List.of() : playlist.getEntries();
        int current = playlist == null ? 0 : playlist.getCurrentIndex();
        boolean enabled = playlist == null || playlist.isEnabled();
        page = clampPage(page, entries.size());

        for (int i = 0; i < PAGE_SIZE; i++) {
            int at = page * PAGE_SIZE + i;
            if (at >= entries.size()) break;
            Playlist.PlaylistEntry e = entries.get(at);
            boolean isCurrent = e.index() == current;
            String key = removeMode ? "remote.playlist.entry_remove"
                    : isCurrent ? "remote.playlist.entry_current" : "remote.playlist.entry";
            int index = e.index();
            set(i, isCurrent ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME, isCurrent, key,
                    lang.vars("index", index, "title", e.title(), "url", shorten(e.url())), true, c -> {
                        if (removeMode || c.isShiftClick()) ctl.actions.playlistRemove(player, name, index);
                        else ctl.actions.playlistPlay(player, name, index);
                    });
        }

        pageButtons(entries.size());

        set(46, Material.OAK_DOOR, false, "remote.playlist.back", null, false, c -> openLater(View.SCREEN, name));
        set(47, Material.EMERALD, false, "remote.playlist.add", null, false, c -> {
            ctl.later(player, player::closeInventory);
            ctl.ask(player, "remote.prompt.playlist_url", (p, url) -> {
                ctl.actions.playlistAdd(p, name, url);
                new RemoteMenu(ctl, p, View.PLAYLIST, name).open();
            }, null);
        });
        set(48, Material.SPECTRAL_ARROW, false, "remote.playlist.prev", null, true,
                c -> ctl.actions.playlistPrev(player, name));
        set(49, Material.SPECTRAL_ARROW, false, "remote.playlist.next", null, true,
                c -> ctl.actions.playlistNext(player, name));
        set(50, enabled ? Material.LIME_DYE : Material.GRAY_DYE, false,
                enabled ? "remote.playlist.enabled" : "remote.playlist.disabled", null, true,
                c -> ctl.actions.playlistSetEnabled(player, name, !enabled));
        set(51, Material.SHEARS, removeMode,
                removeMode ? "remote.playlist.remove_mode_on" : "remote.playlist.remove_mode_off", null, false,
                c -> removeMode = !removeMode);

        boolean confirming = System.currentTimeMillis() < confirmUntilMs;
        set(52, confirming ? Material.TNT : Material.LAVA_BUCKET, confirming,
                confirming ? "remote.playlist.clear_confirm" : "remote.playlist.clear", null, true, c -> {
                    if (System.currentTimeMillis() < confirmUntilMs) {
                        confirmUntilMs = 0;
                        ctl.actions.playlistClear(player, name);
                    } else {
                        confirmUntilMs = System.currentTimeMillis() + CONFIRM_MS;
                    }
                });
    }

    private void pageButtons(int total) {
        if (page > 0) {
            set(45, Material.ARROW, false, "remote.page.prev", null, false, c -> page--);
        }
        if ((page + 1) * PAGE_SIZE < total) {
            set(53, Material.ARROW, false, "remote.page.next", null, false, c -> page++);
        }
    }

    // ==================== Helpers ====================

    /**
     * Puts a button in {@code slot}. Its name is the lang entry
     * {@code key + ".name"}, its lore {@code key + ".lore"}.
     */
    private void set(int slot, Material material, boolean glint, String key, Map<String, String> vars,
                     boolean limited, Consumer<ClickType> onClick) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(RemoteItem.text(lang.tr(key + ".name", vars)));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (lang.has(key + ".lore")) lore.addAll(RemoteItem.lines(lang.tr(key + ".lore", vars)));
        // The owner goes on as its own line, so it shows even with a lang
        // file whose lore texts were written before screens had owners.
        if (SHOWS_OWNER.contains(key)) lore.add(RemoteItem.text(lang.tr("remote.owner_line", vars)));
        if (!lore.isEmpty()) meta.lore(lore);
        if (glint) meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        drawing[slot] = stack;
        if (onClick != null) buttons.put(slot, new Button(limited, onClick));
    }

    private void openLater(View target, String name) {
        ctl.later(player, () -> new RemoteMenu(ctl, player, target, name).open());
    }

    /** "playing", "paused" (stopped part-way) or "stopped" (at the start). */
    private String state(Screen s) {
        if (s.playing()) return "playing";
        CollinsRuntimeState.Playback pb = ctl.runtime.get(s.name());
        return pb.basePosMs > 0 ? "paused" : "stopped";
    }

    private Map<String, String> screenVars(Screen s) {
        long duration = ctl.runtime.getDurationMs(s.name(), s.mp4Url());
        var owner = ctl.store.ownerOf(s.name());
        String url = s.mp4Url() == null || s.mp4Url().isBlank() ? lang.raw("remote.none") : shorten(s.mp4Url());
        return lang.vars(
                "name", s.name(),
                "state", lang.raw("remote.state." + state(s)),
                "pos", ScreenActions.formatMs(ctl.runtime.getCurrentPosMs(s.name())),
                "duration", duration > 0 ? ScreenActions.formatMs(duration) : lang.raw("remote.none"),
                "url", url,
                "loop", lang.raw(s.loop() ? "remote.on" : "remote.off"),
                "quality", YouTubeQuality.display(s.youtubeQuality()),
                "owner", owner == null ? lang.raw("remote.none") : owner.name(),
                "world", s.world(),
                "x1", s.x1(), "y1", s.y1(), "z1", s.z1(),
                "x2", s.x2(), "y2", s.y2(), "z2", s.z2(),
                "seconds", SEEK_SECONDS
        );
    }

    private static int playlistSize(String screenName) {
        Playlist playlist = Playlist.get(screenName);
        return playlist == null ? 0 : playlist.size();
    }

    private static int clampPage(int page, int total) {
        int last = total == 0 ? 0 : (total - 1) / PAGE_SIZE;
        return Math.max(0, Math.min(last, page));
    }

    private static String shorten(String url) {
        return url.length() > 48 ? url.substring(0, 45) + "..." : url;
    }

    /** One step of the global volume, kept on a 0.1 grid. */
    private static float step(float volume, float delta) {
        return Math.round((volume + delta) * 10f) / 10f;
    }

    private static int nextQuality(int current, int direction) {
        List<String> options = YouTubeQuality.options();
        int at = 0;
        for (int i = 0; i < options.size(); i++) {
            Integer q = YouTubeQuality.parse(options.get(i));
            if (q != null && q == current) at = i;
        }
        Integer next = YouTubeQuality.parse(options.get(Math.floorMod(at + direction, options.size())));
        return next == null ? YouTubeQuality.DEFAULT : next;
    }
}
