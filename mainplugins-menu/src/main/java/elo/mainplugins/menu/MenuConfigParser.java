package elo.mainplugins.menu;

import elo.mainplugins.menu.model.MenuButton;
import elo.mainplugins.menu.model.MenuConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Czyta menu.yml. Złe przyciski są pomijane z ostrzeżeniem - nigdy wyjątek. */
public final class MenuConfigParser {

    private MenuConfigParser() {}

    public static MenuConfig parse(ConfigurationSection root, Predicate<String> materialExists, Consumer<String> warn) {
        int size = root.getInt("size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            warn.accept("menu.yml size must be 9, 18, 27, 36, 45 or 54 - using 27.");
            size = 27;
        }
        String background = root.getString("background", MenuConfig.DEFAULT_BACKGROUND);
        if (!background.isEmpty() && !materialExists.test(background)) {
            warn.accept("menu.yml background: unknown material '" + background + "' - using " + MenuConfig.DEFAULT_BACKGROUND + ".");
            background = MenuConfig.DEFAULT_BACKGROUND;
        }
        List<MenuButton> buttons = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        List<?> raw = root.getList("buttons");
        if (raw != null) {
            for (int i = 0; i < raw.size(); i++) {
                String at = "menu.yml buttons[" + (i + 1) + "]";
                if (!(raw.get(i) instanceof Map<?, ?> m)) {
                    warn.accept(at + ": not a map - skipping button.");
                    continue;
                }
                if (!(m.get("slot") instanceof Number n) || n.intValue() < 0 || n.intValue() >= size) {
                    warn.accept(at + ": 'slot' must be 0-" + (size - 1) + " - skipping button.");
                    continue;
                }
                int slot = n.intValue();
                if (!used.add(slot)) {
                    warn.accept(at + ": slot " + slot + " is already taken - skipping button.");
                    continue;
                }
                String material = m.get("item") != null ? String.valueOf(m.get("item")) : "";
                if (!materialExists.test(material)) {
                    warn.accept(at + ": unknown item '" + material + "' - skipping button.");
                    continue;
                }
                String command = m.get("command") != null ? String.valueOf(m.get("command")).trim() : "";
                if (command.startsWith("/")) command = command.substring(1);
                List<String> lore = new ArrayList<>();
                if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(o == null ? "" : String.valueOf(o));
                String requires = m.get("requires") != null ? String.valueOf(m.get("requires")).trim() : "";
                buttons.add(new MenuButton(slot, material, m.get("name") != null ? String.valueOf(m.get("name")) : "",
                        List.copyOf(lore), command, requires));
            }
        }
        return new MenuConfig(size, background, List.copyOf(buttons));
    }
}
