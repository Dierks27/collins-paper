package org.sawiq.collins.paper.command;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.sawiq.collins.paper.CollinsPaperPlugin;
import org.sawiq.collins.paper.model.Playlist;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.net.CollinsMessenger;
import org.sawiq.collins.paper.protect.BuildProtection;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.selection.SelectionVisualizer;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.PlaylistStore;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.ScreenFactory;
import org.sawiq.collins.paper.util.UrlPolicy;
import org.sawiq.collins.paper.util.YouTubeQuality;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Everything a player can do to a screen, in one place. Both entry points
 * call these methods: {@link CollinsCommand} after parsing its arguments, and
 * the TV Remote menus ({@code org.sawiq.collins.paper.remote}) on a click.
 *
 * <p>Each method tells the player what happened through {@link Lang} and
 * returns {@code true} when the action went through. Call them on the
 * player's own thread (a command, an inventory click, or the player's entity
 * scheduler), as the commands always have.</p>
 */
public final class ScreenActions {

    private static final Pattern SCREEN_NAME_RE = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    /** Make screens, and control, change and remove your own. */
    public static final String USE = "collins.use";
    /** Manage anyone's screens and the server-wide settings. */
    public static final String ADMIN = "collins.admin";

    /** How far pos1/pos2 reach, in blocks. */
    public static final int REACH = 200;

    private final CollinsPaperPlugin plugin;
    private final ScreenStore store;
    private final PlaylistStore playlistStore;
    private final CollinsMessenger messenger;
    private final SelectionService selection;
    private final CollinsRuntimeState runtime;
    private final Lang lang;
    private final UrlPolicy urlPolicy;
    private final BuildProtection protection;

    private final Map<UUID, Long> lastActionAtMs = new ConcurrentHashMap<>();

    public ScreenActions(CollinsPaperPlugin plugin,
                         ScreenStore store,
                         PlaylistStore playlistStore,
                         CollinsMessenger messenger,
                         SelectionService selection,
                         CollinsRuntimeState runtime,
                         Lang lang,
                         UrlPolicy urlPolicy,
                         BuildProtection protection) {
        this.plugin = plugin;
        this.store = store;
        this.playlistStore = playlistStore;
        this.messenger = messenger;
        this.selection = selection;
        this.runtime = runtime;
        this.lang = lang;
        this.urlPolicy = urlPolicy;
        this.protection = protection;
    }

    // ==================== Rate limit ====================

    /**
     * Per-player rate limit ({@code security.commandRateLimitMs}), shared by
     * commands and menu clicks. Returns {@code false}, after telling the
     * player, when they are going too fast.
     */
    public boolean checkRateLimit(Player p) {
        long nowMs = System.currentTimeMillis();
        int rateLimitMs = cfgInt("security.commandRateLimitMs", 250);
        if (rateLimitMs > 0) {
            long last = lastActionAtMs.getOrDefault(p.getUniqueId(), 0L);
            long since = nowMs - last;
            if (since >= 0 && since < rateLimitMs) {
                lang.send(p, "error.rate_limited", lang.vars("ms", (rateLimitMs - since)));
                return false;
            }
            lastActionAtMs.put(p.getUniqueId(), nowMs);
        }
        return true;
    }

    /** Drop the rate-limit timestamp of a player who has left. */
    public void forgetPlayer(UUID uuid) {
        if (uuid != null) lastActionAtMs.remove(uuid);
    }

    /** Periodic sweep: drop timestamps of anyone no longer online. */
    public void forgetMissingPlayers(Set<UUID> online) {
        if (online == null) return;
        lastActionAtMs.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    // ==================== Lookup ====================

    /** The screen, or {@code null} after telling the player it does not exist. */
    public Screen requireScreen(Player p, String name) {
        Screen s = store.get(name);
        if (s == null) lang.send(p, "error.screen_not_found", lang.vars("name", name));
        return s;
    }

    // ==================== Whose screen is it ====================

    public boolean isAdmin(Player p) {
        return p.hasPermission(ADMIN);
    }

    /**
     * May this player control, change and remove the screen? Admins may
     * for every screen; everyone else only for the ones they made. A screen
     * with no owner (made before 1.8.0, or cleared) is for admins only.
     */
    public boolean canManage(Player p, String name) {
        if (isAdmin(p)) return true;
        ScreenStore.Owner owner = store.ownerOf(name);
        return owner != null && owner.id().equals(p.getUniqueId());
    }

    /**
     * The screen, if it exists and the player may manage it; otherwise
     * {@code null} after telling the player why not. Every action that
     * changes a screen starts here.
     */
    public Screen requireOwnScreen(Player p, String name) {
        Screen s = requireScreen(p, name);
        if (s == null) return null;
        if (canManage(p, name)) return s;

        ScreenStore.Owner owner = store.ownerOf(name);
        if (owner == null) lang.send(p, "error.screen_admin_only", lang.vars("name", s.name()));
        else lang.send(p, "error.not_your_screen", lang.vars("name", s.name(), "owner", owner.name()));
        return null;
    }

    private boolean requireAdmin(Player p) {
        if (isAdmin(p)) return true;
        lang.send(p, "error.admin_only");
        return false;
    }

    /** Admin: hand a screen to {@code newOwner}, or to nobody (null). */
    public boolean setOwner(Player p, String name, Player newOwner) {
        if (!requireAdmin(p)) return false;
        Screen s = requireScreen(p, name);
        if (s == null) return false;

        if (newOwner == null) {
            store.setOwner(s.name(), null);
            lang.send(p, "cmd.owner.cleared", lang.vars("name", s.name()));
        } else {
            store.setOwner(s.name(), new ScreenStore.Owner(newOwner.getUniqueId(), newOwner.getName()));
            lang.send(p, "cmd.owner.set", lang.vars("name", s.name(), "owner", newOwner.getName()));
        }
        store.save();
        plugin.getLogger().info(p.getName() + " set owner of '" + s.name() + "' -> "
                + (newOwner == null ? "nobody" : newOwner.getName()));
        return true;
    }

    /**
     * May the player have one more screen? Tells them when they are at
     * {@code screens.maxPerPlayer}. Admins have no cap.
     */
    public boolean checkScreenLimit(Player p) {
        int max = cfgInt("screens.maxPerPlayer", 3);
        if (max <= 0 || isAdmin(p)) return true;
        if (store.countOwnedBy(p.getUniqueId()) < max) return true;
        lang.send(p, "error.screen_limit", lang.vars("max", max));
        return false;
    }

    /**
     * May the player put a screen on the rectangle between the two blocks?
     * Refused, with a message, where a protection plugin (Towny, WorldGuard)
     * would not let them build. Admins are not checked.
     */
    public boolean checkBuildAllowed(Player p, Block a, Block b) {
        if (isAdmin(p) || !cfgBool("screens.respectProtection", true)) return true;

        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
        int y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY());
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());

        // Every block of an ordinary screen; a coarser grid (edges always
        // included) once it is big enough for that to cost real time.
        long blocks = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        int step = (int) Math.max(1, Math.ceil(Math.sqrt(blocks / 4096.0)));

        for (int x = x1; ; x = Math.min(x2, x + step)) {
            for (int y = y1; ; y = Math.min(y2, y + step)) {
                for (int z = z1; ; z = Math.min(z2, z + step)) {
                    if (!protection.canBuild(p, a.getWorld().getBlockAt(x, y, z))) {
                        lang.send(p, "error.screen_protected");
                        return false;
                    }
                    if (z == z2) break;
                }
                if (y == y2) break;
            }
            if (x == x2) break;
        }
        return true;
    }

    public boolean isValidScreenName(String name) {
        if (name == null) return false;
        return SCREEN_NAME_RE.matcher(name).matches();
    }

    // ==================== Selection and creation ====================

    public void setPos1(Player p, Block target) {
        selection.setPos1(p, target);
        lang.send(p, "cmd.pos1.set", lang.vars(
                "x", target.getX(),
                "y", target.getY(),
                "z", target.getZ()
        ));
    }

    public void setPos2(Player p, Block target) {
        selection.setPos2(p, target);
        lang.send(p, "cmd.pos2.set", lang.vars(
                "x", target.getX(),
                "y", target.getY(),
                "z", target.getZ()
        ));
        showSelectionFrame(p);
    }

    /** Draws the particle frame of a complete selection; does nothing otherwise. */
    public void showSelectionFrame(Player p) {
        var sel = selection.get(p);
        if (sel.complete() && sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
            SelectionVisualizer.showFrame(
                    plugin, p,
                    sel.pos1().getX(), sel.pos1().getY(), sel.pos1().getZ(),
                    sel.pos2().getX(), sel.pos2().getY(), sel.pos2().getZ(),
                    200L
            );
        }
    }

    public boolean createScreen(Player p, String name) {
        if (!isValidScreenName(name)) {
            lang.send(p, "error.invalid_screen_name", lang.vars("name", name));
            return false;
        }

        var sel = selection.get(p);
        if (!sel.complete()) {
            lang.send(p, "error.selection_not_complete");
            return false;
        }
        if (!sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
            lang.send(p, "error.selection_world_mismatch");
            return false;
        }

        Screen screen = ScreenFactory.create(name, sel.pos1(), sel.pos2(), plugin.defaultYoutubeQuality());
        if (screen == null) {
            lang.send(p, "error.selection_invalid");
            return false;
        }

        // Creating over an existing name redefines that screen, as it always
        // has, but only for someone who may manage it.
        Screen existing = store.get(name);
        if (existing != null && !canManage(p, name)) {
            lang.send(p, "error.screen_exists", lang.vars("name", existing.name()));
            return false;
        }
        ScreenStore.Owner existingOwner = existing == null ? null : store.ownerOf(name);
        boolean alreadyTheirs = existingOwner != null && existingOwner.id().equals(p.getUniqueId());
        if (!alreadyTheirs && !checkScreenLimit(p)) return false;
        if (!checkBuildAllowed(p, sel.pos1(), sel.pos2())) return false;

        store.put(screen);
        store.setOwner(screen.name(), new ScreenStore.Owner(p.getUniqueId(), p.getName()));
        store.save();

        runtime.resetPlayback(screen.name()); // чтобы новый экран не унаследовал таймер

        messenger.requestBroadcastSync();
        SelectionVisualizer.stop(p);

        lang.send(p, "cmd.screen.created", lang.vars("name", name));
        plugin.getLogger().info(p.getName() + " created screen '" + name + "'");
        return true;
    }

    public boolean removeScreen(Player p, String name) {
        if (requireOwnScreen(p, name) == null) return false;
        Screen removed = store.remove(name);
        if (removed == null) {
            lang.send(p, "error.screen_not_found", lang.vars("name", name));
            return false;
        }

        store.save();
        runtime.resetPlayback(removed.name());
        runtime.remove(removed.name());
        Playlist.remove(removed.name());
        playlistStore.save();
        plugin.forgetDurationRequestState(removed.name());

        messenger.requestBroadcastSync();
        SelectionVisualizer.stop(p);

        lang.send(p, "cmd.screen.removed", lang.vars("name", name));
        plugin.getLogger().info(p.getName() + " removed screen '" + name + "'");
        return true;
    }

    // ==================== Playback ====================

    public boolean setUrl(Player p, String name, String url) {
        String safeUrl = validateAndNormalizeUrl(url);
        if (safeUrl == null) {
            lang.send(p, "error.invalid_url");
            plugin.getLogger().info(p.getName() + " rejected URL for screen '" + name + "'");
            return false;
        }
        if (!urlPermitted(p, safeUrl)) return false;

        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;

        Screen updated = new Screen(
                s.name(), s.world(),
                s.x1(), s.y1(), s.z1(),
                s.x2(), s.y2(), s.z2(),
                s.axis(),
                safeUrl,
                s.playing(),
                s.loop(),
                s.volume(),
                s.youtubeQuality()
        );

        // Смена url => сброс таймера, иначе будет seek в старую позицию другого файла
        boolean sameUrl = Objects.equals(s.mp4Url(), safeUrl);
        if (updated.playing()) {
            runtime.restartPlayback(s.name(), sameUrl);
        } else {
            runtime.stopPlayback(s.name(), sameUrl);
        }

        store.put(updated);
        store.save();
        plugin.prefetchDuration(updated);

        messenger.requestBroadcastSync();
        SelectionVisualizer.stop(p);

        lang.send(p, "cmd.url.set", lang.vars("name", name));
        plugin.getLogger().info(p.getName() + " seturl for screen '" + name + "'");
        return true;
    }

    /** Start from the beginning. The result is reported once the URL has been probed. */
    public boolean play(Player p, String name) {
        return start(p, name, /*fromZero=*/true);
    }

    /** Continue from the paused position. */
    public boolean resume(Player p, String name) {
        return start(p, name, /*fromZero=*/false);
    }

    private boolean start(Player p, String name, boolean fromZero) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;

        // Server-side preflight: refuse `play` for URLs we
        // already know we cannot resolve. Without this the
        // server starts a timeline ticking forward against an
        // empty playback while every client spins on
        // "Preparing..." indefinitely.
        String preflight = plugin.checkPlayableUrl(s.mp4Url());
        if (preflight != null) {
            lang.send(p, preflight, lang.vars("name", name, "url", s.mp4Url() == null ? "" : s.mp4Url()));
            return false;
        }
        // An address stored before the allow-list existed, or by someone
        // who has since lost the bypass, does not start for a regular player.
        if (!urlPermitted(p, s.mp4Url())) return false;

        SelectionVisualizer.stop(p);
        plugin.startPlaybackAfterProbe(p, s, fromZero);
        return true;
    }

    public boolean stop(Player p, String name) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;

        // stop = выключить и сбросить позицию на 0
        runtime.stopPlayback(s.name());

        store.put(s.withPlaying(false));
        store.save();
        messenger.requestBroadcastSync();
        SelectionVisualizer.stop(p);

        lang.send(p, "cmd.stopped", lang.vars("name", name));
        plugin.getLogger().info(p.getName() + " stop '" + name + "'");
        return true;
    }

    public boolean pause(Player p, String name) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;

        // pause = выключить, но сохранить позицию
        long now = System.currentTimeMillis();
        CollinsRuntimeState.Playback pb = runtime.get(s.name());
        if (s.playing() && pb.startEpochMs > 0) {
            pb.basePosMs += Math.max(0, now - pb.startEpochMs);
        }
        pb.startEpochMs = 0;

        store.put(s.withPlaying(false));
        store.save();
        messenger.requestBroadcastSync();
        SelectionVisualizer.stop(p);

        lang.send(p, "cmd.paused", lang.vars("name", name));
        plugin.getLogger().info(p.getName() + " pause '" + name + "'");
        return true;
    }

    public boolean setLoop(Player p, String name, boolean loopOn) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;

        store.put(s.withLoop(loopOn));
        store.save();
        messenger.requestBroadcastSync();
        lang.send(p, loopOn ? "cmd.loop.on" : "cmd.loop.off", lang.vars("name", name));
        plugin.getLogger().info(p.getName() + " loop '" + name + "' -> " + loopOn);
        return true;
    }

    public boolean setQuality(Player p, String name, int quality) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;

        Screen updated = s.withYoutubeQuality(quality);
        store.put(updated);
        store.save();
        plugin.prefetchDuration(updated);
        messenger.requestBroadcastSync();
        lang.send(p, "cmd.quality.set", lang.vars(
                "name", name,
                "quality", YouTubeQuality.display(updated.youtubeQuality())
        ));
        plugin.getLogger().info(p.getName() + " quality '" + name + "' -> " + YouTubeQuality.display(updated.youtubeQuality()));
        return true;
    }

    /** Jump forward (or, with a negative value, backward) by {@code seconds}. */
    public boolean seek(Player p, String name, double seconds) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;
        if (!checkSeekSize(p, seconds)) return false;

        long deltaMs = clampSeekMs((long) Math.floor(seconds * 1000.0));
        long now = System.currentTimeMillis();

        CollinsRuntimeState.Playback pb = runtime.get(s.name());
        long curMs = pb.basePosMs;
        if (s.playing() && pb.startEpochMs > 0) {
            curMs += Math.max(0L, now - pb.startEpochMs);
        }

        long nextMs = Math.max(0L, curMs + deltaMs);

        // Проверяем если seek дальше конца видео
        long duration = runtime.getDurationMs(s.name(), s.mp4Url());
        if (duration > 0 && nextMs >= duration && !s.loop()) {
            // Проверяем плейлист
            Playlist playlist = Playlist.get(s.name());
            Playlist.PlaylistEntry currentEntry = playlist != null ? playlist.current() : null;
            if (playlist != null && playlist.isEnabled() && !playlist.isEmpty()
                    && currentEntry != null && Objects.equals(s.mp4Url(), currentEntry.url())) {
                Playlist.PlaylistEntry nextEntry = playlist.next();
                if (nextEntry != null) {
                    // Переключаем на следующее видео
                    plugin.getLogger().info("Seek past end, playlist advance for '" + s.name() + "' -> [" + nextEntry.index() + "] " + nextEntry.title());
                    playlistStore.save();
                    if (!playVideoFromPlaylist(p, s, nextEntry)) return false;
                    lang.send(p, "cmd.seeked_playlist_next", lang.vars(
                            "name", s.name(),
                            "index", nextEntry.index(),
                            "title", nextEntry.title()
                    ));
                    return true;
                }
            }
            // Нет плейлиста или закончился - останавливаем
            store.put(s.withPlaying(false));
            store.save();
            runtime.stopPlayback(s.name());
            messenger.requestBroadcastSync();
            lang.send(p, "cmd.seeked_end", lang.vars("name", s.name()));
            plugin.getLogger().info(p.getName() + " seek past end '" + name + "' -> stopped");
            return true;
        }

        pb.basePosMs = nextMs;
        pb.startEpochMs = s.playing() ? now : 0L;

        messenger.requestBroadcastSync();

        lang.send(p, "cmd.seeked", lang.vars(
                "name", s.name(),
                "from", formatMs(curMs),
                "to", formatMs(nextMs),
                "pos", formatMs(nextMs)
        ));
        plugin.getLogger().info(p.getName() + " seek '" + name + "' " + seconds + "s");
        return true;
    }

    /** Jump backward by {@code seconds} (the sign is ignored). */
    public boolean back(Player p, String name, double seconds) {
        Screen s = requireOwnScreen(p, name);
        if (s == null) return false;
        if (!checkSeekSize(p, seconds)) return false;

        long deltaMs = clampSeekMs(-(long) Math.floor(Math.abs(seconds) * 1000.0));
        long now = System.currentTimeMillis();

        CollinsRuntimeState.Playback pb = runtime.get(s.name());
        long curMs = pb.basePosMs;
        if (s.playing() && pb.startEpochMs > 0) {
            curMs += Math.max(0L, now - pb.startEpochMs);
        }

        long nextMs = Math.max(0L, curMs + deltaMs);
        pb.basePosMs = nextMs;
        pb.startEpochMs = s.playing() ? now : 0L;

        messenger.requestBroadcastSync();

        lang.send(p, "cmd.seeked", lang.vars(
                "name", s.name(),
                "from", formatMs(curMs),
                "to", formatMs(nextMs),
                "pos", formatMs(nextMs)
        ));
        plugin.getLogger().info(p.getName() + " back '" + name + "' " + seconds + "s");
        return true;
    }

    private boolean checkSeekSize(Player p, double seconds) {
        long maxSeekSeconds = cfgLong("security.maxSeekSeconds", 3600L);
        if (!Double.isFinite(seconds) || Math.abs(seconds) > (double) maxSeekSeconds) {
            lang.send(p, "error.seek_too_large", lang.vars("max", maxSeekSeconds));
            return false;
        }
        return true;
    }

    private long clampSeekMs(long rawDeltaMs) {
        long maxDeltaMs = Math.max(0L, cfgLong("security.maxSeekSeconds", 3600L)) * 1000L;
        return Math.max(-maxDeltaMs, Math.min(maxDeltaMs, rawDeltaMs));
    }

    // ==================== Server-wide settings ====================

    public void setGlobalVolume(Player p, float v) {
        if (!requireAdmin(p)) return;
        v = Math.max(0f, Math.min(2f, v));
        runtime.globalVolume = v;
        messenger.requestBroadcastSync();
        lang.send(p, "cmd.global_volume.set", lang.vars("value", v));
        plugin.getLogger().info(p.getName() + " volume set " + v);
    }

    public void resetGlobalVolume(Player p) {
        if (!requireAdmin(p)) return;
        runtime.globalVolume = 1.0f;
        messenger.requestBroadcastSync();
        lang.send(p, "cmd.global_volume.reset");
        plugin.getLogger().info(p.getName() + " volume reset");
    }

    public void setHearRadius(Player p, int r) {
        if (!requireAdmin(p)) return;
        r = Math.max(1, Math.min(512, r));
        runtime.hearRadius = r;
        messenger.requestBroadcastSync();
        lang.send(p, "cmd.hear_radius.set", lang.vars("value", r));
        plugin.getLogger().info(p.getName() + " radius set " + r);
    }

    public void resetHearRadius(Player p) {
        if (!requireAdmin(p)) return;
        runtime.hearRadius = 100;
        messenger.requestBroadcastSync();
        lang.send(p, "cmd.hear_radius.reset");
        plugin.getLogger().info(p.getName() + " radius reset");
    }

    // ==================== Playlist ====================

    public boolean playlistAdd(Player p, String screenName, String url) {
        if (requireOwnScreen(p, screenName) == null) return false;
        String safeUrl = validateAndNormalizeUrl(url);
        if (safeUrl == null) {
            lang.send(p, "error.invalid_url");
            return false;
        }
        if (!urlPermitted(p, safeUrl)) return false;

        Playlist playlist = Playlist.getOrCreate(screenName);
        int index = playlist.add(safeUrl);
        Playlist.PlaylistEntry entry = playlist.get(index);

        lang.send(p, "playlist.added", lang.vars("index", index, "title", entry.title()));
        playlistStore.save();
        plugin.getLogger().info(p.getName() + " playlist add '" + screenName + "' [" + index + "] " + url);
        return true;
    }

    public boolean playlistRemove(Player p, String screenName, int index) {
        if (requireOwnScreen(p, screenName) == null) return false;
        Playlist playlist = requirePlaylist(p, screenName);
        if (playlist == null) return false;

        if (playlist.remove(index)) {
            lang.send(p, "playlist.removed", lang.vars("index", index));
            playlistStore.save();
            plugin.getLogger().info(p.getName() + " playlist remove '" + screenName + "' [" + index + "]");
            return true;
        }
        lang.send(p, "playlist.index_out_of_range", lang.vars("max", playlist.size()));
        return false;
    }

    /** Play entry {@code index} (1-based), or the current entry when {@code index} is null. */
    public boolean playlistPlay(Player p, String screenName, Integer index) {
        Screen screen = requireOwnScreen(p, screenName);
        if (screen == null) return false;
        Playlist playlist = requirePlaylist(p, screenName);
        if (playlist == null) return false;

        Playlist.PlaylistEntry entry;
        if (index != null) {
            entry = playlist.jumpTo(index);
            if (entry == null) {
                lang.send(p, "playlist.index_out_of_range", lang.vars("max", playlist.size()));
                return false;
            }
        } else {
            entry = playlist.current();
            if (entry == null) {
                entry = playlist.jumpTo(1);
            }
        }

        playlistStore.save();
        return playVideoFromPlaylist(p, screen, entry);
    }

    public boolean playlistNext(Player p, String screenName) {
        Screen screen = requireOwnScreen(p, screenName);
        if (screen == null) return false;
        Playlist playlist = requirePlaylist(p, screenName);
        if (playlist == null) return false;

        Playlist.PlaylistEntry entry = playlist.next();
        if (entry == null && screen.loop()) {
            entry = playlist.jumpTo(1); // screen loop on: wrap like advancePlaylist does
        }
        if (entry == null) {
            lang.send(p, "playlist.ended");
            return false;
        }

        playlistStore.save();
        return playVideoFromPlaylist(p, screen, entry);
    }

    public boolean playlistPrev(Player p, String screenName) {
        Screen screen = requireOwnScreen(p, screenName);
        if (screen == null) return false;
        Playlist playlist = requirePlaylist(p, screenName);
        if (playlist == null) return false;

        Playlist.PlaylistEntry entry = playlist.previous();
        if (entry == null) {
            lang.send(p, "playlist.ended");
            return false;
        }

        playlistStore.save();
        return playVideoFromPlaylist(p, screen, entry);
    }

    public void playlistClear(Player p, String screenName) {
        if (requireOwnScreen(p, screenName) == null) return;
        Playlist playlist = Playlist.get(screenName);
        if (playlist != null) {
            playlist.clear();
        }
        Playlist.remove(screenName);
        lang.send(p, "playlist.cleared", lang.vars("screen", screenName));
        playlistStore.save();
        plugin.getLogger().info(p.getName() + " playlist clear '" + screenName + "'");
    }

    public void playlistSetLoop(Player p, String screenName, boolean loopOn) {
        if (requireOwnScreen(p, screenName) == null) return;
        Playlist playlist = Playlist.getOrCreate(screenName);
        playlist.setLoop(loopOn);
        lang.send(p, loopOn ? "playlist.loop.on" : "playlist.loop.off");
        playlistStore.save();
    }

    public void playlistSetEnabled(Player p, String screenName, boolean enabled) {
        if (requireOwnScreen(p, screenName) == null) return;
        Playlist playlist = Playlist.getOrCreate(screenName);
        playlist.setEnabled(enabled);
        lang.send(p, enabled ? "playlist.enabled" : "playlist.disabled");
        playlistStore.save();
    }

    /** The screen's non-empty playlist, or {@code null} after telling the player it is empty. */
    public Playlist requirePlaylist(Player p, String screenName) {
        Playlist playlist = Playlist.get(screenName);
        if (playlist == null || playlist.isEmpty()) {
            lang.send(p, "playlist.empty", lang.vars("screen", screenName));
            return null;
        }
        return playlist;
    }

    private boolean playVideoFromPlaylist(Player p, Screen screen, Playlist.PlaylistEntry entry) {
        String url = entry.url();
        if (!urlPermitted(p, url)) return false;

        // The screen's loop flag is kept: checkVideoEndings() tries the
        // playlist first, so a single entry never replays while the playlist
        // still has a next entry, and with loop on the whole playlist starts
        // over at its end (see CollinsPaperPlugin#advancePlaylist).
        Screen updated = new Screen(
                screen.name(), screen.world(),
                screen.x1(), screen.y1(), screen.z1(),
                screen.x2(), screen.y2(), screen.z2(),
                screen.axis(),
                url,
                true,
                screen.loop(),
                screen.volume(),
                screen.youtubeQuality()
        );

        runtime.restartPlayback(screen.name(), Objects.equals(screen.mp4Url(), url));

        store.put(updated);
        store.save();
        plugin.prefetchDuration(updated);
        messenger.requestBroadcastSync();

        Playlist playlist = Playlist.get(screen.name());
        int total = playlist != null ? playlist.size() : 0;
        int current = playlist != null ? playlist.getCurrentIndex() : entry.index();

        lang.send(p, "playlist.playing", lang.vars("current", current, "total", total, "title", entry.title()));
        plugin.getLogger().info(p.getName() + " playlist play '" + screen.name() + "' [" + entry.index() + "] " + url);
        return true;
    }

    // ==================== Allowed sites ====================

    /**
     * The gate every address passes on its way to a screen. A listed host
     * (or an address already approved) is fine for anyone. Anything else
     * needs {@link UrlPolicy#BYPASS_PERMISSION}; a holder putting it on a
     * screen approves that address, so regular players can then play, pause
     * and resume it. Otherwise the player is told and nothing changes.
     */
    private boolean urlPermitted(Player p, String url) {
        if (urlPolicy.mayPlay(url)) return true;
        if (p.hasPermission(UrlPolicy.BYPASS_PERMISSION)) {
            urlPolicy.approve(url);
            plugin.saveApprovedUrls();
            return true;
        }
        lang.send(p, "error.url_not_allowed");
        plugin.getLogger().info(p.getName() + " was refused an address outside security.allowedHosts: " + url);
        return false;
    }

    // ==================== Helpers ====================

    public static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;

        if (hours > 0) {
            return hours + ":" + pad2(minutes) + ":" + pad2(seconds);
        }
        return minutes + ":" + pad2(seconds);
    }

    private static String pad2(long v) {
        return v < 10 ? ("0" + v) : Long.toString(v);
    }

    private int cfgInt(String path, int def) {
        try {
            return plugin.getConfig().getInt(path, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private long cfgLong(String path, long def) {
        try {
            return plugin.getConfig().getLong(path, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private boolean cfgBool(String path, boolean def) {
        try {
            return plugin.getConfig().getBoolean(path, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private String validateAndNormalizeUrl(String raw) {
        if (raw == null) return null;

        String u = raw.trim();
        if (u.isEmpty()) return null;

        int maxLen = cfgInt("security.maxUrlLength", 2048);
        if (maxLen > 0 && u.length() > maxLen) return null;

        if (u.indexOf('\n') >= 0 || u.indexOf('\r') >= 0 || u.indexOf('\0') >= 0) return null;

        final URI uri;
        try {
            uri = URI.create(u);
        } catch (Exception ignored) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) return null;

        if (uri.getRawUserInfo() != null) return null;

        String host = uri.getHost();
        if (host == null || host.isBlank()) return null;
        if (host.equalsIgnoreCase("localhost")) return null;

        boolean allowPrivate = cfgBool("security.allowPrivateUrls", false);
        boolean dnsResolve = cfgBool("security.dnsResolve", false);

        if (!allowPrivate) {
            InetAddress[] addrs = null;

            boolean looksLikeIp = host.indexOf(':') >= 0 || host.matches("^[0-9.]+$");
            if (looksLikeIp) {
                try {
                    addrs = new InetAddress[] { InetAddress.getByName(host) };
                } catch (Exception ignored) {
                    return null;
                }
            } else if (dnsResolve) {
                try {
                    addrs = InetAddress.getAllByName(host);
                } catch (Exception ignored) {
                    return null;
                }
            }

            if (addrs != null) {
                for (InetAddress a : addrs) {
                    if (a.isAnyLocalAddress()) return null;
                    if (a.isLoopbackAddress()) return null;
                    if (a.isLinkLocalAddress()) return null;
                    if (a.isSiteLocalAddress()) return null;
                    if (a.isMulticastAddress()) return null;
                }
            }
        }

        try {
            return uri.toASCIIString();
        } catch (Exception ignored) {
            return u;
        }
    }
}
