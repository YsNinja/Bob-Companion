package com.quackcraft.quackingly.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Small, bounded action format returned by Bob's model. */
public record BobActionPlan(String speech, List<Action> actions) {
    public static BobActionPlan parse(String modelText) {
        String text = modelText == null ? "" : modelText.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return new BobActionPlan(text, List.of());

        try {
            JsonObject root = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
            String speech = root.has("say") && root.get("say").isJsonPrimitive()
                    ? root.get("say").getAsString().trim() : "";
            List<Action> actions = new ArrayList<>();
            JsonArray array = root.has("actions") && root.get("actions").isJsonArray()
                    ? root.getAsJsonArray("actions") : new JsonArray();
            for (JsonElement element : array) {
                if (actions.size() >= 6 || !element.isJsonObject()) break;
                JsonObject obj = element.getAsJsonObject();
                String type = string(obj, "type", "").toLowerCase(java.util.Locale.ROOT);
                if (!List.of("mine_block", "gather", "place_block", "build_structure", "attack", "trade", "follow", "stay", "come_here", "sleep", "eat", "give_items").contains(type)) continue;
                actions.add(new Action(type, integer(obj, "x"), integer(obj, "y"), integer(obj, "z"),
                        string(obj, "block", ""), string(obj, "pattern", ""), integer(obj, "size"), integer(obj, "height"),
                        integer(obj, "entity_id"), integer(obj, "offer"), integer(obj, "count"),
                        !obj.has("enabled") || obj.get("enabled").getAsBoolean()));
            }
            return new BobActionPlan(speech, List.copyOf(actions));
        } catch (RuntimeException invalidJson) {
            return new BobActionPlan(text, List.of());
        }
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject obj, String key) {
        try { return obj.has(key) ? obj.get(key).getAsInt() : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    public record Action(String type, int x, int y, int z, String block, String pattern,
                         int size, int height, int entityId, int offerIndex, int count, boolean enabled) {}
}
