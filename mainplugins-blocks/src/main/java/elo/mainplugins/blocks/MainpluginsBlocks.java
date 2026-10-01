package elo.mainplugins.blocks;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemProvider;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.Reward;
import elo.mainplugins.license.LicenseGuard;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Własne bloki z Kreatora bloków (Texturepack Creator w aplikacji, dodatek Ultimate). Bloki czyta
 * z paczki zasobów serwera (plugins/MainpluginsCore/resourcepack/pack.zip) - z tego samego pliku
 * gracze dostają ich modele. /@block give &lt;blok&gt; [gracz] [ilość] | list | reload
 * Nagroda "block: id" działa w nagrodach wszystkich pluginów (skrzynki, questy...).
 */
public final class MainpluginsBlocks extends JavaPlugin implements TabExecutor {

    private static final String PREFIX = "assets/mainplugins/blocks/";

    private BlockRegistry registry;
    private LangService lang;
    private String lastProblem;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("blocks")
                || !LicenseGuard.enable(this, "blocks", () -> CoreAPI.getLicenseService().licenseProof("blocks"))) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-blocks - plugin zostanie wyłączony.");
            getLogger().severe("Bloki są częścią Ultimate - skonfiguruj klucz w license.yml (folder danych MainpluginsCore) i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        registry = new BlockRegistry(this);
        disableNoteBlockUpdates();
        reload();
        getServer().getPluginManager().registerEvents(new BlockListener(this, registry), this);
        CoreAPI.getRewardService().registerType(this, "block", this::giveReward);
        // Bloki jako przedmioty katalogu Core ("block_<id>") - dropy mobów, sklep, skrzynki.
        CoreAPI.getCustomItemService().registerProvider(this, new CustomItemProvider() {
            @Override
            public Set<String> ids() {
                Set<String> out = new LinkedHashSet<>();
                for (BlockDef d : registry.all()) out.add("block_" + d.id());
                return out;
            }

            @Override
            public ItemStack create(String id, int amount, Player player) {
                BlockDef def = id.startsWith("block_") ? registry.get(id.substring("block_".length())) : null;
                return def == null ? null : registry.item(def, amount);
            }
        });
        if (getCommand("@block") != null) {
            getCommand("@block").setExecutor(this);
            getCommand("@block").setTabCompleter(this);
        }
    }

    /**
     * Paper ma ustawienie block-updates.disable-noteblock-updates (paper-global.yml) - note blocki
     * nie zmieniają wtedy stanu od sąsiadów i redstone'a, dokładnie to, czego potrzebują własne bloki.
     * Włączamy je na czas działania serwera; nasze zdarzenia (BlockListener) zabezpieczają i bez niego.
     */
    private void disableNoteBlockUpdates() {
        try {
            Class<?> global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Object config = global.getMethod("get").invoke(null);
            Object blockUpdates = config.getClass().getField("blockUpdates").get(config);
            Field flag = blockUpdates.getClass().getField("disableNoteblockUpdates");
            if (!flag.getBoolean(blockUpdates)) {
                flag.setBoolean(blockUpdates, true);
                getLogger().info("Włączono block-updates.disable-noteblock-updates (własne bloki nie zmieniają wyglądu od redstone'a i sąsiadów).");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Ustaw block-updates.disable-noteblock-updates: true w config/paper-global.yml - bez tego redstone może zmieniać wygląd własnych bloków.");
        }
    }

    private void reload() {
        lastProblem = null;
        List<BlockDef> defs = new ArrayList<>();
        File pack = new File(getDataFolder().getParentFile(), "MainpluginsCore/resourcepack/pack.zip");
        if (!pack.exists()) {
            lastProblem = "Nie ma paczki zasobów serwera (" + pack.getPath() + ") - wyślij paczkę z aplikacji (Texturepack Creator).";
            getLogger().warning(lastProblem);
        } else {
            try (ZipFile zip = new ZipFile(pack)) {
                zip.stream()
                        .filter(e -> !e.isDirectory() && e.getName().startsWith(PREFIX) && e.getName().endsWith(".json") && e.getName().indexOf('/', PREFIX.length()) < 0)
                        .forEach(e -> {
                            try {
                                defs.add(BlockLoader.parse(read(zip, e)));
                            } catch (RuntimeException ex) {
                                getLogger().warning("Blok " + e.getName().substring(PREFIX.length()) + ": zły plik - " + ex.getMessage());
                            }
                        });
            } catch (IOException ex) {
                lastProblem = "Nie udało się przeczytać paczki: " + ex.getMessage();
                getLogger().warning(lastProblem);
            }
        }
        String clash = registry.set(defs);
        if (clash != null) {
            lastProblem = clash;
            getLogger().warning(clash);
        }
        getLogger().info("Bloki z paczki (" + registry.all().size() + "): " + String.join(", ", ids()));
    }

    private static String read(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private List<String> ids() {
        return registry.all().stream().map(BlockDef::id).toList();
    }

    /** false = nieznane id -> RewardService użyje nagrody zastępczej. */
    private boolean giveReward(Player player, Reward r) {
        BlockDef def = registry.get(String.valueOf(r.value()));
        if (def == null) return false;
        give(player, def, r.amount());
        if (!r.silent()) lang.send(player, this, "reward.block", Map.of("amount", String.valueOf(r.amount()), "block", def.name()));
        return true;
    }

    private void give(Player player, BlockDef def, int amount) {
        int left = amount;
        while (left > 0) {
            int n = Math.min(64, left);
            left -= n;
            ItemStack item = registry.item(def, n);
            player.getInventory().addItem(item).values().forEach(l -> player.getWorld().dropItemNaturally(player.getLocation(), l));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "give" -> {
                if (args.length < 2) {
                    lang.send(sender, this, "admin.usage");
                    return true;
                }
                BlockDef def = registry.get(args[1].toLowerCase());
                if (def == null) {
                    lang.send(sender, this, "admin.unknown-block", Map.of("block", args[1], "blocks", String.join(", ", ids())));
                    return true;
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    lang.send(sender, this, "admin.unknown-player", Map.of("player", args.length >= 3 ? args[2] : "-"));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Math.min(64 * 36, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }
                give(target, def, amount);
                lang.send(sender, this, "admin.given", Map.of("amount", String.valueOf(amount), "block", def.name(), "player", target.getName()));
            }
            case "list" -> {
                if (registry.all().isEmpty()) lang.send(sender, this, "admin.list-empty");
                else lang.send(sender, this, "admin.list", Map.of("count", String.valueOf(registry.all().size()), "blocks", String.join(", ", ids())));
                if (lastProblem != null) lang.send(sender, this, "admin.problem", Map.of("problem", lastProblem));
            }
            case "reload" -> {
                reload();
                lang.send(sender, this, "admin.reloaded", Map.of("count", String.valueOf(registry.all().size())));
                if (lastProblem != null) lang.send(sender, this, "admin.problem", Map.of("problem", lastProblem));
            }
            default -> lang.send(sender, this, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return List.of("give", "list", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return ids().stream().filter(id -> id.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return null;
        return List.of();
    }
}
