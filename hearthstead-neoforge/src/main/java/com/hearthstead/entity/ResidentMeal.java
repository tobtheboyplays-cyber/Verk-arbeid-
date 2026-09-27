package com.hearthstead.entity;

import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import com.hearthstead.entity.ai.EatFromHearthGoal;
import com.hearthstead.registry.ModSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** One physical meal (or its unreturned container), owned by the resident. */
public final class ResidentMeal {
    public static final int DURATION = 40;
    public static final String NBT_KEY = "ResidentMeal";
    private ItemStack owned = ItemStack.EMPTY;
    private int remaining;
    private long lastAdvance = Long.MIN_VALUE;
    private CompoundTag quarantined;

    public boolean active() { return remaining > 0 && !owned.isEmpty(); }
    public boolean hasCargo() { return quarantined != null || !owned.isEmpty(); }
    public int remainingTicks() { return remaining; }
    public ItemStack displayCopy() { return active() ? owned.copy() : ItemStack.EMPTY; }

    /**
     * Call only at an authoritative receiving contact. Splits the live source,
     * never a renderer copy; failure leaves the source completely unchanged.
     */
    public boolean begin(SettlerEntity resident, ItemStack source) {
        if (!(resident.level() instanceof ServerLevel) || !resident.isAlive()
            || hasCargo() || source == null || source.isEmpty()
            || source.getFoodProperties(resident) == null) {
            return false;
        }
        owned = source.split(1);
        remaining = DURATION;
        lastAdvance = resident.level().getGameTime();
        return true;
    }

    /** Only an active EATING tick advances; duplicate callers cannot eat faster. */
    public boolean tick(SettlerEntity resident) {
        if (!(resident.level() instanceof ServerLevel) || !resident.isAlive()
            || resident.getActivity() != SettlerActivity.EATING || !active()
            || lastAdvance == resident.level().getGameTime()) {
            return false;
        }
        FoodProperties food = owned.getFoodProperties(resident);
        if (food == null) {
            // Keep exact item ownership, but relinquish the EATING goal. A
            // component/registry change must not leave Remaining > 0 forever.
            // The existing periodic remainder/death path returns this same
            // stack through deferred materialization, including capacity retry.
            remaining = 0;
            lastAdvance = resident.level().getGameTime();
            return false; // Cancellation grants no nutrition or completion.
        }
        lastAdvance = resident.level().getGameTime();
        --remaining;
        ServerLevel level = (ServerLevel) resident.level();
        int chew = (DURATION - remaining) % EatFromHearthGoal.EAT_BITE_PERIOD;
        if (chew == EatFromHearthGoal.EAT_BITE_TICK_A
            || chew == EatFromHearthGoal.EAT_BITE_TICK_B) {
            com.hearthstead.entity.ai.WorkSoundSync.play(level, resident.getX(),
                resident.getY() + 1.4, resident.getZ(), ModSounds.SETTLER_EAT.get(), 0.45F, 1.0F);
        }
        if (remaining % 6 == 0) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, owned),
                resident.getX(), resident.getY() + 1.3, resident.getZ(),
                3, 0.1, 0.1, 0.1, 0.05);
        }
        if (remaining > 0) {
            return false;
        }
        // Preserve the existing custom nutrition/morale contract. Do not call
        // arbitrary item-use hooks or add potion/teleport effects in this repair.
        // FoodProperties owns vanilla 1.21.1 bowl/bottle conversion, not a
        // crafting recipe's unrelated remainder.
        owned = food.usingConvertsTo().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        resident.setHunger(resident.getHunger() + food.nutrition() * 8.0F);
        resident.addMorale(2.0F);
        return true;
    }

    /** Completed bowls/bottles retry independently of the eating goal. */
    public boolean releaseRemainder(SettlerEntity resident, ServerLevel level) {
        return active() || releaseCargo(resident, level);
    }

    /** On death the same queue owns unconsumed food or a completed remainder. */
    public boolean releaseCargo(SettlerEntity resident, ServerLevel level) {
        if (quarantined != null) return false;
        if (owned.isEmpty()) {
            return true;
        }
        DeferredItemMaterializationSavedData drops =
            DeferredItemMaterializationSavedData.get(level);
        UUID transfer = drops.queue(level, resident.getX(), resident.getY() + 0.3D,
            resident.getZ(), owned);
        if (transfer == null) {
            return false; // Full/quarantined ledger: corpse retains its saved cargo.
        }
        owned = ItemStack.EMPTY;
        remaining = 0;
        drops.materialize(level, transfer);
        return true;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        if (quarantined != null) return quarantined.copy();
        CompoundTag tag = new CompoundTag();
        if (!owned.isEmpty()) {
            tag.put("Item", owned.save(registries));
            tag.putInt("Remaining", remaining);
            tag.putLong("LastAdvance", lastAdvance);
        }
        return tag;
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        owned = ItemStack.EMPTY;
        remaining = 0;
        lastAdvance = Long.MIN_VALUE;
        quarantined = null;
        if (!tag.contains("Item")) {
            return; // Legacy entities have no meal session.
        }
        ItemStack decoded = ItemStack.parseOptional(registries, tag.getCompound("Item"));
        int ticks = tag.getInt("Remaining");
        if (decoded.isEmpty() || decoded.getCount() != 1 || ticks < 0 || ticks > DURATION) {
            // Keep the raw ownership for recovery without discarding the entire
            // resident when a registry change makes its food undecodable.
            quarantined = tag.copy();
            return;
        }
        owned = decoded;
        remaining = ticks;
        lastAdvance = tag.contains("LastAdvance") ? tag.getLong("LastAdvance") : Long.MIN_VALUE;
    }
}
