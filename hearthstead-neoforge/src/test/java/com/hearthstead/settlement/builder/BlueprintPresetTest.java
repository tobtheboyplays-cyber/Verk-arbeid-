package com.hearthstead.settlement.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Style presets share one catalog row: {@code <base>_<style>} groups under {@code <base>}. */
class BlueprintPresetTest {

    @Test
    void stylePresetsGroupUnderTheirBase() {
        assertEquals("bakery_small", BlueprintLibrary.presetInfo("bakery_small_stone", "stone", null).group());
        assertEquals("bakery_small", BlueprintLibrary.presetInfo("bakery_small_rustic", "rustic", null).group());
        assertEquals("Rustic", BlueprintLibrary.presetInfo("bakery_small_rustic", "rustic", null).label());
    }

    @Test
    void theOriginalBlueprintJoinsItsPresets() {
        // builders_hut_small is style "timber" but its id has no suffix: it is the group itself.
        assertEquals("builders_hut_small", BlueprintLibrary.presetInfo("builders_hut_small", "timber", null).group());
        assertEquals("Timber", BlueprintLibrary.presetInfo("builders_hut_small", "timber", null).label());
    }

    @Test
    void theJsonGroupWinsOverTheIdSuffix() {
        // Blueprint lane: every typed blueprint names its building type as group.
        assertEquals("house", BlueprintLibrary.presetInfo("house_cottage", "elmfield", "Elmfield Timber", "house").group());
        assertEquals("house", BlueprintLibrary.presetInfo("house_two_storey", "elmfield", "Elmfield Timber", "house").group());
        assertEquals("house", BlueprintLibrary.presetInfo("house_stone", "stone", "Stone Hall", "house").group());
    }

    @Test
    void samePresetLabelInAGroupIsToldApartByVariant() {
        java.util.Map<String, BlueprintLibrary.PresetInfo> byId = new java.util.HashMap<>();
        byId.put("bakery_small", BlueprintLibrary.presetInfo("bakery_small", "elmfield", "Elmfield Timber", "bakery"));
        byId.put("bakery_large", BlueprintLibrary.presetInfo("bakery_large", "elmfield", "Elmfield Timber", "bakery"));
        byId.put("bakery_stone", BlueprintLibrary.presetInfo("bakery_stone", "stone", "Stone Hall", "bakery"));
        var out = BlueprintLibrary.distinctLabels(byId, java.util.Map.of("bakery_small", "small", "bakery_large", "large"));
        assertEquals("Elmfield Timber \u2013 Small", out.get("bakery_small").label());
        assertEquals("Elmfield Timber \u2013 Large", out.get("bakery_large").label());
        assertEquals("Stone Hall", out.get("bakery_stone").label());
    }

    @Test
    void anExplicitPresetLabelWins() {
        assertEquals("Stone & slate",
            BlueprintLibrary.presetInfo("tavern_inn_stone", "stone", "Stone & slate").label());
        assertEquals("tavern_inn", BlueprintLibrary.presetInfo("tavern_inn_stone", "stone", "Stone & slate").group());
    }
}
