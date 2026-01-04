package org.sawiq.collins.paper.command;

import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.net.CollinsMessenger;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.selection.SelectionVisualizer;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.ScreenFactory;

import java.util.*;

public final class CollinsCommand implements TabExecutor {

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final CollinsMessenger messenger;
    private final SelectionService selection;
    private final CollinsRuntimeState runtime;

    public CollinsCommand(JavaPlugin plugin,
                          ScreenStore store,
                          CollinsMessenger messenger,
                          SelectionService selection,
                          CollinsRuntimeState runtime) {
        this.plugin = plugin;
        this.store = store;
        this.messenger = messenger;
        this.selection = selection;
        this.runtime = runtime;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (!p.hasPermission("collins.admin")) {
            p.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage("/collins pos1|pos2|create|seturl|play|stop|pause|resume|volume|radius|remove|list|sync");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "pos1" -> {
                Block target = p.getTargetBlockExact(200);
                if (target == null) { p.sendMessage("No target block."); return true; }
                selection.setPos1(p, target);
                p.sendMessage("pos1 = " + target.getX() + " " + target.getY() + " " + target.getZ());
                return true;
            }

            case "pos2" -> {
                Block target = p.getTargetBlockExact(200);
                if (target == null) { p.sendMessage("No target block."); return true; }
                selection.setPos2(p, target);
                p.sendMessage("pos2 = " + target.getX() + " " + target.getY() + " " + target.getZ());

                var sel = selection.get(p);
                if (sel.complete() && sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
                    SelectionVisualizer.showFrame(
                            plugin, p,
                            sel.pos1().getX(), sel.pos1().getY(), sel.pos1().getZ(),
                            sel.pos2().getX(), sel.pos2().getY(), sel.pos2().getZ(),
                            200L
                    );
                }
                return true;
            }

            case "create" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins create <name>"); return true; }
                String name = args[1];

                var sel = selection.get(p);
                if (!sel.complete()) {
                    p.sendMessage("Selection not complete. Use /collins pos1 and /collins pos2.");
                    return true;
                }
                if (!sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
                    p.sendMessage("pos1/pos2 must be in same world.");
                    return true;
                }

                Screen screen = ScreenFactory.create(name, sel.pos1(), sel.pos2());
                if (screen == null) {
                    p.sendMessage("Selection must be a rectangle plane with thickness 1 block (XY/XZ/YZ) and size >= 2x2.");
                    return true;
                }

                store.put(screen);
                store.save();

                runtime.resetPlayback(screen.name()); // чтобы новый экран не унаследовал таймер

                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("Created screen: " + name);
                return true;
            }

            case "seturl" -> {
                if (args.length < 3) { p.sendMessage("Usage: /collins seturl <screen> <url>"); return true; }

                String name = args[1];
                String url = args[2];

                Screen s = store.get(name);
                if (s == null) { p.sendMessage("Screen not found: " + name); return true; }

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        url,
                        s.playing(),
                        s.loop(),
                        s.volume()
                );

                // Смена url => сброс таймера, иначе будет seek в старую позицию другого файла
                runtime.resetPlayback(s.name());
                if (updated.playing()) {
                    CollinsRuntimeState.Playback pb = runtime.get(s.name());
                    pb.startEpochMs = System.currentTimeMillis();
                    pb.basePosMs = 0;
                }

                store.put(updated);
                store.save();

                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("URL set for " + name);
                return true;
            }

            case "play" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins play <screen>"); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { p.sendMessage("Screen not found: " + name); return true; }

                // play = старт с нуля
                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                pb.basePosMs = 0;
                pb.startEpochMs = System.currentTimeMillis();

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        true,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("Playing " + name);
                return true;
            }

            case "stop" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins stop <screen>"); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { p.sendMessage("Screen not found: " + name); return true; }

                // stop = выключить и сбросить позицию на 0
                runtime.resetPlayback(s.name());

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        false,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("Stopped " + name);
                return true;
            }

            case "pause" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins pause <screen>"); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { p.sendMessage("Screen not found: " + name); return true; }

                // pause = выключить, но сохранить позицию
                long now = System.currentTimeMillis();
                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                if (s.playing() && pb.startEpochMs > 0) {
                    pb.basePosMs += Math.max(0, now - pb.startEpochMs);
                }
                pb.startEpochMs = 0;

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        false,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("Paused " + name);
                return true;
            }

            case "resume" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins resume <screen>"); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { p.sendMessage("Screen not found: " + name); return true; }

                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                pb.startEpochMs = System.currentTimeMillis();

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        true,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("Resumed " + name);
                return true;
            }

            case "volume" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins volume set <0..2> | reset"); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    runtime.globalVolume = 1.0f;
                    messenger.broadcastSync();
                    p.sendMessage("Global volume reset to 1.0");
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { p.sendMessage("Usage: /collins volume set <0..2>"); return true; }
                    float v;
                    try { v = Float.parseFloat(args[2]); }
                    catch (Exception e) { p.sendMessage("Bad number."); return true; }

                    v = Math.max(0f, Math.min(2f, v));
                    runtime.globalVolume = v;
                    messenger.broadcastSync();
                    p.sendMessage("Global volume = " + v);
                    return true;
                }

                p.sendMessage("Usage: /collins volume set <0..2> | reset");
                return true;
            }

            case "radius" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins radius set <1..512> | reset"); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    runtime.hearRadius = 100;
                    messenger.broadcastSync();
                    p.sendMessage("Hear radius reset to 100");
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { p.sendMessage("Usage: /collins radius set <1..512>"); return true; }
                    int r;
                    try { r = Integer.parseInt(args[2]); }
                    catch (Exception e) { p.sendMessage("Bad number."); return true; }

                    r = Math.max(1, Math.min(512, r));
                    runtime.hearRadius = r;
                    messenger.broadcastSync();
                    p.sendMessage("Hear radius = " + r);
                    return true;
                }

                p.sendMessage("Usage: /collins radius set <1..512> | reset");
                return true;
            }

            case "remove" -> {
                if (args.length < 2) { p.sendMessage("Usage: /collins remove <screen>"); return true; }
                String name = args[1];

                Screen removed = store.remove(name);
                if (removed == null) { p.sendMessage("Screen not found: " + name); return true; }

                store.save();
                runtime.resetPlayback(removed.name());

                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                p.sendMessage("Removed screen: " + name);
                return true;
            }

            case "list" -> {
                p.sendMessage("Screens:");
                for (Screen s : store.all()) {
                    p.sendMessage("- " + s.name() + " url=" + (s.mp4Url() == null ? "" : s.mp4Url()) + " playing=" + s.playing());
                }
                return true;
            }

            case "sync" -> {
                messenger.broadcastSync();
                p.sendMessage("Broadcasted SYNC to all players.");
                return true;
            }

            default -> {
                p.sendMessage("Unknown subcommand.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return List.of();

        if (args.length == 1) {
            return startsWith(args[0], List.of(
                    "pos1", "pos2", "create", "seturl",
                    "play", "stop", "pause", "resume",
                    "volume", "radius",
                    "remove", "list", "sync"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("seturl") || sub.equals("play") || sub.equals("stop") || sub.equals("pause") || sub.equals("resume") || sub.equals("remove")) {
                List<String> names = new ArrayList<>();
                for (Screen s : store.all()) names.add(s.name());
                return startsWith(args[1], names);
            }
            if (sub.equals("volume") || sub.equals("radius")) {
                return startsWith(args[1], List.of("set", "reset"));
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
}
