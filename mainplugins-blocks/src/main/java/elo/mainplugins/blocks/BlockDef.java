package elo.mainplugins.blocks;

/**
 * Blok z Kreatora bloków (assets/mainplugins/blocks/&lt;id&gt;.json w paczce, pole "block").
 *
 * @param hardness     jak w grze (kamień 1.5); 0 = od razu, mniej niż 0 = niezniszczalny
 * @param tool         none | pickaxe | axe | shovel | hoe
 * @param dropType     self | item | none
 * @param dropItem     id przedmiotu z gry dla dropType=item (np. minecraft:diamond)
 * @param sound        zestaw dźwięków: stone, wood, metal, glass, grass, gravel, sand, wool, deepslate, amethyst
 */
public record BlockDef(String id, String name, int state, double hardness, String tool, boolean requiresTool,
                       String dropType, String dropItem, int dropMin, int dropMax, String sound) {

    public boolean unbreakable() {
        return hardness < 0;
    }
}
