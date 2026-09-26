package com.hearthstead.client.builder;

import com.hearthstead.network.BuilderActionPayload;
import com.hearthstead.network.BuilderPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client-side state of the Builder's Plan: the last catalog, the ghost
 * preview being placed, and the last validation the server returned.
 * Every mutation of the world goes through a {@link BuilderActionPayload};
 * this class only remembers what the server said.
 */
public final class BuilderClientState {

    @Nullable
    private static BuilderPayloads.Catalog catalog;
    @Nullable
    private static BuilderPayloads.Preview preview;
    @Nullable
    private static BuilderPayloads.Validation validation;
    private static int catalogVersion;
    /** The catalog's 3D preview (a PREVIEW that must not start the ghost). */
    @Nullable
    private static BuilderPayloads.Preview thumbnail;
    @Nullable
    private static String wantThumb;
    @Nullable
    private static String wantPlace;
    private static boolean thumbStyled;
    /** "Match town style": re-skin presets with the town's own materials. */
    private static boolean matchTownStyle = true;

    private BuilderClientState() {
    }

    // ------------------------------------------------------------ inbound ---

    public static void acceptCatalog(BuilderPayloads.Catalog payload) {
        catalog = payload;
        catalogVersion++;
        BuilderPlanScreens.refresh();
    }

    public static void acceptPreview(BuilderPayloads.Preview payload) {
        if (payload.id().equals(wantPlace)) {
            wantPlace = null;
            preview = payload;
            BuilderPlacement.startBlueprint(payload);
            return;
        }
        if (payload.id().equals(wantThumb)) {
            thumbnail = payload;
            BuilderPlanScreens.refresh();
        }
    }

    /** The preview shown in the catalog for this id and style, or null while it loads. */
    @Nullable
    public static BuilderPayloads.Preview thumbnail(String id) {
        if (!id.equals(wantThumb) || thumbStyled != matchTownStyle) {
            wantThumb = id;
            thumbStyled = matchTownStyle;
            thumbnail = null;
            send(new BuilderActionPayload(BuilderActionPayload.Action.PREVIEW, id, BlockPos.ZERO,
                BlockPos.ZERO, 0, false, styleBits(), false, BuilderActionPayload.NONE));
            return null;
        }
        return thumbnail != null && thumbnail.id().equals(id) ? thumbnail : null;
    }

    public static boolean matchTownStyle() {
        return matchTownStyle;
    }

    public static void setMatchTownStyle(boolean on) {
        matchTownStyle = on;
    }

    private static int styleBits() {
        return matchTownStyle ? com.hearthstead.network.BuilderNetwork.MATCH_TOWN_STYLE : 0;
    }

    public static void acceptValidation(BuilderPayloads.Validation payload) {
        validation = payload;
        BuilderPlanScreens.showValidation(payload);
    }

    @Nullable
    public static BuilderPayloads.Catalog catalog() {
        return catalog;
    }

    public static int catalogVersion() {
        return catalogVersion;
    }

    @Nullable
    public static BuilderPayloads.Preview preview() {
        return preview;
    }

    @Nullable
    public static BuilderPayloads.Validation validation() {
        return validation;
    }

    // ----------------------------------------------------------- outbound ---

    public static void requestCatalog() {
        send(BuilderActionPayload.simple(BuilderActionPayload.Action.CATALOG));
    }

    public static void requestPreview(String blueprintId) {
        wantPlace = blueprintId;
        send(new BuilderActionPayload(BuilderActionPayload.Action.PREVIEW, blueprintId, BlockPos.ZERO,
            BlockPos.ZERO, 0, false, styleBits(), false, BuilderActionPayload.NONE));
    }

    public static void validateBlueprint(String id, BlockPos origin, int rotation, boolean mirror) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.VALIDATE, id, origin, BlockPos.ZERO,
            rotation, mirror, styleBits(), false, BuilderActionPayload.NONE));
    }

    public static void placeBlueprint(String id, BlockPos origin, int rotation, boolean mirror,
                                      boolean allowOverwrite) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.PLACE, id, origin, BlockPos.ZERO,
            rotation, mirror, styleBits(), allowOverwrite, BuilderActionPayload.NONE));
    }

    public static void validateLine(String kind, BlockPos a, BlockPos b, boolean gate, int gateOffset) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.VALIDATE_LINE, kind, a, b, 0, false,
            gateOffset, gate, BuilderActionPayload.NONE));
    }

    public static void placeLine(String kind, BlockPos a, BlockPos b, boolean gate, int gateOffset) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.PLACE_LINE, kind, a, b, 0, false,
            gateOffset, gate, BuilderActionPayload.NONE));
    }

    public static void siteAction(UUID jobId, int siteActionOrdinal) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.SITE, "", BlockPos.ZERO, BlockPos.ZERO,
            0, false, siteActionOrdinal, false, jobId));
    }

    public static void validateUpgrade(UUID buildingId) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.VALIDATE_UPGRADE, "", BlockPos.ZERO,
            BlockPos.ZERO, 0, false, 0, false, buildingId));
    }

    public static void orderUpgrade(UUID buildingId) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.ORDER_UPGRADE, "", BlockPos.ZERO,
            BlockPos.ZERO, 0, false, 0, false, buildingId));
    }

    public static void deleteDesign(String designId) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.DELETE_DESIGN, designId, BlockPos.ZERO,
            BlockPos.ZERO, 0, false, 0, false, BuilderActionPayload.NONE));
    }

    /** Pickup mode ordinal in number, fill mode ordinal in rotation. */
    public static void settings(int pickup, int fill) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.SETTINGS, "", BlockPos.ZERO, BlockPos.ZERO,
            fill, false, pickup, false, BuilderActionPayload.NONE));
    }

    public static void deconstruct(UUID buildingId, boolean commit) {
        send(new BuilderActionPayload(BuilderActionPayload.Action.DECONSTRUCT, "", BlockPos.ZERO, BlockPos.ZERO,
            0, false, 0, commit, buildingId));
    }

    private static void send(BuilderActionPayload payload) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(payload);
        }
    }
}
