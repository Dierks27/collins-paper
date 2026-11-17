package org.sawiq.collins.paper.panel;

import org.junit.jupiter.api.Test;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.panel.PanelGeometry.Panel;
import org.sawiq.collins.paper.panel.PanelGeometry.Side;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelGeometryTest {

    private static final double OFFSET = 0.001, DEPTH = 0.004, E = 1e-9;

    private static Screen screen(int x1, int y1, int z1, int x2, int y2, int z2, int axis) {
        return new Screen("tv", "world", x1, y1, z1, x2, y2, z2, (byte) axis, "", false, false, 1f, 720);
    }

    private static void assertPanel(Panel p, Side side, double x, double y, double z,
                                    double sx, double sy, double sz, int ax, int az) {
        assertEquals(side, p.side());
        assertEquals(x, p.x(), E, "x");
        assertEquals(y, p.y(), E, "y");
        assertEquals(z, p.z(), E, "z");
        assertEquals(sx, p.sizeX(), E, "sizeX");
        assertEquals(sy, p.sizeY(), E, "sizeY");
        assertEquals(sz, p.sizeZ(), E, "sizeZ");
        assertEquals(ax, p.anchorBlockX(), "anchorBlockX");
        assertEquals(az, p.anchorBlockZ(), "anchorBlockZ");
    }

    @Test
    void uprightAlongX() {
        // 4 wide (x 10..13), 3 high (y 64..66), in the plane z = 20. Corners in either order.
        for (Screen s : List.of(screen(10, 64, 20, 13, 66, 20, 0), screen(13, 66, 20, 10, 64, 20, 0))) {
            List<Panel> p = PanelGeometry.panels(s, OFFSET, DEPTH);
            assertEquals(2, p.size());
            assertPanel(p.get(0), Side.NORTH, 10, 64, 20 - 0.005, 4, 3, DEPTH, 10, 19);
            assertPanel(p.get(1), Side.SOUTH, 10, 64, 21.001, 4, 3, DEPTH, 10, 21);
        }
    }

    @Test
    void uprightAlongZ() {
        // 5 wide (z -3..1), 2 high (y 70..71), in the plane x = -8.
        List<Panel> p = PanelGeometry.panels(screen(-8, 70, -3, -8, 71, 1, 2), OFFSET, DEPTH);
        assertPanel(p.get(0), Side.WEST, -8 - 0.005, 70, -3, DEPTH, 2, 5, -9, -3);
        assertPanel(p.get(1), Side.EAST, -6.999, 70, -3, DEPTH, 2, 5, -7, -3);
    }

    @Test
    void flat() {
        // 3 x 6 on the floor at y = 63.
        List<Panel> p = PanelGeometry.panels(screen(0, 63, 0, 2, 63, 5, 1), OFFSET, DEPTH);
        assertPanel(p.get(0), Side.DOWN, 0, 63 - 0.005, 0, 3, DEPTH, 6, 0, 0);
        assertPanel(p.get(1), Side.UP, 0, 64.001, 0, 3, DEPTH, 6, 0, 0);
    }

    @Test
    void panelsStayBehindTheVideoPlane() {
        Screen s = screen(10, 64, 20, 13, 66, 20, 0);
        List<Panel> p = PanelGeometry.panels(s, OFFSET, DEPTH);
        // South face of the blocks is z = 21; the video is drawn at 21.01.
        double outer = p.get(1).z() + p.get(1).sizeZ();
        assertTrue(outer < 21 + PanelGeometry.VIDEO_PLANE);
        // North face is z = 20; the video is drawn at 19.99.
        assertTrue(p.get(0).z() > 20 - PanelGeometry.VIDEO_PLANE);

        assertFalse(PanelGeometry.coversVideo(OFFSET, DEPTH));
        assertTrue(PanelGeometry.coversVideo(0.01, 0.0625)); // the values first suggested
    }

    @Test
    void anchorChunkDoesNotDependOnTheSettings() {
        // A screen on a chunk border (z = 16): the north panel must be looked
        // for in chunk z = 0 whatever offset and depth are in use.
        Screen s = screen(0, 64, 16, 3, 66, 16, 0);
        for (double[] od : new double[][]{{0.001, 0.004}, {0, 0.0001}, {0.2, 0.25}}) {
            Panel north = PanelGeometry.panels(s, od[0], od[1]).get(0);
            assertEquals(15, north.anchorBlockZ());
            assertTrue(Math.floor(north.z()) == 15);
        }
    }
}
