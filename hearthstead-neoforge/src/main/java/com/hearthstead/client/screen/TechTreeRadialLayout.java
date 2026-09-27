package com.hearthstead.client.screen;

import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pure radial layout of the v3 tech tree (no client classes; JUnit-tested).
 * The spec is tools/techtree_radial_preview.py.
 *
 * <ul>
 *   <li>The Banner seal ({@code settlement_charter}) is the centre; the other
 *       Crown charters are wax seals on the top spoke, on the ring boundary
 *       that opens their rank.</li>
 *   <li>Watch, Logistics, Commons and Craft own wedges clockwise from the gap,
 *       sized by node count.</li>
 *   <li>Each rank (tier 1..4) is a band of sub-rings {@link #RG} apart. A node
 *       whose same-rank, same-wedge prerequisite exists sits at least one
 *       sub-ring further out, so every edge runs outward. A sub-ring holds as
 *       many nodes as its arc allows at {@link #S} spacing; pick-one partners
 *       are never split and stay adjacent.</li>
 *   <li>Angles: nodes start at their in-wedge parents' mean angle, then a
 *       1-D relaxation enforces the minimum spacing inside the wedge.</li>
 * </ul>
 * Angles are degrees clockwise from north (up); radius in world units.
 */
public final class TechTreeRadialLayout {
    public static final List<String> SECTORS = List.of("watch", "logistics", "commons", "craft");
    /** Closed circle (owner, 26 Sep); the Crown seals sit on the top spoke. */
    public static final double GAP = 0.0;
    /** Extra clearance each side of the top spoke for the charter seals. */
    public static final double SEAL_PAD = 7.0;
    public static final double PAD = 3.5;
    public static final double S = 70.0;
    public static final double RG = 54.0;
    public static final double R0 = 118.0;
    public static final double MED = 11.0;
    public static final double CENTRE = 30.0;
    /**
     * Founding trades (tech tree Option 2): nodes flagged {@code founding}
     * sit on this inner ring round the Banner, each inside its home wedge;
     * the Hamlet sub-rings then start one ring spacing further out.
     */
    public static final double FOUNDING_R = 100.0;

    public record Polar(double r, double angle) {
        public double x(double rotation) {
            return Math.sin(Math.toRadians(angle + rotation)) * r;
        }

        public double y(double rotation) {
            return -Math.cos(Math.toRadians(angle + rotation)) * r;
        }
    }

    public record Sector(String branch, double a0, double a1) {
        public double mid() {
            return (a0 + a1) / 2.0;
        }
    }

    private final Map<String, Polar> pos;
    private final Map<String, Sector> sectors;
    private final double[] rankStart; // index 1..5 (5 = outer edge of Castle)
    private double foundingRing;

    private TechTreeRadialLayout(Map<String, Polar> pos, Map<String, Sector> sectors, double[] rankStart) {
        this.pos = Map.copyOf(pos);
        this.sectors = Map.copyOf(sectors);
        this.rankStart = rankStart;
    }

    public Polar pos(String id) {
        return pos.get(id);
    }

    public Map<String, Polar> positions() {
        return pos;
    }

    public Sector sector(String branch) {
        return sectors.get(branch);
    }

    /** First sub-ring radius of tier {@code t} (1..4); t = 5 is the outer edge. */
    public double rankStart(int t) {
        return rankStart[Math.max(1, Math.min(5, t))];
    }

    /** The dotted boundary ring that opens rank {@code t} (2..5). */
    public double rankRing(int t) {
        return rankStart(t) - RG / 2.0;
    }

    public double outer() {
        return rankStart[5];
    }

    /** Radius of the founding-trades ring, or 0 when no node is flagged founding. */
    public double foundingRing() {
        return foundingRing;
    }

    /** Angle (degrees) of the middle of the widest empty arc of the founding ring: its label goes there. */
    public double foundingLabelAngle() {
        List<Double> angles = new ArrayList<>();
        for (Map.Entry<String, Polar> e : pos.entrySet()) {
            if (foundingRing > 0 && Math.abs(e.getValue().r() - foundingRing) < 1e-6) {
                angles.add(e.getValue().angle());
            }
        }
        if (angles.isEmpty()) {
            return 0.0;
        }
        java.util.Collections.sort(angles);
        double best = -1;
        double mid = 0;
        for (int i = 0; i < angles.size(); i++) {
            double a = angles.get(i);
            double b = i + 1 < angles.size() ? angles.get(i + 1) : angles.get(0) + 360.0;
            if (b - a > best) {
                best = b - a;
                mid = (a + b) / 2.0;
            }
        }
        return mid % 360.0;
    }

    /**
     * Connector from prerequisite to node as polar points: a spoke out of the
     * parent, an arc along the mid radius, a spoke into the child. Never leaves
     * the wedge and never passes the centre.
     */
    public List<Polar> edge(String from, String to) {
        Polar p = pos.get(from);
        Polar c = pos.get(to);
        List<Polar> out = new ArrayList<>();
        if (p == null || c == null) {
            return out;
        }
        double rm = c.r() > p.r() ? p.r() + (c.r() - p.r()) * 0.5 : p.r() + RG / 2.0;
        out.add(new Polar(p.r() + MED + 1.0, p.angle()));
        out.add(new Polar(rm, p.angle()));
        int steps = Math.max(1, (int) (Math.abs(c.angle() - p.angle()) / 1.5));
        for (int k = 1; k <= steps; k++) {
            out.add(new Polar(rm, p.angle() + (c.angle() - p.angle()) * k / steps));
        }
        out.add(new Polar(c.r() - MED - 2.0, c.angle()));
        return out;
    }

    public static TechTreeRadialLayout compute(TechTreeData data) {
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (TechNodeDef n : data.nodes()) {
            if (SECTORS.contains(n.branch())) {
                counts.merge(n.branch(), 1, Integer::sum);
                total++;
            }
        }
        Map<String, Sector> sectors = new LinkedHashMap<>();
        double a = GAP / 2.0;
        for (String b : SECTORS) {
            double w = (360.0 - GAP) * counts.getOrDefault(b, 0) / Math.max(1, total);
            sectors.put(b, new Sector(b, a, a + w));
            a += w;
        }
        Map<String, TechNodeDef> by = new LinkedHashMap<>();
        for (TechNodeDef n : data.nodes()) {
            by.put(n.id(), n);
        }
        List<TechNodeDef> order = new ArrayList<>(data.nodes());
        Map<String, Integer> depth = new HashMap<>();
        for (TechNodeDef n : order) {
            depth(n, by, depth);
        }
        Map<String, Polar> pos = new HashMap<>();
        double[] rankStart = new double[6];
        boolean founding = false;
        for (TechNodeDef n : order) {
            founding |= n.founding() && SECTORS.contains(n.branch());
        }
        double r = founding ? FOUNDING_R + RG : R0;
        for (int t = 1; t <= 4; t++) {
            rankStart[t] = r;
            int subsNeeded = 1;
            Map<String, List<List<TechNodeDef>>> plan = new LinkedHashMap<>();
            for (String b : SECTORS) {
                Sector sec = sectors.get(b);
                List<TechNodeDef> group = new ArrayList<>();
                for (TechNodeDef n : order) {
                    if (n.branch().equals(b) && n.tier() == t && !n.founding()) {
                        group.add(n);
                    }
                }
                List<List<TechNodeDef>> rings = new ArrayList<>();
                TreeSet<Integer> levels = new TreeSet<>();
                for (TechNodeDef n : group) {
                    levels.add(depth.get(n.id()));
                }
                for (int level : levels) {
                    List<TechNodeDef> atLevel = new ArrayList<>();
                    for (TechNodeDef n : group) {
                        if (depth.get(n.id()) == level) {
                            atLevel.add(n);
                        }
                    }
                    final double mid = sec.mid();
                    atLevel.sort((x, y) -> {
                        int c = Double.compare(parentAngle(x, b, by, pos, mid), parentAngle(y, b, by, pos, mid));
                        return c != 0 ? c : Integer.compare(order.indexOf(x), order.indexOf(y));
                    });
                    List<List<TechNodeDef>> units = units(atLevel, by);
                    int u = 0;
                    while (u < units.size()) {
                        double rr = r + rings.size() * RG;
                        int cap = Math.max(1, (int) (rr * Math.toRadians(usable(sec)) / S));
                        List<TechNodeDef> ring = new ArrayList<>();
                        while (u < units.size() && (ring.isEmpty() || ring.size() + units.get(u).size() <= cap)) {
                            ring.addAll(units.get(u));
                            u++;
                        }
                        rings.add(ring);
                    }
                }
                plan.put(b, rings);
                subsNeeded = Math.max(subsNeeded, rings.size());
            }
            for (Map.Entry<String, List<List<TechNodeDef>>> e : plan.entrySet()) {
                Sector sec = sectors.get(e.getKey());
                List<List<TechNodeDef>> rings = e.getValue();
                for (int sub = 0; sub < rings.size(); sub++) {
                    place(rings.get(sub), r + sub * RG, sec, by, pos);
                }
            }
            r += subsNeeded * RG;
        }
        rankStart[5] = r;
        if (founding) {
            for (String b : SECTORS) {
                List<TechNodeDef> ring = new ArrayList<>();
                for (TechNodeDef n : order) {
                    if (n.founding() && n.branch().equals(b)) {
                        ring.add(n);
                    }
                }
                place(ring, FOUNDING_R, sectors.get(b), by, pos);
            }
        }
        pos.put("settlement_charter", new Polar(0.0, 0.0));
        String[] seals = {null, null, "first_raid_aftermath", "town_charter", "castle_charter", "kingdom_crown"};
        for (int t = 2; t <= 5; t++) {
            if (by.containsKey(seals[t])) {
                pos.put(seals[t], new Polar(rankStart[t] - RG / 2.0, 0.0));
            }
        }
        // Anything not placed (unknown branch) goes to the top gap's outer edge.
        for (TechNodeDef n : order) {
            pos.putIfAbsent(n.id(), new Polar(r + RG, 0.0));
        }
        TechTreeRadialLayout layout = new TechTreeRadialLayout(pos, sectors, rankStart);
        layout.foundingRing = founding ? FOUNDING_R : 0.0;
        return layout;
    }

    private static void place(List<TechNodeDef> ring, double rr, Sector sec, Map<String, TechNodeDef> by,
                              Map<String, Polar> pos) {
        int k = ring.size();
        if (k == 0) {
            return;
        }
        double lo = sec.a0() + PAD + (touchesTop(sec.a0()) ? SEAL_PAD : 0.0);
        double hi = sec.a1() - PAD - (touchesTop(sec.a1()) ? SEAL_PAD : 0.0);
        double step = Math.toDegrees(S / rr);
        double[] ang = new double[k];
        for (int i = 0; i < k; i++) {
            double even = lo + (hi - lo) * (i + 0.5) / k;
            ang[i] = Math.min(hi, Math.max(lo, parentAngle(ring.get(i), sec.branch(), by, pos, even)));
        }
        // Pick-one partners (adjacent in the unit order) share one target angle.
        for (int i = 1; i < k; i++) {
            if (ring.get(i).excludes().contains(ring.get(i - 1).id())) {
                double mean = (ang[i] + ang[i - 1]) / 2.0;
                ang[i - 1] = mean;
                ang[i] = mean;
            }
        }
        // Keep the unit order (partners adjacent): make the wanted angles monotone.
        for (int i = 1; i < k; i++) {
            ang[i] = Math.max(ang[i], ang[i - 1]);
        }
        for (int it = 0; it < 60; it++) {
            for (int q = 1; q < k; q++) {
                if (ang[q] < ang[q - 1] + step) {
                    double mid = (ang[q] + ang[q - 1]) / 2.0;
                    ang[q - 1] = mid - step / 2.0;
                    ang[q] = mid + step / 2.0;
                }
            }
            double shiftLo = lo - ang[0];
            double shiftHi = hi - ang[k - 1];
            double shift = shiftLo > 0 ? shiftLo : shiftHi < 0 ? shiftHi : 0.0;
            for (int q = 0; q < k; q++) {
                ang[q] += shift;
            }
        }
        for (int q = 0; q < k; q++) {
            pos.put(ring.get(q).id(), new Polar(rr, ang[q]));
        }
    }

    private static boolean touchesTop(double angle) {
        return Math.abs(angle) < 1e-6 || Math.abs(angle - 360.0) < 1e-6;
    }

    private static double usable(Sector sec) {
        return sec.a1() - sec.a0() - 2 * PAD
            - (touchesTop(sec.a0()) ? SEAL_PAD : 0.0) - (touchesTop(sec.a1()) ? SEAL_PAD : 0.0);
    }

    /**
     * Rank band edges: {@code bandEdge(0)} is the centre medallion's rim,
     * {@code bandEdge(t)} the boundary between tier t and t+1 (t = 1..4).
     */
    public double bandEdge(int t) {
        return t <= 0 ? CENTRE + 16.0 : rankStart(t + 1) - RG / 2.0;
    }

    /** Groups a level into units: a node followed by its pick-one partners on the same level. */
    private static List<List<TechNodeDef>> units(List<TechNodeDef> level, Map<String, TechNodeDef> by) {
        List<List<TechNodeDef>> units = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TechNodeDef n : level) {
            if (!seen.add(n.id())) {
                continue;
            }
            List<TechNodeDef> unit = new ArrayList<>();
            unit.add(n);
            for (String ex : n.excludes()) {
                TechNodeDef mate = by.get(ex);
                if (mate != null && level.contains(mate) && seen.add(ex)) {
                    unit.add(mate);
                }
            }
            units.add(unit);
        }
        return units;
    }

    private static double parentAngle(TechNodeDef n, String band, Map<String, TechNodeDef> by,
                                      Map<String, Polar> pos, double fallback) {
        double sum = 0;
        int count = 0;
        for (String r : n.requires()) {
            TechNodeDef p = by.get(r);
            Polar at = pos.get(r);
            if (p != null && at != null && p.branch().equals(band)) {
                sum += at.angle();
                count++;
            }
        }
        return count == 0 ? fallback : sum / count;
    }

    private static int depth(TechNodeDef n, Map<String, TechNodeDef> by, Map<String, Integer> memo) {
        Integer known = memo.get(n.id());
        if (known != null) {
            return known;
        }
        memo.put(n.id(), 0);
        int d = 0;
        for (String r : n.requires()) {
            TechNodeDef p = by.get(r);
            // A founding parent sits on the inner ring: its children still
            // start on the first Hamlet sub-ring.
            if (p != null && p.branch().equals(n.branch()) && p.tier() == n.tier() && !p.founding()) {
                d = Math.max(d, depth(p, by, memo) + 1);
            }
        }
        memo.put(n.id(), d);
        return d;
    }
}
