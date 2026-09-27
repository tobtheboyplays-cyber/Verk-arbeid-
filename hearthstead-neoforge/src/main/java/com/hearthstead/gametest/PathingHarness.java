package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.SettlerDoorGoal;
import com.hearthstead.entity.path.BedApproach;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.entity.path.StandCells;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.work.ContainerApproach;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives one settler through a list of destinations the way the production
 * work, rest and container goals do, and fails with a precise trace when a
 * leg exceeds its tick budget.
 *
 * <p>Only {@link SettlerDoorGoal} is kept on the settler, so no unrelated
 * goal can move it; every movement request comes from the same production
 * entry points the real goals call ({@link BedApproach},
 * {@link ContainerApproach}, {@link StandCells}, plain navigation).
 */
final class PathingHarness {
    /** How often a leg re-issues its request while not arrived (work goals use 20-60). */
    static final int DRIVE_INTERVAL = 40;

    private PathingHarness() {
    }

    abstract static class Leg {
        final String name;
        final int budget;

        Leg(String name, int budget) {
            this.name = name;
            this.budget = budget;
        }

        abstract boolean arrived(ServerLevel level, SettlerEntity settler);

        abstract void drive(ServerLevel level, SettlerEntity settler);

        String detail(ServerLevel level, SettlerEntity settler) {
            return "";
        }
    }

    /** Night rest: plan a bounded building route to a legal bed contact cell. */
    static Leg bed(String name, BlockPos bed, int budget) {
        return new Leg(name, budget) {
            @Override
            boolean arrived(ServerLevel level, SettlerEntity settler) {
                return BedApproach.canContact(level, settler, bed);
            }

            @Override
            void drive(ServerLevel level, SettlerEntity settler) {
                var contacts = BedApproach.contactCells(level, settler, bed);
                if (contacts.isEmpty()) return;
                Path installed = settler.getNavigation().getPath();
                if (installed != null && !installed.isDone() && installed.canReach()
                    && contacts.contains(installed.getTarget())) return;
                Path route = new RoadNavigation(settler, level).createBuildingPath(contacts, 0);
                if (route != null && route.canReach() && contacts.contains(route.getTarget())) {
                    settler.getNavigation().moveTo(route, 0.9D);
                }
            }

            @Override
            String detail(ServerLevel level, SettlerEntity settler) {
                return "contacts=" + BedApproach.contactCells(level, settler, bed);
            }
        };
    }

    /** Container work: the exact production contact routine. */
    static Leg container(String name, BlockPos chest, int budget) {
        return new Leg(name, budget) {
            @Override
            boolean arrived(ServerLevel level, SettlerEntity settler) {
                return ContainerApproach.inspect(level, settler, chest).canInteract();
            }

            @Override
            void drive(ServerLevel level, SettlerEntity settler) {
                ContainerApproach.moveToContact(level, settler, chest, 1.0D);
            }
        };
    }

    /** Workstation: stand in reach of a non-container block (bench, table, lectern). */
    static Leg station(String name, BlockPos block, int budget) {
        return new Leg(name, budget) {
            @Override
            boolean arrived(ServerLevel level, SettlerEntity settler) {
                return StandCells.inReach(level, settler, block);
            }

            @Override
            void drive(ServerLevel level, SettlerEntity settler) {
                StandCells.moveNextTo(level, settler, block, 1.0D);
            }

            @Override
            String detail(ServerLevel level, SettlerEntity settler) {
                return "cells=" + StandCells.standCells(level, settler, block);
            }
        };
    }

    /** Plain destination, as a schedule post (vanilla accuracy of one block). */
    static Leg point(String name, BlockPos where, int budget) {
        return new Leg(name, budget) {
            @Override
            boolean arrived(ServerLevel level, SettlerEntity settler) {
                Vec3 p = settler.position();
                double dx = p.x - (where.getX() + 0.5D);
                double dz = p.z - (where.getZ() + 0.5D);
                return dx * dx + dz * dz <= 2.25D && Math.abs(p.y - where.getY()) < 1.0D;
            }

            @Override
            void drive(ServerLevel level, SettlerEntity settler) {
                settler.getNavigation().moveTo(where.getX() + 0.5D, where.getY(),
                    where.getZ() + 0.5D, 1.0D);
            }
        };
    }

    static SettlerEntity walker(GameTestHelper helper, BlockPos start) {
        SettlerEntity walker = ModEntities.SETTLER.get().create(helper.getLevel());
        walker.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, 0, 0);
        for (var goal : List.copyOf(walker.goalSelector.getAvailableGoals())) {
            if (!(goal.getGoal() instanceof SettlerDoorGoal)) {
                walker.goalSelector.removeGoal(goal.getGoal());
            }
        }
        walker.setPersistenceRequired();
        helper.getLevel().addFreshEntity(walker);
        return walker;
    }

    /**
     * Runs every leg in order. Each leg must finish within its own budget.
     * After the last leg, every wooden door, gate and trapdoor in the test
     * volume that started closed must be closed again.
     */
    static void run(GameTestHelper helper, String scenario, SettlerEntity settler,
                    List<Leg> legs, BlockPos volumeMin, BlockPos volumeMax) {
        ServerLevel level = helper.getLevel();
        Map<BlockPos, Boolean> openables = openableStates(level, volumeMin, volumeMax);
        BlockPos origin = volumeMin;
        int[] index = {0};
        long[] legStart = {-1};
        long[] lastDrive = {-1000};
        long[] finishedAt = {-1};
        List<String> timings = new ArrayList<>();
        StringBuilder trace = new StringBuilder();
        float startHealth = settler.getHealth();
        helper.onEachTick(() -> {
            long now = helper.getTick();
            if (finishedAt[0] >= 0 && (now - finishedAt[0]) % 20 == 0) {
                List<String> open = new ArrayList<>();
                openables.forEach((pos, wasOpen) -> {
                    if (!wasOpen && isOpen(level.getBlockState(pos))) open.add(rel(origin, pos));
                });
                if (!open.isEmpty()) {
                    Hearthstead.LOGGER.info("HSQA_PATHING_DOORS scenario={} t+{} open={} steward={} pos={} goals={}",
                        scenario, now - finishedAt[0], open,
                        com.hearthstead.entity.path.SettlerDoorSteward.tracked(level),
                        settler.position(), running(settler));
                }
            }
            if (now < 3 || finishedAt[0] >= 0) return;
            if (index[0] >= legs.size()) return;
            Leg leg = legs.get(index[0]);
            if (legStart[0] < 0) {
                legStart[0] = now;
                lastDrive[0] = -1000;
                trace.setLength(0);
            }
            long elapsed = now - legStart[0];
            if (leg.arrived(level, settler)) {
                timings.add(leg.name + "=" + elapsed);
                Hearthstead.LOGGER.info("HSQA_PATHING scenario={} leg={} ticks={} budget={}",
                    scenario, leg.name, elapsed, leg.budget);
                settler.getNavigation().stop();
                index[0]++;
                legStart[0] = -1;
                if (index[0] >= legs.size()) finishedAt[0] = now;
                return;
            }
            if (elapsed > leg.budget) {
                Hearthstead.LOGGER.info("HSQA_PATHING scenario={} leg={} FAILED budget={} trace={}",
                    scenario, leg.name, leg.budget, trace);
                String message = scenario + ": leg '" + leg.name + "' not reached within "
                    + leg.budget + " ticks; done=" + timings + " pos="
                    + rel(origin, settler.blockPosition()) + " exact=" + settler.position()
                    + " route=" + settler.routeFailureNote() + " " + leg.detail(level, settler)
                    + " path=" + describe(origin, settler.getNavigation().getPath())
                    + " trace=" + trace;
                Hearthstead.LOGGER.info("HSQA_PATHING_FAIL {}", message);
                helper.fail(message.length() > 900 ? message.substring(0, 900) : message);
                return;
            }
            boolean navIdle = settler.getNavigation().isDone();
            if (now - lastDrive[0] >= DRIVE_INTERVAL || (navIdle && now - lastDrive[0] >= 10)) {
                lastDrive[0] = now;
                leg.drive(level, settler);
            }
            if (elapsed % 20 == 0 && trace.length() < 3000) {
                trace.append(elapsed).append(':').append(rel(origin, settler.blockPosition()))
                    .append(navIdle ? "i" : "m").append(' ');
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(finishedAt[0] >= 0, scenario + ": legs incomplete " + timings);
            // The door goal closes behind the settler; the steward sweeps any
            // passage left open within about three seconds.
            helper.assertTrue(helper.getTick() >= finishedAt[0] + 100,
                scenario + ": settling doors");
            List<String> leftOpen = new ArrayList<>();
            openables.forEach((pos, wasOpen) -> {
                if (!wasOpen && isOpen(level.getBlockState(pos))) {
                    leftOpen.add(rel(origin, pos));
                }
            });
            helper.assertTrue(leftOpen.isEmpty(), scenario + ": left open " + leftOpen
                + " timings=" + timings);
            helper.assertTrue(settler.getHealth() >= startHealth,
                scenario + ": route must not hurt the settler");
            Hearthstead.LOGGER.info("HSQA_PATHING scenario={} PASS timings={}", scenario, timings);
        });
    }

    /**
     * Several settlers each walk one leg at the same time (a crowd leaving
     * through one door). Every one must arrive within the budget, and every
     * passage must be shut again afterwards.
     */
    static void runCrowd(GameTestHelper helper, String scenario, List<SettlerEntity> settlers,
                         java.util.function.Function<SettlerEntity, Leg> legFor,
                         BlockPos volumeMin, BlockPos volumeMax) {
        ServerLevel level = helper.getLevel();
        Map<BlockPos, Boolean> openables = openableStates(level, volumeMin, volumeMax);
        List<Leg> legs = settlers.stream().map(legFor).toList();
        long[] lastDrive = new long[settlers.size()];
        long[] arrivedAt = new long[settlers.size()];
        java.util.Arrays.fill(arrivedAt, -1);
        java.util.Arrays.fill(lastDrive, -1000);
        helper.onEachTick(() -> {
            long now = helper.getTick();
            if (now < 3) return;
            for (int i = 0; i < settlers.size(); i++) {
                if (arrivedAt[i] >= 0) continue;
                SettlerEntity settler = settlers.get(i);
                Leg leg = legs.get(i);
                if (leg.arrived(level, settler)) {
                    arrivedAt[i] = now;
                    settler.getNavigation().stop();
                    Hearthstead.LOGGER.info("HSQA_PATHING scenario={} settler={} leg={} ticks={}",
                        scenario, i, leg.name, now);
                    continue;
                }
                if (now > leg.budget) {
                    StringBuilder all = new StringBuilder();
                    for (int j = 0; j < settlers.size(); j++) {
                        if (arrivedAt[j] >= 0) continue;
                        SettlerEntity other = settlers.get(j);
                        all.append(" [").append(j).append(" at ").append(other.position())
                            .append(" path=").append(describe(volumeMin, other.getNavigation().getPath()))
                            .append(" stalled=").append(other.getNavigation() instanceof
                                com.hearthstead.entity.path.RoadNavigation road ? road.stalledTicks() : -1)
                            .append(" goals=").append(running(other)).append(']');
                    }
                    List<String> doors = new ArrayList<>();
                    openables.forEach((pos, wasOpen) -> doors.add(rel(volumeMin, pos) + "="
                        + isOpen(level.getBlockState(pos))));
                    Hearthstead.LOGGER.info("HSQA_PATHING_CROWD_DEBUG doors={} {}", doors, all);
                    String message = scenario + ": settler " + i + " not out within " + leg.budget
                        + " pos=" + rel(volumeMin, settler.blockPosition()) + " route=" + settler.routeFailureNote();
                    Hearthstead.LOGGER.info("HSQA_PATHING_FAIL {}", message);
                    helper.fail(message);
                    return;
                }
                if (now - lastDrive[i] >= DRIVE_INTERVAL
                    || (settler.getNavigation().isDone() && now - lastDrive[i] >= 10)) {
                    lastDrive[i] = now;
                    leg.drive(level, settler);
                }
            }
        });
        helper.succeedWhen(() -> {
            long last = -1;
            for (long at : arrivedAt) {
                helper.assertTrue(at >= 0, scenario + ": crowd still inside");
                last = Math.max(last, at);
            }
            helper.assertTrue(helper.getTick() >= last + 100, scenario + ": settling doors");
            List<String> leftOpen = new ArrayList<>();
            openables.forEach((pos, wasOpen) -> {
                if (!wasOpen && isOpen(level.getBlockState(pos))) leftOpen.add(rel(volumeMin, pos));
            });
            helper.assertTrue(leftOpen.isEmpty(), scenario + ": left open " + leftOpen);
            Hearthstead.LOGGER.info("HSQA_PATHING scenario={} PASS last={}", scenario, last);
        });
    }

    static Map<BlockPos, Boolean> openableStates(ServerLevel level, BlockPos min, BlockPos max) {
        Map<BlockPos, Boolean> result = new LinkedHashMap<>();
        for (BlockPos rel : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(rel);
            boolean openable = state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
                && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapDoorBlock && state.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS);
            if (openable) result.put(rel.immutable(), isOpen(state));
        }
        return result;
    }

    static boolean isOpen(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
    }

    static String running(SettlerEntity settler) {
        StringBuilder out = new StringBuilder();
        settler.goalSelector.getAvailableGoals().forEach(goal -> {
            if (goal.isRunning()) out.append(goal.getGoal().getClass().getSimpleName()).append(' ');
        });
        return out.toString().trim();
    }

    /** Position relative to the test volume's minimum corner, compact. */
    static String rel(BlockPos origin, BlockPos pos) {
        BlockPos d = pos.subtract(origin);
        return d.getX() + "," + d.getY() + "," + d.getZ();
    }

    static String describe(BlockPos origin, Path path) {
        if (path == null) return "null";
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < path.getNodeCount() && i < 60; i++) {
            nodes.append(i == path.getNextNodeIndex() ? ">" : "")
                .append(rel(origin, path.getNodePos(i)))
                .append(' ');
        }
        return "reach=" + path.canReach() + " target=" + rel(origin, path.getTarget())
            + " nodes=" + nodes;
    }
}
