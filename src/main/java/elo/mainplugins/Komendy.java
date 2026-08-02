package elo.mainplugins;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Komendy implements CommandExecutor {

    private final MenuPomocyManager menuPomocyManager;
    private final TeleportManager teleportManager;

    public Komendy(MenuPomocyManager menuPomocyManager, TeleportManager teleportManager) {
        this.menuPomocyManager = menuPomocyManager;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Te komendy mogą być używane tylko przez gracza!");
            return true;
        }

        String cmd = command.getName().toLowerCase();

        switch (cmd) {
            case "menu" -> {
                menuPomocyManager.otworzMenuPomocy(player);
                return true;
            }
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
        }
        return false;
    }
}

// Pomocnicza klasa obsługująca logikę teleportacji, trzymana razem w sekcji komend
class TeleportManager {
    private final Map<UUID, UUID> oczekujaceProsby = new HashMap<>();

    public void wyslijProsbe(Player wysylajacy, String nazwaOdbiorcy) {
        Player odbiorca = Bukkit.getPlayerExact(nazwaOdbiorcy);

        if (odbiorca == null) {
            wysylajacy.sendMessage("Nie znaleziono gracza o takim nicku!");
            return;
        }

        if (wysylajacy.equals(odbiorca)) {
            wysylajacy.sendMessage("Nie możesz wysłać prośby do samego siebie!");
            return;
        }

        oczekujaceProsby.put(odbiorca.getUniqueId(), wysylajacy.getUniqueId());

        wysylajacy.sendMessage("Wysłano prośbę o teleportację do " + odbiorca.getName() + ".");
        odbiorca.sendMessage("Gracz " + wysylajacy.getName() + " chce się do Ciebie przeteleportować!");
        odbiorca.sendMessage("Wpisz /tpaccept, aby zaakceptować lub /tpdeny, aby odrzucić.");
    }

    public void zaakceptujProsbe(Player odbiorca) {
        UUID wysylajacyUUID = oczekujaceProsby.remove(odbiorca.getUniqueId());

        if (wysylajacyUUID == null) {
            odbiorca.sendMessage("Nie masz żadnych oczekujących próśb o teleportację.");
            return;
        }

        Player wysylajacy = Bukkit.getPlayer(wysylajacyUUID);
        if (wysylajacy == null) {
            odbiorca.sendMessage("Gracz, który wysłał prośbę, jest już offline.");
            return;
        }

        wysylajacy.teleport(odbiorca.getLocation());
        wysylajacy.sendMessage("Twoja prośba została zaakceptowana! Teleportacja...");
        odbiorca.sendMessage("Zaakceptowano prośbę gracza " + wysylajacy.getName() + ".");
    }

    public void odrzucProsbe(Player odbiorca) {
        UUID wysylajacyUUID = oczekujaceProsby.remove(odbiorca.getUniqueId());

        if (wysylajacyUUID == null) {
            odbiorca.sendMessage("Nie masz żadnych oczekujących próśb o teleportację.");
            return;
        }

        Player wysylajacy = Bukkit.getPlayer(wysylajacyUUID);
        if (wysylajacy != null) {
            wysylajacy.sendMessage("Twoja prośba o teleportację została odrzucona przez " + odbiorca.getName() + ".");
        }

        odbiorca.sendMessage("Odrzucono prośbę o teleportację.");
    }
}