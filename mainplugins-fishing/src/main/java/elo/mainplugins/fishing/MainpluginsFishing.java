package elo.mainplugins.fishing;

import elo.mainplugins.fishing.config.FishingConfigLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class MainpluginsFishing extends JavaPlugin {

    private FishingManager fishingManager;
    private FishingStatsManager statsManager;

    @Override
    public void onEnable() {
        statsManager = new FishingStatsManager(this);
        fishingManager = new FishingManager(this, FishingConfigLoader.load(this), statsManager);
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

        // /rybtop - ranking sumy zlowionych kg (na zawsze, bez resetow) + rekord serwera
        // na najciezsza pojedyncza rybe w historii (patrz FishingStatsManager). Dostepne
        // dla wszystkich, tez z konsoli (czysto informacyjne, nikomu nic nie psuje).
        if (getCommand("rybtop") != null) {
            getCommand("rybtop").setExecutor((sender, command, label, args) -> {
                sender.sendMessage(Component.text("=== Ranking wędkarski ===", NamedTextColor.AQUA, TextDecoration.BOLD));

                FishingStatsManager.RekordSerwera rekord = statsManager.getRekordSerwera();
                if (rekord != null) {
                    sender.sendMessage(Component.text("Rekord serwera: ", NamedTextColor.GOLD)
                            .append(Component.text(rekord.nick(), NamedTextColor.YELLOW))
                            .append(Component.text(" - " + String.format(Locale.ROOT, "%.1f", rekord.wagaKg()) + " kg (" + rekord.gatunek() + ")", NamedTextColor.GRAY)));
                }

                List<FishingStatsManager.TopRybak> top = statsManager.getTop(10);
                if (top.isEmpty()) {
                    sender.sendMessage(Component.text("Nikt jeszcze nic nie złowił w łowisku.", NamedTextColor.GRAY));
                } else {
                    int miejsce = 1;
                    for (FishingStatsManager.TopRybak rybak : top) {
                        sender.sendMessage(Component.text(miejsce + ". ", NamedTextColor.GRAY)
                                .append(Component.text(rybak.nick(), NamedTextColor.WHITE))
                                .append(Component.text(" - " + String.format(Locale.ROOT, "%.1f", rybak.sumaKg()) + " kg", NamedTextColor.GREEN)));
                        miejsce++;
                    }
                }

                if (sender instanceof Player player) {
                    int pozycja = statsManager.getPozycjaWRankingu(player.getUniqueId());
                    if (pozycja > 0) {
                        sender.sendMessage(Component.text("Twoja pozycja: #" + pozycja + " (" + String.format(Locale.ROOT, "%.1f", statsManager.sumaKg(player.getUniqueId())) + " kg)", NamedTextColor.YELLOW));
                    }
                }
                return true;
            });
        }

        // /rybindeks - "pokedex" gatunkow ryb, jako GUI (patrz FishingManager.otworzIndeks) -
        // pokazuje WYLACZNIE gatunki, ktore gracz JUZ kiedykolwiek zlowil. Nieodkryte
        // gatunki sa CALKOWICIE pominiete - zero "???"/placeholderow, tylko licznik
        // postepu w GUI (user 2026-08-29: nie chcial zeby kazda ryba byla widoczna w
        // indeksie z gory, i chcial GUI zamiast czatu).
        if (getCommand("rybindeks") != null) {
            getCommand("rybindeks").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                fishingManager.otworzIndeks(player);
                return true;
            });
        }

        // Kasuje CALKOWICIE statystyki rybackie (sumy kg, osobiste rekordy, rekordy
        // gatunkow) - patrz FishingStatsManager.wyczyscWszystko. Glownie do sprzatania po
        // testach na wedkach testowych (/wedka1-3 itd.), zeby fejkowe polowy admina nie
        // zostaly na zawsze w prawdziwej topce/rekordach graczy - nieodwracalne, bez
        // potwierdzenia (admin-only, patrz permisja).
        if (getCommand("@resetrybtop") != null) {
            getCommand("@resetrybtop").setExecutor((sender, command, label, args) -> {
                statsManager.wyczyscWszystko();
                sender.sendMessage("§aStatystyki rybackie (suma kg, rekordy) zostaly wyczyszczone.");
                return true;
            });
        }

        if (getCommand("@reloadfishing") != null) {
            getCommand("@reloadfishing").setExecutor((sender, command, label, args) -> {
                fishingManager.aktualizujKonfiguracje(FishingConfigLoader.load(this));
                sender.sendMessage("§aKonfiguracja lowienia (fishing-config.yml + ryby.yml) zostala przeladowana.");
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

    /** Zapis statystyk rybackich (suma kg/rekord) natychmiast, zanim serwer sie zamknie - patrz FishingStatsManager.zamknij. */
    @Override
    public void onDisable() {
        if (statsManager != null) statsManager.zamknij();
    }
}
