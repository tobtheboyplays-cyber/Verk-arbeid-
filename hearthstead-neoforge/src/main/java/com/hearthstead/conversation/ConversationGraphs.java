package com.hearthstead.conversation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.hearthstead.Hearthstead;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * All known dialog graphs: those registered in code (event content, the raid
 * parley) plus JSON under {@code data/<ns>/conversations/}. JSON wins on the
 * same id so a datapack can rewrite a built-in talk. Every graph passes
 * {@link GraphValidator} or is refused with its problems logged.
 */
public final class ConversationGraphs {
    private static final Map<String, ConversationGraph> CODE = new ConcurrentHashMap<>();
    private static volatile Map<String, ConversationGraph> data = Map.of();

    private ConversationGraphs() {
    }

    /** Registers (or replaces) a code graph. Returns false and logs when invalid. */
    public static boolean register(ConversationGraph graph) {
        List<String> problems = GraphValidator.problems(graph);
        if (!problems.isEmpty()) {
            Hearthstead.LOGGER.error("Conversation graph {} refused: {}", graph == null ? "?" : graph.id(), problems);
            return false;
        }
        CODE.put(graph.id(), graph);
        return true;
    }

    @Nullable
    public static ConversationGraph get(String id) {
        if (id == null) return null;
        ConversationGraph fromData = data.get(id);
        return fromData != null ? fromData : CODE.get(id);
    }

    public static int size() {
        return CODE.size() + data.size();
    }

    /** Server data reload listener for {@code data/<ns>/conversations/*.json}. */
    public static final class Loader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        public Loader() {
            super(GSON, "conversations");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<String, ConversationGraph> loaded = new ConcurrentHashMap<>();
            files.forEach((location, json) -> {
                String id = location.toString();
                try {
                    ConversationGraph graph = GraphJson.read(id, json.getAsJsonObject());
                    List<String> problems = GraphValidator.problems(graph);
                    if (problems.isEmpty()) loaded.put(id, graph);
                    else Hearthstead.LOGGER.error("Conversation graph {} refused: {}", id, problems);
                } catch (RuntimeException malformed) {
                    Hearthstead.LOGGER.error("Conversation graph {} is malformed", id, malformed);
                }
            });
            data = Map.copyOf(loaded);
            Hearthstead.LOGGER.info("Loaded {} conversation graph(s) from data, {} from code", loaded.size(), CODE.size());
        }
    }
}
