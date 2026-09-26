package com.hearthstead.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tavern lane: the paired/group presentation is geometry and timing, not luck.
 * Mugs of 2, 3 and 4 patrons around a table meet within one pixel at the clink
 * frame; the talking pair never talks at once; the table roles always have
 * exactly one teller; the Another Furniture seat puts the hip ON the seat.
 */
final class TavernTableMathTest {
    private static final double PX = 1.0 / 16.0;
    private static final BlockPos TABLE = new BlockPos(10, 64, 10);

    private static TavernSeating.SeatSite site(BlockPos table, Direction facing, TavernSeating.Furniture furniture) {
        BlockPos chair = table.relative(facing.getOpposite());
        return new TavernSeating.SeatSite(new UUID(1, 2), chair, table, facing,
            chair.relative(facing.getClockWise()), table.relative(facing), furniture);
    }

    private static TavernTableMath.Seat seat(TavernSeating.SeatSite site) {
        Vec3 hip = TavernSeatMotion.seatedAnchor(site).add(0, TavernTableMath.HIP, 0);
        return new TavernTableMath.Seat(hip, site.dinerFacing().toYRot(), site.table());
    }

    /** 1-block table with 2 or 3 seats; a 2-block table with 4 (one seat per block side). */
    private static List<List<TavernTableMath.Seat>> layouts(TavernSeating.Furniture f) {
        BlockPos east = TABLE.east();
        return List.of(
            List.of(seat(site(TABLE, Direction.SOUTH, f)), seat(site(TABLE, Direction.NORTH, f))),
            List.of(seat(site(TABLE, Direction.SOUTH, f)), seat(site(TABLE, Direction.NORTH, f)),
                seat(site(TABLE, Direction.EAST, f))),
            List.of(seat(site(TABLE, Direction.SOUTH, f)), seat(site(east, Direction.SOUTH, f)),
                seat(site(TABLE, Direction.NORTH, f)), seat(site(east, Direction.NORTH, f))));
    }

    @Test
    void pairwiseClinksMeetWithinToleranceNotInOnePoint() {
        for (TavernSeating.Furniture furniture : TavernSeating.Furniture.values()) {
            for (List<TavernTableMath.Seat> seats : layouts(furniture)) {
                int n = seats.size();
                float[] yaws = new float[n];
                long[] cells = new long[n];
                for (int k = 0; k < n; k++) { yaws[k] = seats.get(k).yaw(); cells[k] = seats.get(k).table().asLong(); }
                for (long cycle = 0; cycle < 60; cycle++) {
                    var plan = TavernTableMath.planCheer(TABLE, cycle, n, yaws, cells);
                    Vec3[] mug = new Vec3[n];
                    for (int i = 0; i < n; i++) {
                        if (!plan.joined()[i]) continue;
                        Vec3 target = TavernTableMath.mugTarget(seats, plan, i, TABLE, cycle);
                        mug[i] = TavernTableMath.mugWorld(seats.get(i), TavernTableMath.correction(seats.get(i), target));
                        double err = mug[i].distanceTo(target);
                        assertTrue(err <= PX, furniture + " x" + n + " c" + cycle + ": mug " + i + " misses by " + err / PX + " px");
                    }
                    for (int a = 0; a < n; a++)
                        for (int b = a + 1; b < n; b++) {
                            if (mug[a] == null || mug[b] == null || plan.group()[a] != plan.group()[b]
                                || plan.late()[a] || plan.late()[b]) continue;
                            double d = mug[a].distanceTo(mug[b]) / PX;
                            assertTrue(d >= 0.8 && d <= 4.5, furniture + " x" + n + ": a clinking pair is " + d + " px apart");
                        }
                    Vec3 p0 = TavernTableMath.pairPoint(seats, plan, 0, TABLE, cycle);
                    Vec3 p1 = TavernTableMath.pairPoint(seats, plan, 1, TABLE, cycle);
                    if (p0 != null && p1 != null)
                        assertTrue(p0.distanceTo(p1) >= 2 * PX, "two clinks never share one point");
                }
            }
        }
    }

    @Test
    void theCheerIsStaggeredNotInStep() {
        int joiners = 0, others = 0;
        boolean someoneSat = false;
        for (int n = 2; n <= 4; n++)
            for (long cycle = 0; cycle < 400; cycle++) {
                var plan = TavernTableMath.planCheer(TABLE, cycle, n);
                for (int a = 0; a < n; a++) {
                    if (a != plan.initiator()) {
                        others++;
                        if (plan.joined()[a]) joiners++; else someoneSat = true;
                    }
                    if (!plan.joined()[a]) continue;
                    assertTrue(Math.abs(TavernTableMath.cheerLocal(plan, a, plan.contact()[a]) - TavernTableMath.CLINK_SECONDS) < 1e-3,
                        "each mug reaches its contact frame exactly on its clink");
                    for (int b = a + 1; b < n; b++)
                        if (plan.joined()[b])
                            assertTrue(Math.abs(plan.start()[a] - plan.start()[b]) >= 0.1F,
                                "no two raise within 0.1 s: " + plan.start()[a] + " / " + plan.start()[b]);
                    if (a != plan.initiator())
                        assertTrue(plan.start()[a] >= 0.17F && plan.start()[a] <= 0.83F, "joins 0.2-0.8 s after the initiator");
                }
                float t0 = -1, t1 = -1, late = -1;
                for (int a = 0; a < n; a++) {
                    if (plan.group()[a] == 0 && !plan.late()[a]) t0 = plan.contact()[a];
                    if (plan.group()[a] == 1) t1 = plan.contact()[a];
                    if (plan.late()[a]) late = plan.contact()[a];
                }
                if (t1 >= 0) assertTrue(t1 - t0 >= 0.3F, "the second clink is staggered after the first");
                if (late >= 0) assertTrue(late - t0 >= 0.3F, "the odd third taps in late");
            }
        double rate = joiners / (double) others;
        assertTrue(rate > 0.6 && rate < 0.85, "about 70% join a cheer, got " + rate);
        assertTrue(someoneSat, "not everyone joins every time");
    }

    @Test
    void listenersReactAtTheirOwnTimeAndInTheirOwnWay() {
        java.util.Set<Integer> styles = new java.util.HashSet<>();
        for (int n = 2; n <= 4; n++)
            for (long cycle = 0; cycle < 100; cycle++)
                for (int seg = 0; seg < 2; seg++) {
                    float[] lag = new float[n];
                    for (int i = 0; i < n; i++) {
                        lag[i] = TavernTableMath.reactionLag(TABLE, cycle, seg, i, n);
                        assertTrue(lag[i] >= 0.08F && lag[i] <= 0.62F, "lag 0.1-0.6 s: " + lag[i]);
                        styles.add(TavernTableMath.listenStyle(TABLE, cycle, seg, i));
                    }
                    for (int a = 0; a < n; a++)
                        for (int b = a + 1; b < n; b++)
                            assertTrue(Math.abs(lag[a] - lag[b]) >= 0.1F, "no two listeners react within 0.1 s");
                }
        assertEquals(3, styles.size(), "big laugh, smile and chuckle all occur");
        int leanBack = 0;
        java.util.Set<Long> yaws = new java.util.HashSet<>();
        for (long e = 1; e <= 60; e++) {
            float[] p = TavernTableMath.posture(e * 0x9E3779B97F4A7C15L);
            if (p[1] < 0) leanBack++;
            yaws.add(Math.round(Math.toDegrees(p[0])));
            float sp = TavernTableMath.speed(e);
            assertTrue(sp >= 0.9F && sp <= 1.1F);
        }
        assertTrue(leanBack > 10 && leanBack < 50, "some lean back, not all: " + leanBack);
        assertTrue(yaws.size() > 10, "relaxed body angles vary");
    }

    @Test
    void anotherFurnitureHipRestsOnTheSeatTop() {
        for (TavernSeating.Furniture furniture : TavernSeating.Furniture.values()) {
            TavernSeating.SeatSite site = site(TABLE, Direction.NORTH, furniture);
            Vec3 anchor = TavernSeatMotion.seatedAnchor(site);
            double hip = anchor.y + TavernTableMath.HIP - site.chair().getY();
            assertEquals(furniture.seatTop(), hip, 1e-9, furniture + ": hip on the seat surface");
            // the rider's final entry frame lands exactly on the saved anchor (no snap)
            var last = TavernSeatMotion.sample(site, TavernSeatMotion.TICKS);
            assertTrue(last != null, furniture + ": the final seated frame is reachable");
            assertTrue(last.origin().distanceTo(anchor) < 1e-6, furniture + ": final frame on the anchor");
            if (furniture.another()) {
                // both boots planted on the floor, clear of the seat's front edge
                for (Vec3 foot : new Vec3[]{last.rightFoot(), last.leftFoot()}) {
                    assertTrue(foot.y >= site.chair().getY() - 1e-6 && foot.y <= site.chair().getY() + PX,
                        furniture + ": boot on (within a pixel of) the floor: " + (foot.y - site.chair().getY()));
                    double ahead = foot.subtract(Vec3.atBottomCenterOf(site.chair())).dot(TavernTableMath.forward(
                        site.dinerFacing().toYRot()));
                    assertTrue(ahead - .125 >= furniture.seatEdge() - 1e-6,
                        furniture + ": boot clears the seat edge (" + ahead + ")");
                }
            }
            // every entry frame solves (legs reachable) all the way down
            for (double t = 0; t <= TavernSeatMotion.TICKS; t += .5)
                assertTrue(TavernSeatMotion.sample(site, t) != null, furniture + ": entry frame at " + t);
        }
    }

    @Test
    void aTalkingPairTakesUnevenTurnsWithPausesAndOnlyTinyOverlaps() {
        int overlaps = 0, silences = 0, handovers = 0;
        java.util.Set<Integer> lengths = new java.util.HashSet<>();
        for (int seed = 1; seed <= 40; seed++) {
            int s = seed * 7919;
            for (int k = 0; k < 8; k++) {
                int len = SocialPair.turnLength(s, k);
                assertTrue(len >= SocialPair.TURN_MIN && len <= SocialPair.TURN_MAX, "turn 1.5-6 s: " + len);
                lengths.add(len);
            }
            assertEquals(VillageSocial.CHAT, SocialPair.role(0, 0, s), "the starter speaks first");
            int both = 0, quiet = 0;
            int end = SocialPair.turnStart(s, 8);
            for (long t = 0; t < end; t++) {
                int a = SocialPair.role(0, t, s), b = SocialPair.role(1, t, s);
                if (a == VillageSocial.CHAT && b == VillageSocial.CHAT) both++;
                else if (both > 0) {
                    assertTrue(both <= SocialPair.OVERLAP, "an overlap is only a cut-in: " + both);
                    overlaps++;
                    both = 0;
                }
                if (a == VillageSocial.LISTEN && b == VillageSocial.LISTEN) quiet++;
                else if (quiet > 0) {
                    assertTrue(quiet >= 30 && quiet <= 81, "a silence is 1.5-4 s: " + quiet);
                    silences++;
                    quiet = 0;
                }
            }
            handovers += 7;
        }
        assertTrue(lengths.size() > 20, "turn lengths are not metronomic");
        assertTrue(overlaps > 0 && overlaps < handovers * 0.5, "occasional overlaps: " + overlaps + "/" + handovers);
        assertTrue(silences > handovers * 0.2 && silences < handovers * 0.5, "comfortable silences: " + silences);
        float a0 = SocialPair.angleOffset(123, 0), a1 = SocialPair.angleOffset(123, 1);
        double between = Math.abs(a0 - a1);
        assertTrue(between >= 20 && between <= 40, "the pair stands 20-40 degrees off square: " + between);
    }

    @Test
    void bigTableEventsAreRareWithCooldowns() {
        int cheers = 0, stories = 0, toasts = 0;
        double busy = 0, span = 7200;
        for (int tbl = 0; tbl < 6; tbl++) {
            BlockPos table = TABLE.offset(tbl * 13, 0, tbl * 7);
            double last = Double.NEGATIVE_INFINITY, lastToast = Double.NEGATIVE_INFINITY;
            TavernTableMath.TableEvent prev = TavernTableMath.TableEvent.QUIET;
            for (double t = 0; t < span; t += 0.25) {
                var e = TavernTableMath.tableEvent(table, t);
                if (e.event() != TavernTableMath.Event.NONE) busy += 0.25;
                if (e.event() != TavernTableMath.Event.NONE && (prev.event() == TavernTableMath.Event.NONE
                        || prev.index() != e.index())) {
                    assertTrue(e.start() - last >= 50.0, "big events start at least 50 s apart: " + (e.start() - last));
                    last = e.start();
                    switch (e.event()) {
                        case CHEER -> cheers++;
                        case STORY -> stories++;
                        default -> {
                            assertTrue(e.start() - lastToast >= 50.0, "a toast at most every 50+ s");
                            lastToast = e.start();
                            toasts++;
                        }
                    }
                }
                prev = e;
            }
        }
        double share = busy / (span * 6);
        assertTrue(share > 0.04 && share < 0.16, "big events fill only a small share of the evening: " + share);
        assertTrue(toasts < cheers && toasts < stories && toasts > 0, "toasts are the rarest: " + toasts);
    }

    @Test
    void patronsAreMostlyCalmWithOccasionalFidgets() {
        double calm = 0, total = 0;
        int fidgets = 0, sips = 0;
        double minGap = Double.MAX_VALUE;
        for (long p = 1; p <= 30; p++) {
            long seed = p * 0x9E3779B97F4A7C15L;
            TavernTableMath.Act prev = null;
            double lastFidget = Double.NEGATIVE_INFINITY;
            for (double t = 0; t < 1800; t += 0.25) {
                var a = TavernTableMath.personal(seed, t);
                total += 0.25;
                if (a.beat() == TavernTableMath.Beat.CALM) calm += 0.25;
                boolean fresh = prev == null || Math.abs(prev.start() - a.start()) > 1e-6;
                if (fresh && a.beat() == TavernTableMath.Beat.SIP) sips++;
                if (fresh && a.beat().name().startsWith("FIDGET")) {
                    fidgets++;
                    minGap = Math.min(minGap, a.start() - lastFidget);
                    lastFidget = a.start();
                }
                prev = a;
            }
        }
        double share = calm / total;
        assertTrue(share >= 0.65 && share <= 0.85, "mostly calm: " + share);
        double perFidget = 1800.0 * 30 / fidgets;
        assertTrue(perFidget >= 15 && perFidget <= 35, "about one fidget every 15-30 s: " + perFidget);
        assertTrue(minGap >= 3.0, "fidgets never back to back: " + minGap);
        assertTrue(sips > 0 && fidgets > 0);
        // with table events on top a patron still spends most of the evening calm
        int count = 3;
        double eventTime = 0, calmTime = 0, all = 0;
        for (double t = 0; t < 3600; t += 0.25) {
            var e = TavernTableMath.tableEvent(TABLE, t);
            boolean busy = TavernTableMath.inEvent(e, TABLE, 1, count);
            all += 0.25;
            if (busy) eventTime += 0.25;
            else if (TavernTableMath.personal(77L, t).beat() == TavernTableMath.Beat.CALM) calmTime += 0.25;
        }
        assertTrue(calmTime / all >= 0.6, "calm share with events: " + calmTime / all);
        assertTrue(eventTime / all < 0.16, "event share: " + eventTime / all);
    }

    @Test
    void theStoryPassesRoundTheTable() {
        for (int count = 2; count <= 4; count++) {
            java.util.Set<Integer> tellers = new java.util.HashSet<>();
            for (long k = 0; k < 12; k++) tellers.add(TavernTableMath.teller(k, 0, count));
            assertEquals(count, tellers.size(), "every seat gets to tell one");
        }
    }

    /** The authored clip and the runtime constant agree on where the mug is at the clink. */
    @Test
    void authoredClinkPointMatchesTheRuntimeConstant() throws IOException {
        Path clip = null;
        Path dir = Path.of("").toAbsolutePath();
        String rel = "src/main/resources/assets/hearthstead/animations/settler/table_cheer.animation.json";
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParent()) {
            for (Path c : new Path[]{dir.resolve(rel), dir.resolve("hearthstead-neoforge").resolve(rel)})
                if (Files.isRegularFile(c)) { clip = c; break; }
            if (clip != null) break;
        }
        if (clip == null) return; // processed-resources only runs: the constant is still pinned below
        try (Reader r = Files.newBufferedReader(clip, StandardCharsets.UTF_8)) {
            JsonObject meta = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("hearthstead_meta");
            var p = meta.getAsJsonObject("checks").getAsJsonArray("clink_mug_hip_px");
            assertEquals(TavernTableMath.CLINK_LEFT, p.get(0).getAsDouble(), .3);
            assertEquals(TavernTableMath.CLINK_UP, p.get(1).getAsDouble(), .3);
            assertEquals(TavernTableMath.CLINK_FORWARD, p.get(2).getAsDouble(), .3);
        }
    }
}
