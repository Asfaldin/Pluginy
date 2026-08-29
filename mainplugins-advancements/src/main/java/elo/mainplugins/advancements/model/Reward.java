package elo.mainplugins.advancements.model;

import org.bukkit.Material;

/**
 * Jedna nagroda za odebranie osiągnięcia - osiągnięcie może mieć ich wiele naraz
 * (lista {@code rewards} w {@link AchievementDef}). Ten sam wzorzec co RewardEntry
 * w mainplugins-quests, ale węższy zestaw wariantów: bez ewoluujących narzędzi
 * i tytułów (te systemy nie mają publicznego settera w CoreAPI), za to z
 * {@link Command} jako uniwersalną furtką na wszystko inne.
 */
public sealed interface Reward {

    /** Pieniądze (EconomyService.dodajKase). */
    record Money(double amount) implements Reward {}

    /** Zwykły wanilijski item - upuszczany pod nogi, gdy ekwipunek pełny. */
    record Item(Material material, int amount) implements Reward {}

    /** Item z rejestru custom-items.yml (mainplugins-core), wydany przez CustomItemService.create(id, amount). */
    record CustomItem(String id, int amount) implements Reward {}

    /**
     * Tajemnicza Skrzynka danego tieru + uniwersalny klucz (CrateService).
     * Gdy mainplugins-crates nie jest wgrany - wpis jest po cichu pomijany.
     */
    record Crate(int tier) implements Reward {}

    /**
     * Komenda wykonywana z konsoli w chwili odbioru. {@code %gracz%} podmieniane
     * na nick. Do nagród, których nie obsługuje żaden z pozostałych wariantów
     * (np. {@code lp user %gracz% permission set ...}, {@code broadcast ...}).
     */
    record Command(String command) implements Reward {}
}
