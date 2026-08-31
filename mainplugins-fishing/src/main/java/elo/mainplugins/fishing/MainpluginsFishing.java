package elo.mainplugins.fishing;

import elo.mainplugins.fishing.config.FishingConfigLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class MainpluginsFishing extends JavaPlugin {

    private FishingManager fishingManager;
    private FishingStatsManager statsManager;
    private WiaderkoManager wiaderkoManager;
    private LowiskoManager lowiskoManager;

    @Override
    public void onEnable() {
        statsManager = new FishingStatsManager(this);
        wiaderkoManager = new WiaderkoManager(this);
        lowiskoManager = new LowiskoManager(this);
        fishingManager = new FishingManager(this, FishingConfigLoader.load(this), statsManager, wiaderkoManager, lowiskoManager);
        getServer().getPluginManager().registerEvents(fishingManager, this);
        getServer().getPluginManager().registerEvents(wiaderkoManager, this);
        getServer().getPluginManager().registerEvents(lowiskoManager, this);

        // /wiaderko - Bezdenne Wiaderko (patrz WiaderkoManager) - TYMCZASOWA komenda,
        // dopoki nie ustalimy docelowego sposobu zdobycia (sklep? quest? - user
        // 2026-08-30 swiadomie zostawil to otwarte). Na razie admin-only, tak jak wedki
        // testowe - patrz permisja mainplugins.fishing.admin w plugin.yml.
        if (getCommand("wiaderko") != null) {
            getCommand("wiaderko").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(wiaderkoManager.stworzWiaderko());
                player.sendMessage("§aOtrzymałeś Bezdenne Wiaderko.");
                return true;
            });
        }

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
                fishingManager.wyslijRanking(sender);
                return true;
            });
        }

        // /rybiemenu - otwiera "Dziennik Rybaka", glowny rybacki hub GUI (patrz
        // FishingManager.otworzMenuGlowne) - na razie jedyna prawdziwa opcja w srodku to
        // Indeks Rybacki (patrz FishingManager.otworzIndeks), "pokedex" gatunkow ryb ktore
        // gracz JUZ kiedykolwiek zlowil. Nieodkryte gatunki sa CALKOWICIE pominiete - zero
        // "???"/placeholderow, tylko licznik postepu w GUI (user 2026-08-29/08-30).
        if (getCommand("rybiemenu") != null) {
            getCommand("rybiemenu").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                fishingManager.otworzMenuGlowne(player);
                return true;
            });
        }

        // Kasuje CALKOWICIE statystyki rybackie (sumy kg, osobiste rekordy, rekordy
        // gatunkow) - patrz FishingStatsManager.wyczyscWszystko. Glownie do sprzatania po
        // testach na wedkach testowych rzadkosci (/wedkazwykla itd.), zeby fejkowe polowy
        // admina nie zostaly na zawsze w prawdziwej topce/rekordach graczy - nieodwracalne,
        // bez potwierdzenia (admin-only, patrz permisja).
        if (getCommand("@resetrybtop") != null) {
            getCommand("@resetrybtop").setExecutor((sender, command, label, args) -> {
                statsManager.wyczyscWszystko();
                sender.sendMessage("§aStatystyki rybackie (suma kg, rekordy) zostaly wyczyszczone.");
                return true;
            });
        }

        // /@lowisko - strefy łowisk (patrz LowiskoManager, LowiskoCommands) - zastąpiło
        // dawne /@obszar ryby z mainplugins-spawn (usunięte 2026-08-31c).
        if (getCommand("@lowisko") != null) {
            LowiskoCommands lowiskoExecutor = new LowiskoCommands(lowiskoManager);
            getCommand("@lowisko").setExecutor(lowiskoExecutor);
            getCommand("@lowisko").setTabCompleter(lowiskoExecutor);
        }

        if (getCommand("@reloadfishing") != null) {
            getCommand("@reloadfishing").setExecutor((sender, command, label, args) -> {
                fishingManager.aktualizujKonfiguracje(FishingConfigLoader.load(this));
                lowiskoManager.przeladuj();
                sender.sendMessage("§aKonfiguracja lowienia (fishing-config.yml + ryby.yml + lowiska.yml) zostala przeladowana.");
                return true;
            });
        }

        // /@rybykomunikaty wlacz|wylacz - awaryjny wylacznik WSZYSTKICH publicznych
        // ogloszen na czacie zwiazanych z rybami (rekord gatunku/serwera, skompletowanie
        // indeksu - patrz FishingManager.komunikatyZablokowane) - user 2026-08-30: "gdyby
        // ktos znalazl jakiegos buga". Bez argumentu pokazuje aktualny stan. TYLKO w
        // pamieci (nie w pliku) - doraznie narzedzie, nie trwale ustawienie.
        if (getCommand("@rybykomunikaty") != null) {
            getCommand("@rybykomunikaty").setExecutor((sender, command, label, args) -> {
                if (args.length == 0) {
                    sender.sendMessage("§eOgloszenia rybackie na czacie sa teraz: " + (fishingManager.czyKomunikatyZablokowane() ? "§cZABLOKOWANE" : "§aWLACZONE") + "§e. Uzyj /@rybykomunikaty wlacz|wylacz.");
                    return true;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "wylacz" -> {
                        fishingManager.ustawKomunikatyZablokowane(true);
                        sender.sendMessage("§cOgloszenia rybackie na czacie ZABLOKOWANE (rekordy, skompletowanie indeksu).");
                    }
                    case "wlacz" -> {
                        fishingManager.ustawKomunikatyZablokowane(false);
                        sender.sendMessage("§aOgloszenia rybackie na czacie ODBLOKOWANE.");
                    }
                    default -> sender.sendMessage("§cUzycie: /@rybykomunikaty wlacz|wylacz");
                }
                return true;
            });
        }

        // Wędki testowe /wedka<rzadkosc> (np. /wedkazwykla, /wedkamityczna) - patrz
        // FishingManager.stworzWedkeTestowa. Każda wymusza LOSOWY gatunek z DANEJ
        // rzadkości (nie konkretny gatunek jak dawne /wedka1-3) i prawie natychmiastowe
        // branie, żeby dało się łatwo przetestować każdy poziom rzadkości po kolei. Za
        // permisją mainplugins.fishing.admin (patrz plugin.yml) - NIE dla zwykłych graczy.
        for (RybaGatunek.Rzadkosc rzadkosc : RybaGatunek.Rzadkosc.values()) {
            String komenda = "wedka" + rzadkosc.name().toLowerCase(Locale.ROOT);
            if (getCommand(komenda) != null) {
                getCommand(komenda).setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                        return true;
                    }
                    player.getInventory().addItem(fishingManager.stworzWedkeTestowa(rzadkosc));
                    player.sendMessage("§aOtrzymałeś wędkę testową rzadkości: " + rzadkosc + ".");
                    return true;
                });
            }
        }

        // Wędki testowe profilu /wedka1 /wedka2 /wedka3 (user 2026-08-31b: numerowane
        // komendy "tak jak było kiedyś", zamiast nazwanych po profilu jak dotąd) - patrz
        // FishingManager.stworzWedkeProfilTestowa i WedkaProfil. W odróżnieniu od wędek
        // rzadkości wyżej NIE wymuszają gatunku, tylko przechylają normalne losowanie i
        // fizykę suwaka pod dany profil - do testowania różnic MIĘDZY wędkami. Kolejność
        // 1/2/3 = ZROWNOWAZONA/CIERPLIWA/SZARPANA (kolejność deklaracji w WedkaProfil),
        // każda z osobnym kolorem tekstury w resourcepacku (patrz stworzWedkeProfilTestowa)
        // - Zrównoważona zostaje przy dotychczasowym domyślnym wyglądzie wędki. Też za
        // permisją mainplugins.fishing.admin.
        WedkaProfil[] profileNumerowane = { WedkaProfil.ZROWNOWAZONA, WedkaProfil.CIERPLIWA, WedkaProfil.SZARPANA };
        for (int i = 0; i < profileNumerowane.length; i++) {
            WedkaProfil profil = profileNumerowane[i];
            String komenda = "wedka" + (i + 1);
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
