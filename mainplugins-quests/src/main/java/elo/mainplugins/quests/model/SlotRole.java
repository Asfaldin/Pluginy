package elo.mainplugins.quests.model;

/**
 * Rola pojedynczego slotu w 54-slotowym GUI (menu kategorii albo strona questów danej
 * kategorii) - zastępuje dawne hardkodowane tablice SLOTY_WEZYK_C/SLOTY_KATEGORII_BOCZNYCH
 * i sztywne numery slotów 45/49/53 nawigacji. Slot spoza listy w configu = domyślnie FILLER.
 */
public enum SlotRole {
    /** Kolejny quest z listy kategorii (kolejność wpisów w page-layout = kolejność wizualna/odblokowania). */
    QUEST_SLOT,
    /** Kolejna kategoria z category-order (tylko main-menu.layout). */
    CATEGORY_SLOT,
    NAV_BACK,
    NAV_PREV,
    NAV_NEXT,
    /** Wypełniacz tła/przerwy - domyślny material (jeśli nie podano) to czarne szkło, jak dawne panelCzarny(). */
    FILLER
}
