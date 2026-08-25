package elo.mainplugins.tools.evolving;

/**
 * Biblioteka "hooków" efektów dostępnych z YAML (ewoluujace-narzedzia.yml) - każdy typ to
 * ZAIMPLEMENTOWANY w Javie (EvolvingToolManager) mechanizm, który config parametryzuje
 * (szansa/kwota/cząsteczka/dźwięk/mikstura), zamiast dawać dowolny kod. Dodanie NOWEGO
 * rodzaju zachowania wymaga dopisania go tutaj + w EvolvingToolManager - dobranie
 * istniejącego typu do nowego narzędzia to już czysty config.
 */
public enum EffectType {
    /** Szansa na zdublowanie dropu wykopanego/zebranego bloku (x2) - PICKAXE/AXE/HOE/SHOVEL. */
    DUPLIKUJ_DROP,
    /** Stały efekt mikstury (np. Pośpiech) trzymany cały czas, gdy narzędzie w ręce. */
    AURA_MIKSTURY,
    /** Szansa na bonusową wypłatę pieniędzy przy trigger evencie (kopanie/atak). */
    BONUS_PIENIADZE,
    /** Szansa na bonusowy orb doświadczenia przy trigger evencie. */
    BONUS_XP,
    /** Przyciąga do gracza pobliskie dropy po trigger evencie. */
    MAGNES,
    /** Stały bonus obrażeń w walce (AttributeModifier) - głównie SWORD. */
    PVP_BONUS_OBRAZENIA,
    /** Narzędzie nigdy nie traci wytrzymałości (PlayerItemDamageEvent#setCancelled). */
    NIENISZCZALNY,
    /** Własny odpowiednik Silk Touch (patrz CustomItemKeys#SPECJALNY_SILK_TOUCH) - zbiera cały custom-blok generatora zamiast zwykłego dropu. */
    SPECJALNY_SILK_TOUCH,
    /** Czysto kosmetyczna cząsteczka/dźwięk przy trigger evencie (bez żadnego mechanicznego efektu). */
    CZASTKI_PRZY_TRIGGERZE,
    /** Szansa na skruszenie dodatkowych SĄSIEDNICH bloków tego samego Materiału naraz (kwota = ile maks., twardy limit 6) - PICKAXE/AXE/HOE/SHOVEL. */
    OBSZAR_KRUSZENIA,
    /** "Żyła górnicza" - szansa na skruszenie do kwota (twardy limit 32) POŁĄCZONYCH bloków tego samego Materiału (flood-fill, jak vein miner) - PICKAXE/AXE/HOE/SHOVEL. */
    ZYLA_GORNICZA,
    /** Szansa, że drop z TEGO konkretnego wykopania trafia prosto do ekwipunku, bez fizycznego itemu na ziemi. */
    TELEKINEZA,
    /** Szansa na DODATKOWY przedmiot z tabeli (material LUB custom-id z CustomItemService) - najbardziej uniwersalny hook, pod dowolną "mega custom" nagrodę (klucz do skrzyni, trofeum, cokolwiek zarejestrowane). */
    BONUS_PRZEDMIOT,
    /** Jak BONUS_PIENIADZE, ale z osobną, bardziej dramatyczną oprawą (dłuższy dźwięk/tytuł) - pod rzadkie, duże jednorazowe wypłaty. */
    JACKPOT,
    /** SWORD - szansa na natychmiastowy DRUGI cios w to samo trafione stworzenie (ta sama obrażenia co pierwszy cios). */
    PODWOJNY_ATAK,
    /** SWORD - szansa na nałożenie efektu mikstury (mikstura/poziomMikstury) na TRAFIONE stworzenie, czasem trwania kwota (w tickach). */
    DEBUFF_PRZECIWNIKA,
    /** Szansa na odbicie kwota % otrzymanych obrażeń z powrotem na atakującego - działa gdy GRACZ trzymający narzędzie jest ofiarą, niezależnie od kategorii. */
    ODBICIE_OBRAZEN,
    /** Szansa na uleczenie gracza o kwota HP przy triggerze. */
    LECZENIE,
    /** Szansa na przywrócenie kwota punktów nasycenia/głodu przy triggerze. */
    SYCENIE,
    /** Rzadka szansa na uderzenie piorunem w miejsce triggera (blok/przeciwnik) - realne obrażenia, jeśli trafia istotę. */
    PIORUN
}
