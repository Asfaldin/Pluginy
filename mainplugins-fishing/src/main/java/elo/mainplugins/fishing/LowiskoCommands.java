package elo.mainplugins.fishing;

import elo.mainplugins.core.util.TabCompleteUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * /@lowisko <wand|usun|lista|info|border> ... - patrz LowiskoManager. Sama lista gatunków
 * per łowisko NIE ma tu własnej podkomendy (celowo, patrz Lowisko/LowiskoManager) - to
 * ręcznie edytowalna sekcja "gatunki" w lowiska.yml + /@reloadfishing, ten sam wzorzec co
 * ryby.yml, żeby admin mógł wkleić/poprawić całą listę naraz zamiast klikać komendami
 * pojedynczo. Ta klasa dostarcza też podpowiedzi Tab (patrz onTabComplete) - ten sam
 * mechanizm co SpawnCommands/@obszar w mainplugins-spawn (świadomie zduplikowany, nie
 * reużywany - patrz LowiskoManager).
 */
public class LowiskoCommands implements CommandExecutor, TabCompleter {

    private static final List<String> PODKOMENDY = List.of("wand", "usun", "lista", "info", "border");
    private static final List<String> PODKOMENDY_Z_NAZWA = List.of("wand", "usun", "info", "border");

    private final LowiskoManager lowiskoManager;

    public LowiskoCommands(LowiskoManager lowiskoManager) {
        this.lowiskoManager = lowiskoManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz może użyć tej komendy.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Użycie: /@lowisko <wand|usun|lista|info|border> ...", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "wand" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@lowisko wand <nazwa>", NamedTextColor.RED));
                    return true;
                }
                lowiskoManager.dajRozdzke(player, args[1]);
            }
            case "usun" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@lowisko usun <nazwa>", NamedTextColor.RED));
                    return true;
                }
                lowiskoManager.usun(player, args[1]);
            }
            case "lista" -> lowiskoManager.listuj(player);
            case "info" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@lowisko info <nazwa>", NamedTextColor.RED));
                    return true;
                }
                lowiskoManager.info(player, args[1]);
            }
            case "border" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Użycie: /@lowisko border <nazwa>", NamedTextColor.RED));
                    return true;
                }
                lowiskoManager.pokazGranice(player, args[1]);
            }
            default -> player.sendMessage(Component.text("Użycie: /@lowisko <wand|usun|lista|info|border> ...", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return TabCompleteUtils.dopasuj(args[0], PODKOMENDY);
        }
        if (args.length == 2 && PODKOMENDY_Z_NAZWA.contains(args[0].toLowerCase())) {
            return TabCompleteUtils.dopasuj(args[1], lowiskoManager.nazwyLowisk());
        }
        return TabCompleteUtils.PUSTA;
    }
}
