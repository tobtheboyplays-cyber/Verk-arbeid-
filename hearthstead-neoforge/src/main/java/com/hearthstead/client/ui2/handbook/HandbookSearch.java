package com.hearthstead.client.ui2.handbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure helpers the handbook ranks and sorts with (JUnit-tested):
 * search ranking, and the "most familiar ingredient first" order that keeps a
 * recipe grid showing oak and cobblestone instead of crimson stems.
 */
public final class HandbookSearch {
    /** Most familiar options first for tags, so the grid rests on oak and cobblestone. */
    static final List<String> COMMON = List.of("minecraft:oak_log", "minecraft:oak_planks",
        "minecraft:cobblestone", "minecraft:oak_slab", "minecraft:oak_sapling", "minecraft:white_wool",
        "minecraft:coal", "minecraft:stick");

    private HandbookSearch() {
    }

    /** Sort key: the listed common ids, then any other oak item, then vanilla, then the rest. */
    public static int commonRank(String itemId) {
        int i = COMMON.indexOf(itemId);
        if (i >= 0) return i;
        if (itemId.startsWith("minecraft:oak_")) return 50;
        return itemId.startsWith("minecraft:") ? 100 : 200;
    }

    /**
     * Pages whose text contains every term, best first: a term in the page title
     * scores 8, in the chapter heading 4, in bullets/steps/tip 2, in "More
     * detail" 1. Ties keep reading order. All texts are already lower case.
     */
    public static <P> List<P> rank(List<P> pages, String[] terms, List<String> titles, List<String> headings,
                                   List<String> bodies, List<String> details, int limit) {
        record Hit<P>(P page, int score, int order) {
        }
        List<Hit<P>> hits = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            int score = 0;
            boolean all = true;
            for (String t : terms) {
                int s = (titles.get(i).contains(t) ? 8 : 0) + (headings.get(i).contains(t) ? 4 : 0)
                    + (bodies.get(i).contains(t) ? 2 : 0) + (details.get(i).contains(t) ? 1 : 0);
                if (s == 0) {
                    all = false;
                    break;
                }
                score += s;
            }
            if (all) hits.add(new Hit<>(pages.get(i), score, i));
        }
        hits.sort(Comparator.comparingInt((Hit<P> h) -> -h.score()).thenComparingInt(Hit::order));
        List<P> out = new ArrayList<>();
        for (Hit<P> h : hits) {
            if (out.size() >= limit) break;
            out.add(h.page());
        }
        return out;
    }
}
