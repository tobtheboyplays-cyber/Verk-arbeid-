package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.look.Archetype;
import com.hearthstead.entity.look.CharacterGenome;
import com.hearthstead.entity.look.CharacterLooks;
import com.hearthstead.entity.look.VoiceProfile;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Skins lane: the look state lives on real entities, survives save/load, and
 * the common-side archetype/voice API works on the (dedicated) server.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CharacterLookGameTests {
    @GameTest(batch = "looks", template = "empty16", timeoutTicks = 40)
    public void settlerLookAndCostumeSurviveSaveAndLoad(GameTestHelper helper) {
        SettlerEntity visitor = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 3));
        visitor.setSettlerName("Sigrun");
        visitor.setLookCostume(CharacterLooks.costumeForRole("minstrel"));
        CharacterGenome before = CharacterLooks.genomeOf(visitor);
        helper.assertTrue(before.sex() == 1 && before.beard() == 0, "a Sigrun presents feminine, no beard");
        helper.assertTrue(CharacterLooks.archetypeOf(visitor) == Archetype.MINSTREL, "costume sets the archetype");

        CompoundTag saved = new CompoundTag();
        visitor.addAdditionalSaveData(saved);
        SettlerEntity loaded = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(loaded != null, "reload probe must exist");
        loaded.readAdditionalSaveData(saved);
        loaded.setSettlerName("Sigrun");
        helper.assertTrue(loaded.getAppearanceSeed() == visitor.getAppearanceSeed(), "seed survives the save");
        helper.assertTrue(CharacterLooks.genomeOf(loaded).equals(before), "the same face after a reload");
        helper.assertTrue(loaded.getLookCostume() == CharacterLooks.COSTUME_MINSTREL, "costume survives the save");
        VoiceProfile a = CharacterLooks.voiceProfile(visitor);
        VoiceProfile b = CharacterLooks.voiceProfile(loaded);
        helper.assertTrue(a.equals(b), "the voice follows the look across a reload");

        // An old save with no Appearance key derives a stable look from the UUID.
        CompoundTag legacy = saved.copy();
        legacy.remove("Appearance");
        legacy.remove("LookCostume");
        SettlerEntity old = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(old != null, "legacy probe must exist");
        old.readAdditionalSaveData(legacy);
        helper.assertTrue(old.getAppearanceSeed() == old.getUUID().hashCode(), "legacy seed = UUID hash");
        helper.assertTrue(old.getLookCostume() == 0, "legacy saves wear their own clothes");
        visitor.discard();
        helper.succeed();
    }

    @GameTest(batch = "looks", template = "empty16", timeoutTicks = 40)
    public void raidersGetArchetypesAndCaptainsKeepTheirLook(GameTestHelper helper) {
        RaiderEntity grunt = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 3));
        helper.assertTrue(CharacterLooks.archetypeOf(grunt) == Archetype.SKIRMISHER, "plain raider is a skirmisher");
        RaiderEntity brute = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(5, 1, 3));
        brute.setVariant(RaiderEntity.Variant.BRUTE);
        brute.setCustomName(Component.translatableWithFallback("hearthstead.event.brute_toll.who", "demands food"));
        helper.assertTrue(CharacterLooks.archetypeOf(brute) == Archetype.TOLL_CHIEF, "toll chief read from its name");
        helper.assertTrue(CharacterLooks.voiceProfile(brute).build() == 2, "brutes always sound big");
        int v = CharacterLooks.raiderVariant(grunt.getUUID(), CharacterLooks.SKIRMISHER_VARIANTS);
        helper.assertTrue(v == CharacterLooks.raiderVariant(grunt.getUUID(), CharacterLooks.SKIRMISHER_VARIANTS),
            "variant is a pure function of the UUID");
        grunt.discard();
        brute.discard();
        helper.succeed();
    }
}
