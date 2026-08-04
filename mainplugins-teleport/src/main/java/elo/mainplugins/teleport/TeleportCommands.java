package elo.mainplugins.teleport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Wcześniej ta klasa (jako "Komendy") w ogóle nie była zarejestrowana w głównym
 * pluginie - żyła w repo jako martwy kod, a jej komendy (/tp, /tpaccept, /tpdeny)
 * nie istniały nawet w plugin.yml. Tutaj jest w końcu realnie podpięta. Case "menu"
 * z oryginału został usunięty - komenda /menu należy wyłącznie do MainpluginsMenu,
 * nie może jej rejestrować drugi plugin.
 */
public class TeleportCommands implements CommandExecutor {

    private final TeleportManager teleportManager;
    private final SpawnManager spawnManager;

    public TeleportCommands(TeleportManager teleportManager, SpawnManager spawnManager) {
        this.teleportManager = teleportManager;
        this.spawnManager = spawnManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Te komendy mogą być używane tylko przez gracza!");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "tp" -> {
                if (args.length < 1) {
                    player.sendMessage("Użycie: /tp <gracz>");
                    return true;
                }
                teleportManager.wyslijProsbe(player, args[0]);
                return true;
            }
            case "tpaccept" -> {
                teleportManager.zaakceptujProsbe(player);
                return true;
            }
            case "tpdeny" -> {
                teleportManager.odrzucProsbe(player);
                return true;
            }
            case "spawn" -> {
                spawnManager.teleportujNaSpawn(player);
                return true;
            }
            case "spawnset" -> {
                if (!player.hasPermission("mainplugins.teleport.spawnset")) {
                    player.sendMessage("Nie masz uprawnień do tej komendy.");
                    return true;
                }
                spawnManager.ustawSpawn(player);
                return true;
            }
        }
        return false;
    }
}