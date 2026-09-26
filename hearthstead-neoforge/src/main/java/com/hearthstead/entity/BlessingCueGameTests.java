package com.hearthstead.entity;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exact APPLIED-only event and authored-contact timing contract. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingCueGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_receive_event_and_contact_are_applied_only")
    public void receiveEventAndContactAreAppliedOnly(GameTestHelper helper) {
        CueSpy settler = spawnSpy(helper, new BlockPos(2, 1, 2));
        long appliedTick = helper.getLevel().getGameTime();
        helper.assertTrue(settler.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.APPLIED
                && settler.applyBlessing(BlessingId.WARDEN_OATH)
                    == TargetBlessingState.ApplyResult.APPLIED
                && settler.applyBlessing(BlessingId.WARDEN_OATH)
                    == TargetBlessingState.ApplyResult.APPLIED,
            "the three legal ranks must each produce one authoritative APPLIED");
        helper.assertTrue(settler.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.MAXED
                && settler.broadcasts == 3
                && settler.pendingBlessingCueCount() == 3,
            "MAXED must add neither event 73 nor a delayed contact cue");

        CueSpy invalid = spawnSpy(helper, new BlockPos(3, 1, 2));
        CompoundTag corrupted = new CompoundTag();
        invalid.addAdditionalSaveData(corrupted);
        corrupted.remove("TargetBlessings");
        invalid.readAdditionalSaveData(corrupted);
        helper.assertTrue(invalid.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.INVALID
                && invalid.broadcasts == 0
                && invalid.pendingBlessingCueCount() == 0,
            "INVALID must emit no receive event and queue no contact accent");

        CueSpy reloaded = spawnSpy(helper, new BlockPos(2, 1, 3));
        helper.assertTrue(reloaded.applyBlessing(BlessingId.THORNED_ROADS)
                == TargetBlessingState.ApplyResult.APPLIED
                && reloaded.pendingBlessingCueCount() == 1,
            "fixture must own one pending runtime-only contact cue");
        CompoundTag saved = new CompoundTag();
        reloaded.addAdditionalSaveData(saved);
        reloaded.readAdditionalSaveData(saved);
        helper.assertTrue(reloaded.pendingBlessingCueCount() == 0,
            "an entity reload must discard transient contact cues, never replay them");

        helper.runAfterDelay(SettlerEntity.BLESSING_CONTACT_DELAY_TICKS - 1L,
            () -> {
                helper.assertTrue(settler.contacts == 0,
                    "the sound/VFX must not lead the authored 0.50s contact");
                helper.succeedWhen(() -> {
                    helper.assertTrue(settler.contacts == 3
                            && settler.lastContact == BlessingId.WARDEN_OATH
                            && settler.contactTick - appliedTick
                                == SettlerEntity.BLESSING_CONTACT_DELAY_TICKS
                            && settler.pendingBlessingCueCount() == 0,
                        "every APPLIED rank must land one cue exactly on contact tick 10, "
                            + "with no stuck queue");
                });
            });
    }

    private static CueSpy spawnSpy(GameTestHelper helper, BlockPos relativePos) {
        CueSpy settler = new CueSpy(ModEntities.SETTLER.get(), helper.getLevel());
        BlockPos absolute = helper.absolutePos(relativePos);
        settler.moveTo(absolute.getX() + 0.5D, absolute.getY(),
            absolute.getZ() + 0.5D, 0.0F, 0.0F);
        settler.setNoAi(true);
        helper.getLevel().addFreshEntity(settler);
        return settler;
    }

    private static final class CueSpy extends SettlerEntity {
        private int broadcasts;
        private int contacts;
        private long contactTick = Long.MIN_VALUE;
        private BlessingId lastContact;

        private CueSpy(EntityType<? extends SettlerEntity> type, Level level) {
            super(type, level);
        }

        @Override
        protected void broadcastBlessingReceiveEvent() {
            broadcasts++;
        }

        @Override
        protected void presentBlessingContact(BlessingId blessing) {
            contacts++;
            contactTick = level().getGameTime();
            lastContact = blessing;
        }
    }
}
