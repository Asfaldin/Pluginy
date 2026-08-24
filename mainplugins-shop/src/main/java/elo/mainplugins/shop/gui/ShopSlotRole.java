package elo.mainplugins.shop.gui;

/**
 * Rola pojedynczego slotu w 54/27-slotowym GUI sklepu (menu główne, strona kategorii,
 * ekran wyboru ilości, wyniki wyszukiwania) - zastępuje dawne hardkodowane tablice
 * SLOTY_SIATKI/SLOTY_MENU_KATEGORII/SLOTY_WYBORU i sloty-guziki rozrzucone jako literały.
 * Slot spoza listy w configu = domyślnie FILLER.
 */
public enum ShopSlotRole {
    /** Kolejna kategoria z category-order (tylko main-menu). */
    CATEGORY_SLOT,
    /** Kolejny item strony (kategoria/wyniki wyszukiwania). */
    ITEM_SLOT,
    /** Opcja ilości w ekranie kupna - jedyna rola z dodatkowym polem "amount". */
    AMOUNT_SLOT,
    NAV_BACK,
    NAV_PREV,
    NAV_NEXT,
    EXIT,
    SEARCH,
    SORT,
    /** Wypełniacz tła - domyślny material (jeśli nie podano) to szare szkło, jak dawne wypelnijTloSzare(). */
    FILLER
}
