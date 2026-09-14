package elo.mainplugins.quests;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.quests.model.After;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.ItemRef;
import elo.mainplugins.quests.model.QuestConfig;
import elo.mainplugins.quests.model.QuestDef;
import elo.mainplugins.quests.model.QuestSettings;
import elo.mainplugins.quests.model.Requirement;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Czyta quests.yml. Złe wpisy są pomijane albo poprawiane z ostrzeżeniem - nigdy wyjątek. */
public final class QuestConfigParser {

    private QuestConfigParser() {}

    public static QuestConfig parse(ConfigurationSection root,
                                    BiFunction<List<?>, String, List<Reward>> rewards,
                                    Predicate<String> materialExists,
                                    Consumer<String> warn) {
        QuestSettings settings = parseSettings(root.getConfigurationSection("settings"), materialExists, warn);
        List<SlotEntry> mainMenu = parseLayout(root.getList("main-menu.layout"), "quests.yml main-menu.layout", materialExists, warn);

        Map<String, String> titles = new LinkedHashMap<>();
        ConfigurationSection t = root.getConfigurationSection("titles");
        if (t != null) for (String id : t.getKeys(false)) titles.put(id, t.getString(id, ""));

        Map<String, CategoryDef> categories = new LinkedHashMap<>();
        ConfigurationSection cats = root.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                String where = "quests.yml categories." + id;
                ConfigurationSection s = cats.getConfigurationSection(id);
                if (s == null) {
                    warn.accept(where + ": not a section - skipping category.");
                    continue;
                }
                categories.put(id, parseCategory(id, s, where, rewards, materialExists, warn));
            }
        }
        // after wskazuje na inną kategorię - sprawdzane, gdy wszystkie są już wczytane
        for (CategoryDef c : List.copyOf(categories.values())) {
            if (c.after() != null && !categories.containsKey(c.after().category())) {
                warn.accept("quests.yml categories." + c.id() + ".after: unknown category '" + c.after().category() + "' - ignoring it.");
                categories.put(c.id(), c.withAfter(null));
            }
        }

        List<String> order = new ArrayList<>();
        for (String id : root.getStringList("category-order")) {
            if (!categories.containsKey(id)) warn.accept("quests.yml category-order: unknown category '" + id + "' - ignoring it.");
            else if (!order.contains(id)) order.add(id);
        }
        for (String id : categories.keySet()) {
            if (!order.contains(id)) warn.accept("quests.yml categories." + id + ": not in category-order - it will not show in the menu.");
        }
        return new QuestConfig(settings, List.copyOf(mainMenu), List.copyOf(order),
                Collections.unmodifiableMap(titles), Collections.unmodifiableMap(categories));
    }

    private static CategoryDef parseCategory(String id, ConfigurationSection s, String where,
                                             BiFunction<List<?>, String, List<Reward>> rewards,
                                             Predicate<String> materialExists, Consumer<String> warn) {
        ItemRef icon = validItem(ItemRef.from(s.get("icon")), materialExists);
        if (icon == null) {
            warn.accept(where + ": missing or unknown 'icon' - using BOOK.");
            icon = new ItemRef("BOOK", null, 1);
        }
        After after = null;
        ConfigurationSection a = s.getConfigurationSection("after");
        if (a != null) {
            String cat = a.getString("category");
            int quest = a.getInt("quest", -1);
            if (cat == null || quest < 1) warn.accept(where + ".after: needs 'category' and 'quest' - ignoring it.");
            else after = new After(cat, quest);
        }
        String unlock = s.getString("requires-unlock");
        unlock = unlock == null || unlock.isBlank() ? null : unlock.trim().toLowerCase(Locale.ROOT);

        List<SlotEntry> layout = parseLayout(s.getList("page-layout"), where + ".page-layout", materialExists, warn);
        List<QuestDef> quests = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        List<?> raw = s.getList("quests", List.of());
        for (int i = 0; i < raw.size(); i++) {
            String at = where + ".quests[" + (i + 1) + "]";
            QuestDef q = parseQuest(raw.get(i), at, rewards, materialExists, warn);
            if (q == null) continue;
            if (!ids.add(q.id())) {
                warn.accept(at + ": quest id " + q.id() + " is used twice in this category - skipping.");
                continue;
            }
            quests.add(q);
        }
        return new CategoryDef(id, s.getString("name", id), icon, s.getString("description", ""),
                // main-path: stara nazwa z pierwszej wersji - dziś znaczy tylko blask na ikonce
                s.getBoolean("glow", s.getBoolean("main-path")), s.getBoolean("sequential"), after, unlock,
                List.copyOf(layout), List.copyOf(quests));
    }

    private static QuestDef parseQuest(Object raw, String at, BiFunction<List<?>, String, List<Reward>> rewards,
                                       Predicate<String> materialExists, Consumer<String> warn) {
        if (!(raw instanceof Map<?, ?> m)) {
            warn.accept(at + ": not a map - skipping quest.");
            return null;
        }
        int id = m.get("id") instanceof Number n ? n.intValue() : -1;
        if (id < 1) {
            warn.accept(at + ": 'id' must be a whole number 1 or more - skipping quest.");
            return null;
        }
        Requirement req = parseRequirement(m.get("requirement"), at + ".requirement", materialExists, warn);
        if (req == null) return null;
        List<Reward> list = m.get("rewards") instanceof List<?> l ? rewards.apply(l, at + ".rewards") : List.of();
        String title = m.get("title") != null ? String.valueOf(m.get("title")) : "Quest " + id;
        List<String> desc = new ArrayList<>();
        if (m.get("description") instanceof List<?> d) for (Object o : d) desc.add(o == null ? "" : String.valueOf(o));
        Object label = m.get("reward-label");
        String rewardLabel = label == null || String.valueOf(label).isBlank() ? null : String.valueOf(label);
        return new QuestDef(id, title, List.copyOf(desc), req, List.copyOf(list), rewardLabel);
    }

    private static Requirement parseRequirement(Object raw, String at, Predicate<String> materialExists, Consumer<String> warn) {
        if (!(raw instanceof Map<?, ?> m)) {
            warn.accept(at + ": missing - skipping quest.");
            return null;
        }
        String type = String.valueOf(m.get("type")).toLowerCase(Locale.ROOT);
        switch (type) {
            case "free" -> {
                return new Requirement.Free();
            }
            case "money" -> {
                double amount = m.get("amount") instanceof Number n ? n.doubleValue() : -1;
                if (amount <= 0) {
                    warn.accept(at + ": 'amount' must be a number above 0 - skipping quest.");
                    return null;
                }
                return new Requirement.Money(amount);
            }
            case "items" -> {
                List<ItemRef> out = new ArrayList<>();
                if (m.get("items") instanceof List<?> l) {
                    for (int i = 0; i < l.size(); i++) {
                        ItemRef r = validItem(ItemRef.from(l.get(i)), materialExists);
                        if (r == null) warn.accept(at + ".items[" + (i + 1) + "]: missing or unknown item - ignoring it.");
                        else out.add(r);
                    }
                }
                if (out.isEmpty()) {
                    warn.accept(at + ": 'items' has no valid item - skipping quest.");
                    return null;
                }
                return new Requirement.Items(List.copyOf(out));
            }
            case "have-item" -> {
                ItemRef r = validItem(ItemRef.from(m.get("item")), materialExists);
                if (r == null) {
                    warn.accept(at + ": missing or unknown 'item' - skipping quest.");
                    return null;
                }
                int amount = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : r.amount();
                return new Requirement.HaveItem(new ItemRef(r.material(), r.customId(), amount));
            }
            default -> {
                warn.accept(at + ": unknown type '" + m.get("type") + "' (use free, items, money or have-item) - skipping quest.");
                return null;
            }
        }
    }

    private static List<SlotEntry> parseLayout(List<?> raw, String where, Predicate<String> materialExists, Consumer<String> warn) {
        List<SlotEntry> out = new ArrayList<>();
        if (raw == null) return out;
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String at = where + "[" + (i + 1) + "]";
            if (!(raw.get(i) instanceof Map<?, ?> m)) {
                warn.accept(at + ": not a map - skipping.");
                continue;
            }
            int slot = m.get("slot") instanceof Number n ? n.intValue() : -1;
            if (slot < 0 || slot > 53) {
                warn.accept(at + ": 'slot' must be 0-53 - skipping.");
                continue;
            }
            SlotRole role;
            try {
                role = SlotRole.valueOf(String.valueOf(m.get("role")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn.accept(at + ": unknown role '" + m.get("role") + "' - skipping.");
                continue;
            }
            if (!used.add(slot)) {
                warn.accept(at + ": slot " + slot + " is used twice - skipping.");
                continue;
            }
            String material = m.get("item") != null ? String.valueOf(m.get("item")).toUpperCase(Locale.ROOT) : null;
            if (material != null && !materialExists.test(material)) {
                warn.accept(at + ": unknown item '" + material + "' - using the default.");
                material = null;
            }
            out.add(new SlotEntry(slot, role, material));
        }
        return out;
    }

    private static QuestSettings parseSettings(ConfigurationSection s, Predicate<String> materialExists, Consumer<String> warn) {
        QuestSettings d = QuestSettings.DEFAULTS;
        if (s == null) return d;
        return new QuestSettings(
                item(s, "filler", d.filler(), materialExists, warn),
                item(s, "icons.available", d.available(), materialExists, warn),
                item(s, "icons.completed", d.completed(), materialExists, warn),
                item(s, "icons.locked", d.locked(), materialExists, warn),
                item(s, "icons.category-locked", d.categoryLocked(), materialExists, warn),
                item(s, "icons.category-empty", d.categoryEmpty(), materialExists, warn),
                item(s, "buttons.back", d.back(), materialExists, warn),
                item(s, "buttons.prev", d.prev(), materialExists, warn),
                item(s, "buttons.next", d.next(), materialExists, warn));
    }

    private static ItemRef item(ConfigurationSection s, String path, ItemRef def, Predicate<String> materialExists, Consumer<String> warn) {
        if (!s.contains(path)) return def;
        ItemRef r = validItem(ItemRef.from(s.get(path)), materialExists);
        if (r == null) {
            warn.accept("quests.yml settings." + path + ": missing or unknown item - using the default.");
            return def;
        }
        return r;
    }

    /** Zwykły materiał musi istnieć; custom item sprawdzany dopiero przy tworzeniu przedmiotu. */
    private static ItemRef validItem(ItemRef ref, Predicate<String> materialExists) {
        if (ref == null) return null;
        if (!ref.isCustom() && !materialExists.test(ref.material())) return null;
        return ref;
    }
}
