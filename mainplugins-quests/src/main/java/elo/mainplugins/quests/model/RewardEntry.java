package elo.mainplugins.quests.model;

import org.bukkit.Material;

import java.util.List;

/**
 * Jedna nagroda za ukończenie questu - quest może mieć ich dowolnie wiele naraz w liście
 * {@code rewards} (patrz QuestDefinition), co zastępuje WSZYSTKIE dawne specjalne przypadki
 * po ID questu w QuestManager#wreczNagrode/wreczSkrzynkeKamienMilowy (1/4/6/9 -> narzędzie,
 * 8 -> tylko skrzynka, 40 -> dodatkowy Beacon, 18/20/30/40 -> cicha skrzynka milowa) - to
 * teraz zwykłe, dodatkowe wpisy na liście, żadnych hardkodowanych ID w kodzie.
 *
 * {@code silent} (domyślnie false na każdym wariancie) wyklucza dany wpis z auto-generowanej
 * etykiety nagrody (dawne "ciche" skrzynki milowe/bonusowy Beacon - widoczne w ekwipunku,
 * ale nie wymienione w tekście "Otrzymałeś: ...").
 */
public sealed interface RewardEntry {

    boolean silent();

    /** Zwykły wanilijski item. */
    record ItemReward(Material material, int amount, boolean silent) implements RewardEntry {}

    /** Item z rejestru custom-items.yml (mainplugins-core), wydany przez CustomItemService.create(id, amount). */
    record CustomItemReward(String id, int amount, boolean silent) implements RewardEntry {}

    /** Pieniądze (EconomyService.dodajKase). */
    record MoneyReward(double amount, boolean silent) implements RewardEntry {}

    /**
     * Skrzynka+klucz danego tieru (CrateService) - {@code fallback} wręczany zamiast, gdy
     * mainplugins-crates nie jest wgrany (generalizacja dawnego fallbacku, wcześniej tylko dla questu 8).
     */
    record CrateReward(int tier, List<RewardEntry> fallback, boolean silent) implements RewardEntry {}

    /** Prawdziwe ewoluujące narzędzie z mainplugins-tools (ToolsService.dajEwoluujacy*), nie placeholder ItemStack. */
    record ToolReward(ToolKind tool, boolean silent) implements RewardEntry {}

    /** Dopisuje id tytułu (patrz QuestContent.titles) do trwałego zbioru tytułów gracza. */
    record TitleReward(String titleId, boolean silent) implements RewardEntry {}
}
