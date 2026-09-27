package elo.mainplugins.shop;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.DynamicSettings;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /@shop reload | info | price | reset | resetall | confirm | cancel | multiplier | event | rotation | stats.
 * Zmiana ceny trafia do pliku kategorii (przeżywa restart), potem sklep się przeładowuje.
 */
final class ShopCommand implements CommandExecutor, TabCompleter {

    private static final long CONFIRM_SECONDS = 30;

    /** Gdzie w sklepie jest przedmiot: kategoria, czy w puli rotacji, numer na liście. */
    private record Loc(Category category, boolean pool, int index, ShopItem item) {}

    /** Czeka na /@shop confirm. Dla "price": field = buy/sell, amount = nowa cena paczki. */
    private record Pending(String action, String key, long at, String field, Double amount) {}

    private final Plugin plugin;
    private final LangService lang;
    private final Supplier<ShopConfig> config;
    private final Runnable reload;
    private final DynamicPriceManager prices;
    private final RotationManager rotation;
    private final ShopStats stats;
    private final ShopItems items;
    private final ShopManager shop;
    private final ShopPlaces places;
    private final ShopDeals deals;
    private final ShopHistory history;
    private final Map<String, Pending> pending = new HashMap<>();

    /** Słowa na "cały sklep" w /@shop sale. */
    private static final List<String> ALL_WORDS = List.of("all", "wszystko", "sklep", "shop");

    /** Słowa na "menu główne" zamiast kategorii: /@shop npc create main. */
    private static final List<String> MAIN_WORDS = List.of("main", "menu", "glowne", "główne");

    ShopCommand(Plugin plugin, LangService lang, Supplier<ShopConfig> config, Runnable reload,
                DynamicPriceManager prices, RotationManager rotation, ShopStats stats, ShopItems items,
                ShopManager shop, ShopPlaces places, ShopDeals deals, ShopHistory history) {
        this.deals = deals;
        this.history = history;
        this.plugin = plugin;
        this.lang = lang;
        this.config = config;
        this.reload = reload;
        this.prices = prices;
        this.rotation = rotation;
        this.stats = stats;
        this.items = items;
        this.shop = shop;
        this.places = places;
    }

    private void send(CommandSender s, String key, Map<String, String> ph) {
        lang.send(s, plugin, key, ph);
    }

    private void send(CommandSender s, String key) {
        lang.send(s, plugin, key);
    }

    private static String senderId(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }

    /** Mnożnik -> "+50" / "-20" - admin widzi procenty, nie mnożniki. */
    private static String signed(double multiplier) {
        int pct = ShopRules.multiplierToPercent(multiplier);
        return (pct >= 0 ? "+" : "") + pct;
    }

    private static String fmt(double x) {
        return String.format(Locale.US, "%.2f", x);
    }

    /** "diamond" -> "DIAMOND", "custom:My_Gem" -> "custom:my_gem". */
    private static String normalize(String arg) {
        return arg.toLowerCase(Locale.ROOT).startsWith("custom:") ? arg.toLowerCase(Locale.ROOT) : arg.toUpperCase(Locale.ROOT);
    }

    private Loc find(String key) {
        for (Category c : config.get().categories().values()) {
            for (int i = 0; i < c.items().size(); i++) if (c.items().get(i).key().equals(key)) return new Loc(c, false, i, c.items().get(i));
            if (c.rotation() != null) {
                List<ShopItem> pool = c.rotation().pool();
                for (int i = 0; i < pool.size(); i++) if (pool.get(i).key().equals(key)) return new Loc(c, true, i, pool.get(i));
            }
        }
        return null;
    }

    private Loc findOrWarn(CommandSender s, String arg) {
        Loc loc = find(normalize(arg));
        if (loc == null) send(s, "admin.unknown-item", Map.of("item", arg));
        return loc;
    }

    private Double number(CommandSender s, String raw) {
        try {
            return Double.parseDouble(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            send(s, "admin.not-a-number", Map.of("value", raw));
            return null;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                reload.run();
                int count = config.get().categories().values().stream().mapToInt(c -> c.items().size()).sum();
                send(sender, "admin.reloaded", Map.of("categories", String.valueOf(config.get().categories().size()), "items", String.valueOf(count)));
            }
            case "info" -> {
                if (args.length < 2) send(sender, "admin.usage");
                else info(sender, args[1]);
            }
            case "price" -> {
                if (args.length < 4) send(sender, "admin.usage");
                else price(sender, args[1], args[2], args[3]);
            }
            case "reset" -> {
                if (args.length < 2) {
                    send(sender, "admin.usage");
                    return true;
                }
                Loc loc = findOrWarn(sender, args[1]);
                if (loc == null) return true;
                pending.put(senderId(sender), new Pending("reset", loc.item().key(), System.currentTimeMillis(), null, null));
                send(sender, "admin.confirm-reset", Map.of("item", loc.item().key(), "percent", signed(prices.getMnoznik(loc.item().key())),
                        "seconds", String.valueOf(CONFIRM_SECONDS)));
            }
            case "resetall" -> {
                pending.put(senderId(sender), new Pending("resetall", null, System.currentTimeMillis(), null, null));
                send(sender, "admin.confirm-resetall", Map.of("count", String.valueOf(prices.getWszystkieMnozniki().size()),
                        "seconds", String.valueOf(CONFIRM_SECONDS)));
            }
            case "confirm" -> confirm(sender);
            case "cancel" -> {
                pending.remove(senderId(sender));
                send(sender, "admin.cancelled");
            }
            case "multiplier" -> {
                if (args.length < 3) {
                    send(sender, "admin.usage");
                    return true;
                }
                Loc loc = findOrWarn(sender, args[1]);
                Double x = loc == null ? null : percent(sender, args[2]);
                if (x == null) return true;
                prices.ustawMnoznik(loc.item().key(), x);
                int pct = ShopRules.multiplierToPercent(prices.getMnoznik(loc.item().key()));
                send(sender, "admin.multiplier-set", Map.of("item", loc.item().key(),
                        "percent", (pct >= 0 ? "+" : "") + pct));
            }
            case "event" -> event(sender, args);
            case "rotation" -> rotationCmd(sender, args);
            case "stats" -> statsCmd(sender, args);
            case "open" -> openCmd(sender, args);
            case "sign" -> signCmd(sender, args);
            case "npc" -> npcCmd(sender, args);
            case "sale" -> saleCmd(sender, args);
            case "history" -> historyCmd(sender, args);
            case "top" -> topCmd(sender, args);
            default -> send(sender, "admin.usage");
        }
        return true;
    }

    // ---------- open / sign / npc ----------

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    /** "" = menu główne, id kategorii, null = nie ma takiej (komunikat już wysłany). */
    private String target(CommandSender s, String raw) {
        if (raw.isBlank() || MAIN_WORDS.contains(raw.toLowerCase(Locale.ROOT))) return "";
        String id = shop.findCategory(raw);
        if (id == null) send(s, "places.unknown-category", Map.of("value", raw));
        return id;
    }

    /** /@shop open <gracz> [kategoria] - dla NPC z innych pluginów, menu serwera, bloków poleceń. Po cichu, gdy się uda. */
    private void openCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "admin.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "places.unknown-player", Map.of("player", args[1]));
            return;
        }
        String where = target(sender, join(args, 2));
        if (where != null) shop.openFor(target, where.isEmpty() ? null : where);
    }

    private Player admin(CommandSender sender) {
        if (sender instanceof Player p) return p;
        send(sender, "admin.players-only");
        return null;
    }

    /** /@shop sign <kategoria|main> albo /@shop sign remove - patrząc na tabliczkę. */
    private void signCmd(CommandSender sender, String[] args) {
        Player p = admin(sender);
        if (p == null) return;
        org.bukkit.block.Sign sign = places.lookedAtSign(p);
        if (sign == null) {
            send(p, "places.sign-none");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
            send(p, places.unmarkSign(sign) ? "places.sign-removed" : "places.sign-not-shop");
            return;
        }
        String where = target(p, join(args, 1));
        if (where == null) return;
        places.markSign(sign, where);
        send(p, "places.sign-set", Map.of("target", places.describe(where)));
    }

    /** /@shop npc create|name|type|category|remove. */
    private void npcCmd(CommandSender sender, String[] args) {
        Player p = admin(sender);
        if (p == null) return;
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("create")) {
            String where = target(p, args.length >= 3 ? args[2] : "");
            if (where == null) return;
            String name = args.length >= 4 ? join(args, 3) : places.defaultName(where);
            places.createNpc(p.getLocation(), org.bukkit.entity.EntityType.VILLAGER, where, name);
            send(p, "places.npc-created", Map.of("target", places.describe(where)));
            return;
        }
        if (!List.of("name", "type", "category", "remove").contains(action)) {
            send(p, "admin.usage");
            return;
        }
        org.bukkit.entity.LivingEntity npc = places.lookedAtNpc(p);
        if (npc == null) {
            send(p, "places.npc-none");
            return;
        }
        switch (action) {
            case "name" -> {
                if (args.length < 3) {
                    send(p, "admin.usage");
                    return;
                }
                places.rename(npc, join(args, 2));
                send(p, "places.npc-renamed", Map.of("name", join(args, 2)));
            }
            case "type" -> {
                if (args.length < 3) {
                    send(p, "admin.usage");
                    return;
                }
                org.bukkit.entity.EntityType type = ShopPlaces.npcType(args[2]);
                if (type == null) {
                    send(p, "places.npc-bad-type", Map.of("value", args[2]));
                    return;
                }
                org.bukkit.entity.Villager.Profession job = null;
                if (args.length >= 4) {
                    job = ShopPlaces.profession(args[3]);
                    if (job == null || type != org.bukkit.entity.EntityType.VILLAGER) {
                        send(p, "places.npc-bad-profession", Map.of("value", args[3]));
                        return;
                    }
                }
                org.bukkit.entity.LivingEntity fresh = npc.getType() == type ? npc : places.changeType(npc, type);
                if (job != null && fresh instanceof org.bukkit.entity.Villager v) v.setProfession(job);
                send(p, "places.npc-type-set", Map.of("type", args[2].toLowerCase(Locale.ROOT)));
                if (fresh instanceof org.bukkit.entity.Monster && p.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
                    send(p, "places.npc-peaceful");
                }
            }
            case "category" -> {
                String where = target(p, args.length >= 3 ? join(args, 2) : "");
                if (where == null) return;
                places.retarget(npc, where);
                send(p, "places.npc-retargeted", Map.of("target", places.describe(where)));
            }
            default -> {
                npc.remove();
                send(p, "places.npc-removed");
            }
        }
    }

    private boolean inRange(CommandSender s, double x) {
        DynamicSettings d = config.get().settings().dynamic();
        if (x >= d.minMultiplier() && x <= d.maxMultiplier()) return true;
        send(s, "admin.percent-range", Map.of("min", String.valueOf(ShopRules.multiplierToPercent(d.minMultiplier())),
                "max", "+" + ShopRules.multiplierToPercent(d.maxMultiplier())));
        return false;
    }

    /** "+50" / "-20" / "50%" -> mnożnik, z komunikatem o błędzie. Null = zły zapis albo poza zakresem. */
    private Double percent(CommandSender s, String raw) {
        Double x = ShopRules.percentToMultiplier(raw);
        if (x == null) {
            send(s, "admin.not-a-percent", Map.of("value", raw));
            return null;
        }
        return inRange(s, x) ? x : null;
    }

    private void confirm(CommandSender sender) {
        Pending p = pending.remove(senderId(sender));
        if (p == null) {
            send(sender, "admin.confirm-nothing");
            return;
        }
        if (System.currentTimeMillis() - p.at() > CONFIRM_SECONDS * 1000) {
            send(sender, "admin.confirm-expired");
            return;
        }
        if (p.action().equals("price")) {
            applyPrice(sender, p);
        } else if (p.action().equals("reset")) {
            prices.resetujItem(p.key());
            send(sender, "admin.reset-done", Map.of("item", p.key()));
        } else {
            prices.wymusReset();
            send(sender, "admin.resetall-done");
        }
    }

    private void info(CommandSender sender, String arg) {
        Loc loc = findOrWarn(sender, arg);
        if (loc == null) return;
        ShopItem it = loc.item();
        String key = it.key();
        String none = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lang.msg(plugin, "admin.info-none"));
        double m = prices.getMnoznik(key);
        String state = prices.czyZablokowany(key) ? "admin.info-locked" : m > 1.02 ? "admin.info-up" : m < 0.98 ? "admin.info-down" : "admin.info-normal";
        var d = config.get().settings().dynamic();
        Map<String, String> ph = new HashMap<>();
        ph.put("item", key);
        ph.put("category", loc.category().id() + (loc.pool() ? " (rotation pool)" : ""));
        ph.put("buy", it.buyable() ? per(it.buy(), it.amount()) : none);
        ph.put("sell", it.sellable() ? per(it.sell(), it.sellAmount()) : none);
        ph.put("real", it.sellable() ? per(ShopRules.sellPerLot(it, m, d.maxSellShare(), config.get().settings().rounding()), it.sellAmount()) : none);
        ph.put("percent", signed(m));
        ph.put("state", net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(lang.msg(plugin, state)));
        ph.put("norm", String.format(Locale.US, "%.1f", prices.getNorma(key)));
        ph.put("drought", String.valueOf(prices.getLicznikSuszy(key)));
        ph.put("days", prices.resetWlaczony() ? String.valueOf(prices.dniDoResetu())
                : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(lang.msg(plugin, "admin.info-reset-off")));
        send(sender, "admin.info", ph);
        if (!prices.enabled()) send(sender, "admin.dynamic-off");
    }

    private String per(double price, int amount) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                lang.msg(plugin, "admin.info-per", Map.of("price", ShopManager.money(price), "amount", String.valueOf(amount))));
    }

    private void price(CommandSender sender, String arg, String type, String rawAmount) {
        Loc loc = findOrWarn(sender, arg);
        if (loc == null) return;
        String field = switch (type.toLowerCase(Locale.ROOT)) {
            case "buy" -> "buy";
            case "sell" -> "sell";
            default -> null;
        };
        if (field == null) {
            send(sender, "admin.price-type");
            return;
        }
        Double amount = number(sender, rawAmount);
        if (amount == null) return;
        if (amount <= 0) {
            send(sender, "admin.price-positive");
            return;
        }
        amount = Math.round(amount * 100) / 100.0;
        ShopItem it = loc.item();
        Double buy = field.equals("buy") ? amount : it.buy();
        Double sell = field.equals("sell") ? amount : it.sell();
        // Skup nie może dawać tyle co kupno - inaczej kup-sprzedaj daje pieniądze bez pracy.
        if (buy != null && sell != null && sell / it.sellAmount() >= buy / it.amount()) {
            send(sender, "admin.price-margin", Map.of("sell", fmt(sell / it.sellAmount()), "buy", fmt(buy / it.amount())));
            return;
        }
        int pieces = field.equals("buy") ? it.amount() : it.sellAmount();
        Double old = field.equals("buy") ? it.buy() : it.sell();
        pending.put(senderId(sender), new Pending("price", it.key(), System.currentTimeMillis(), field, amount));
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("item", it.key());
        ph.put("pieces", String.valueOf(pieces));
        ph.put("old", old == null ? "-" : ShopManager.money(old));
        ph.put("old-each", old == null ? "-" : ShopManager.money(old / pieces));
        ph.put("new", ShopManager.money(amount));
        ph.put("new-each", ShopManager.money(amount / pieces));
        ph.put("seconds", String.valueOf(CONFIRM_SECONDS));
        send(sender, field.equals("buy") ? "admin.confirm-price-buy" : "admin.confirm-price-sell", ph);
    }

    /** Zapisuje cenę potwierdzoną przez /@shop confirm. */
    private void applyPrice(CommandSender sender, Pending p) {
        Loc loc = find(p.key());
        if (loc == null) {
            send(sender, "admin.unknown-item", Map.of("item", p.key()));
            return;
        }
        String error = writePrice(loc, p.field(), p.amount());
        if (error != null) {
            send(sender, "admin.price-failed", Map.of("error", error));
            return;
        }
        reload.run();
        int pieces = p.field().equals("buy") ? loc.item().amount() : loc.item().sellAmount();
        send(sender, "admin.price-set", Map.of("type", p.field(), "item", p.key(), "amount", ShopManager.money(p.amount()),
                "pieces", String.valueOf(pieces), "each", ShopManager.money(p.amount() / pieces),
                "category", loc.category().id()));
        double m = prices.getMnoznik(p.key());
        if (p.field().equals("sell") && Math.abs(m - 1.0) > 0.02) {
            send(sender, "admin.price-multiplier-note", Map.of("percent", signed(m), "item", p.key()));
        }
    }

    /** Zmienia jedno pole pozycji w pliku kategorii (reszta pliku zostaje). Null = udało się. */
    @SuppressWarnings("unchecked")
    private String writePrice(Loc loc, String field, double amount) {
        File file = new File(plugin.getDataFolder(), "categories/" + loc.category().id() + ".yml");
        if (!file.exists()) return "categories/" + loc.category().id() + ".yml not found";
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String path = loc.pool() ? "rotation.pool" : "items";
        List<Object> list = new ArrayList<>(yml.getList(path, List.of()));
        // Numer z parsera liczy tylko poprawne wpisy - szukamy po kluczu, żeby trafić w dobrą linijkę pliku.
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> raw)) continue;
            String key = raw.get("custom") != null ? "custom:" + String.valueOf(raw.get("custom")).toLowerCase(Locale.ROOT)
                    : raw.get("item") != null ? String.valueOf(raw.get("item")).toUpperCase(Locale.ROOT) : null;
            if (!loc.item().key().equals(key)) continue;
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) raw);
            copy.put(field, amount == Math.rint(amount) ? (Object) (long) amount : amount);
            list.set(i, copy);
            yml.set(path, list);
            try {
                yml.save(file);
                return null;
            } catch (IOException e) {
                return e.getMessage();
            }
        }
        return "item not found in the file";
    }

    private void event(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("offall")) {
            int n = prices.zakonczWszystkieEventy().size();
            if (n == 0) {
                send(sender, "admin.event-list-empty");
                return;
            }
            send(sender, "admin.event-offall", Map.of("count", String.valueOf(n)));
            if (config.get().settings().dynamic().announceEvents()) {
                Bukkit.getOnlinePlayers().forEach(p -> lang.send(p, plugin, "event.broadcast-all-off"));
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            Map<String, Double> locked = prices.getZablokowane();
            if (locked.isEmpty()) {
                send(sender, "admin.event-list-empty");
                return;
            }
            send(sender, "admin.event-list-header");
            locked.forEach((k, v) -> {
                int pct = ShopRules.multiplierToPercent(v);
                Long left = prices.zostaloEventu(k);
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("item", k);
                ph.put("percent", (pct >= 0 ? "+" : "") + pct);
                if (left != null) ph.put("time", ShopRules.formatDuration(left));
                send(sender, left == null ? "admin.event-list-line" : "admin.event-list-line-timed", ph);
            });
            return;
        }
        if (args.length < 3) {
            send(sender, "admin.usage");
            return;
        }
        Loc loc = findOrWarn(sender, args[1]);
        if (loc == null) return;
        String key = loc.item().key();
        if (args[2].equalsIgnoreCase("off")) {
            if (!prices.czyZablokowany(key)) {
                send(sender, "admin.event-not-locked", Map.of("item", key));
                return;
            }
            prices.odblokujMnoznik(key);
            send(sender, "admin.event-off", Map.of("item", key));
            broadcastEvent(loc.item(), "event.broadcast-off", Map.of());
            return;
        }
        Double x = percent(sender, args[2]);
        if (x == null) return;
        long until = 0L;
        String timeText = null;
        if (args.length >= 4) {
            Long span = ShopRules.parseDuration(args[3]);
            if (span == null) {
                send(sender, "admin.not-a-duration", Map.of("value", args[3]));
                return;
            }
            until = System.currentTimeMillis() + span;
            timeText = ShopRules.formatDuration(span);
        }
        prices.zablokujMnoznik(key, x, until);
        int percent = ShopRules.multiplierToPercent(x);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("item", key);
        ph.put("percent", (percent >= 0 ? "+" : "") + percent);
        if (timeText != null) ph.put("time", timeText);
        send(sender, timeText == null ? "admin.event-set" : "admin.event-set-timed", ph);
        Map<String, String> bc = new LinkedHashMap<>();
        bc.put("percent", String.valueOf(Math.abs(percent)));
        if (timeText != null) bc.put("time", timeText);
        String dir = percent >= 0 ? "up" : "down";
        broadcastEvent(loc.item(), "event.broadcast-" + dir + (timeText == null ? "" : "-timed"), bc);
        if (!prices.enabled()) send(sender, "admin.dynamic-off");
    }

    /** Ogłasza event wszystkim na serwerze (shop.yml dynamic-prices.announce-events: false wyłącza). Teksty w lang/. */
    private void broadcastEvent(ShopItem item, String key, Map<String, String> ph) {
        if (!config.get().settings().dynamic().announceEvents()) return;
        Component line = lang.msg(plugin, key, ph).replaceText(
                TextReplacementConfig.builder().matchLiteral("{item}").replacement(items.name(item)).build());
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(line));
    }

    // ---------- promocje: /@shop sale ----------

    /** Nazwa celu promocji: przedmiot (w języku gracza), kategoria (z kolorami) albo "cały sklep". */
    Component saleTargetName(String saleKey) {
        if (saleKey.equals(ShopRules.SALE_ALL)) return lang.msg(plugin, "sale.target-all");
        if (saleKey.startsWith("category:")) {
            Category c = config.get().categories().get(saleKey.substring("category:".length()));
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(c == null ? saleKey.substring("category:".length()) : c.name());
        }
        String key = saleKey.substring("item:".length());
        Loc loc = find(key);
        return loc == null ? Component.text(key) : items.name(loc.item());
    }

    private String plainTarget(String saleKey) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(saleTargetName(saleKey));
    }

    /** "all" / kategoria (id albo nazwa) / przedmiot -> klucz promocji; null = nic takiego (komunikat już wysłany). */
    private String saleKey(CommandSender s, String arg) {
        if (ALL_WORDS.contains(arg.toLowerCase(Locale.ROOT))) return ShopRules.SALE_ALL;
        String cat = shop.findCategory(arg);
        if (cat != null) return ShopRules.saleKeyCategory(cat);
        Loc loc = find(normalize(arg));
        if (loc != null) return ShopRules.saleKeyItem(loc.item().key());
        send(s, "admin.sale-unknown-target", Map.of("value", arg));
        return null;
    }

    /** Ogłasza promocję wszystkim (shop.yml sales.announce: false wyłącza). {target} jako komponent. */
    void broadcastSale(String textKey, String saleKey, Map<String, String> ph) {
        if (!config.get().settings().extras().announceSales()) return;
        Component line = lang.msg(plugin, textKey, ph).replaceText(
                TextReplacementConfig.builder().matchLiteral("{target}").replacement(saleTargetName(saleKey)).build());
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(line));
    }

    private void saleCmd(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            Map<String, ShopRules.Sale> active = deals.active();
            if (active.isEmpty()) {
                send(sender, "admin.sale-list-empty");
                return;
            }
            send(sender, "admin.sale-list-header");
            long now = System.currentTimeMillis();
            active.forEach((k, s) -> {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("target", plainTarget(k));
                ph.put("percent", ShopManager.pct(s.percent()));
                if (s.until() > 0) ph.put("time", ShopRules.formatDuration(s.until() - now));
                send(sender, s.until() > 0 ? "admin.sale-list-line-timed" : "admin.sale-list-line", ph);
            });
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("offall")) {
            int n = deals.stopAll();
            if (n == 0) {
                send(sender, "admin.sale-list-empty");
                return;
            }
            send(sender, "admin.sale-offall", Map.of("count", String.valueOf(n)));
            if (config.get().settings().extras().announceSales()) {
                Bukkit.getOnlinePlayers().forEach(p -> lang.send(p, plugin, "sale.broadcast-all-end"));
            }
            return;
        }
        if (args.length < 3) {
            send(sender, "admin.usage");
            return;
        }
        String key = saleKey(sender, args[1]);
        if (key == null) return;
        if (args[2].equalsIgnoreCase("off")) {
            if (!deals.stop(key)) {
                send(sender, "admin.sale-not-active", Map.of("target", plainTarget(key)));
                return;
            }
            send(sender, "admin.sale-off", Map.of("target", plainTarget(key)));
            broadcastSale("sale.broadcast-end", key, Map.of());
            return;
        }
        // "-20", "20" i "20%" znaczą to samo: 20% taniej.
        Double x = ShopRules.percentToMultiplier(args[2]);
        double percent = x == null ? -1 : Math.abs(ShopRules.multiplierToPercent(x));
        if (percent < 1 || percent > 90) {
            send(sender, "admin.sale-bad-percent", Map.of("value", args[2]));
            return;
        }
        long until = 0L;
        String timeText = null;
        if (args.length >= 4) {
            Long span = ShopRules.parseDuration(args[3]);
            if (span == null) {
                send(sender, "admin.not-a-duration", Map.of("value", args[3]));
                return;
            }
            until = System.currentTimeMillis() + span;
            timeText = ShopRules.formatDuration(span);
        }
        deals.start(key, percent, until);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("target", plainTarget(key));
        ph.put("percent", ShopManager.pct(percent));
        if (timeText != null) ph.put("time", timeText);
        send(sender, timeText == null ? "admin.sale-set" : "admin.sale-set-timed", ph);
        Map<String, String> bc = new LinkedHashMap<>();
        bc.put("percent", ShopManager.pct(percent));
        if (timeText != null) bc.put("time", timeText);
        broadcastSale(timeText == null ? "sale.broadcast-start" : "sale.broadcast-start-timed", key, bc);
    }

    // ---------- historia i ranking ----------

    private void historyCmd(CommandSender sender, String[] args) {
        if (!config.get().settings().statsEnabled()) {
            send(sender, "admin.stats-disabled");
            return;
        }
        if (args.length < 2) {
            send(sender, "admin.usage");
            return;
        }
        Loc loc = findOrWarn(sender, args[1]);
        if (loc == null) return;
        int days = Math.min(14, config.get().settings().extras().historyDays());
        List<ShopHistory.DayLine> lines = history.itemHistory(loc.item().key(), java.time.LocalDate.now(), days);
        send(sender, "admin.history-header", Map.of("item", items.plainName(loc.item()), "days", String.valueOf(days)));
        if (lines.isEmpty()) {
            send(sender, "admin.history-empty");
            return;
        }
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM");
        for (ShopHistory.DayLine l : lines) {
            send(sender, "admin.history-line", Map.of("date", l.day().format(f), "amount", String.valueOf(l.amount()),
                    "money", ShopManager.money(l.money()), "price", ShopManager.money(l.money() / Math.max(1, l.amount()))));
        }
    }

    private void topCmd(CommandSender sender, String[] args) {
        if (!config.get().settings().statsEnabled()) {
            send(sender, "admin.stats-disabled");
            return;
        }
        String when = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean week = when.startsWith("tydz") || when.startsWith("week") || when.equals("7");
        List<ShopHistory.TopLine> top = history.top(java.time.LocalDate.now(), week ? 7 : 1, 10);
        send(sender, week ? "admin.top-header-week" : "admin.top-header-today");
        if (top.isEmpty()) {
            send(sender, "admin.top-empty");
            return;
        }
        int nr = 1;
        for (ShopHistory.TopLine l : top) {
            send(sender, "admin.top-line", Map.of("nr", String.valueOf(nr++), "player", l.name(), "money", ShopManager.money(l.money()),
                    "amount", String.valueOf(l.amount())));
        }
    }

    private void rotationCmd(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("force")) {
            int n = rotation.force(args.length >= 3 ? args[2] : null);
            send(sender, "admin.rotation-forced", Map.of("count", String.valueOf(n)));
            return;
        }
        boolean any = false;
        var rounding = config.get().settings().rounding();
        for (Category c : config.get().categories().values()) {
            if (c.rotation() == null || !c.rotation().enabled()) continue;
            any = true;
            send(sender, "admin.rotation-header", Map.of("category", c.id(), "days", String.valueOf(rotation.daysLeft(c.id())),
                    "pool", String.valueOf(c.rotation().pool().size()), "resting", String.valueOf(rotation.onCooldown(c.id()))));
            for (ShopItem it : rotation.active(c)) {
                send(sender, "admin.rotation-line", Map.of("item", items.plainName(it),
                        "price", it.buyable() ? ShopManager.money(ShopRules.buyPrice(it, it.amount(), rounding)) : "-"));
            }
        }
        if (!any) send(sender, "admin.rotation-none");
    }

    private void statsCmd(CommandSender sender, String[] args) {
        if (!stats.enabled()) {
            send(sender, "admin.stats-disabled");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("snapshot")) {
            stats.wymusSnapshot();
            send(sender, "admin.stats-snapshot");
            return;
        }
        List<ShopStats.PozycjaTopki> top = stats.getTopDzis(10, prices.getWszystkieMnozniki());
        send(sender, "admin.stats-header");
        if (top.isEmpty()) {
            send(sender, "admin.stats-empty");
            return;
        }
        int nr = 1;
        for (ShopStats.PozycjaTopki p : top) {
            String trend = p.mnoznik() > 1.02 ? " &a▲" : p.mnoznik() < 0.98 ? " &c▼" : "";
            send(sender, "admin.stats-line", Map.of("nr", String.valueOf(nr++), "item", p.item(), "amount", String.valueOf(p.sztuk()),
                    "money", ShopManager.money(p.wyplacono()), "trend", trend));
        }
        send(sender, "admin.stats-total", Map.of("money", ShopManager.money(stats.getWyplaconoDzis())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return TabCompleteUtils.dopasuj(args[0], List.of("reload", "info", "price", "reset", "resetall", "confirm", "cancel",
                    "multiplier", "event", "sale", "history", "top", "rotation", "stats", "open", "sign", "npc"));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> targets = new ArrayList<>(config.get().categories().keySet());
        targets.add(0, "main");
        if (sub.equals("open")) {
            if (args.length == 2) return null; // nicki graczy online
            if (args.length == 3) return TabCompleteUtils.dopasuj(args[2], targets);
            return TabCompleteUtils.PUSTA;
        }
        if (sub.equals("sign") && args.length == 2) {
            List<String> opts = new ArrayList<>(targets);
            opts.add("remove");
            return TabCompleteUtils.dopasuj(args[1], opts);
        }
        if (sub.equals("npc")) {
            if (args.length == 2) return TabCompleteUtils.dopasuj(args[1], List.of("create", "name", "type", "category", "remove"));
            String act = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && (act.equals("create") || act.equals("category"))) return TabCompleteUtils.dopasuj(args[2], targets);
            if (args.length == 3 && act.equals("type")) {
                return TabCompleteUtils.dopasuj(args[2], List.of("villager", "wandering_trader", "piglin", "iron_golem", "zombie",
                        "skeleton", "allay", "fox", "cat", "armor_stand"));
            }
            if (args.length == 4 && act.equals("type") && args[2].equalsIgnoreCase("villager")) {
                List<String> jobs = new ArrayList<>();
                org.bukkit.Registry.VILLAGER_PROFESSION.forEach(j -> jobs.add(j.getKey().getKey()));
                return TabCompleteUtils.dopasuj(args[3], jobs);
            }
            return TabCompleteUtils.PUSTA;
        }
        if (args.length == 2) {
            switch (sub) {
                case "info", "price", "reset", "multiplier", "event", "sale", "history" -> {
                    List<String> keys = new ArrayList<>();
                    if (sub.equals("event") || sub.equals("sale")) {
                        keys.add("list");
                        keys.add("offall");
                    }
                    if (sub.equals("sale")) {
                        keys.add("all");
                        keys.addAll(config.get().categories().keySet());
                    }
                    for (Category c : config.get().categories().values()) {
                        for (ShopItem it : c.items()) keys.add(it.key());
                        if (c.rotation() != null) for (ShopItem it : c.rotation().pool()) keys.add(it.key());
                    }
                    return TabCompleteUtils.dopasuj(args[1], keys);
                }
                case "rotation" -> {
                    return TabCompleteUtils.dopasuj(args[1], List.of("force"));
                }
                case "stats" -> {
                    return TabCompleteUtils.dopasuj(args[1], List.of("snapshot"));
                }
                case "top" -> {
                    return TabCompleteUtils.dopasuj(args[1], List.of("dzis", "tydzien"));
                }
                default -> {
                    return TabCompleteUtils.PUSTA;
                }
            }
        }
        if (args.length == 3 && sub.equals("price")) return TabCompleteUtils.dopasuj(args[2], List.of("buy", "sell"));
        if (args.length == 3 && sub.equals("event")) return TabCompleteUtils.dopasuj(args[2], List.of("off", "-20", "+20", "+50"));
        if (args.length == 4 && sub.equals("event")) return TabCompleteUtils.dopasuj(args[3], List.of("30m", "2h", "6h", "1d", "3d"));
        if (args.length == 3 && sub.equals("sale")) return TabCompleteUtils.dopasuj(args[2], List.of("off", "-10", "-20", "-50"));
        if (args.length == 4 && sub.equals("sale")) return TabCompleteUtils.dopasuj(args[3], List.of("30m", "2h", "6h", "1d", "3d"));
        if (args.length == 3 && sub.equals("rotation")) return TabCompleteUtils.dopasuj(args[2], config.get().categories().keySet());
        return TabCompleteUtils.PUSTA;
    }
}
