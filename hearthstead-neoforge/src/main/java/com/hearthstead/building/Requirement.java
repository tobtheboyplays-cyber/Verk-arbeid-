package com.hearthstead.building;

import com.hearthstead.settlement.RoomScanner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * One demand a building type makes of its room, and the means to measure it
 * against a completed scan.
 *
 * <p>Requirements report a COUNT, not a yes/no, so the plaque can say "1 of 2
 * lanterns" instead of a bare red cross. That partial state is the difference
 * between a player who knows what to do next and a player who is guessing.
 */
public record Requirement(String id, int needed, ToIntFunction<RoomScanner.Result> counter) {

    /** How far along one requirement is, for both the glow and the UI list. */
    public record Status(Requirement requirement, int have, int needed) {

        public boolean met() {
            return have >= needed;
        }

        /** Some progress, but not enough — the amber case. */
        public boolean partial() {
            return have > 0 && have < needed;
        }

        public Component describe() {
            return Component.translatable(
                "hearthstead.requirement." + requirement.id(), have, needed);
        }
    }

    public Status measure(RoomScanner.Result result) {
        return new Status(this, counter.applyAsInt(result), needed);
    }

    // ------------------------------------------------------------ factories --

    public static Requirement beds(int n) {
        return new Requirement("beds", n, r -> r.beds().size());
    }

    public static Requirement doors(int n) {
        return new Requirement("doors", n, RoomScanner.Result::doors);
    }

    public static Requirement lights(int n) {
        return new Requirement("lights", n, RoomScanner.Result::lights);
    }

    /** Interior cells — the room's usable size, not its bounding box. */
    public static Requirement floorSpace(int n) {
        return new Requirement("floor_space", n, RoomScanner.Result::volume);
    }

    /** Counts blocks of any of the given kinds found inside the room. */
    public static Requirement blocks(String id, int n, Block... kinds) {
        List<Block> accepted = List.of(kinds);
        return new Requirement(id, n, result -> result.countBlocks(accepted));
    }
}
