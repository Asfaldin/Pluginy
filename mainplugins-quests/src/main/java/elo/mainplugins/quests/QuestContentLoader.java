package elo.mainplugins.quests;

import elo.mainplugins.quests.model.CategoryDefinition;
import elo.mainplugins.quests.model.MaterialRequirement;
import elo.mainplugins.quests.model.QuestContent;
import elo.mainplugins.quests.model.QuestDefinition;
import elo.mainplugins.quests.model.Requirement;
import elo.mainplugins.quests.model.RewardEntry;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;
import elo.mainplugins.quests.model.ToolKind;
import elo.mainplugins.quests.model.UnlockCondition;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje quests-content.yml (cała treść systemu questów - kategorie, ich layouty GUI i
 * questy) do niemutowalnego {@link QuestContent}. Ten sam wzorzec co CustomItemManager#reload
 * w mainplugins-core: plik kopiowany z zasobu TYLKO przy pierwszym uruchomieniu (nigdy potem
 * nadpisywany), a każdy pojedynczy zły/nieznany wpis dostaje warning w logu i jest POMIJANY
 * zamiast crashować cały serwer przy starcie - jeden literówka w configu ma popsuć jeden
 * quest/nagrodę, nie całą kategorię czy cały plugin.
 */
final class QuestContentLoader {

    private QuestContentLoader() {}

    static QuestContent load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "quests-content.yml");
        if (!file.exists()) {
            plugin.saveResource("quests-content.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        List<SlotEntry> mainMenuLayout = parseLayout(cfg.getMapList("main-menu.layout"), log, "main-menu");
        List<String> categoryOrder = cfg.getStringList("category-order");

        Map<String, String> titles = new LinkedHashMap<>();
        ConfigurationSection titlesSec = cfg.getConfigurationSection("titles");
        if (titlesSec != null) {
            for (String id : titlesSec.getKeys(false)) {
                titles.put(id, titlesSec.getString(id, ""));
            }
        }

        Map<String, CategoryDefinition> categories = new LinkedHashMap<>();
        ConfigurationSection categoriesSec = cfg.getConfigurationSection("categories");
        if (categoriesSec != null) {
            for (String id : categoriesSec.getKeys(false)) {
                ConfigurationSection catSec = categoriesSec.getConfigurationSection(id);
                if (catSec == null) continue;
                categories.put(id, parseCategory(id, catSec, log));
            }
        }

        log.info("quests-content.yml: wczytano " + categories.size() + " kategorii.");
        return new QuestContent(mainMenuLayout, categoryOrder, titles, categories);
    }

    private static CategoryDefinition parseCategory(String id, ConfigurationSection sec, Logger log) {
        Material icon = Material.matchMaterial(sec.getString("icon", ""));
        if (icon == null) {
            log.warning("quests-content.yml: kategoria '" + id + "' ma zły/brakujący 'icon' - używam BARRIER.");
            icon = Material.BARRIER;
        }

        UnlockCondition unlock = null;
        ConfigurationSection unlockSec = sec.getConfigurationSection("unlock");
        if (unlockSec != null) {
            String category = unlockSec.getString("category");
            int questId = unlockSec.getInt("quest-id", -1);
            if (category == null || questId < 0) {
                log.warning("quests-content.yml: kategoria '" + id + "' ma niepoprawny 'unlock' - ignoruję (zawsze dostępna).");
            } else {
                unlock = new UnlockCondition(category, questId);
            }
        }

        List<SlotEntry> pageLayout = parseLayout(sec.getMapList("page-layout"), log, id);

        List<QuestDefinition> quests = new ArrayList<>();
        for (Object rawQuest : sec.getList("quests", List.of())) {
            QuestDefinition quest = parseQuest(rawQuest, id, log);
            if (quest != null) quests.add(quest);
        }

        return new CategoryDefinition(
                id,
                sec.getString("display-name", id),
                icon,
                sec.getString("description", ""),
                sec.getBoolean("main-path", false),
                sec.getBoolean("sequential", false),
                unlock,
                pageLayout,
                quests
        );
    }

    private static List<SlotEntry> parseLayout(List<Map<?, ?>> raw, Logger log, String context) {
        List<SlotEntry> list = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            int slot = asInt(m.get("slot"), -1);
            if (slot < 0 || slot > 53) {
                log.warning("quests-content.yml: '" + context + "' ma layout z nieprawidłowym slotem (" + m.get("slot") + ") - pomijam wpis.");
                continue;
            }
            SlotRole role;
            try {
                role = SlotRole.valueOf(String.valueOf(m.get("role")));
            } catch (IllegalArgumentException e) {
                log.warning("quests-content.yml: '" + context + "' slot " + slot + " ma nieznaną rolę ('" + m.get("role") + "') - pomijam wpis.");
                continue;
            }
            Material material = null;
            Object matRaw = m.get("material");
            if (matRaw != null) {
                material = Material.matchMaterial(String.valueOf(matRaw));
                if (material == null) {
                    log.warning("quests-content.yml: '" + context + "' slot " + slot + " ma zły material ('" + matRaw + "') - używam domyślnego.");
                }
            }
            list.add(new SlotEntry(slot, role, material));
        }
        return list;
    }

    private static QuestDefinition parseQuest(Object rawObj, String categoryId, Logger log) {
        if (!(rawObj instanceof Map<?, ?> raw)) {
            log.warning("quests-content.yml: kategoria '" + categoryId + "' ma quest, który nie jest mapą - pomijam.");
            return null;
        }
        int id = asInt(raw.get("id"), -1);
        if (id < 0) {
            log.warning("quests-content.yml: kategoria '" + categoryId + "' ma quest bez poprawnego 'id' - pomijam.");
            return null;
        }
        Object titleRaw = raw.get("title");
        String title = titleRaw != null ? String.valueOf(titleRaw) : "???";

        Requirement requirement = parseRequirement(raw.get("requirement"), categoryId, id, log);
        if (requirement == null) {
            log.warning("quests-content.yml: " + categoryId + "#" + id + " ma niepoprawny 'requirement' - CAŁY QUEST pomijany.");
            return null;
        }

        List<RewardEntry> rewards = new ArrayList<>();
        Object rawRewards = raw.get("rewards");
        if (rawRewards instanceof List<?> list) {
            for (Object rawReward : list) {
                RewardEntry reward = parseReward(rawReward, categoryId, id, log);
                if (reward != null) rewards.add(reward);
            }
        }

        Object rawLabel = raw.get("reward-label");
        String rewardLabel = rawLabel != null ? String.valueOf(rawLabel) : null;

        return new QuestDefinition(id, title, asStringList(raw.get("description")), requirement, rewards, rewardLabel);
    }

    private static Requirement parseRequirement(Object rawObj, String categoryId, int questId, Logger log) {
        if (!(rawObj instanceof Map<?, ?> raw)) return null;
        String type = String.valueOf(raw.get("type"));
        return switch (type) {
            case "FREE" -> new Requirement.FreeRequirement();
            case "MARKET_OFFER" -> new Requirement.MarketOfferRequirement();
            case "MONEY" -> {
                double amount = asDouble(raw.get("amount"), -1);
                if (amount < 0) yield null;
                yield new Requirement.MoneyRequirement(amount);
            }
            case "TOOL_POSSESS" -> {
                Material material = Material.matchMaterial(String.valueOf(raw.get("material")));
                yield material != null ? new Requirement.ToolPossessRequirement(material) : null;
            }
            case "TOOL_LEVEL" -> {
                ToolKind tool = parseToolKind(raw.get("tool"));
                if (tool == null) yield null;
                if (tool != ToolKind.PICKAXE && tool != ToolKind.AXE && tool != ToolKind.SWORD) {
                    log.warning("quests-content.yml: " + categoryId + "#" + questId
                            + " - TOOL_LEVEL dla " + tool + " nie jest obsługiwany (ToolsService nie ma metody poziomu dla HOE/SHOVEL).");
                    yield null;
                }
                int level = asInt(raw.get("level"), -1);
                yield level >= 0 ? new Requirement.ToolLevelRequirement(tool, level) : null;
            }
            case "ITEM" -> {
                List<MaterialRequirement> materials = new ArrayList<>();
                Object rawMats = raw.get("materials");
                if (rawMats instanceof List<?> list) {
                    for (Object rawMat : list) {
                        MaterialRequirement mr = parseMaterialRequirement(rawMat);
                        if (mr != null) materials.add(mr);
                    }
                }
                yield materials.isEmpty() ? null : new Requirement.ItemRequirement(materials);
            }
            case "BUY_ITEM" -> {
                MaterialRequirement mr = parseMaterialRequirement(raw.get("material"));
                yield mr != null ? new Requirement.BuyItemRequirement(mr) : null;
            }
            case "SELL_ITEM" -> {
                MaterialRequirement mr = parseMaterialRequirement(raw.get("material"));
                yield mr != null ? new Requirement.SellItemRequirement(mr) : null;
            }
            case "MARKET_LISTINGS" -> {
                int amount = asInt(raw.get("amount"), -1);
                yield amount > 0 ? new Requirement.MarketListingsRequirement(amount) : null;
            }
            default -> null;
        };
    }

    private static MaterialRequirement parseMaterialRequirement(Object rawObj) {
        if (!(rawObj instanceof Map<?, ?> raw)) return null;
        Material material = Material.matchMaterial(String.valueOf(raw.get("material")));
        if (material == null) return null;
        int amount = asInt(raw.get("amount"), 1);
        Object customId = raw.get("custom-id");
        Object displayName = raw.get("display-name");
        return new MaterialRequirement(material, amount, customId != null ? String.valueOf(customId) : null,
                displayName != null ? String.valueOf(displayName) : null);
    }

    private static RewardEntry parseReward(Object rawObj, String categoryId, int questId, Logger log) {
        if (!(rawObj instanceof Map<?, ?> raw)) return null;
        String type = String.valueOf(raw.get("type"));
        boolean silent = raw.get("silent") instanceof Boolean b && b;

        return switch (type) {
            case "ITEM" -> {
                Material material = Material.matchMaterial(String.valueOf(raw.get("material")));
                if (material == null) yield null;
                yield new RewardEntry.ItemReward(material, asInt(raw.get("amount"), 1), silent);
            }
            case "CUSTOM_ITEM" -> {
                Object id = raw.get("id");
                if (id == null) yield null;
                yield new RewardEntry.CustomItemReward(String.valueOf(id), asInt(raw.get("amount"), 1), silent);
            }
            case "MONEY" -> new RewardEntry.MoneyReward(asDouble(raw.get("amount"), 0), silent);
            case "CRATE" -> {
                List<RewardEntry> fallback = new ArrayList<>();
                if (raw.get("fallback") instanceof List<?> list) {
                    for (Object rawFb : list) {
                        RewardEntry fb = parseReward(rawFb, categoryId, questId, log);
                        if (fb != null) fallback.add(fb);
                    }
                }
                yield new RewardEntry.CrateReward(asInt(raw.get("tier"), 1), fallback, silent);
            }
            case "TOOL" -> {
                ToolKind tool = parseToolKind(raw.get("tool"));
                yield tool != null ? new RewardEntry.ToolReward(tool, silent) : null;
            }
            case "TITLE" -> {
                Object id = raw.get("id");
                yield id != null ? new RewardEntry.TitleReward(String.valueOf(id), silent) : null;
            }
            default -> {
                log.warning("quests-content.yml: " + categoryId + "#" + questId + " ma nagrodę nieznanego typu ('" + type + "') - pomijam wpis.");
                yield null;
            }
        };
    }

    private static ToolKind parseToolKind(Object raw) {
        if (raw == null) return null;
        try {
            return ToolKind.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int asInt(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static double asDouble(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }

    private static List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object o : list) result.add(String.valueOf(o));
        return result;
    }
}
