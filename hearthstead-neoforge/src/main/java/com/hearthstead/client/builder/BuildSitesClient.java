package com.hearthstead.client.builder;

import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.builder.BuildStatus;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Client cache of the build sites snapshot (BUILDER lane). Read-only API for
 * other client lanes: the map lane's Banner-screen Buildings page and map
 * outlines, the settler sheet, and this lane's own Sites tab and world label.
 *
 * <p>{@link #version()} increases on every accepted snapshot, so a screen can
 * rebuild only when it changed.
 */
public final class BuildSitesClient {

    private static List<BuilderPayloads.Site> sites = List.of();
    private static UUID settlementId;
    private static int version;

    private BuildSitesClient() {
    }

    public static void accept(BuilderPayloads.Sites payload) {
        sites = payload.sites();
        settlementId = payload.settlementId();
        version++;
    }

    /** Every site, active ones first in queue order. */
    public static List<BuilderPayloads.Site> sites() {
        return sites;
    }

    public static int version() {
        return version;
    }

    public static UUID settlementId() {
        return settlementId;
    }

    public static void clear() {
        sites = List.of();
        settlementId = null;
        version++;
    }

    /** The one-line status of a site, e.g. "Waiting for 24 Oak Planks (warehouse 0, on the way 0)". */
    public static Component status(BuilderPayloads.Site site) {
        return BuildStatus.byOrdinal(site.status()).describe(site.args());
    }

    /** "Missing: 24 Oak Planks, 6 Cobblestone" or empty when nothing is missing. */
    public static Component missingLine(BuilderPayloads.Site site) {
        if (site.missing().isEmpty()) {
            return Component.empty();
        }
        var out = Component.translatable("hearthstead.builder.ui.missing");
        boolean first = true;
        for (BuilderPayloads.Stock stock : site.missing()) {
            if (stock.shortfall() <= 0) {
                continue;
            }
            out.append(first ? " " : ", ");
            out.append(Component.literal(stock.shortfall() + " ")).append(stock.item().getDescription());
            first = false;
        }
        return first ? Component.empty() : out;
    }

    /** The site a given Builder is working, or null. */
    public static BuilderPayloads.Site siteOf(UUID builder) {
        for (BuilderPayloads.Site site : sites) {
            if (builder.equals(site.builder())) {
                return site;
            }
        }
        return null;
    }
}
