package elo.mainplugins.mobs.model;

import java.util.List;
import java.util.Map;

/**
 * Zachowanie moba w grze - pole "behavior" pliku moba z Kreatora (zakładka Behavior w aplikacji),
 * a dla starych mobów przepisane z config.yml (patrz BehaviorLoader.fromLegacy).
 *
 * @param movement  walk | fly | swim | stationary
 * @param attitude  hostile (atakuje graczy) | neutral (dopiero gdy ktoś go uderzy) | passive (ucieka)
 * @param attack    melee | ranged | none
 * @param nameplate always | near | never - nazwa nad mobem
 */
public record MobBehavior(double health, double damage, double speed, double armor, double knockbackResistance,
                          double followRange, double attackCooldown,
                          String movement, String attitude, String attack, Params ranged,
                          String name, String nameplate, boolean healthBar, BossBar bossBar,
                          int xp, List<Drop> drops,
                          String ambientSound, String hurtSound, String deathSound,
                          String attackSound, String shootSound, String spawnSound, String stepSound, String talkSound,
                          Map<String, Double> weakPoints,
                          boolean look, boolean feet, boolean springs, boolean partHitboxes, boolean shatter,
                          List<String> randomAnimations, double randomInterval, List<String> backgroundAnimations,
                          List<Phase> phases, List<Skill> skills, boolean persistent, Npc npc) {

    /**
     * NPC: nieśmiertelny, nie walczy, reaguje na kliknięcie - kolejna linijka dialogu, a po ostatniej
     * (albo od razu, gdy dialogu brak) akcje onClick: sklep, questy, nagroda, komenda, teleport.
     *
     * @param homeRadius   0 = stoi w miejscu, więcej = spaceruje w tym promieniu od miejsca postawienia
     * @param dialogueMode chat | title
     * @param clickCooldown sekundy między kliknięciami jednego gracza
     * @param oncePerPlayer akcje (np. nagroda) tylko raz na gracza - zapamiętane na mobie, przeżywa restart
     */
    public record Npc(boolean enabled, boolean invulnerable, double homeRadius, List<String> dialogue, String dialogueMode,
                      List<Action> onClick, double clickCooldown, boolean oncePerPlayer) {
        public static final Npc OFF = new Npc(false, false, 0, List.of(), "chat", List.of(), 1, false);
    }

    /** color: pink blue red green yellow purple white; style: progress notched_6 notched_10 notched_12 notched_20. */
    public record BossBar(boolean enabled, String color, String style, double range) {}

    /** item: "minecraft:feather" albo "block:&lt;id&gt;" (Kreator bloków); chance 0-1. */
    public record Drop(String item, int min, int max, double chance) {}

    /** Poniżej części życia (below 0-1): animacja wejścia, wygląd (wariant), mnożnik obrażeń, dźwięk. */
    public record Phase(double below, String animation, String variant, double damageMultiplier, String sound) {}

    /**
     * Umiejętność: kiedy (trigger), jak często (cooldown, s), pod jakim warunkiem (odległość od celu,
     * życie poniżej, szansa, od której fazy) i co robi (akcje w czasie od startu, animacja gra od 0).
     *
     * @param trigger combat (co cooldown, gdy ma cel) | timer (co cooldown, zawsze) | hit (trafił cel) |
     *                hurt (oberwał) | spawn | death | phase (wejście w fazę o numerze {@code phase})
     * @param phase   dla "phase": numer fazy (1 = pierwsza z listy); dla reszty: działa dopiero od tej fazy (0 = zawsze)
     */
    public record Skill(String name, String trigger, double cooldown, double rangeMin, double rangeMax, double chance,
                        double healthBelow, int phase, String animation, List<Action> actions) {}

    /** Akcja umiejętności: typ, chwila (s od startu) i jej ustawienia. */
    public record Action(String type, double at, Params p) {}

    /** Ustawienia akcji / ataku z dystansu - luźna mapa, każdy typ czyta swoje klucze z wartością domyślną. */
    public record Params(Map<String, Object> values) {
        public static final Params EMPTY = new Params(Map.of());

        public double num(String key, double def) {
            Object v = values.get(key);
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    return def;
                }
            }
            return def;
        }

        public String str(String key, String def) {
            Object v = values.get(key);
            return v == null ? def : String.valueOf(v);
        }

        public boolean bool(String key, boolean def) {
            Object v = values.get(key);
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) return Boolean.parseBoolean(s);
            return def;
        }
    }
}
