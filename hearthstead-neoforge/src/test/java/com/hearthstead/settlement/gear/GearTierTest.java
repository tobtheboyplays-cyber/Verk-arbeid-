package com.hearthstead.settlement.gear;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GearTierTest {

    // ------------------------------------------------------------ tier lookup

    @Test
    void vanillaGearLandsOnTheLadder() {
        assertEquals(0, GearTiers.classify(Items.WOODEN_SWORD));
        assertEquals(0, GearTiers.classify(Items.STONE_AXE));
        assertEquals(0, GearTiers.classify(Items.IRON_SWORD), "iron hand gear is Common");
        assertEquals(0, GearTiers.classify(Items.IRON_HOE));
        assertEquals(0, GearTiers.classify(Items.LEATHER_CHESTPLATE));
        assertEquals(0, GearTiers.classify(Items.GOLDEN_HELMET));
        assertEquals(0, GearTiers.classify(Items.BOW));
        assertEquals(1, GearTiers.classify(Items.CHAINMAIL_HELMET));
        assertEquals(1, GearTiers.classify(Items.SHIELD));
        assertEquals(1, GearTiers.classify(Items.TURTLE_HELMET));
        assertEquals(2, GearTiers.classify(Items.IRON_CHESTPLATE));
        assertEquals(2, GearTiers.classify(Items.CROSSBOW));
        assertEquals(3, GearTiers.classify(Items.DIAMOND_SWORD));
        assertEquals(3, GearTiers.classify(Items.DIAMOND_PICKAXE));
        assertEquals(3, GearTiers.classify(Items.TRIDENT));
        assertEquals(3, GearTiers.classify(Items.MACE));
        assertEquals(4, GearTiers.classify(Items.NETHERITE_BOOTS));
        assertEquals(4, GearTiers.classify(Items.NETHERITE_AXE));
        assertEquals(0, GearTiers.classify(Items.BREAD), "non-gear is Common");
    }

    @Test
    void tierOfStacksMatchesTheHeuristicWithoutBoundTags() {
        assertEquals(0, GearTiers.tierOf(ItemStack.EMPTY));
        assertEquals(3, GearTiers.tierOf(new ItemStack(Items.DIAMOND_SWORD)));
        assertSame(GearTier.PLATE, GearTiers.gearTierOf(new ItemStack(Items.IRON_LEGGINGS)));
    }

    /** The shipped data tags and the built-in heuristic must never disagree. */
    @Test
    void shippedTagsAgreeWithTheHeuristic() throws IOException {
        for (int t = 0; t <= GearTier.MAX; t++) {
            String resource = "/data/hearthstead/tags/item/gear_tier/t" + t + ".json";
            JsonObject json;
            try (InputStream in = GearTierTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, "missing " + resource);
                json = JsonParser.parseString(new String(in.readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            }
            assertFalse(json.get("replace").getAsBoolean());
            for (JsonElement value : json.getAsJsonArray("values")) {
                if (!value.isJsonPrimitive()) {
                    // Optional mod entries ({"id", "required": false}) may be
                    // placed off-heuristic on purpose (the iron longsword is Mail).
                    assertFalse(value.getAsJsonObject().get("required").getAsBoolean());
                    continue;
                }
                ResourceLocation id = ResourceLocation.parse(value.getAsString());
                assertTrue(BuiltInRegistries.ITEM.containsKey(id), "unknown item " + id);
                Item item = BuiltInRegistries.ITEM.get(id);
                assertEquals(t, GearTiers.classify(item), id + " tagged t" + t);
            }
        }
    }

    @Test
    void moddedStatsFallBackSensibly() {
        assertEquals(0, GearTiers.armorTierByStats(7, 0.0F, 0.0F));   // leather
        assertEquals(1, GearTiers.armorTierByStats(12, 0.0F, 0.0F));  // chain
        assertEquals(2, GearTiers.armorTierByStats(15, 0.0F, 0.0F));  // iron
        assertEquals(3, GearTiers.armorTierByStats(20, 2.0F, 0.0F));  // diamond
        assertEquals(4, GearTiers.armorTierByStats(20, 3.0F, 0.1F));  // netherite
        assertEquals(0, GearTiers.toolTierByUses(250));
        assertEquals(3, GearTiers.toolTierByUses(1561));
        assertEquals(4, GearTiers.toolTierByUses(2031));
    }

    // ------------------------------------------------------------- the rule

    @Test
    void personalLaddersAreCumulative() {
        assertEquals(0, GearTier.personalTier(GuardRank.RECRUIT));
        assertEquals(1, GearTier.personalTier(GuardRank.SPEARMAN));
        assertEquals(2, GearTier.personalTier(GuardRank.VETERAN));
        assertEquals(3, GearTier.personalTier(GuardRank.SERGEANT));
        assertEquals(4, GearTier.personalTier(GuardRank.CAPTAIN));
        assertEquals(0, GearTier.personalTier(ArcherRank.RECRUIT));
        assertEquals(2, GearTier.personalTier(ArcherRank.SHARPSHOOTER));
        assertEquals(4, GearTier.personalTier(ArcherRank.MASTER));
        assertEquals(0, GearTier.personalTier(2));
        assertEquals(1, GearTier.personalTier(3));
        assertEquals(2, GearTier.personalTier(7));
        assertEquals(3, GearTier.personalTier(8));
        assertEquals(4, GearTier.personalTier(10));
    }

    @Test
    void bothHalvesMustHold() {
        int everything = 0b11111;
        int villageOnly = 0b00011;
        assertTrue(GearTier.allowed(0, 0, 0), "Common needs nothing");
        assertFalse(GearTier.allowed(3, 0, everything), "a Recruit refuses diamond");
        assertFalse(GearTier.allowed(3, 4, villageOnly), "a Captain without the Castle refuses diamond");
        assertTrue(GearTier.allowed(3, 3, everything));
        assertTrue(GearTier.allowed(1, 1, villageOnly));
        assertEquals(1, GearTier.usableTier(4, villageOnly));
        assertEquals(0, GearTier.usableTier(0, everything));
        assertSame(GearTier.MAIL, GearTier.nextLocked(0, everything));
        assertSame(GearTier.PLATE, GearTier.nextLocked(4, villageOnly));
        assertNull(GearTier.nextLocked(4, everything));
    }

    @Test
    void knowledgeGroupsAreAllOfAnyOf() {
        Set<String> plateByDrill = Set.of("node:first_raid_aftermath", "upgrade:guard_arms_iron");
        assertEquals(0b00111, GearTier.knowledgeMask(plateByDrill::contains));
        Set<String> halfCastle = Set.of("node:castle_charter");
        assertFalse(GearTier.DIAMOND.knowledgeMet(halfCastle::contains),
            "the Castle Charter alone is only half of Diamond");
        Set<String> castle = Set.of("node:castle_charter", "node:master_armoury");
        assertTrue(GearTier.DIAMOND.knowledgeMet(castle::contains));
        assertFalse(GearTier.NETHERITE.knowledgeMet(castle::contains));
        assertTrue(GearTier.COMMON.knowledgeMet(id -> false));
    }

    @Test
    void clearanceSurvivesThePackedProjection() {
        for (GearTier.Role role : GearTier.Role.values()) {
            for (int personal = 0; personal <= GearTier.MAX; personal++) {
                for (int mask = 0; mask < 32; mask++) {
                    GearGate.Clearance c = GearGate.unpack(GearGate.pack(role, personal, mask));
                    assertEquals(role, c.role());
                    assertEquals(personal, c.personal());
                    assertEquals(mask, c.knowledgeMask());
                }
            }
        }
    }

    @Test
    void killSwitchOffOpensEverythingAndLiftsTheCap() {
        assertTrue(GearGate.enabled(), "on by default (config not loaded = default true)");
        GearGate.setEnabledOverrideForTest(false);
        try {
            assertFalse(GearGate.enabled());
            assertTrue(GearGate.open(GearTier.Role.GUARD).allows(GearTier.MAX));
            EquipmentRequirement capped = new EquipmentRequirement(Items.DIAMOND_SWORD,
                null, 1).withMaxGearTier(0);
            assertTrue(capped.matches(new ItemStack(Items.DIAMOND_SWORD)),
                "switched off, a persisted cap no longer filters");
        } finally {
            GearGate.setEnabledOverrideForTest(null);
        }
        assertFalse(new EquipmentRequirement(Items.DIAMOND_SWORD, null, 1)
            .withMaxGearTier(0).matches(new ItemStack(Items.DIAMOND_SWORD)));
    }

    // ------------------------------------------------- request cap plumbing

    @Test
    void requirementCapFiltersAboveTierAndRoundTrips() {
        EquipmentRequirement swords = new EquipmentRequirement(Items.WOODEN_SWORD,
            ItemTags.SWORDS.location(), 8);
        assertEquals(GearTier.MAX, swords.maxGearTier());
        EquipmentRequirement recruit = swords.withMaxGearTier(0);
        assertFalse(recruit.matches(new ItemStack(Items.DIAMOND_SWORD)),
            "a Recruit's sword request is never filled with diamond");
        assertTrue(recruit.matches(new ItemStack(Items.WOODEN_SWORD)));
        EquipmentRequirement back = EquipmentRequirement.readNbt(recruit.writeNbt());
        assertNotNull(back);
        assertEquals(0, back.maxGearTier());
        EquipmentRequirement legacy = EquipmentRequirement.readNbt(swords.writeNbt());
        assertNotNull(legacy);
        assertEquals(GearTier.MAX, legacy.maxGearTier(), "old saves carry no cap");
        assertSame(swords, swords.withMaxGearTier(GearTier.MAX));
    }
}
