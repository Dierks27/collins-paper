package org.sawiq.collins.paper.remote;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Keyed;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.sawiq.collins.paper.CollinsPaperPlugin;
import org.sawiq.collins.paper.command.ScreenActions;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.model.Selection;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.selection.SelectionVisualizer;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.ScreenFactory;
import org.sawiq.collins.paper.util.YouTubeQuality;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * What the TV Remote does when it is used: opens the menus, runs the
 * "new screen" corner selection, and takes the chat answers the menus ask
 * for (a URL, a screen name).
 *
 * <p>Folia: every handler here runs on the acting player's own region
 * thread, except chat, which is asynchronous and hops onto the player's
 * entity scheduler before touching anything.</p>
 */
public final class RemoteController implements Listener {

    private static final long PROMPT_TIMEOUT_TICKS = 60L * 20L;
    private static final long SELECTING_TIMEOUT_MS = 5L * 60L * 1000L;
    private static final long DEBOUNCE_MS = 150L;

    /** A question the player answers with their next chat message. */
    private record Prompt(BiConsumer<Player, String> onAnswer, Runnable onAbort) {}

    final CollinsPaperPlugin plugin;
    final ScreenStore store;
    final CollinsRuntimeState runtime;
    final ScreenActions actions;
    final Lang lang;
    private final SelectionService selection;
    private final RemoteItem item;

    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    /** Players marking the corners of a new screen -> when that mode expires. */
    private final Map<UUID, Long> selecting = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUseAtMs = new ConcurrentHashMap<>();

    public RemoteController(CollinsPaperPlugin plugin,
                            ScreenStore store,
                            CollinsRuntimeState runtime,
                            ScreenActions actions,
                            SelectionService selection,
                            RemoteItem item,
                            Lang lang) {
        this.plugin = plugin;
        this.store = store;
        this.runtime = runtime;
        this.actions = actions;
        this.selection = selection;
        this.item = item;
        this.lang = lang;
    }

    // ==================== Crafting the remote ====================

    /** The remote is only craftable by players who may use it. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed keyed) || !keyed.getKey().equals(item.key())) return;
        if (e.getViewers().stream().noneMatch(v -> v.hasPermission("collins.admin"))) {
            e.getInventory().setResult(null);
        }
    }

    /** Puts the recipe in the recipe book, so nobody has to look it up. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("collins.admin") && Bukkit.getRecipe(item.key()) != null) {
            p.discoverRecipe(item.key());
        }
    }

    // ==================== Using the remote ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) return;
        // The off-hand event counts too: with the remote in the main hand, a
        // torch in the other hand must not get placed by the same click.
        if (!item.is(e.getItem()) && !holdsRemote(e.getPlayer())) return;

        // The remote never does what the vanilla item (or the block behind it) would.
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        use(e.getPlayer(), left, e.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!item.is(p.getInventory().getItem(e.getHand())) && !holdsRemote(p)) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) use(p, false, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (holdsRemote(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && holdsRemote(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && holdsRemote(p)) e.setCancelled(true);
    }

    private boolean holdsRemote(Player p) {
        return item.is(p.getInventory().getItemInMainHand());
    }

    private void use(Player p, boolean leftClick, Block clicked) {
        // One physical click can arrive as two events (block + item use).
        long now = System.currentTimeMillis();
        Long last = lastUseAtMs.put(p.getUniqueId(), now);
        if (last != null && now - last >= 0 && now - last < DEBOUNCE_MS) return;

        if (!p.hasPermission("collins.admin")) {
            lang.send(p, "error.no_permission");
            return;
        }

        if (isSelecting(p)) {
            if (p.isSneaking()) {
                stopSelecting(p);
                lang.send(p, "remote.new.cancelled");
                return;
            }
            markCorner(p, leftClick, clicked);
            return;
        }

        if (leftClick) return;

        Screen looked = findLookedAt(p);
        if (looked != null) {
            new RemoteMenu(this, p, RemoteMenu.View.SCREEN, looked.name()).open();
        } else {
            new RemoteMenu(this, p, RemoteMenu.View.LIST, null).open();
        }
    }

    /**
     * The screen the player is looking at, within the same reach as
     * pos1/pos2, or {@code null}. A screen hidden behind a nearer block does
     * not count.
     */
    private Screen findLookedAt(Player p) {
        Location eye = p.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection();
        String world = p.getWorld().getName();

        Screen hit = null;
        double hitDistance = Double.MAX_VALUE;
        for (Screen s : store.all()) {
            if (!world.equals(s.world())) continue;
            BoundingBox box = new BoundingBox(
                    Math.min(s.x1(), s.x2()), Math.min(s.y1(), s.y2()), Math.min(s.z1(), s.z2()),
                    Math.max(s.x1(), s.x2()) + 1, Math.max(s.y1(), s.y2()) + 1, Math.max(s.z1(), s.z2()) + 1);
            double distance;
            if (box.contains(origin)) {
                distance = 0;
            } else {
                RayTraceResult r = box.rayTrace(origin, direction, ScreenActions.REACH);
                if (r == null) continue;
                distance = r.getHitPosition().distance(origin);
            }
            if (distance < hitDistance) {
                hitDistance = distance;
                hit = s;
            }
        }

        if (hit != null && hitDistance > 0.1) {
            RayTraceResult wall = p.getWorld().rayTraceBlocks(eye, direction, hitDistance, FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitPosition().distance(origin) < hitDistance - 0.1) return null;
        }
        return hit;
    }

    // ==================== New screen ====================

    void startNewScreen(Player p) {
        prompts.remove(p.getUniqueId());
        selection.forget(p.getUniqueId());
        SelectionVisualizer.stop(p);
        selecting.put(p.getUniqueId(), System.currentTimeMillis() + SELECTING_TIMEOUT_MS);
        lang.send(p, "remote.new.start");
    }

    private boolean isSelecting(Player p) {
        Long until = selecting.get(p.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            stopSelecting(p);
            return false;
        }
        return true;
    }

    private void stopSelecting(Player p) {
        selecting.remove(p.getUniqueId());
        prompts.remove(p.getUniqueId());
        selection.forget(p.getUniqueId());
        SelectionVisualizer.stop(p);
    }

    private void markCorner(Player p, boolean first, Block clicked) {
        Block target = clicked != null ? clicked : p.getTargetBlockExact(ScreenActions.REACH);
        if (target == null) {
            lang.send(p, "error.no_target_block");
            return;
        }
        selecting.put(p.getUniqueId(), System.currentTimeMillis() + SELECTING_TIMEOUT_MS);

        if (first) {
            actions.setPos1(p, target);
            actions.showSelectionFrame(p);
        } else {
            actions.setPos2(p, target);
        }

        Selection sel = selection.get(p);
        if (!sel.complete()) return;

        // Say what is wrong with the rectangle now, not after a name was typed.
        if (!sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
            prompts.remove(p.getUniqueId());
            lang.send(p, "error.selection_world_mismatch");
            return;
        }
        if (ScreenFactory.create("x", sel.pos1(), sel.pos2(), YouTubeQuality.DEFAULT) == null) {
            prompts.remove(p.getUniqueId());
            lang.send(p, "error.selection_invalid");
            return;
        }
        askScreenName(p);
    }

    private void askScreenName(Player p) {
        ask(p, "remote.new.name_prompt", (player, name) -> {
            if (!isSelecting(player)) return;
            if (store.get(name) != null) {
                lang.send(player, "remote.new.name_taken", lang.vars("name", name));
                askScreenName(player);
                return;
            }
            if (!actions.createScreen(player, name)) {
                askScreenName(player);
                return;
            }
            selecting.remove(player.getUniqueId());
            selection.forget(player.getUniqueId());
            new RemoteMenu(this, player, RemoteMenu.View.SCREEN, name).open();
        }, () -> stopSelecting(p));
    }

    // ==================== Chat prompts ====================

    /**
     * Sends {@code messageKey} and takes the player's next chat message as
     * the answer. "cancel" or 60 seconds of silence drops the question and
     * runs {@code onAbort} (may be null).
     */
    void ask(Player p, String messageKey, BiConsumer<Player, String> onAnswer, Runnable onAbort) {
        Prompt prompt = new Prompt(onAnswer, onAbort);
        prompts.put(p.getUniqueId(), prompt);
        lang.send(p, messageKey);

        p.getScheduler().runDelayed(plugin, t -> {
            // Only if this very question is still the open one.
            if (!prompts.remove(p.getUniqueId(), prompt)) return;
            if (prompt.onAbort() != null) prompt.onAbort().run();
            lang.send(p, "remote.prompt.timeout");
        }, null, PROMPT_TIMEOUT_TICKS);
    }

    // The legacy chat event on purpose: Paper fires it before the modern
    // AsyncChatEvent, so cancelling here keeps the answer (often a private
    // URL) away from every other chat listener, old or new.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Prompt prompt = prompts.remove(p.getUniqueId());
        if (prompt == null) return;

        e.setCancelled(true);
        String answer = e.getMessage().trim();

        p.getScheduler().run(plugin, t -> {
            if (answer.equalsIgnoreCase("cancel")) {
                if (prompt.onAbort() != null) prompt.onAbort().run();
                lang.send(p, "remote.prompt.cancelled");
                return;
            }
            if (!p.hasPermission("collins.admin")) {
                if (prompt.onAbort() != null) prompt.onAbort().run();
                lang.send(p, "error.no_permission");
                return;
            }
            prompt.onAnswer().accept(p, answer);
        }, null);
    }

    // ==================== Menu plumbing ====================

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RemoteMenu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getClick() == ClickType.DOUBLE_CLICK) return; // already delivered as two single clicks
        menu.click(e.getSlot(), e.getClick());
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RemoteMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RemoteMenu menu) menu.closed();
    }

    /** Runs {@code task} on the player's region thread on the next tick. */
    void later(Player p, Runnable task) {
        p.getScheduler().run(plugin, t -> task.run(), null);
    }

    // ==================== Lifecycle ====================

    public void forgetPlayer(UUID uuid) {
        if (uuid == null) return;
        prompts.remove(uuid);
        selecting.remove(uuid);
        lastUseAtMs.remove(uuid);
    }

    public void forgetMissingPlayers(Set<UUID> online) {
        if (online == null) return;
        prompts.keySet().removeIf(uuid -> !online.contains(uuid));
        selecting.keySet().removeIf(uuid -> !online.contains(uuid));
        lastUseAtMs.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    /**
     * Closes every open remote menu. Once the plugin is disabled nothing
     * cancels clicks any more, and a menu left open would be a chest full of
     * free items.
     */
    public void closeAllMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof RemoteMenu) {
                    p.closeInventory();
                }
            } catch (Exception ignored) {
                // Folia: not our region during shutdown; the server is closing the view anyway.
            }
        }
    }
}
