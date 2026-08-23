package com.hearthstead.building;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The kinds of building a plaque can declare, and what each one demands of
 * the room around it.
 *
 * <p>A plaque is the surveyor: it is bought from the architect already
 * dedicated to one of these types, and when placed it scans the room it hangs
 * in and measures it against that type's requirements. Nothing else declares
 * what a building is.
 *
 * <p>Requirement costs are sized against vanilla effort. A house asks for a
 * bed, a door and a lantern — an evening's work. A warehouse asks for real
 * storage and floor space. A lumber camp asks for the tools of the trade. The
 * intent is that each rung feels earned without becoming a chore.
 */
public enum BuildingType {

    HOUSE("house", 4, 1,
        Requirement.beds(1),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(9)),

    LODGING("lodging", 8, 0,
        Requirement.beds(4),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(24)),

    WAREHOUSE("warehouse", 0, 2,
        Requirement.blocks("storage", 4, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(25)),

    LUMBER_CAMP("lumber_camp", 0, 2,
        Requirement.blocks("workbench", 1, Blocks.CRAFTING_TABLE),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    FARMHOUSE("farmhouse", 0, 2,
        Requirement.blocks("composter", 1, Blocks.COMPOSTER),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    ARCHITECTS_STUDY("architects_study", 0, 1,
        Requirement.blocks("lectern", 1, Blocks.LECTERN),
        Requirement.blocks("bookshelf", 2, Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(16));

    private final String id;
    private final int residentCapacity;
    private final int workerCapacity;
    private final List<Requirement> requirements;

    BuildingType(String id, int residentCapacity, int workerCapacity,
                 Requirement... requirements) {
        this.id = id;
        this.residentCapacity = residentCapacity;
        this.workerCapacity = workerCapacity;
        this.requirements = List.of(requirements);
    }

    public String id() {
        return id;
    }

    /**
     * Beds still decide how many people a dwelling sleeps; this is the ceiling
     * the type imposes on top of that. Work buildings house nobody.
     */
    public int residentCapacity() {
        return residentCapacity;
    }

    public int workerCapacity() {
        return workerCapacity;
    }

    public List<Requirement> requirements() {
        return requirements;
    }

    public boolean housesResidents() {
        return residentCapacity > 0;
    }

    public boolean employsWorkers() {
        return workerCapacity > 0;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.building." + id);
    }

    public static BuildingType byId(String id) {
        for (BuildingType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return HOUSE;
    }
}
