package org.sawiq.collins.paper.protect;

import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Asks the land-protection plugins on the server whether a player may build
 * at a block, so a screen cannot be put on someone else's claim.
 *
 * <p>Towny and WorldGuard are soft dependencies: each is asked only when it
 * is installed and enabled, and with neither present every block is fine.
 * The code that touches their classes lives in the two nested hook classes,
 * which the JVM only loads when the matching plugin is there.</p>
 */
public final class BuildProtection {

    private final Logger log;
    private final boolean towny;
    private final boolean worldGuard;
    private boolean warned;

    public BuildProtection(Logger log) {
        this.log = log;
        this.towny = Bukkit.getPluginManager().isPluginEnabled("Towny");
        this.worldGuard = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
        if (towny || worldGuard) {
            log.info("Screens respect build protection from: "
                    + (towny ? "Towny " : "") + (worldGuard ? "WorldGuard" : ""));
        }
    }

    /** True when no installed protection plugin objects to the player building at the block. */
    public boolean canBuild(Player player, Block block) {
        try {
            if (towny && !TownyHook.canBuild(player, block)) return false;
            if (worldGuard && !WorldGuardHook.canBuild(player, block)) return false;
            return true;
        } catch (Throwable t) {
            // A protection plugin whose API we could not call must not let
            // screens onto land it would have protected.
            if (!warned) {
                warned = true;
                log.warning("Could not ask the protection plugin whether " + player.getName()
                        + " may build; refusing the screen. " + t);
            }
            return false;
        }
    }

    private static final class TownyHook {
        static boolean canBuild(Player player, Block block) {
            Material type = block.getType().isAir() ? Material.STONE : block.getType();
            return PlayerCacheUtil.getCachePermission(player, block.getLocation(), type,
                    TownyPermission.ActionType.BUILD);
        }
    }

    private static final class WorldGuardHook {
        static boolean canBuild(Player player, Block block) {
            Material type = block.getType().isAir() ? Material.STONE : block.getType();
            return WorldGuardPlugin.inst().createProtectionQuery()
                    .testBlockPlace(player, block.getLocation(), type);
        }
    }
}
