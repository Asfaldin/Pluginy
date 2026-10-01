package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobBehavior;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorLoaderTest {

    @Test
    void domyslneJakZombie() {
        MobBehavior b = BehaviorLoader.defaults("Golem");
        assertEquals(20, b.health());
        assertEquals(3, b.damage());
        assertEquals("walk", b.movement());
        assertEquals("hostile", b.attitude());
        assertEquals("melee", b.attack());
        assertEquals("Golem", b.name());
        assertTrue(b.skills().isEmpty());
        assertTrue(b.persistent());
    }

    @Test
    void zachowanieZAplikacji() {
        String json = """
                {"id":"wyvern","name":"Wyvern","bones":[],"animations":[],
                 "behavior":{"health":300,"damage":9,"movement":"fly","attitude":"neutral","attack":"ranged",
                   "ranged":{"kind":"small_fireball","range":20,"cooldown":3},
                   "name":"Ancient wyvern","bossBar":{"enabled":true,"color":"purple"},
                   "drops":[{"item":"minecraft:diamond","min":2,"max":1,"chance":0.5},{"item":""}],
                   "weakPoints":{"head":2.5},
                   "phases":[{"below":0.25,"damageMultiplier":2},{"below":0.6,"variant":"rage"}],
                   "skills":[{"name":"breath","trigger":"combat","cooldown":8,"rangeMax":12,"animation":"roar",
                     "actions":[{"type":"particles","at":0.8,"particle":"flame","count":40},{"type":"damage","at":0.2,"radius":4,"amount":6}]},
                     {"name":"bad","trigger":"nonsense"}]}}
                """;
        MobBehavior b = BehaviorLoader.fromJson(BehaviorLoader.behaviorOf(json), "Wyvern");
        assertEquals(300, b.health());
        assertEquals("fly", b.movement());
        assertEquals("neutral", b.attitude());
        assertEquals("ranged", b.attack());
        assertEquals(20, b.ranged().num("range", 0));
        assertEquals("small_fireball", b.ranged().str("kind", ""));
        assertEquals("Ancient wyvern", b.name());
        assertTrue(b.bossBar().enabled());
        assertEquals("purple", b.bossBar().color());
        assertEquals(1, b.drops().size());
        assertEquals(2, b.drops().get(0).min());
        assertEquals(2, b.drops().get(0).max());
        assertEquals(2.5, b.weakPoints().get("head"));
        // Fazy od najwyższego progu.
        assertEquals(0.6, b.phases().get(0).below());
        assertEquals("rage", b.phases().get(0).variant());
        assertEquals(2, b.skills().size());
        MobBehavior.Skill breath = b.skills().get(0);
        assertEquals("roar", breath.animation());
        // Akcje po czasie.
        assertEquals("damage", breath.actions().get(0).type());
        assertEquals(40, breath.actions().get(1).p().num("count", 0));
        assertTrue(SkillRunner.exclusive(breath));
        // Nieznany wyzwalacz -> combat.
        assertEquals("combat", b.skills().get(1).trigger());
        assertFalse(SkillRunner.exclusive(b.skills().get(1)));
    }

    @Test
    void zlePliki() {
        assertNull(BehaviorLoader.behaviorOf("{\"id\":\"x\"}"));
        MobBehavior b = BehaviorLoader.fromJson(com.google.gson.JsonParser.parseString(
                "{\"health\":-5,\"speed\":99,\"movement\":\"teleport\",\"nameplate\":\"x\"}").getAsJsonObject(), "x");
        assertEquals(1, b.health());
        assertEquals(2, b.speed());
        assertEquals("walk", b.movement());
        assertEquals("near", b.nameplate());
    }

    @Test
    void staryConfigPrzepisanyNaUmiejetnosci() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                zycie: 150
                szybkosc: 0.33
                obrazenia: 9
                slabe-punkty:
                  head: 2.0
                faza-2:
                  ponizej-zycia: 0.5
                  wariant: burza
                  obrazenia-mnoznik: 1.5
                umiejetnosci:
                  skok:
                    typ: skok-na-gracza
                    animacja: jump
                    co-ile-sekund: 7
                    zasieg-od: 4
                    zasieg-do: 12
                    obrazenia: 7
                    wybicie: 0.3
                    ladowanie: 1.1
                  wielki-skok:
                    typ: wielki-skok
                    animacja: skok
                    animacja-upadku: upadek
                    wybicie: 0.35
                    czas-lotu: 1.5
                    wysokosc: 7
                  nieznana:
                    typ: cos
                """);
        MobBehavior b = BehaviorLoader.fromLegacy(y, "Drzewiec");
        assertEquals(150, b.health());
        assertEquals(0.33, b.speed());
        assertEquals(2.0, b.weakPoints().get("head"));
        assertEquals("burza", b.phases().get(0).variant());
        assertFalse(b.persistent());
        assertEquals(2, b.skills().size());
        MobBehavior.Skill leap = b.skills().get(0);
        assertEquals(7, leap.cooldown());
        assertEquals(4, leap.rangeMin());
        MobBehavior.Action l = leap.actions().stream().filter(a -> a.type().equals("leap")).findFirst().orElseThrow();
        assertEquals(0.3, l.at(), 1e-9);
        assertEquals(0.8, l.p().num("duration", 0), 1e-9);
        MobBehavior.Action dmg = leap.actions().stream().filter(a -> a.type().equals("damage")).findFirst().orElseThrow();
        assertEquals(1.1, dmg.at(), 1e-9);
        assertEquals(7, dmg.p().num("amount", 0));
        MobBehavior.Skill big = b.skills().get(1);
        MobBehavior.Action stun = big.actions().stream().filter(a -> a.type().equals("stun")).findFirst().orElseThrow();
        assertEquals(1.85, stun.at(), 1e-9);
        assertEquals("upadek", stun.p().str("animation", ""));
    }

    @Test
    void mobsYmlZAplikacji() throws Exception {
        // Tak zapisuje aplikacja (lib/mobsYaml.ts, js-yaml).
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                mobs:
                  wyvern:
                    health: 250
                    movement: fly
                    ranged:
                      kind: small_fireball
                      range: 20
                    bossBar:
                      enabled: true
                      color: purple
                    drops:
                      - item: minecraft:diamond
                        min: 1
                        max: 3
                        chance: 0.5
                    weakPoints:
                      head: 2
                    skills:
                      - id: skill_abc
                        name: roar
                        trigger: combat
                        cooldown: 15
                        animation: roar
                        actions:
                          - type: sound
                            at: 0
                            sound: entity.ravager.roar
                          - type: potion
                            at: 0.2
                            target: area
                            fire: true
                """);
        MobBehavior b = BehaviorLoader.fromJson(BehaviorLoader.toJson(y.getConfigurationSection("mobs.wyvern")), "Wyvern");
        assertEquals(250, b.health());
        assertEquals("fly", b.movement());
        assertEquals("small_fireball", b.ranged().str("kind", ""));
        assertTrue(b.bossBar().enabled());
        assertEquals(0.5, b.drops().get(0).chance());
        assertEquals(2.0, b.weakPoints().get("head"));
        MobBehavior.Skill s = b.skills().get(0);
        assertEquals("roar", s.name());
        assertEquals(15, s.cooldown());
        assertEquals("entity.ravager.roar", s.actions().get(0).p().str("sound", ""));
        assertEquals("area", s.actions().get(1).p().str("target", ""));
        assertTrue(s.actions().get(1).p().bool("fire", false));
    }

    @Test
    void npc() {
        MobBehavior b = BehaviorLoader.fromJson(com.google.gson.JsonParser.parseString("""
                {"npc":{"enabled":true,"homeRadius":5,"dialogue":["Hi {player}","Bye"],"dialogueMode":"title",
                 "onClick":[{"type":"reward","at":0,"reward":"money","value":100},{"type":"open_shop","at":0,"category":"food"}],"oncePerPlayer":true}}
                """).getAsJsonObject(), "Kupiec");
        assertTrue(b.npc().enabled());
        assertTrue(b.npc().invulnerable());
        assertEquals(5, b.npc().homeRadius());
        assertEquals("title", b.npc().dialogueMode());
        assertEquals(2, b.npc().dialogue().size());
        assertEquals("food", b.npc().onClick().get(1).p().str("category", ""));
        assertTrue(b.npc().oncePerPlayer());
        assertFalse(BehaviorLoader.defaults("x").npc().enabled());
    }
}
