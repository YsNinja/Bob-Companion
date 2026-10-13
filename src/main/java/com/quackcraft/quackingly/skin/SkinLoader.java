package com.quackcraft.quackingly.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.util.HttpUtils;

import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;

/**
 * Fetches a Minecraft player skin from Mojang's session API.
 *
 * Flow:
 *   1. GET https://api.mojang.com/users/profiles/minecraft/<username>
 *      -> { "id":"<uuid without dashes>", "name":"<username>" }
 *   2. GET https://sessionserver.mojang.com/session/minecraft/profile/<uuid>?unsigned=false
 *      -> { "properties":[ { "name":"textures", "value":"<base64 JSON>" } ] }
 *   3. Decode base64 value -> { "textures":{ "SKIN":{ "url":"https://textures.minecraft.net/texture/<id>" } } }
 *   4. Return the skin URL (and we let MC's PlayerSkinProvider cache & apply it).
 *
 * This is the same approach SkinsRestorer uses. Mojang rate-limits this endpoint
 * (600 req / 10 min), so we cache results locally.
 */
public final class SkinLoader {

    public static class FetchedSkin {
        public final String username;
        public final UUID uuid;
        public final String textureUrl;
        public final String textureBase64;   // raw base64 from Mojang, can be applied directly
        public final String signatureBase64; // may be null if unsigned

        public FetchedSkin(String username, UUID uuid, String textureUrl,
                           String textureBase64, String signatureBase64) {
            this.username = username;
            this.uuid = uuid;
            this.textureUrl = textureUrl;
            this.textureBase64 = textureBase64;
            this.signatureBase64 = signatureBase64;
        }
    }

    private SkinLoader() {}

    public static FetchedSkin fetch(String username) throws Exception {
        // 1. UUID lookup
        HttpResponse<String> r1 = HttpUtils.get(
                "https://api.mojang.com/users/profiles/minecraft/" + username, null);
        if (r1.statusCode() == 404 || r1.body() == null || r1.body().isBlank()) {
            throw new IllegalArgumentException("No Minecraft account named '" + username + "'");
        }
        if (r1.statusCode() >= 400) {
            throw new RuntimeException("Mojang profile lookup failed: HTTP " + r1.statusCode());
        }
        JsonObject profile = JsonParser.parseString(r1.body()).getAsJsonObject();
        String idNoDashes = profile.get("id").getAsString();
        UUID uuid = parseUuidNoDashes(idNoDashes);

        // 2. Session profile lookup (with signature)
        HttpResponse<String> r2 = HttpUtils.get(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + idNoDashes
                        + "?unsigned=false", null);
        if (r2.statusCode() >= 400) {
            throw new RuntimeException("Mojang session lookup failed: HTTP " + r2.statusCode());
        }
        JsonObject session = JsonParser.parseString(r2.body()).getAsJsonObject();
        JsonArray props = session.getAsJsonArray("properties");
        String valueB64 = null, sigB64 = null;
        for (int i = 0; i < props.size(); i++) {
            JsonObject p = props.get(i).getAsJsonObject();
            if ("textures".equals(p.get("name").getAsString())) {
                valueB64 = p.get("value").getAsString();
                if (p.has("signature")) sigB64 = p.get("signature").getAsString();
                break;
            }
        }
        if (valueB64 == null) throw new RuntimeException("No textures property in Mojang profile");

        // 3. Decode the base64 to get the skin URL (for debug/display)
        String decoded = new String(Base64.getDecoder().decode(valueB64));
        JsonObject tex = JsonParser.parseString(decoded).getAsJsonObject()
                .getAsJsonObject("textures");
        String skinUrl = tex.has("SKIN")
                ? tex.getAsJsonObject("SKIN").get("url").getAsString() : null;

        return new FetchedSkin(username, uuid, skinUrl, valueB64, sigB64);
    }

    public static UUID parseUuidNoDashes(String id) {
        if (id == null || id.length() != 32) {
            throw new IllegalArgumentException("Expected 32-char UUID, got length " + (id == null ? "null" : id.length()));
        }
        String dashed = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16)
                + "-" + id.substring(16, 20) + "-" + id.substring(20);
        return UUID.fromString(dashed);
    }
}
