package elo.mainplugins.fishing;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsFishing extends JavaPlugin {

    private FishingManager fishingManager;

    @Override
    public void onEnable() {
        fishingManager = new FishingManager(this);
        getServer().getPluginManager().registerEvents(fishingManager, this);

        if (getCommand("wedka") != null) {
            getCommand("wedka").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(fishingManager.stworzWedke());
                player.sendMessage("§aOtrzymałeś wędkę.");
                return true;
            });
        }

        // /rybpasek gora|dol - patrz PozycjaPaska, FishingManager.pozycjaPaska/ustawPozycjePaska.
        // Bez argumentów pokazuje aktualne ustawienie. Dostępne dla wszystkich graczy, nie
        // tylko admina - to preferencja UI, nie coś co wpływa na rozgrywkę innych.
        if (getCommand("rybpasek") != null) {
            getCommand("rybpasek").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                if (args.length == 0) {
                    PozycjaPaska aktualna = fishingManager.pozycjaPaska(player);
                    player.sendMessage("§eAktualna pozycja paska łowienia: " + aktualna.opis() + ". Użyj /rybpasek gora albo /rybpasek dol.");
                    return true;
                }
                PozycjaPaska nowa = switch (args[0].toLowerCase()) {
                    case "gora", "góra", "up", "top" -> PozycjaPaska.GORA;
                    case "dol", "dół", "down", "bottom" -> PozycjaPaska.DOL;
                    default -> null;
                };
                if (nowa == null) {
                    player.sendMessage("§cUżycie: /rybpasek gora|dol");
                    return true;
                }
                fishingManager.ustawPozycjePaska(player, nowa);
                player.sendMessage("§aPasek minigry łowienia będzie teraz wyświetlany: " + nowa.opis() + ".");
                return true;
            });
        }

        if (getCommand("@reloadfishing") != null) {
            getCommand("@reloadfishing").setExecutor((sender, command, label, args) -> {
                fishingManager.wczytajGatunki();
                sender.sendMessage("§aGatunki ryb zostały przeładowane.");
                return true;
            });
        }

        // Wędki testowe /wedka1../wedka3 - patrz FishingManager.stworzWedkeTestowa. Za
        // permisją mainplugins.fishing.admin (patrz plugin.yml) - NIE dla zwykłych graczy,
        // bo wymuszają gatunek i prawie natychmiastowe branie.
        for (int i = 1; i <= 3; i++) {
            int indeks = i;
            if (getCommand("wedka" + i) != null) {
                getCommand("wedka" + i).setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                        return true;
                    }
                    player.getInventory().addItem(fishingManager.stworzWedkeTestowa(indeks));
                    player.sendMessage("§aOtrzymałeś wędkę testową #" + indeks + ".");
                    return true;
                });
            }
        }

        // Wędki testowe profilu /wedkazrownowazona, /wedkacierpliwa, /wedkaszarpana -
        // patrz FishingManager.stworzWedkeProfilTestowa i WedkaProfil. W odróżnieniu od
        // wedka1-3 wyżej NIE wymuszają gatunku, tylko przechylają normalne losowanie i
        // fizykę suwaka pod dany profil - do testowania różnic MIĘDZY wędkami, nie
        // konkretnych gatunków. Też za permisją mainplugins.fishing.admin.
        for (WedkaProfil profil : WedkaProfil.values()) {
            String komenda = "wedka" + profil.name().toLowerCase();
            if (getCommand(komenda) != null) {
                getCommand(komenda).setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                        return true;
                    }
                    player.getInventory().addItem(fishingManager.stworzWedkeProfilTestowa(profil));
                    player.sendMessage("§aOtrzymałeś wędkę testową profilu: " + profil.nazwa() + ".");
                    return true;
                });
            }
        }
    }
}
