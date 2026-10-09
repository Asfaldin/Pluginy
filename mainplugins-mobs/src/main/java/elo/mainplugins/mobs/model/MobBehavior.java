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
 * @param fixedFacing nigdy się nie obraca (ani po kliknięciu, ani w umiejętnościach) - np. scena z ławką w modelu,
 *                    która musi stać tam, gdzie mob został postawiony
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
                          List<Phase> phases, List<Skill> skills, boolean persistent, Npc npc, boolean fixedFacing) {

    /**
     * NPC: nieśmiertelny, nie walczy, reaguje na kliknięcie - kolejna linijka dialogu, a po ostatniej
     * (albo od razu, gdy dialogu brak) akcje onClick: sklep, questy, nagroda, komenda, teleport.
     *
     * @param homeRadius   0 = stoi w miejscu, więcej = spaceruje w tym promieniu od miejsca postawienia
     * @param dialogueMode chat | title
     * @param clickCooldown sekundy między kliknięciami jednego gracza
     * @param oncePerPlayer akcje (np. nagroda) tylko raz na gracza - zapamiętane na mobie, przeżywa restart
     * @param trades       oferty okna handlu (akcja open_trade) - jak u wieśniaka z gry
     */
    public record Npc(boolean enabled, boolean invulnerable, double homeRadius, List<String> dialogue, String dialogueMode,
                      List<Action> onClick, double clickCooldown, boolean oncePerPlayer, List<Trade> trades) {
        public static final Npc OFF = new Npc(false, false, 0, List.of(), "chat", List.of(), 1, false, List.of());
    }

    /** Oferta handlu: gracz daje buy x buyAmount, dostaje sell x sellAmount (id przedmiotów z gry, np. "minecraft:emerald"). */
    public record Trade(String buy, int buyAmount, String sell, int sellAmount) {}

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
     *                hurt (oberwał) | spawn | death | phase (wejście w fazę o numerze {@code phase}) |
     *                item (ktoś rzucił przedmiot {@code item} w zasięgu rangeMax - np. emerald dla wieśniaka) |
     *                trade (gracz coś kupił w oknie handlu tego moba i je zamknął) |
     *                signal (mob obok wysłał akcją signal sygnał o nazwie tej umiejętności; cel = nadawca)
     * @param phase   dla "phase": numer fazy (1 = pierwsza z listy); dla reszty: działa dopiero od tej fazy (0 = zawsze)
     * @param item    dla "item": przedmiot, np. "minecraft:emerald" (puste = dowolny); akcja take_item go podnosi
     */
    public record Skill(String name, String trigger, double cooldown, double rangeMin, double rangeMax, double chance,
                        double healthBelow, int phase, String animation, List<Action> actions, String item, List<Cond> conditions,
                        List<String> also) {
        public Skill(String name, String trigger, double cooldown, double rangeMin, double rangeMax, double chance,
                     double healthBelow, int phase, String animation, List<Action> actions, String item) {
            this(name, trigger, cooldown, rangeMin, rangeMax, chance, healthBelow, phase, animation, actions, item, List.of(), List.of());
        }

        /** Czy ten wyzwalacz uruchamia umiejętność (główny albo jeden z dodatkowych - "also": gdy podejdzie LUB gdy kliknięty). */
        public boolean on(String t) {
            return trigger.equals(t) || also.contains(t);
        }
    }

    /**
     * Akcja umiejętności: typ, chwila (s od startu), ustawienia i warunki ("if" - wszystkie muszą się zgadzać).
     * Wspólne ustawienia każdej akcji: on (na kim: target, self, attacker, nearest_player, random_player, players,
     * allies, mobs, everyone) z onRadius; repeat/every rozwija BehaviorLoader w kopie akcji.
     */
    public record Action(String type, double at, Params p, List<Cond> conditions) {
        public Action(String type, double at, Params p) {
            this(type, at, p, List.of());
        }
    }

    /**
     * Warunek umiejętności albo akcji. type: health_below/health_above (% życia moba), target_health_below/above,
     * distance_below/above (do celu), chance (%), has_target, target_is_player, day, night, raining, in_water,
     * on_ground, phase_at_least, players_nearby/allies_nearby (radius, value = ile co najmniej),
     * var (name, op, value), target_has_effect (effect). not = odwrotnie.
     */
    public record Cond(String type, boolean not, Params p) {}

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
