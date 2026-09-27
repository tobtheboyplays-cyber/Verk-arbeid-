package com.hearthstead.client.builder;

import com.hearthstead.network.BuilderActionPayload;
import com.hearthstead.network.BuilderNetwork;
import com.hearthstead.network.BuilderPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Several blueprint previews at once for the Building Plan style picker (the
 * catalog's own preview slot holds one). Requests go through the Builder's
 * normal PREVIEW action; {@link BuilderClientState#acceptPreview} hands every
 * arriving preview here first and then carries on as before, so this cache
 * never takes a preview away from the Builder's Plan.
 */
public final class PlanPreviews {

    private static final Map<String, BuilderPayloads.Preview> CACHE = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static boolean styled = true;

    private PlanPreviews() {
    }

    /** Called for every preview the server sends. Never consumes it. */
    public static void accept(BuilderPayloads.Preview preview) {
        if (preview != null && PENDING.remove(preview.id())) {
            CACHE.put(preview.id(), preview);
            PlanPlacement.previewArrived(preview);
        }
    }

    /** The preview for this blueprint (in the current town-style mode), or null while it loads. */
    @Nullable
    public static BuilderPayloads.Preview get(String id) {
        boolean now = BuilderClientState.matchTownStyle();
        if (now != styled) {
            styled = now;
            CACHE.clear();
            PENDING.clear();
        }
        BuilderPayloads.Preview cached = CACHE.get(id);
        if (cached == null && PENDING.add(id)) {
            send(id);
        }
        return cached;
    }

    public static void clear() {
        CACHE.clear();
        PENDING.clear();
    }

    private static void send(String id) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new BuilderActionPayload(BuilderActionPayload.Action.PREVIEW, id,
            BlockPos.ZERO, BlockPos.ZERO, 0, false, styled ? BuilderNetwork.MATCH_TOWN_STYLE : 0, false,
            BuilderActionPayload.NONE));
    }
}
