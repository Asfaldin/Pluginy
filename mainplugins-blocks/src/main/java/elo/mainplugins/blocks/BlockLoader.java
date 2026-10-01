package elo.mainplugins.blocks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

/** Czyta blok z JSON-a Kreatora bloków. Zły plik = wyjątek z opisem (plugin pomija blok z ostrzeżeniem). */
public final class BlockLoader {

    private static final Set<String> TOOLS = Set.of("none", "pickaxe", "axe", "shovel", "hoe");
    private static final Set<String> SOUNDS = Set.of("stone", "wood", "metal", "glass", "grass", "gravel", "sand", "wool", "deepslate", "amethyst");

    private BlockLoader() {}

    public static BlockDef parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String id = root.get("id").getAsString();
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("nieprawidłowe id \"" + id + "\"");
        String name = root.has("name") ? root.get("name").getAsString() : id;
        if (!root.has("block")) throw new IllegalArgumentException("brak ustawień bloku (pole \"block\") - zapisz blok w aplikacji jeszcze raz");
        JsonObject b = root.getAsJsonObject("block");
        int state = b.get("state").getAsInt();
        if (state < 1 || state > BlockStates.MAX_STATE) throw new IllegalArgumentException("stan note blocka poza zakresem: " + state);
        double hardness = b.has("hardness") ? b.get("hardness").getAsDouble() : 1.5;
        String tool = b.has("tool") ? b.get("tool").getAsString() : "none";
        if (!TOOLS.contains(tool)) tool = "none";
        boolean requiresTool = b.has("requiresTool") && b.get("requiresTool").getAsBoolean() && !tool.equals("none");
        String sound = b.has("sound") ? b.get("sound").getAsString() : "stone";
        if (!SOUNDS.contains(sound)) sound = "stone";

        String dropType = "self";
        String dropItem = null;
        int min = 1, max = 1;
        if (b.has("drop") && b.get("drop").isJsonObject()) {
            JsonObject d = b.getAsJsonObject("drop");
            dropType = d.has("type") ? d.get("type").getAsString() : "self";
            if (dropType.equals("item")) {
                dropItem = d.has("item") ? d.get("item").getAsString() : null;
                if (dropItem == null || dropItem.isBlank()) dropType = "none";
                min = d.has("min") ? Math.max(1, d.get("min").getAsInt()) : 1;
                max = d.has("max") ? Math.max(1, d.get("max").getAsInt()) : min;
                if (max < min) {
                    int t = min;
                    min = max;
                    max = t;
                }
            } else if (!dropType.equals("none")) {
                dropType = "self";
            }
        }
        return new BlockDef(id, name, state, hardness, tool, requiresTool, dropType, dropItem, min, Math.min(64, max), sound);
    }
}
