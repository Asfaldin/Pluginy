package elo.mainplugins.quests.model;

/**
 * Dźwięki menu zadań (settings.sounds w quests.yml): klucze Minecrafta, np. entity.player.levelup.
 * complete = zadanie zdane, deny = czegoś brakuje / zablokowane. Pusty = bez dźwięku.
 */
public record QuestSounds(String complete, String deny) {

    public static final QuestSounds DEFAULT = new QuestSounds("entity.player.levelup", "entity.villager.no");
}
