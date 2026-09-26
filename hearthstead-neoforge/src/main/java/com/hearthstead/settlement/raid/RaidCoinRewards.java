package com.hearthstead.settlement.raid;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Saved victory receipts and undelivered physical coins; never creates world drops. */
public final class RaidCoinRewards {
    public static final int FIRST_VICTORY_COINS = 8;
    public static final int RECURRING_VICTORY_COINS = 4;
    private boolean firstAwarded;
    private long lastRecurringSerial;
    private int pending;
    private boolean quarantined;

    public int pending() { return pending; }
    public boolean hasCapacity(int amount) {
        return amount >= 0 && !quarantined && pending <= Integer.MAX_VALUE - amount;
    }
    public boolean awardFirst() {
        if (quarantined) return false;
        if (firstAwarded) return true;
        if (!hasCapacity(FIRST_VICTORY_COINS)) return false;
        firstAwarded = true;
        pending += FIRST_VICTORY_COINS;
        return true;
    }
    public boolean awardRecurring(long serial) {
        if (quarantined || serial <= 0) return false;
        if (serial <= lastRecurringSerial) return true;
        if (!hasCapacity(RECURRING_VICTORY_COINS)) return false;
        lastRecurringSerial = serial;
        pending += RECURRING_VICTORY_COINS;
        return true;
    }

    /** Only the bound, loaded Hearth accepts goods; unaccepted debt stays saved. */
    public void deliver(ServerLevel level, Settlement settlement) {
        if (quarantined || pending == 0 || !level.isLoaded(settlement.center)) return;
        if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
                || !settlement.id.equals(hearth.getSettlementId())) return;
        int offered = Math.min(64, pending);
        ItemStack remaining = hearth.insertGoods(new ItemStack(ModItems.GOLD_COIN.get(), offered));
        int inserted = offered - remaining.getCount();
        if (inserted > 0) {
            pending -= inserted;
            SettlementSavedData.get(level).setDirty();
        }
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putBoolean("FirstAwarded", firstAwarded);
        tag.putLong("LastRecurringSerial", lastRecurringSerial);
        tag.putInt("Pending", pending);
        tag.putBoolean("Quarantined", quarantined);
        return tag;
    }

    public static RaidCoinRewards readNbt(CompoundTag settlementTag) {
        RaidCoinRewards result = new RaidCoinRewards();
        if (!settlementTag.contains("RaidCoinRewards")) return result;
        if (!settlementTag.contains("RaidCoinRewards", Tag.TAG_COMPOUND)) {
            result.quarantined = true;
            return result;
        }
        CompoundTag tag = settlementTag.getCompound("RaidCoinRewards");
        result.quarantined = tag.getBoolean("Quarantined")
            || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.contains("Quarantined", Tag.TAG_BYTE)
            || tag.getByte("Quarantined") < 0 || tag.getByte("Quarantined") > 1
            || !tag.contains("FirstAwarded", Tag.TAG_BYTE)
            || tag.getByte("FirstAwarded") < 0 || tag.getByte("FirstAwarded") > 1
            || !tag.contains("LastRecurringSerial", Tag.TAG_LONG)
            || !tag.contains("Pending", Tag.TAG_INT)
            || tag.getLong("LastRecurringSerial") < 0 || tag.getInt("Pending") < 0;
        if (!result.quarantined) {
            result.firstAwarded = tag.getBoolean("FirstAwarded");
            result.lastRecurringSerial = tag.getLong("LastRecurringSerial");
            result.pending = tag.getInt("Pending");
        }
        return result;
    }
}
