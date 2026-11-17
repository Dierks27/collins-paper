package org.sawiq.collins.paper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelIdsTest {

    @Test
    void validIds() {
        assertEquals("collins:tv_remote", ModelIds.parse("collins:tv_remote"));
        assertEquals("collins:tv_remote", ModelIds.parse("  Collins:TV_Remote "));
        assertEquals("collins:item/tv_remote", ModelIds.parse("collins:item/tv_remote"));
        assertEquals("minecraft:blaze_rod", ModelIds.parse("blaze_rod"));
    }

    @Test
    void blankMeansNoModel() {
        assertEquals("", ModelIds.parse(""));
        assertEquals("", ModelIds.parse("   "));
        assertEquals("", ModelIds.parse(null));
    }

    @Test
    void invalidIds() {
        assertNull(ModelIds.parse("collins:tv remote"));
        assertNull(ModelIds.parse("collins:"));
        assertNull(ModelIds.parse(":tv_remote"));
        assertNull(ModelIds.parse("col/lins:tv_remote"));
        assertNull(ModelIds.parse("collins:tv:remote"));
        assertNull(ModelIds.parse("collins:tv_remote!"));
    }
}
