package com.hearthstead.network;

import com.hearthstead.building.BuildingType;
import com.hearthstead.item.BuildingPlanItem;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BuilderUnlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Server side of the crafted Building Plan (building-plan lane, 26 Sep).
 * The plan is only an entry point: the order itself is the Builder's normal,
 * fully re-validated PLACE. Once {@code BuildJobs.commit} has accepted that
 * order, one matching plan in the main hand is used up. Silent no-op when
 * the hand is empty or holds anything else (the Builder's Plan catalog, a
 * GameTest's mock player); a refused or validate-only order never gets here.
 */
public final class BuildingPlanOrders {

    private BuildingPlanOrders() {
    }

    /** Whether this stack is a plan for the blueprint's building. */
    public static boolean matches(ItemStack stack, @Nullable Blueprint blueprint) {
        if (blueprint == null || stack.isEmpty() || !(stack.getItem() instanceof BuildingPlanItem)) {
            return false;
        }
        BuildingType planned = BuildingPlanItem.typeOf(stack);
        BuildingType built = BuilderUnlocks.buildingType(blueprint.meta().buildingType());
        return planned != null && planned == built;
    }

    /**
     * Uses up one plan after an accepted PLACE. Returns whether one was
     * consumed (creative players keep theirs; the method still returns true
     * for a matching plan so callers and tests can tell it was a plan order).
     */
    public static boolean consumeAfterPlace(@Nullable ServerPlayer player, @Nullable Blueprint blueprint) {
        if (player == null) {
            return false;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!matches(held, blueprint)) {
            return false;
        }
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        return true;
    }
}
