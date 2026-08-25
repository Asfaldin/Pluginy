package elo.mainplugins.quests.model;

import org.bukkit.Material;

import java.util.List;

/**
 * Wymóg ukończenia questu - sealed zamiast dawnego TypWymogu+pól-które-mają-sens-tylko-dla-
 * -niektórych-wariantów (np. `prog` służył i za próg monet, i za minimalny poziom narzędzia).
 * Każdy wariant niesie WYŁĄCZNIE dane, które faktycznie go dotyczą. TOOL_LEVEL zastępuje trzy
 * dawne osobne warianty (POZIOM_KILOFA/SIEKIERY/MIECZA) jednym + polem {@link ToolKind}.
 */
public sealed interface Requirement {

    /** Przynieś N sztuk jednego lub kilku materiałów naraz - zabierane po zdaniu (dawne PRZEDMIOT). */
    record ItemRequirement(List<MaterialRequirement> materials) implements Requirement {}

    /** Brak wymogu - kliknięcie od razu zdaje quest (dawne DARMOWY). */
    record FreeRequirement() implements Requirement {}

    /** Zapłać w monetach (EconomyService) - próg to koszt, nie próg posiadania. */
    record MoneyRequirement(double amount) implements Requirement {}

    /** Posiadaj dany wanilijski Material - NIE zabiera itemu po zdaniu (dawne NARZEDZIE). */
    record ToolPossessRequirement(Material material) implements Requirement {}

    /** Wbij danemu narzędziu minimalny poziom (ToolsService.poziom*) - tylko PICKAXE/AXE/SWORD mają dziś taką metodę. */
    record ToolLevelRequirement(ToolKind tool, int level) implements Requirement {}

    /** Masz choć jedną aktywną ofertę na Targu graczy (MarketService.maAktywnaOferte) - nic nie zabiera. */
    record MarketOfferRequirement() implements Requirement {}

    /**
     * Kup ŁĄCZNIE (licznik narastający, nie stan bieżący) tyle sztuk w sklepie (mainplugins-shop) -
     * customId w material() rozróżnia custom-tagowany item od zwykłego Materiału, tak jak w
     * ItemRequirement. Licznik zliczany od momentu wczytania questu (QuestManager#zarejestrujZakup) -
     * NIE zabiera nic po zdaniu, przedmioty zostają u gracza.
     */
    record BuyItemRequirement(MaterialRequirement material) implements Requirement {}

    /** Jak {@link BuyItemRequirement}, ale sprzedaż w sklepie (skup - /sprzedaj, /sprzedajwszystko, GUI). */
    record SellItemRequirement(MaterialRequirement material) implements Requirement {}

    /**
     * Wystaw ŁĄCZNIE tyle ofert na Targu graczy (licznik ZDARZEŃ wystawienia - QuestManager#
     * zarejestrujWystawienieNaTarg), NIEZALEŻNY od {@link MarketOfferRequirement} (ten sprawdza
     * "czy masz TERAZ aktywną ofertę" - tu liczy się historia, nawet jeśli oferty już sprzedane/wycofane).
     */
    record MarketListingsRequirement(int amount) implements Requirement {}
}
