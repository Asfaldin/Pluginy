package elo.mainplugins.advancements.model;

import elo.mainplugins.core.api.Rank;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;

/**
 * Skąd plugin wie, że dane osiągnięcie zostało zdobyte. Dwa światy naraz:
 * {@link Vanilla} po prostu nasłuchuje wbudowanego advancementu Minecrafta
 * (natywny "toast" pokazuje sam Minecraft), reszta wariantów to WŁASNE warunki
 * sprawdzane cyklicznie przez plugin (patrz CustomTriggerChecker) - Minecraft
 * o nich nie wie, więc powiadomienie robimy sami (patrz AchievementNotifier).
 *
 * sealed + record per wariant zamiast enum-typ + worek pól - dokładnie ten sam
 * wzorzec co Requirement/RewardEntry w mainplugins-quests: każdy wariant niesie
 * wyłącznie dane, które faktycznie go dotyczą.
 */
public sealed interface TriggerSource {

    /**
     * Powiązanie z wbudowanym advancementem po jego kluczu (np.
     * {@code minecraft:story/mine_stone}, {@code minecraft:nether/root}).
     * Nic nie sprawdzamy w pętli - łapiemy PlayerAdvancementDoneEvent.
     */
    record Vanilla(NamespacedKey key) implements TriggerSource {}

    /** Miej na koncie (EconomyService) co najmniej tyle złotówek. */
    record Balance(double amount) implements TriggerSource {}

    /** Miej rangę (RankService) równą lub wyższą niż podana. */
    record RankAtLeast(Rank rank) implements TriggerSource {}

    /** Przegraj na serwerze łącznie tyle godzin (Statistic.PLAY_ONE_MINUTE). */
    record Playtime(int hours) implements TriggerSource {}

    /**
     * Osiągnij próg wbudowanej statystyki Bukkita. {@code material}/{@code entity}
     * dotyczą tylko statystyk typowanych (np. MINE_BLOCK potrzebuje materiału,
     * KILL_ENTITY - typu moba); dla UNTYPED oba są null.
     */
    record StatisticThreshold(Statistic statistic, org.bukkit.Material material,
                              org.bukkit.entity.EntityType entity, int amount) implements TriggerSource {}

    /**
     * Ukończ łącznie tyle DOWOLNYCH wbudowanych advancementów (pomijając ukryte
     * przepisy z {@code minecraft:recipes/...}) - "meta" osiągnięcie za sam postęp
     * w drzewku advancementów.
     */
    record AdvancementCount(int amount) implements TriggerSource {}
}
