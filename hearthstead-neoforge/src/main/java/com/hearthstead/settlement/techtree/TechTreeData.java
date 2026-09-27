package com.hearthstead.settlement.techtree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The v3 tech tree definitions (84 nodes, 5 branches), read once from the mod
 * jar: {@code data/hearthstead/techtree/tree.json} plus one file per branch.
 *
 * <p>Loaded from the classpath on first use, on both client and server, so
 * the node layout never has to be synced and JUnit can read it without a
 * game. Each branch lane owns its own branch file.
 */
public final class TechTreeData {
    public static final List<String> BRANCHES =
        List.of("crown", "watch", "logistics", "commons", "craft");
    private static final String ROOT = "/data/hearthstead/techtree/";

    public record Branch(String id, String name, int color, String direction) {
    }

    public record Tier(int id, String name, String requirement) {
    }

    private static volatile TechTreeData instance;

    private final List<TechNodeDef> nodes;
    private final Map<String, TechNodeDef> byId;
    private final List<Branch> branches;
    private final List<Tier> tiers;
    private Map<String, String> icons = Map.of();
    private Map<String, String> shortNames = Map.of();

    private TechTreeData(List<TechNodeDef> nodes, List<Branch> branches, List<Tier> tiers) {
        this.nodes = List.copyOf(nodes);
        Map<String, TechNodeDef> index = new LinkedHashMap<>();
        for (TechNodeDef node : nodes) {
            index.putIfAbsent(node.id(), node);
        }
        this.byId = Collections.unmodifiableMap(index);
        this.branches = List.copyOf(branches);
        this.tiers = List.copyOf(tiers);
    }

    public static TechTreeData get() {
        TechTreeData loaded = instance;
        if (loaded == null) {
            synchronized (TechTreeData.class) {
                loaded = instance;
                if (loaded == null) {
                    loaded = loadClasspath();
                    instance = loaded;
                }
            }
        }
        return loaded;
    }

    public List<TechNodeDef> nodes() {
        return nodes;
    }

    @Nullable
    public TechNodeDef node(String id) {
        if (id == null) {
            return null;
        }
        TechNodeDef direct = byId.get(id);
        return direct != null ? direct : byId.get(TechIdMigration.canonical(id));
    }

    /** Card icon item id for a node (icons.json), or null. */
    @Nullable
    public String iconItem(String id) {
        return icons.get(id);
    }

    /** Short map label for a node (icons.json "short"), or null. */
    @Nullable
    public String shortName(String id) {
        return shortNames.get(id);
    }

    public List<Branch> branches() {
        return branches;
    }

    public List<Tier> tiers() {
        return tiers;
    }

    @Nullable
    public Branch branch(String id) {
        for (Branch branch : branches) {
            if (branch.id().equals(id)) {
                return branch;
            }
        }
        return null;
    }

    /** Nodes that list {@code id} in their requires (the arrows out of it). */
    public List<TechNodeDef> dependents(String id) {
        List<TechNodeDef> out = new ArrayList<>();
        for (TechNodeDef node : nodes) {
            if (node.requires().contains(id)) {
                out.add(node);
            }
        }
        return out;
    }

    /** Every node {@code id} needs, transitively (not including itself). */
    public Set<String> ancestors(String id) {
        Set<String> seen = new HashSet<>();
        Deque<String> open = new ArrayDeque<>();
        TechNodeDef start = byId.get(id);
        if (start != null) {
            open.addAll(start.requires());
        }
        while (!open.isEmpty()) {
            String next = open.pop();
            if (seen.add(next)) {
                TechNodeDef def = byId.get(next);
                if (def != null) {
                    open.addAll(def.requires());
                }
            }
        }
        return seen;
    }

    /**
     * Structural problems, empty when the tree is sound: duplicate ids,
     * unknown requires/excludes, asymmetric pick-ones, cycles, bad tiers.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (TechNodeDef node : nodes) {
            if (!ids.add(node.id())) {
                errors.add("duplicate id " + node.id());
            }
            if (!node.id().matches("[a-z0-9_]+")) {
                errors.add("bad id " + node.id());
            }
            if (!BRANCHES.contains(node.branch())) {
                errors.add(node.id() + ": unknown branch " + node.branch());
            }
            if (node.tier() < 1 || node.tier() > 5) {
                errors.add(node.id() + ": tier " + node.tier());
            }
            if (node.coins() < 0 || node.studyDays() < 0) {
                errors.add(node.id() + ": negative cost or study time");
            }
            for (TechNodeDef.GoodsLine line : node.goods()) {
                if (line.count() <= 0 || (line.item() == null) == (line.tag() == null)) {
                    errors.add(node.id() + ": bad goods line " + line);
                }
            }
        }
        for (TechNodeDef node : nodes) {
            for (String req : node.requires()) {
                if (!byId.containsKey(req)) {
                    errors.add(node.id() + " requires unknown " + req);
                }
                if (req.equals(node.id())) {
                    errors.add(node.id() + " requires itself");
                }
            }
            for (String ex : node.excludes()) {
                TechNodeDef other = byId.get(ex);
                if (other == null) {
                    errors.add(node.id() + " excludes unknown " + ex);
                } else if (!other.excludes().contains(node.id())) {
                    errors.add(node.id() + " excludes " + ex + " but not the other way round");
                }
                if (node.requires().contains(ex)) {
                    errors.add(node.id() + " both requires and excludes " + ex);
                }
            }
            for (TechNodeDef.Gate gate : node.gates()) {
                for (String other : gate.nodes()) {
                    if (!byId.containsKey(other)) {
                        errors.add(node.id() + " gate names unknown node " + other);
                    }
                }
            }
            if (ancestors(node.id()).contains(node.id())) {
                errors.add(node.id() + " is in a requires cycle");
            }
        }
        return errors;
    }

    // --------------------------------------------------------------- loading

    static TechTreeData loadClasspath() {
        List<TechNodeDef> nodes = new ArrayList<>();
        List<Branch> branches = new ArrayList<>();
        List<Tier> tiers = new ArrayList<>();
        JsonObject tree = read(ROOT + "tree.json");
        if (tree != null) {
            for (JsonElement e : array(tree, "branches")) {
                JsonObject b = e.getAsJsonObject();
                branches.add(new Branch(str(b, "id"), str(b, "name"),
                    parseColor(str(b, "color")), str(b, "direction")));
            }
            for (JsonElement e : array(tree, "tiers")) {
                JsonObject t = e.getAsJsonObject();
                tiers.add(new Tier(t.get("id").getAsInt(), str(t, "name"),
                    str(t, "requirement")));
            }
        }
        for (String branch : BRANCHES) {
            JsonObject file = read(ROOT + branch + ".json");
            if (file == null) {
                continue;
            }
            for (JsonElement e : array(file, "nodes")) {
                nodes.add(parseNode(branch, e.getAsJsonObject()));
            }
        }
        TechTreeData out = new TechTreeData(nodes, branches, tiers);
        JsonObject iconFile = read(ROOT + "icons.json");
        if (iconFile != null && iconFile.has("icons")) {
            Map<String, String> icons = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : iconFile.getAsJsonObject("icons").entrySet()) {
                icons.put(e.getKey(), e.getValue().getAsString());
            }
            out.icons = Map.copyOf(icons);
        }
        if (iconFile != null && iconFile.has("short")) {
            Map<String, String> shorts = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : iconFile.getAsJsonObject("short").entrySet()) {
                shorts.put(e.getKey(), e.getValue().getAsString());
            }
            out.shortNames = Map.copyOf(shorts);
        }
        return out;
    }

    /** Test seam: parse one branch file's JSON text. */
    static List<TechNodeDef> parseBranch(String branch, String json) {
        JsonObject file = JsonParser.parseString(json).getAsJsonObject();
        List<TechNodeDef> out = new ArrayList<>();
        for (JsonElement e : array(file, "nodes")) {
            out.add(parseNode(branch, e.getAsJsonObject()));
        }
        return out;
    }

    private static TechNodeDef parseNode(String branch, JsonObject o) {
        List<TechNodeDef.GoodsLine> goods = new ArrayList<>();
        for (JsonElement e : array(o, "goods")) {
            JsonObject g = e.getAsJsonObject();
            goods.add(new TechNodeDef.GoodsLine(
                g.has("item") ? g.get("item").getAsString() : null,
                g.has("tag") ? g.get("tag").getAsString() : null,
                g.has("count") ? g.get("count").getAsInt() : 0));
        }
        List<TechNodeDef.Gate> gates = new ArrayList<>();
        for (JsonElement e : array(o, "gates")) {
            JsonObject g = e.getAsJsonObject();
            gates.add(new TechNodeDef.Gate(str(g, "kind"),
                g.has("objective") ? g.get("objective").getAsString() : null,
                intOr(g, "target", 1), strings(g, "nodes"), intOr(g, "tier", 0),
                intOr(g, "count", 0), intOr(g, "branches", 0)));
        }
        return new TechNodeDef(
            str(o, "id"),
            branch,
            intOr(o, "tier", 1),
            str(o, "type"),
            intOr(o, "x", 0),
            intOr(o, "y", 0),
            intOr(o, "coins", 0),
            List.copyOf(goods),
            intOr(o, "study_days", 0),
            strings(o, "requires"),
            strings(o, "excludes"),
            List.copyOf(gates),
            str(o, "gate_text"),
            o.has("auto") && o.get("auto").getAsBoolean(),
            o.has("legacy") ? o.get("legacy").getAsString() : null,
            str(o, "design_status"),
            str(o, "impl"),
            str(o, "name"),
            str(o, "offers"),
            strings(o, "details"),
            str(o, "flavor"),
            strings(o, "depends_on"),
            strings(o, "unlocks_recipes"),
            o.has("founding") && o.get("founding").getAsBoolean());
    }

    @Nullable
    private static JsonObject read(String path) {
        try (InputStream in = TechTreeData.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Bad tech tree data " + path, failure);
        }
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(o, key)) {
            out.add(e.getAsString());
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }

    private static int parseColor(String hex) {
        try {
            return 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return 0xFF888888;
        }
    }

    /** Test/QA seam. */
    static Map<String, Integer> countsByBranch(TechTreeData data) {
        Map<String, Integer> out = new HashMap<>();
        for (TechNodeDef node : data.nodes) {
            out.merge(node.branch(), 1, Integer::sum);
        }
        return out;
    }
}
