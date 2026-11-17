package org.sawiq.collins.paper.remote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * The "TV Remote": an ordinary item carrying a tag in its persistent data
 * container. Only the tag identifies it, so renaming it in an anvil neither
 * breaks a real remote nor makes a fake one.
 */
public final class RemoteItem {

    private final JavaPlugin plugin;
    private final Lang lang;
    private final NamespacedKey key;

    public RemoteItem(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.key = new NamespacedKey(plugin, "tv_remote");
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    /** The key of the crafting recipe (and of the item's tag). */
    public NamespacedKey key() {
        return key;
    }

    /**
     * Adds the crafting recipe, unless {@code remote.craftable} is off:
     * <pre>
     *   . G .     G = glass pane
     *   I R I     R = redstone dust
     *   . I .     I = iron ingot
     * </pre>
     * Returns whether the recipe is registered.
     */
    public boolean registerRecipe() {
        Bukkit.removeRecipe(key); // a /reload leaves the old one behind
        if (!plugin.getConfig().getBoolean("remote.craftable", true)) return false;

        ShapedRecipe recipe = new ShapedRecipe(key, create());
        recipe.shape(" G ", "IRI", " I ");
        recipe.setIngredient('G', Material.GLASS_PANE);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('I', Material.IRON_INGOT);
        return Bukkit.addRecipe(recipe);
    }

    public void unregisterRecipe() {
        Bukkit.removeRecipe(key);
    }

    /** The configured item model, or null for none (blank or invalid; invalid is logged). */
    private NamespacedKey itemModel() {
        String raw = plugin.getConfig().getString("remote.item_model", "collins:tv_remote");
        String id = org.sawiq.collins.paper.util.ModelIds.parse(raw);
        if (id == null) {
            if (warnedModel == null || !warnedModel.equals(raw)) {
                warnedModel = raw;
                plugin.getLogger().warning("config.yml remote.item_model '" + raw
                        + "' is not a valid model id (like collins:tv_remote); the remote gets no custom model.");
            }
            return null;
        }
        return id.isEmpty() ? null : NamespacedKey.fromString(id);
    }

    private String warnedModel;

    /**
     * Brings the item model of an existing remote in line with the config.
     * Changes nothing else about the item. Returns whether it changed.
     */
    public boolean refreshModel(ItemStack stack) {
        if (!is(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey want = itemModel();
        NamespacedKey have = meta.hasItemModel() ? meta.getItemModel() : null;
        if (java.util.Objects.equals(want, have)) return false;
        meta.setItemModel(want);
        stack.setItemMeta(meta);
        return true;
    }

    public ItemStack create() {
        ItemStack stack = new ItemStack(material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(lang.raw("remote.item.name")));
        meta.lore(lines(lang.raw("remote.item.lore")));
        if (plugin.getConfig().getBoolean("remote.glint", true)) meta.setEnchantmentGlintOverride(true);
        NamespacedKey model = itemModel();
        if (model != null) meta.setItemModel(model);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean is(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Puts a remote in the player's inventory, or at their feet when it is full. */
    public void give(Player player) {
        for (ItemStack left : player.getInventory().addItem(create()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private Material material() {
        String raw = plugin.getConfig().getString("remote.material", "BLAZE_ROD");
        Material m = raw == null ? null : Material.matchMaterial(raw.trim());
        if (m == null || !m.isItem() || m.isAir()) {
            plugin.getLogger().warning("config.yml remote.material '" + raw + "' is not an item; using BLAZE_ROD.");
            return Material.BLAZE_ROD;
        }
        return m;
    }

    /** A legacy-coloured ({@code §}) string as an item text line, without the default italics. */
    static Component text(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** A legacy-coloured string with {@code \n} line breaks as item lore. */
    static List<Component> lines(String legacy) {
        List<Component> out = new ArrayList<>();
        for (String line : legacy.split("\\n", -1)) out.add(text(line));
        return out;
    }
}
