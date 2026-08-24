package elo.mainplugins.shop.gui;

import java.util.List;

/**
 * Cały układ GUI sklepu wczytany z sklep-gui.yml (patrz ShopGuiLoader) - jeden niemutowalny
 * snapshot, podmieniany w całości przy /@reloadsklep razem z resztą configu.
 *
 * {@code categoryOrder} determinuje DWIE rzeczy: (1) w jakiej kolejności kopiować domyślne
 * pliki categories/*.yml przy pierwszym starcie, (2) i-ty CATEGORY_SLOT w mainMenu.layout()
 * -> categoryOrder.get(i). Kategoria spoza tej listy nadal się wczytuje/sprzedaje, po prostu
 * nie ma własnej ikony w menu głównym.
 */
public record ShopGuiContent(List<String> categoryOrder, ScreenLayout mainMenu, ScreenLayout categoryPage,
                              ScreenLayout buyPicker, ScreenLayout searchResults, ShopGuiStyle styl) {
}
