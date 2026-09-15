package elo.mainplugins.shop.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * shop.yml bez kategorii. menus: main-menu, category-page, buy-picker, search-results.
 * buttonMaterials: wygląd przycisków (teksty są w lang).
 */
public record ShopSettings(List<String> categoryOrder, Rounding rounding, DynamicSettings dynamic, boolean statsEnabled,
                           Map<String, MenuScreen> menus, Map<String, String> buttonMaterials) {

    public static final List<String> SCREENS = List.of("main-menu", "category-page", "buy-picker", "search-results");
    public static final List<String> BUTTONS = List.of("search", "exit", "back", "prev", "next", "sort", "sort-sell", "picker-back");

    public MenuScreen menu(String screen) {
        return menus.get(screen);
    }

    public String button(String id) {
        return buttonMaterials.get(id);
    }

    public static Map<String, String> defaultButtons() {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("search", "OAK_SIGN");
        b.put("exit", "BARRIER");
        b.put("back", "COMPASS");
        b.put("prev", "SPECTRAL_ARROW");
        b.put("next", "SPECTRAL_ARROW");
        b.put("sort", "HOPPER");
        b.put("sort-sell", "GOLD_INGOT");
        b.put("picker-back", "ARROW");
        return b;
    }

    /** Siatka 7x4 (pola 10-16, 19-25, 28-34, 37-43) - kategoria i wyniki wyszukiwania. */
    private static List<SlotEntry> itemGrid() {
        List<SlotEntry> out = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) out.add(new SlotEntry(row * 9 + col, SlotRole.ITEM_SLOT, null, 0));
        }
        return out;
    }

    public static Map<String, MenuScreen> defaultMenus() {
        Map<String, MenuScreen> m = new LinkedHashMap<>();

        List<SlotEntry> main = new ArrayList<>();
        main.add(new SlotEntry(4, SlotRole.SEARCH, null, 0));
        main.add(new SlotEntry(49, SlotRole.EXIT, null, 0));
        for (int s = 19; s <= 25; s++) main.add(new SlotEntry(s, SlotRole.CATEGORY_SLOT, null, 0));
        for (int s = 28; s <= 34; s++) main.add(new SlotEntry(s, SlotRole.CATEGORY_SLOT, null, 0));
        m.put("main-menu", new MenuScreen(54, List.copyOf(main)));

        List<SlotEntry> cat = new ArrayList<>();
        cat.add(new SlotEntry(4, SlotRole.SORT, null, 0));
        cat.addAll(itemGrid());
        cat.add(new SlotEntry(45, SlotRole.NAV_PREV, null, 0));
        cat.add(new SlotEntry(48, SlotRole.NAV_BACK, null, 0));
        cat.add(new SlotEntry(50, SlotRole.EXIT, null, 0));
        cat.add(new SlotEntry(53, SlotRole.NAV_NEXT, null, 0));
        m.put("category-page", new MenuScreen(54, List.copyOf(cat)));

        List<SlotEntry> picker = new ArrayList<>();
        int[] amounts = {1, 8, 16, 32, 64};
        for (int i = 0; i < amounts.length; i++) picker.add(new SlotEntry(11 + i, SlotRole.AMOUNT_SLOT, null, amounts[i]));
        picker.add(new SlotEntry(22, SlotRole.NAV_BACK, null, 0));
        m.put("buy-picker", new MenuScreen(27, List.copyOf(picker)));

        List<SlotEntry> search = new ArrayList<>(itemGrid());
        search.add(new SlotEntry(48, SlotRole.NAV_BACK, null, 0));
        m.put("search-results", new MenuScreen(54, List.copyOf(search)));
        return m;
    }

    public static ShopSettings defaults() {
        return new ShopSettings(List.of(), Rounding.CENTS, DynamicSettings.defaults(), false,
                Map.copyOf(defaultMenus()), Map.copyOf(defaultButtons()));
    }
}
