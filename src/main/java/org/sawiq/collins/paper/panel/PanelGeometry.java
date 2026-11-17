package org.sawiq.collins.paper.panel;

import org.sawiq.collins.paper.model.Screen;

import java.util.List;

/**
 * Where a screen's panels go. No Bukkit types, so it can be unit-tested.
 *
 * <p>The client mod draws the video {@link #VIDEO_PLANE} blocks out from the
 * screen's block face, on whichever side of the screen the viewer is on
 * ({@code VideoScreenRenderer} in collins-fabric). So a screen gets a panel
 * on both faces, each lying between the block face and the video plane:
 * from {@code offset} to {@code offset + depth} out from the face.</p>
 *
 * <p>A panel is described by its origin (the corner with the lowest x, y and
 * z) and its size along each axis, which is exactly how a display entity
 * at the origin with a scale-only transformation draws a unit block.</p>
 */
public final class PanelGeometry {

    /** How far out from the block face collins-fabric draws the video. */
    public static final double VIDEO_PLANE = 0.01;

    private PanelGeometry() {}

    /** Which face of the screen a panel covers. */
    public enum Side {
        NORTH, SOUTH, // -Z, +Z (upright screen spanning X)
        DOWN, UP,     // -Y, +Y (flat screen)
        WEST, EAST    // -X, +X (upright screen spanning Z)
    }

    /**
     * One panel.
     *
     * @param anchorBlockX x of the block the origin lies in (decides the chunk)
     * @param anchorBlockZ z of the block the origin lies in (decides the chunk)
     */
    public record Panel(Side side,
                        double x, double y, double z,
                        double sizeX, double sizeY, double sizeZ,
                        int anchorBlockX, int anchorBlockZ) {}

    /** Both panels of a screen. */
    public static List<Panel> panels(Screen s, double offset, double depth) {
        int minX = Math.min(s.x1(), s.x2()), maxX = Math.max(s.x1(), s.x2());
        int minY = Math.min(s.y1(), s.y2()), maxY = Math.max(s.y1(), s.y2());
        int minZ = Math.min(s.z1(), s.z2()), maxZ = Math.max(s.z1(), s.z2());

        double w = maxX - minX + 1;
        double h = maxY - minY + 1;
        double d = maxZ - minZ + 1;

        double near = offset + depth; // the low face of a panel on a "negative" side
        return switch (s.axis()) {
            case 0 -> List.of( // XY: upright, spans X, faces -Z and +Z
                    new Panel(Side.NORTH, minX, minY, minZ - near, w, h, depth, minX, minZ - 1),
                    new Panel(Side.SOUTH, minX, minY, maxZ + 1 + offset, w, h, depth, minX, maxZ + 1));
            case 1 -> List.of( // XZ: flat, faces down and up
                    new Panel(Side.DOWN, minX, minY - near, minZ, w, depth, d, minX, minZ),
                    new Panel(Side.UP, minX, maxY + 1 + offset, minZ, w, depth, d, minX, minZ));
            default -> List.of( // YZ: upright, spans Z, faces -X and +X
                    new Panel(Side.WEST, minX - near, minY, minZ, depth, h, d, minX - 1, minZ),
                    new Panel(Side.EAST, maxX + 1 + offset, minY, minZ, depth, h, d, maxX + 1, minZ));
        };
    }

    /** True when a panel with these settings would reach past the video plane and cover the video. */
    public static boolean coversVideo(double offset, double depth) {
        return offset + depth >= VIDEO_PLANE;
    }
}
