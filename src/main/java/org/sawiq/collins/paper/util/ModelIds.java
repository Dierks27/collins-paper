package org.sawiq.collins.paper.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Item model ids from config.yml. No Bukkit types, so it can be unit-tested. */
public final class ModelIds {

    /** Valid namespaced id, as Minecraft accepts it: {@code namespace:path}. */
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    private ModelIds() {}

    /**
     * Returns the id to use, in {@code namespace:path} form, or {@code ""}
     * for "no custom model" (blank), or {@code null} when the value is not a
     * valid id. A bare path gets the {@code minecraft} namespace, as
     * Minecraft does.
     */
    public static String parse(String raw) {
        if (raw == null) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return "";
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        return MODEL_ID.matcher(id).matches() ? id : null;
    }
}
