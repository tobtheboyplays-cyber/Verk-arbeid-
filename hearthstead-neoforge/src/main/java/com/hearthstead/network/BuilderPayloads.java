package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-to-client payloads of the Builder's Plan (BUILDER lane). Every list
 * is capped on both ends, so a malformed packet can never allocate without
 * bound.
 */
public final class BuilderPayloads {

    public static final int MAX_ENTRIES = 512;
    public static final int MAX_LINES = 24;
    public static final int MAX_CELLS = 8192;
    public static final int MAX_PALETTE = 1024;
    public static final int MAX_SITES = 32;

    private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM = ByteBufCodecs.registry(Registries.ITEM);
    private static final StreamCodec<io.netty.buffer.ByteBuf, BlockState> STATE =
        ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY);

    private BuilderPayloads() {
    }

    // ---------------------------------------------------------- shared ---

    /** One item and a count. */
    public record ItemLine(Item item, int count) {
        static void write(RegistryFriendlyByteBuf buf, ItemLine line) {
            ITEM.encode(buf, line.item);
            buf.writeVarInt(line.count);
        }

        static ItemLine read(RegistryFriendlyByteBuf buf) {
            return new ItemLine(ITEM.decode(buf), buf.readVarInt());
        }
    }

    /** One material line: what is needed and where it stands. */
    public record Stock(Item item, int needed, int inHut, int inWarehouse, int onTheWay) {
        public int shortfall() {
            return Math.max(0, needed - inHut - onTheWay);
        }

        static void write(RegistryFriendlyByteBuf buf, Stock s) {
            ITEM.encode(buf, s.item);
            buf.writeVarInt(s.needed);
            buf.writeVarInt(s.inHut);
            buf.writeVarInt(s.inWarehouse);
            buf.writeVarInt(s.onTheWay);
        }

        static Stock read(RegistryFriendlyByteBuf buf) {
            return new Stock(ITEM.decode(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt());
        }
    }

    private static <T> void writeList(RegistryFriendlyByteBuf buf, List<T> list, int max,
                                      java.util.function.BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        int n = Math.min(list.size(), max);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            writer.accept(buf, list.get(i));
        }
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buf, int max,
                                        java.util.function.Function<RegistryFriendlyByteBuf, T> reader) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("builder payload list too long: " + n);
        }
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(reader.apply(buf));
        }
        return List.copyOf(out);
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> list) {
        writeList(buf, list, 8, (b, s) -> b.writeUtf(s, 128));
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        return readList(buf, 8, b -> b.readUtf(128));
    }

    // --------------------------------------------------------- catalog ---

    public record CatalogEntry(String id, String name, String category, String kind, String style,
                               int sizeX, int sizeY, int sizeZ, int blocks, String lockKey,
                               List<ItemLine> materials, String group, String preset) {
        static void write(RegistryFriendlyByteBuf buf, CatalogEntry e) {
            buf.writeUtf(e.id, 64);
            buf.writeUtf(e.name, 96);
            buf.writeUtf(e.category, 32);
            buf.writeUtf(e.kind, 16);
            buf.writeUtf(e.style, 32);
            buf.writeVarInt(e.sizeX);
            buf.writeVarInt(e.sizeY);
            buf.writeVarInt(e.sizeZ);
            buf.writeVarInt(e.blocks);
            buf.writeUtf(e.lockKey, 96);
            writeList(buf, e.materials, MAX_LINES, ItemLine::write);
            buf.writeUtf(e.group, 64);
            buf.writeUtf(e.preset, 32);
        }

        static CatalogEntry read(RegistryFriendlyByteBuf buf) {
            return new CatalogEntry(buf.readUtf(64), buf.readUtf(96), buf.readUtf(32), buf.readUtf(16),
                buf.readUtf(32), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(96), readList(buf, MAX_LINES, ItemLine::read), buf.readUtf(64), buf.readUtf(32));
        }
    }

    public record GapRow(String id, int have, int needed, boolean builderCan) {
        static void write(RegistryFriendlyByteBuf buf, GapRow g) {
            buf.writeUtf(g.id, 48);
            buf.writeVarInt(g.have);
            buf.writeVarInt(g.needed);
            buf.writeBoolean(g.builderCan);
        }

        static GapRow read(RegistryFriendlyByteBuf buf) {
            return new GapRow(buf.readUtf(48), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }
    }

    public record UpgradeRow(UUID buildingId, String typeId, int level, int maxLevel, BlockPos plaque,
                             List<GapRow> gaps) {
        static void write(RegistryFriendlyByteBuf buf, UpgradeRow r) {
            UUIDUtil.STREAM_CODEC.encode(buf, r.buildingId);
            buf.writeUtf(r.typeId, 48);
            buf.writeVarInt(r.level);
            buf.writeVarInt(r.maxLevel);
            BlockPos.STREAM_CODEC.encode(buf, r.plaque);
            writeList(buf, r.gaps, MAX_LINES, GapRow::write);
        }

        static UpgradeRow read(RegistryFriendlyByteBuf buf) {
            return new UpgradeRow(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(48), buf.readVarInt(),
                buf.readVarInt(), BlockPos.STREAM_CODEC.decode(buf), readList(buf, MAX_LINES, GapRow::read));
        }
    }

    /** The whole Builder's Plan menu in one packet. */
    public record Catalog(boolean enabled, boolean hasBuilder, boolean palisade, boolean stone,
                          boolean barricade, List<CatalogEntry> entries, List<UpgradeRow> upgrades,
                          int hutLevel, int pickup, int fill, boolean townStyle)
        implements CustomPacketPayload {

        public static final Type<Catalog> TYPE = new Type<>(Hearthstead.id("builder_catalog"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Catalog> CODEC = StreamCodec.of(
            (buf, c) -> {
                buf.writeBoolean(c.enabled);
                buf.writeBoolean(c.hasBuilder);
                buf.writeBoolean(c.palisade);
                buf.writeBoolean(c.stone);
                buf.writeBoolean(c.barricade);
                writeList(buf, c.entries, MAX_ENTRIES, CatalogEntry::write);
                writeList(buf, c.upgrades, 64, UpgradeRow::write);
                buf.writeVarInt(c.hutLevel);
                buf.writeVarInt(c.pickup);
                buf.writeVarInt(c.fill);
                buf.writeBoolean(c.townStyle);
            },
            buf -> new Catalog(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), readList(buf, MAX_ENTRIES, CatalogEntry::read),
                readList(buf, 64, UpgradeRow::read), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // --------------------------------------------------------- preview ---

    /**
     * A blueprint's cells for the ghost: palette + packed local cells
     * ({@code x | y << 8 | z << 16}, then the palette index). The client
     * applies rotation and mirror with the same {@code BlueprintTransform}.
     */
    public record Preview(String id, int sizeX, int sizeY, int sizeZ, List<BlockState> palette,
                          int[] cells, int groundLevel) implements CustomPacketPayload {

        public static final Type<Preview> TYPE = new Type<>(Hearthstead.id("builder_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preview> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.id, 64);
                buf.writeVarInt(p.sizeX);
                buf.writeVarInt(p.sizeY);
                buf.writeVarInt(p.sizeZ);
                int n = Math.min(p.palette.size(), MAX_PALETTE);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    STATE.encode(buf, p.palette.get(i));
                }
                int cells = Math.min(p.cells.length / 2, MAX_CELLS);
                buf.writeVarInt(cells);
                for (int i = 0; i < cells * 2; i++) {
                    buf.writeVarInt(p.cells[i]);
                }
                buf.writeVarInt(p.groundLevel);
            },
            buf -> {
                String id = buf.readUtf(64);
                int sx = buf.readVarInt();
                int sy = buf.readVarInt();
                int sz = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0 || n > MAX_PALETTE) {
                    throw new IllegalArgumentException("palette");
                }
                List<BlockState> palette = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    palette.add(STATE.decode(buf));
                }
                int cells = buf.readVarInt();
                if (cells < 0 || cells > MAX_CELLS) {
                    throw new IllegalArgumentException("cells");
                }
                int[] packed = new int[cells * 2];
                for (int i = 0; i < packed.length; i++) {
                    packed[i] = buf.readVarInt();
                }
                return new Preview(id, sx, sy, sz, List.copyOf(palette), packed, buf.readVarInt());
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------ validation ---

    /**
     * What confirming would do: steps, clears, fills, player blocks in the
     * way, and every material with its stock -- shown before anything is
     * committed. Echoes the placement so the confirm button commits exactly it.
     */
    public record Validation(int kind, String subject, BlockPos a, BlockPos b, int rotation, boolean mirror,
                             int number, boolean flag, UUID target, boolean ok, String reasonKey,
                             List<String> reasonArgs, int steps, int clears, int fills,
                             List<BlockPos> playerBlocks, int playerBlockCount, List<Stock> materials)
        implements CustomPacketPayload {

        public static final int BLUEPRINT = 0;
        public static final int LINE = 1;
        public static final int UPGRADE = 2;
        public static final int DECONSTRUCT = 3;

        public static final Type<Validation> TYPE = new Type<>(Hearthstead.id("builder_validation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Validation> CODEC = StreamCodec.of(
            (buf, v) -> {
                buf.writeVarInt(v.kind);
                buf.writeUtf(v.subject, 64);
                BlockPos.STREAM_CODEC.encode(buf, v.a);
                BlockPos.STREAM_CODEC.encode(buf, v.b);
                buf.writeVarInt(v.rotation);
                buf.writeBoolean(v.mirror);
                buf.writeVarInt(v.number);
                buf.writeBoolean(v.flag);
                UUIDUtil.STREAM_CODEC.encode(buf, v.target);
                buf.writeBoolean(v.ok);
                buf.writeUtf(v.reasonKey, 96);
                writeStrings(buf, v.reasonArgs);
                buf.writeVarInt(v.steps);
                buf.writeVarInt(v.clears);
                buf.writeVarInt(v.fills);
                writeList(buf, v.playerBlocks, 32, (b, p) -> BlockPos.STREAM_CODEC.encode(b, p));
                buf.writeVarInt(v.playerBlockCount);
                writeList(buf, v.materials, MAX_ENTRIES, Stock::write);
            },
            buf -> new Validation(buf.readVarInt(), buf.readUtf(64), BlockPos.STREAM_CODEC.decode(buf),
                BlockPos.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                buf.readBoolean(), UUIDUtil.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readUtf(96),
                readStrings(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                readList(buf, 32, b -> BlockPos.STREAM_CODEC.decode(b)), buf.readVarInt(),
                readList(buf, MAX_ENTRIES, Stock::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ----------------------------------------------------------- sites ---

    /**
     * One construction site for the Sites tab, the Banner screen's Buildings
     * page (map lane), the in-world label and the settler sheet.
     */
    public record Site(UUID id, String label, int kind, int minX, int minY, int minZ, int maxX, int maxY,
                       int maxZ, float progress, int status, List<String> args, boolean paused,
                       boolean rush, int skipped, int blocked, UUID builder, int order,
                       List<Stock> missing) {
        static void write(RegistryFriendlyByteBuf buf, Site s) {
            UUIDUtil.STREAM_CODEC.encode(buf, s.id);
            buf.writeUtf(s.label, 64);
            buf.writeVarInt(s.kind);
            buf.writeVarInt(s.minX);
            buf.writeVarInt(s.minY);
            buf.writeVarInt(s.minZ);
            buf.writeVarInt(s.maxX);
            buf.writeVarInt(s.maxY);
            buf.writeVarInt(s.maxZ);
            buf.writeFloat(s.progress);
            buf.writeVarInt(s.status);
            writeStrings(buf, s.args);
            buf.writeBoolean(s.paused);
            buf.writeBoolean(s.rush);
            buf.writeVarInt(s.skipped);
            buf.writeVarInt(s.blocked);
            UUIDUtil.STREAM_CODEC.encode(buf, s.builder);
            buf.writeVarInt(s.order);
            writeList(buf, s.missing, 12, Stock::write);
        }

        static Site read(RegistryFriendlyByteBuf buf) {
            return new Site(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readFloat(), buf.readVarInt(), readStrings(buf), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readVarInt(), readList(buf, 12, Stock::read));
        }
    }

    /** All sites of the viewer's settlement, sent while it is nearby. */
    public record Sites(UUID settlementId, int revision, List<Site> sites) implements CustomPacketPayload {
        public static final Type<Sites> TYPE = new Type<>(Hearthstead.id("builder_sites"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sites> CODEC = StreamCodec.of(
            (buf, s) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, s.settlementId);
                buf.writeVarInt(s.revision);
                writeList(buf, s.sites, MAX_SITES, Site::write);
            },
            buf -> new Sites(UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(),
                readList(buf, MAX_SITES, Site::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
