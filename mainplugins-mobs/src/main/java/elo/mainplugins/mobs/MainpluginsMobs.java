package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Plugin TESTOWY mobów z Kreatora mobów. Moby czyta z paczki zasobów serwera (tej, którą
 * Core udostępnia graczom: plugins/MainpluginsCore/resourcepack/pack.zip) - z tego samego pliku
 * gracz dostaje modele części. /@mob spawn <id> | list | killall | reload
 */
public final class MainpluginsMobs extends JavaPlugin implements Listener, TabExecutor {

    private static final String PREFIX = "assets/mainplugins/mobs/";

    private final Map<String, MobDef> mobs = new LinkedHashMap<>();
    private final List<LiveMob> live = new ArrayList<>();
    private String lastProblem = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("@mob") != null) {
            getCommand("@mob").setExecutor(this);
            getCommand("@mob").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            live.removeIf(m -> {
                // zabity: części zostają na animację śmierci i rozpad, potem znikają
                if (!m.alive() && !m.dying()) m.startDeath();
                if (!m.finished()) return false;
                m.remove();
                return true;
            });
            for (LiveMob m : live) m.tick();
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        live.forEach(LiveMob::remove);
        live.clear();
    }

    private void reload() {
        reloadConfig();
        mobs.clear();
        lastProblem = null;
        File pack = new File(getDataFolder().getParentFile(), "MainpluginsCore/resourcepack/pack.zip");
        if (!pack.exists()) {
            lastProblem = "Nie ma paczki zasobów serwera (" + pack.getPath() + ") - wyślij paczkę z aplikacji (Texturepack Creator).";
            getLogger().warning(lastProblem);
            return;
        }
        try (ZipFile zip = new ZipFile(pack)) {
            Map<String, String> jsons = new HashMap<>();
            zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(PREFIX) && e.getName().endsWith(".json"))
                    .forEach(e -> jsons.put(e.getName().substring(PREFIX.length()), read(zip, e)));
            for (Map.Entry<String, String> e : jsons.entrySet()) {
                if (e.getKey().endsWith(".display.json")) continue;
                String id = e.getKey().substring(0, e.getKey().length() - ".json".length());
                String display = jsons.get(id + ".display.json");
                if (display == null) {
                    getLogger().warning("Mob " + id + ": brak " + id + ".display.json - zapisz go w aplikacji jeszcze raz (nowa wersja zapisuje modele części).");
                    continue;
                }
                try {
                    MobDef def = MobLoader.parse(e.getValue(), display);
                    mobs.put(def.id().toLowerCase(Locale.ROOT), def);
                } catch (RuntimeException ex) {
                    getLogger().warning("Mob " + id + ": zły plik - " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            lastProblem = "Nie udało się przeczytać paczki: " + ex.getMessage();
            getLogger().warning(lastProblem);
        }
        getLogger().info("Moby z paczki: " + String.join(", ", mobs.keySet()));
    }

    private static String read(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        for (LiveMob m : live) if (m.base.equals(event.getDamager())) m.attack();
    }

    /** Cios w hitbox części (obiekt interakcji) = cios w moba, słabe punkty mocniej. */
    @EventHandler(ignoreCancelled = true)
    public void onPartAttack(PrePlayerAttackEntityEvent event) {
        for (LiveMob m : live) {
            String bone = m.partOf(event.getAttacked());
            if (bone == null) continue;
            event.setCancelled(true);
            m.hitPart(event.getPlayer(), bone);
            return;
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        for (LiveMob m : live) {
            if (m.base.equals(event.getEntity()) || m.ownsBird(event.getEntity())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cTylko gracz.");
                    return true;
                }
                if (args.length < 2 || !mobs.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage("§cUżycie: /@mob spawn <" + String.join("|", mobs.keySet()) + ">"
                            + (lastProblem != null ? " §7(" + lastProblem + ")" : ""));
                    return true;
                }
                MobDef def = mobs.get(args[1].toLowerCase(Locale.ROOT));
                live.add(new LiveMob(def, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4)),
                        getConfig().getConfigurationSection("moby." + def.id().toLowerCase(Locale.ROOT))));
                sender.sendMessage("§aPostawiono: " + def.name());
            }
            case "list" -> sender.sendMessage(mobs.isEmpty() ? "§eBrak mobów w paczce." + (lastProblem != null ? " " + lastProblem : "")
                    : "§eMoby: §f" + String.join(", ", mobs.keySet()) + " §7(na świecie: " + live.size() + ")");
            case "killall" -> {
                live.forEach(LiveMob::remove);
                sender.sendMessage("§aUsunięto moby testowe: " + live.size());
                live.clear();
            }
            case "reload" -> {
                reload();
                sender.sendMessage("§aWczytano config.yml i moby z paczki: " + mobs.size() + (lastProblem != null ? " §7(" + lastProblem + ")" : ""));
            }
            default -> sender.sendMessage("§eUżycie: /@mob spawn <mob> | list | killall | reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return List.of("spawn", "list", "killall", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) return new ArrayList<>(mobs.keySet());
        return List.of();
    }
}
