package org.sawiq.collins.paper.command;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.sawiq.collins.paper.model.Playlist;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.remote.RemoteItem;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.YouTubeQuality;

import java.util.*;

/**
 * Parses {@code /collins ...} and hands the work to {@link ScreenActions},
 * which the TV Remote menus call as well.
 */
public final class CollinsCommand implements TabExecutor {

    private final ScreenStore store;
    private final ScreenActions actions;
    private final RemoteItem remoteItem;
    private final Lang lang;

    public CollinsCommand(ScreenStore store, ScreenActions actions, RemoteItem remoteItem, Lang lang) {
        this.store = store;
        this.actions = actions;
        this.remoteItem = remoteItem;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (!p.hasPermission("collins.admin")) {
            lang.send(p, "error.no_permission");
            return true;
        }

        if (!actions.checkRateLimit(p)) return true;

        if (args.length == 0) {
            lang.send(p, "cmd.help");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "pos1" -> {
                Block target = p.getTargetBlockExact(ScreenActions.REACH);
                if (target == null) { lang.send(p, "error.no_target_block"); return true; }
                actions.setPos1(p, target);
                return true;
            }

            case "pos2" -> {
                Block target = p.getTargetBlockExact(ScreenActions.REACH);
                if (target == null) { lang.send(p, "error.no_target_block"); return true; }
                actions.setPos2(p, target);
                return true;
            }

            case "create" -> {
                if (args.length < 2) { usage(p, "/collins create <name>"); return true; }
                actions.createScreen(p, args[1]);
                return true;
            }

            case "seturl" -> {
                if (args.length < 3) { usage(p, "/collins seturl <screen> <url>"); return true; }
                actions.setUrl(p, args[1], args[2]);
                return true;
            }

            case "play" -> {
                if (args.length < 2) { usage(p, "/collins play <screen>"); return true; }
                actions.play(p, args[1]);
                return true;
            }

            case "stop" -> {
                if (args.length < 2) { usage(p, "/collins stop <screen>"); return true; }
                actions.stop(p, args[1]);
                return true;
            }

            case "pause" -> {
                if (args.length < 2) { usage(p, "/collins pause <screen>"); return true; }
                actions.pause(p, args[1]);
                return true;
            }

            case "resume" -> {
                if (args.length < 2) { usage(p, "/collins resume <screen>"); return true; }
                actions.resume(p, args[1]);
                return true;
            }

            case "loop" -> {
                if (args.length < 3) { usage(p, "/collins loop <screen> on|off"); return true; }
                String name = args[1];
                if (actions.requireScreen(p, name) == null) return true;

                String raw = args[2].toLowerCase(Locale.ROOT);
                if (!raw.equals("on") && !raw.equals("off")) {
                    usage(p, "/collins loop <screen> on|off");
                    return true;
                }
                actions.setLoop(p, name, raw.equals("on"));
                return true;
            }

            case "quality" -> {
                if (args.length < 2) { usage(p, "/collins quality <screen> [auto|360|480|720|1080|1440|2160]"); return true; }
                String name = args[1];
                Screen s = actions.requireScreen(p, name);
                if (s == null) return true;

                if (args.length < 3) {
                    lang.send(p, "cmd.quality.current", lang.vars(
                            "name", name,
                            "quality", YouTubeQuality.display(s.youtubeQuality())
                    ));
                    return true;
                }

                Integer quality = YouTubeQuality.parse(args[2]);
                if (quality == null) {
                    usage(p, "/collins quality <screen> <auto|360|480|720|1080|1440|2160>");
                    return true;
                }
                actions.setQuality(p, name, quality);
                return true;
            }

            case "seek", "back" -> {
                if (args.length < 3) { usage(p, "/collins " + sub + " <screen> <seconds>"); return true; }
                String name = args[1];
                if (actions.requireScreen(p, name) == null) return true;

                double seconds;
                try { seconds = Double.parseDouble(args[2]); }
                catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                if (sub.equals("seek")) actions.seek(p, name, seconds);
                else actions.back(p, name, seconds);
                return true;
            }

            case "volume" -> {
                if (args.length < 2) { usage(p, "/collins volume set <0..2> | reset"); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    actions.resetGlobalVolume(p);
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { usage(p, "/collins volume set <0..2>"); return true; }
                    float v;
                    try { v = Float.parseFloat(args[2]); }
                    catch (Exception e) { lang.send(p, "error.bad_number"); return true; }
                    actions.setGlobalVolume(p, v);
                    return true;
                }

                usage(p, "/collins volume set <0..2> | reset");
                return true;
            }

            case "radius" -> {
                if (args.length < 2) { usage(p, "/collins radius set <1..512> | reset"); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    actions.resetHearRadius(p);
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { usage(p, "/collins radius set <1..512>"); return true; }
                    int r;
                    try { r = Integer.parseInt(args[2]); }
                    catch (Exception e) { lang.send(p, "error.bad_number"); return true; }
                    actions.setHearRadius(p, r);
                    return true;
                }

                usage(p, "/collins radius set <1..512> | reset");
                return true;
            }

            case "remove" -> {
                if (args.length < 2) { usage(p, "/collins remove <screen>"); return true; }
                actions.removeScreen(p, args[1]);
                return true;
            }

            case "list" -> {
                lang.send(p, "cmd.screens.header");
                for (Screen s : store.all()) {
                    lang.send(p, "cmd.screens.item", lang.vars(
                            "name", s.name(),
                            "url", (s.mp4Url() == null ? "" : s.mp4Url()),
                            "playing", s.playing(),
                            "loop", s.loop(),
                            "quality", YouTubeQuality.display(s.youtubeQuality())
                    ));
                }
                return true;
            }

            case "playlist" -> {
                return handlePlaylist(p, args);
            }

            case "remote" -> {
                Player target = p;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        lang.send(p, "error.player_not_found", lang.vars("name", args[1]));
                        return true;
                    }
                }
                giveRemote(p, target);
                return true;
            }

            default -> {
                usage(p, "/collins");
                return true;
            }
        }
    }

    private void usage(Player p, String usage) {
        lang.send(p, "error.usage", lang.vars("usage", usage));
    }

    private void giveRemote(Player giver, Player target) {
        if (giver.equals(target)) {
            remoteItem.give(target);
            lang.send(target, "remote.given");
            return;
        }
        // Folia: another player's inventory belongs to that player's region.
        String giverName = giver.getName();
        target.getScheduler().run(remoteItem.plugin(), t -> {
            remoteItem.give(target);
            lang.send(target, "remote.received", lang.vars("name", giverName));
        }, null);
        lang.send(giver, "remote.given_other", lang.vars("name", target.getName()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return List.of();

        if (args.length == 1) {
            return startsWith(args[0], List.of(
                    "pos1", "pos2", "create", "seturl",
                    "play", "stop", "pause", "resume",
                    "loop", "quality",
                    "seek", "back",
                    "volume", "radius",
                    "remove", "list", "playlist", "remote"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("seturl") || sub.equals("play") || sub.equals("stop") || sub.equals("pause") || sub.equals("resume") || sub.equals("loop") || sub.equals("quality") || sub.equals("remove") || sub.equals("seek") || sub.equals("back")) {
                List<String> names = new ArrayList<>();
                for (Screen s : store.all()) names.add(s.name());
                return startsWith(args[1], names);
            }
            if (sub.equals("volume") || sub.equals("radius")) {
                return startsWith(args[1], List.of("set", "reset"));
            }
            if (sub.equals("playlist")) {
                List<String> names = new ArrayList<>();
                for (Screen s : store.all()) names.add(s.name());
                return startsWith(args[1], names);
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remote")) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
            return startsWith(args[1], names);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("loop")) {
            return startsWith(args[2], List.of("on", "off"));
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("quality")) {
            return startsWith(args[2], YouTubeQuality.options());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("playlist")) {
            return startsWith(args[2], List.of("add", "remove", "play", "next", "prev", "list", "clear", "loop", "enable", "disable"));
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("playlist")) {
            String action = args[2].toLowerCase(Locale.ROOT);
            if (action.equals("loop")) {
                return startsWith(args[3], List.of("on", "off"));
            }
        }

        return List.of();
    }

    private List<String> startsWith(String token, List<String> options) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        }
        return out;
    }

    // ==================== Playlist Commands ====================

    private boolean handlePlaylist(Player p, String[] args) {
        // /collins playlist <screen> <action> [args...]
        if (args.length < 2) {
            lang.send(p, "playlist.usage");
            showPlaylistHelp(p);
            return true;
        }

        String screenName = args[1];
        if (actions.requireScreen(p, screenName) == null) return true;

        if (args.length < 3) {
            return showPlaylistInfo(p, screenName);
        }

        String action = args[2].toLowerCase(Locale.ROOT);

        switch (action) {
            case "add" -> {
                if (args.length < 4) {
                    usage(p, "/collins playlist " + screenName + " add <url>");
                    return true;
                }
                actions.playlistAdd(p, screenName, args[3]);
                return true;
            }

            case "remove" -> {
                if (args.length < 4) {
                    usage(p, "/collins playlist " + screenName + " remove <index>");
                    return true;
                }
                int index;
                try {
                    index = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    lang.send(p, "error.bad_number");
                    return true;
                }
                actions.playlistRemove(p, screenName, index);
                return true;
            }

            case "play" -> {
                if (actions.requirePlaylist(p, screenName) == null) return true;

                Integer index = null;
                if (args.length >= 4) {
                    try {
                        index = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        lang.send(p, "error.bad_number");
                        return true;
                    }
                }
                actions.playlistPlay(p, screenName, index);
                return true;
            }

            case "next" -> {
                actions.playlistNext(p, screenName);
                return true;
            }

            case "prev" -> {
                actions.playlistPrev(p, screenName);
                return true;
            }

            case "list" -> {
                return showPlaylistInfo(p, screenName);
            }

            case "clear" -> {
                actions.playlistClear(p, screenName);
                return true;
            }

            case "loop" -> {
                if (args.length < 4) {
                    usage(p, "/collins playlist " + screenName + " loop on|off");
                    return true;
                }
                actions.playlistSetLoop(p, screenName, args[3].equalsIgnoreCase("on"));
                return true;
            }

            case "enable" -> {
                actions.playlistSetEnabled(p, screenName, true);
                return true;
            }

            case "disable" -> {
                actions.playlistSetEnabled(p, screenName, false);
                return true;
            }

            default -> {
                lang.send(p, "playlist.usage");
                showPlaylistHelp(p);
                return true;
            }
        }
    }

    private void showPlaylistHelp(Player p) {
        lang.send(p, "playlist.help.header");
        lang.send(p, "playlist.help.show");
        lang.send(p, "playlist.help.add");
        lang.send(p, "playlist.help.play");
        lang.send(p, "playlist.help.next");
        lang.send(p, "playlist.help.prev");
        lang.send(p, "playlist.help.remove");
        lang.send(p, "playlist.help.clear");
        lang.send(p, "playlist.help.loop");
        lang.send(p, "playlist.help.enable");
        lang.send(p, "playlist.help.disable");
    }

    private boolean showPlaylistInfo(Player p, String screenName) {
        Playlist playlist = Playlist.get(screenName);
        if (playlist == null || playlist.isEmpty()) {
            lang.send(p, "playlist.empty", lang.vars("screen", screenName));
            lang.send(p, "playlist.empty_hint", lang.vars("screen", screenName));
            return true;
        }

        String status = lang.raw(playlist.isEnabled() ? "playlist.status.enabled" : "playlist.status.disabled");
        String loop = lang.raw(playlist.isLoop() ? "playlist.loop.enabled" : "playlist.loop.disabled");
        lang.send(p, "playlist.header", lang.vars("screen", screenName, "status", status, "loop", loop));

        for (Playlist.PlaylistEntry e : playlist.getEntries()) {
            boolean current = e.index() == playlist.getCurrentIndex();
            lang.send(p, current ? "playlist.entry.current" : "playlist.entry.normal",
                    lang.vars("index", e.index(), "title", e.title()));
        }
        return true;
    }
}
