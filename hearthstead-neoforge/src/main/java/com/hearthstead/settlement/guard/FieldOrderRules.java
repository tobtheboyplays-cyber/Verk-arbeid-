package com.hearthstead.settlement.guard;

import java.util.Optional;

/**
 * Stable wire vocabulary and pure validation for live field orders (R =
 * Knights, G = Archers). Wire ids are frozen: append, never renumber.
 */
public final class FieldOrderRules {
    /** Orders reach only soldiers within this many blocks of the commander. */
    public static final double EARSHOT = 48.0;
    /** Aim range of the command raycast (client) plus slack for latency. */
    public static final double AIM_RANGE = 64.0;
    public static final double SERVER_TARGET_RANGE = 72.0;
    /** Minimum game ticks between two orders from the same player. */
    public static final int RATE_LIMIT_TICKS = 4;
    /** Order lifetime once the raid it belonged to has ended. */
    public static final long AFTER_RAID_GRACE_TICKS = 30L * 20L;
    /** An order given in peacetime (a drill) lapses after five minutes. */
    public static final long PEACETIME_LIFETIME_TICKS = 5L * 60L * 20L;
    /** Settlement search radius beyond its claim. */
    public static final int COMMAND_REACH_BEYOND_CLAIM = 64;

    /** How a role executes the same order (see {@code FieldOrders#allowsTarget}). */
    public enum Style { MELEE, RANGED, SUPPORT }

    /**
     * Commandable roles. Members are matched by {@code Profession.key()} so a
     * role whose profession is not yet in the game simply has no members.
     */
    public enum Group {
        ALL(0, "all", "", Style.MELEE),
        KNIGHTS(1, "knights", "guard", Style.MELEE),
        ARCHERS(2, "archers", "archer", Style.RANGED),
        SPEARMEN(3, "spearmen", "spearman", Style.MELEE),
        LONGSWORDSMEN(4, "longswordsmen", "longswordsman", Style.MELEE),
        MAGES(5, "mages", "rune_mage", Style.RANGED),
        HEALERS(6, "healers", "healer", Style.SUPPORT);

        /** Every single role, in HUD order. */
        public static final Group[] ROLES = {KNIGHTS, SPEARMEN, LONGSWORDSMEN, ARCHERS, MAGES, HEALERS};

        private final int wireId;
        private final String id;
        private final String professionKey;
        private final Style style;

        Group(int wireId, String id, String professionKey, Style style) {
            this.wireId = wireId;
            this.id = id;
            this.professionKey = professionKey;
            this.style = style;
        }

        public int wireId() { return wireId; }
        public String id() { return id; }
        public String professionKey() { return professionKey; }
        public Style style() { return style; }
        public boolean ranged() { return style == Style.RANGED; }
        public boolean support() { return style == Style.SUPPORT; }

        /** True if {@code role} (a single role) is part of this group. */
        public boolean includes(Group role) {
            return this == ALL ? role != ALL : this == role;
        }

        /** The single role whose profession key this is, if any. */
        public static Optional<Group> forProfessionKey(String key) {
            if (key == null || key.isEmpty()) return Optional.empty();
            for (Group role : ROLES) if (role.professionKey.equals(key)) return Optional.of(role);
            return Optional.empty();
        }

        public static Optional<Group> fromWire(int wire) {
            for (Group group : values()) if (group.wireId == wire) return Optional.of(group);
            return Optional.empty();
        }
    }

    public enum Kind {
        /** Knights: shield line here. Archers: take position here, fire at will. */
        LINE(1, "line", true, false),
        /** Knights: charge that enemy. Archers: focus only that enemy. */
        ATTACK(2, "attack", false, true),
        /** Archers: climb the aimed wall/tower. (Knights receive LINE at its base.) */
        HIGH_GROUND(3, "high_ground", true, false),
        /** Escort the commander. */
        FOLLOW(4, "follow", false, false),
        /** Back to posts: the smart default defence resumes. */
        RETURN(5, "return", false, false),
        /** Archers stop shooting (self-defence only) until Fire at will. */
        HOLD_FIRE(6, "hold_fire", false, false),
        FIRE_AT_WILL(7, "fire_at_will", false, false);

        private final int wireId;
        private final String id;
        private final boolean needsPos;
        private final boolean needsEnemy;

        Kind(int wireId, String id, boolean needsPos, boolean needsEnemy) {
            this.wireId = wireId;
            this.id = id;
            this.needsPos = needsPos;
            this.needsEnemy = needsEnemy;
        }

        public int wireId() { return wireId; }
        public String id() { return id; }
        public boolean needsPos() { return needsPos; }
        public boolean needsEnemy() { return needsEnemy; }
        /** Stance toggles change how an order is executed, not where. */
        public boolean stance() { return this == HOLD_FIRE || this == FIRE_AT_WILL; }

        public static Optional<Kind> fromWire(int wire) {
            for (Kind kind : values()) if (kind.wireId == wire) return Optional.of(kind);
            return Optional.empty();
        }
    }

    /** Refusal reasons, each with a player-facing translation key suffix. */
    public enum Refusal {
        NONE("none"),
        UNKNOWN_GROUP("unknown_group"),
        UNKNOWN_KIND("unknown_kind"),
        BAD_FACING("bad_facing"),
        BAD_WIDTH("bad_width"),
        NO_TARGET("no_target"),
        OUT_OF_RANGE("out_of_range"),
        INVALID_ENEMY("invalid_enemy"),
        WRONG_GROUP("wrong_group"),
        TOO_FAST("too_fast"),
        NOT_ALLOWED("not_allowed"),
        NO_SETTLEMENT("no_settlement"),
        NOBODY_HEARD("nobody_heard"),
        /** Server switch [features] guardCommands=false. */
        DISABLED("disabled");

        private final String id;

        Refusal(String id) { this.id = id; }

        public String id() { return id; }
        public String translationKey() { return "hearthstead.command.refused." + id; }
    }

    /**
     * Shape validation that needs no world: ids, facing, width and whether the
     * kind has the target it requires. Distances are passed in by the caller.
     *
     * @param distanceToPos horizontal+vertical distance from the commander to
     *                      the ordered point, or a negative number if none
     * @param hasEnemy      whether the request names an enemy
     */
    public static Refusal validateShape(int groupWire, int kindWire, int octant, int width,
                                        double distanceToPos, boolean hasEnemy) {
        Optional<Group> group = Group.fromWire(groupWire);
        if (group.isEmpty()) return Refusal.UNKNOWN_GROUP;
        Optional<Kind> kind = Kind.fromWire(kindWire);
        if (kind.isEmpty()) return Refusal.UNKNOWN_KIND;
        if (!FormationMath.validOctant(octant)) return Refusal.BAD_FACING;
        if (width < FormationMath.MIN_WIDTH || width > FormationMath.MAX_WIDTH) return Refusal.BAD_WIDTH;
        Kind k = kind.get();
        Group g = group.get();
        if (!allowedFor(g, k)) return Refusal.WRONG_GROUP;
        if (k.needsEnemy() && !hasEnemy) return Refusal.NO_TARGET;
        if (k.needsPos() && distanceToPos < 0) return Refusal.NO_TARGET;
        if (k.needsPos() && distanceToPos > SERVER_TARGET_RANGE) return Refusal.OUT_OF_RANGE;
        return Refusal.NONE;
    }

    /**
     * Which orders a group may receive. ALL accepts every kind (each role
     * then takes its own meaning); a single role only its style's orders.
     */
    public static boolean allowedFor(Group group, Kind kind) {
        if (group == Group.ALL) return true;
        return switch (kind) {
            case LINE, FOLLOW, RETURN -> true;
            case ATTACK -> !group.support();
            case HIGH_GROUND, HOLD_FIRE, FIRE_AT_WILL -> group.ranged();
        };
    }

    /** The meaning of {@code kind} for one role (e.g. melee hold the base of a wall). */
    public static Optional<Kind> kindFor(Group role, Kind kind) {
        if (allowedFor(role, kind)) return Optional.of(kind);
        if (kind == Kind.HIGH_GROUND) return Optional.of(Kind.LINE);
        return Optional.empty();
    }

    public static boolean withinEarshot(double distanceSqr) {
        return withinEarshot(distanceSqr, EARSHOT);
    }

    /** Earshot with a settlement's own reach (tech tree: Commander's Horn). */
    public static boolean withinEarshot(double distanceSqr, double earshot) {
        return distanceSqr <= earshot * earshot;
    }

    public static boolean rateLimited(long lastOrderTick, long now) {
        return lastOrderTick != Long.MIN_VALUE && now - lastOrderTick < RATE_LIMIT_TICKS && now >= lastOrderTick;
    }

    /**
     * Lifetime of one order. An order given while a raid (or the alarm) is on
     * lasts until {@link #AFTER_RAID_GRACE_TICKS} after it ends; a peacetime
     * drill lapses after {@link #PEACETIME_LIFETIME_TICKS}. A raid that
     * starts during a drill converts it into a raid order.
     */
    public static final class Lifetime {
        private final long issuedAt;
        private boolean sawRaid;
        private long raidEndedAt = Long.MIN_VALUE;
        private long raidGraceTicks = AFTER_RAID_GRACE_TICKS;

        public Lifetime(long issuedAt, boolean raidActive) {
            this.issuedAt = issuedAt;
            this.sawRaid = raidActive;
        }

        /** Tech tree (Commander's Horn): raid orders may hold longer. */
        public Lifetime raidGrace(long ticks) {
            this.raidGraceTicks = Math.max(AFTER_RAID_GRACE_TICKS, ticks);
            return this;
        }

        /** @return true once the order has expired */
        public boolean update(long now, boolean raidActive) {
            if (raidActive) {
                sawRaid = true;
                raidEndedAt = Long.MIN_VALUE;
                return false;
            }
            if (sawRaid) {
                if (raidEndedAt == Long.MIN_VALUE) raidEndedAt = now;
                return now - raidEndedAt >= raidGraceTicks;
            }
            return now - issuedAt >= PEACETIME_LIFETIME_TICKS;
        }

        public boolean sawRaid() { return sawRaid; }
    }

    private FieldOrderRules() {
    }
}
