package com.hearthstead.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only, server-authored projection of one physical crafting action.
 *
 * <p>This object never owns inventory. Its grid contains exact one-count
 * copies of the recipe units bound to an action's reserved source slots, and
 * its output is an exact copy of the protected output escrow. Inventory
 * mutation remains in the owning crafting service. Keeping this contract
 * recipe-shaped instead of Lumberer-shaped lets later {@code WORK_CRAFT}
 * roles reuse the same 3x3 table renderer without borrowing the wooden-axe
 * recipe or animation.
 */
public final class CraftPresentation {
    public static final int GRID_SIZE = 9;
    public static final int FULL_GRID_MASK = (1 << GRID_SIZE) - 1;

    public enum Phase {
        NONE(0),
        LAY_OUT(1),
        WIND_UP(2),
        RESULT_READ(3),
        PICK_UP(4),
        CARRIED(5),
        DEPOSIT(6);

        private final int wireId;

        Phase(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Phase byWireId(int wireId) {
            for (Phase value : values()) {
                if (value.wireId == wireId) {
                    return value;
                }
            }
            return NONE;
        }
    }

    private static final CraftPresentation EMPTY = new CraftPresentation();

    @Nullable
    private final UUID actionId;
    @Nullable
    private final BlockPos anchorPos;
    private final Direction facing;
    private final Phase phase;
    private final int visibleGridMask;
    private final List<ItemStack> recipeGrid;
    private final ItemStack output;

    private CraftPresentation() {
        this.actionId = null;
        this.anchorPos = null;
        this.facing = Direction.NORTH;
        this.phase = Phase.NONE;
        this.visibleGridMask = 0;
        this.recipeGrid = emptyGrid();
        this.output = ItemStack.EMPTY;
    }

    public CraftPresentation(UUID actionId, BlockPos anchorPos,
                             Direction facing, Phase phase,
                             int visibleGridMask, List<ItemStack> recipeGrid,
                             ItemStack output) {
        this.actionId = java.util.Objects.requireNonNull(actionId, "actionId");
        this.anchorPos = java.util.Objects.requireNonNull(anchorPos,
            "anchorPos").immutable();
        this.facing = horizontal(java.util.Objects.requireNonNull(facing,
            "facing"));
        this.phase = java.util.Objects.requireNonNull(phase, "phase");
        if (phase == Phase.NONE || recipeGrid == null
            || recipeGrid.size() != GRID_SIZE) {
            throw new IllegalArgumentException(
                "active craft presentation needs one exact 3x3 grid");
        }
        this.visibleGridMask = visibleGridMask & FULL_GRID_MASK;
        List<ItemStack> copied = new ArrayList<>(GRID_SIZE);
        for (ItemStack stack : recipeGrid) {
            copied.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        this.recipeGrid = List.copyOf(copied);
        this.output = output == null ? ItemStack.EMPTY : output.copy();
    }

    public static CraftPresentation empty() {
        return EMPTY;
    }

    public boolean active() {
        return phase != Phase.NONE && actionId != null && anchorPos != null;
    }

    @Nullable
    public UUID actionId() {
        return actionId;
    }

    @Nullable
    public BlockPos anchorPos() {
        return anchorPos;
    }

    public Direction facing() {
        return facing;
    }

    public Phase phase() {
        return phase;
    }

    public int visibleGridMask() {
        return visibleGridMask;
    }

    public boolean slotVisible(int slot) {
        return slot >= 0 && slot < GRID_SIZE
            && (visibleGridMask & 1 << slot) != 0
            && !recipeGrid.get(slot).isEmpty();
    }

    public List<ItemStack> recipeGrid() {
        List<ItemStack> copy = new ArrayList<>(GRID_SIZE);
        for (ItemStack stack : recipeGrid) {
            copy.add(stack.copy());
        }
        return List.copyOf(copy);
    }

    public ItemStack recipeSlot(int slot) {
        return slot < 0 || slot >= GRID_SIZE
            ? ItemStack.EMPTY : recipeGrid.get(slot).copy();
    }

    public ItemStack output() {
        return output.copy();
    }

    /**
     * Advances only the presentation state of the same action. A different
     * action ID must construct a new projection so stale packets cannot
     * retime or reveal another worker's props.
     */
    public CraftPresentation advance(Phase nextPhase, int nextMask,
                                     BlockPos nextAnchor, Direction nextFacing,
                                     ItemStack nextOutput) {
        if (!active()) {
            throw new IllegalStateException("cannot advance an empty craft action");
        }
        return new CraftPresentation(actionId, nextAnchor, nextFacing,
            nextPhase, nextMask, recipeGrid, nextOutput);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (!active()) {
            return tag;
        }
        tag.putUUID("Action", actionId);
        tag.put("Anchor", NbtUtils.writeBlockPos(anchorPos));
        tag.putByte("Facing", (byte) facing.get3DDataValue());
        tag.putByte("Phase", (byte) phase.wireId());
        tag.putInt("VisibleMask", visibleGridMask);
        ListTag grid = new ListTag();
        for (ItemStack stack : recipeGrid) {
            Tag encoded = stack.copy().saveOptional(registries);
            grid.add(encoded instanceof CompoundTag compound
                ? compound : new CompoundTag());
        }
        tag.put("Grid", grid);
        Tag encodedOutput = output.copy().saveOptional(registries);
        tag.put("Output", encodedOutput instanceof CompoundTag compound
            ? compound : new CompoundTag());
        return tag;
    }

    public static CraftPresentation load(HolderLookup.Provider registries,
                                         CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Action")
            || !tag.contains("Anchor", Tag.TAG_COMPOUND)) {
            return empty();
        }
        Phase phase = Phase.byWireId(tag.getByte("Phase"));
        BlockPos anchor = NbtUtils.readBlockPos(tag, "Anchor").orElse(null);
        ListTag encodedGrid = tag.getList("Grid", Tag.TAG_COMPOUND);
        if (phase == Phase.NONE || anchor == null
            || encodedGrid.size() != GRID_SIZE) {
            return empty();
        }
        List<ItemStack> grid = new ArrayList<>(GRID_SIZE);
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            grid.add(ItemStack.parseOptional(registries,
                encodedGrid.getCompound(slot)));
        }
        ItemStack output = ItemStack.parseOptional(registries,
            tag.getCompound("Output"));
        try {
            return new CraftPresentation(tag.getUUID("Action"), anchor,
                Direction.from3DDataValue(tag.getByte("Facing")), phase,
                tag.getInt("VisibleMask"), grid, output);
        } catch (RuntimeException malformed) {
            return empty();
        }
    }

    private static List<ItemStack> emptyGrid() {
        List<ItemStack> grid = new ArrayList<>(GRID_SIZE);
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            grid.add(ItemStack.EMPTY);
        }
        return List.copyOf(grid);
    }

    private static Direction horizontal(Direction direction) {
        return direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
    }
}
