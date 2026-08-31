package elo.mainplugins.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * /spawn i /warp (każdy), /@setspawn [info], /@obszar <wand|usun|lista|info|moby|border> ...,
 * /@setwarp <nazwa> i /@delwarp <nazwa> - te ostatnie cztery chronione uprawnieniem
 * mainplugins.spawn.admin (patrz plugin.yml - Bukkit sam odrzuca wywołanie zanim trafi do
 * onCommand). Cała logika w SpawnManager/ObszarManager/WarpManager. Ta sama klasa dostarcza
 * też podpowiedzi Tab (patrz onTabComplete) - żeby admin nie musiał pamiętać z głowy
 * dokładnej listy podkomend/nazw obszarów/warpów.
 *
 * Podkomenda "ryby" (łowisko) USUNIĘTA 2026-08-31c - mainplugins-fishing ma teraz WŁASNY,
 * całkowicie niezależny system stref łowisk (patrz LowiskoManager w mainplugins-fishing,
 * komenda /@lowisko) - ten plugin idzie na sprzedaż, więc nie powinien nic wiedzieć o rybach.
 */
public class SpawnCommands implements CommandExecutor, TabCompleter {

    private static final List<String> PODKOMENDY_OBSZAR = List.of("wand", "usun", "lista", "info", "moby", "border");
    private static final List<String> PODKOMENDY_SETSPAWN = List.of("info");
    private static final List<String> TYPY_MOBOW = List.of("pasywne", "agresywne");
    private static final List<String> STANY = List.of("on", "off");
    // Podkomendy, których drugi argument to nazwa istniejącego obszaru - patrz onTabComplete.
    private static final List<String> PODKOMENDY_Z_NAZWA = List.of("wand", "usun", "info", "moby", "border");

    private final SpawnManager spawnManager;
    private final ObszarManager obszarManager;
    private final WarpManager warpManager;

    public SpawnCommands(SpawnManager spawnManager, ObszarManager obszarManager, WarpManager warpManager) {
        this.spawnManager = spawnManager;
        this.obszarManager = obszarManager;
        this.warpManager = warpManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz może użyć tej komendy.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "spawn" -> spawnManager.teleportujNaSpawn(player);
            case "@setspawn" -> handleSetspawn(player, args);
            case "@obszar" -> handleObszar(player, args);
            case "warp" -> handleWarp(player, args);
            case "@setwarp" -> handleSetwarp(player, args);
            case "@delwarp" -> handleDelwarp(player, args);
        }
        return true;
    }

    private void handleWarp(Player player, String[] args) {
        if (args.length == 0) {
            Set<String> nazwy = warpManager.nazwyWarpow();
            player.sendMessage(Component.text(nazwy.isEmpty() ? "Brak ustawionych warpów." : "Warpy: " + String.join(", ", nazwy), NamedTextColor.YELLOW));
            return;
        }
        warpManager.teleportujDoWarpu(player, args[0]);
    }

    private void handleSetwarp(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Component.text("Użycie: /@setwarp <nazwa>", NamedTextColor.RED));
            return;
        }
        warpManager.ustawWarp(player, args[0]);
    }

    private void handleDelwarp(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Component.text("Użycie: /@delwarp <nazwa>", NamedTextColor.RED));
            return;
        }
        warpManager.usunWarp(player, args[0]);
    }

    private void handleSetspawn(Player player, String[] args) {
        if (args.length == 0) {
            spawnManager.ustawPunkt(player);
        } else if (args[0].equalsIgnoreCase("info")) {
            player.sendMessage(Component.text("Punkt spawnu: " + spawnManager.opisPunktu(), NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Użycie: /@setspawn [info]", NamedTextColor.RED));
        }
    }

    private void handleObszar(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Component.text("Użycie: /@obszar <wand|usun|lista|info|moby|border> ...", NamedTextColor.RED));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "border" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@obszar border <nazwa>", NamedTextColor.RED));
                    return;
                }
                obszarManager.pokazGranice(player, args[1]);
            }
            case "wand" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@obszar wand <nazwa>", NamedTextColor.RED));
                    return;
                }
                obszarManager.dajRozdzke(player, args[1]);
            }
            case "usun" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@obszar usun <nazwa>", NamedTextColor.RED));
                    return;
                }
                obszarManager.usun(player, args[1]);
            }
            case "lista" -> obszarManager.listuj(player);
            case "info" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@obszar info <nazwa>", NamedTextColor.RED));
                    return;
                }
                obszarManager.info(player, args[1]);
            }
            case "moby" -> handleObszarMoby(player, args);
            default -> player.sendMessage(Component.text("Użycie: /@obszar <wand|usun|lista|info|moby|ryby|border> ...", NamedTextColor.RED));
        }
    }

    private void handleObszarMoby(Player player, String[] args) {
        if (args.length < 4 || !isTyp(args[2]) || !isStan(args[3])) {
            player.sendMessage(Component.text("Użycie: /@obszar moby <nazwa> <pasywne|agresywne> <on|off>", NamedTextColor.RED));
            return;
        }
        boolean pasywne = args[2].equalsIgnoreCase("pasywne");
        boolean wlacz = args[3].equalsIgnoreCase("on");
        obszarManager.ustawMoby(player, args[1], pasywne, wlacz);
    }

    private boolean isTyp(String s) {
        return s.equalsIgnoreCase("pasywne") || s.equalsIgnoreCase("agresywne");
    }

    private boolean isStan(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("off");
    }

    // ==================================================== Podpowiedzi Tab ====

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        String nazwaKomendy = command.getName().toLowerCase();

        if (nazwaKomendy.equals("@setspawn")) {
            return args.length == 1 ? dopasuj(args[0], PODKOMENDY_SETSPAWN) : List.of();
        }
        if (nazwaKomendy.equals("warp") || nazwaKomendy.equals("@delwarp")) {
            return args.length == 1 ? dopasuj(args[0], warpManager.nazwyWarpow()) : List.of();
        }
        if (!nazwaKomendy.equals("@obszar")) return List.of();

        if (args.length == 1) {
            return dopasuj(args[0], PODKOMENDY_OBSZAR);
        }

        String podkomenda = args[0].toLowerCase();
        if (args.length == 2 && PODKOMENDY_Z_NAZWA.contains(podkomenda)) {
            return dopasuj(args[1], obszarManager.nazwyObszarow());
        }
        if (podkomenda.equals("moby")) {
            if (args.length == 3) return dopasuj(args[2], TYPY_MOBOW);
            if (args.length == 4) return dopasuj(args[3], STANY);
        }
        return List.of();
    }

    private List<String> dopasuj(String wpisane, Collection<String> opcje) {
        return StringUtil.copyPartialMatches(wpisane, opcje, new ArrayList<>());
    }
}
