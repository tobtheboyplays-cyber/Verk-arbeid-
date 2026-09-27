package com.hearthstead.settlement;

import com.hearthstead.HearthsteadServerConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The [start] kitBread switch for the one-time start kit (owner request, Sunday save). */
class StarterKitConfigTest {

    @AfterEach
    void reset() {
        StarterKitConfig.overrideForTests(null);
    }

    @Test
    void theServerConfigHasAStartSectionWithKitBreadZeroByDefault() {
        Object value = HearthsteadServerConfig.SPEC.getSpec().get("start.kitBread");
        assertNotNull(value, "serverconfig has [start] kitBread");
        ModConfigSpec.ValueSpec spec = (ModConfigSpec.ValueSpec) value;
        // Owner, 27 Sep: the Guildmaster's welcome gives the bread; the kit is the Handbook only.
        assertEquals(0, spec.getDefault());
        assertTrue(spec.test(0), "0 turns the food off");
        assertTrue(!spec.test(-1) && !spec.test(StarterKitConfig.MAX_KIT_BREAD + 1), "range is bounded");
    }

    @Test
    void beforeTheWorldConfigLoadsTheKitIsTheDefault() {
        assertEquals(StarterKitConfig.DEFAULT_KIT_BREAD, StarterKitConfig.kitBread());
    }

    @Test
    void overridesAreClampedAndZeroMeansNoFood() {
        StarterKitConfig.overrideForTests(0);
        assertEquals(0, StarterKitConfig.kitBread());
        StarterKitConfig.overrideForTests(-5);
        assertEquals(0, StarterKitConfig.kitBread());
        StarterKitConfig.overrideForTests(1000);
        assertEquals(StarterKitConfig.MAX_KIT_BREAD, StarterKitConfig.kitBread());
        StarterKitConfig.overrideForTests(null);
        assertEquals(StarterKitConfig.DEFAULT_KIT_BREAD, StarterKitConfig.kitBread());
    }
}
