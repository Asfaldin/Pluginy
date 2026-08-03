package elo.mainplugins.hud;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.TopGracz;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.UUID;

/**
 * Wspólne pobieranie danych do tablicy graczy i scoreboardu. IslandService jest
 * opcjonalny (MainpluginsSkyblock może w ogóle nie być wgrany) - w przeciwieństwie
 * do EconomyService nie ma go w depend, więc zawsze sprawdzamy przez
 * ServicesManager na bieżąco, zamiast raz w konstruktorze (Skyblock mógłby
 * włączyć się później niż HUD).
 *
 * Topki (najbogatsi/największe wyspy) pokazują dane zmyślone, jeśli prawdziwych
 * jeszcze nie ma (świeży serwer, zero graczy z kasą albo zero wysp) - żeby HUD
 * od pierwszej sekundy wyglądał gotowo, a nie pusto. Dane o TOBIE (własna wyspa)
 * nigdy nie są zmyślane - albo są prawdziwe, albo pokazujemy szczerze "Brak".
 */
final class HudData {

    private HudData() {}

    private static final List<TopGracz> FAKE_TOP_GRACZY = List.of(
            new TopGracz(null, "Steve", 15000.0),
            new TopGracz(null, "Alex", 10500.0),
            new TopGracz(null, "Notch", 8200.0)
    );

    private static final List<IslandSummary> FAKE_TOP_WYSP = List.of(
            new IslandSummary(null, "Steve", 120, 3, java.util.Map.of()),
            new IslandSummary(null, "Alex", 95, 2, java.util.Map.of()),
            new IslandSummary(null, "Notch", 80, 1, java.util.Map.of())
    );

    static List<TopGracz> pobierzTopGraczy(EconomyService economy, int limit) {
        List<TopGracz> realne = economy.getTop(limit);
        if (!realne.isEmpty()) return realne;
        return FAKE_TOP_GRACZY.subList(0, Math.min(limit, FAKE_TOP_GRACZY.size()));
    }

    static List<IslandSummary> pobierzTopWysp(int limit) {
        IslandService islandService = znajdzIslandService();
        if (islandService != null) {
            List<IslandSummary> realne = islandService.getTopIslands(limit);
            if (!realne.isEmpty()) return realne;
        }
        return FAKE_TOP_WYSP.subList(0, Math.min(limit, FAKE_TOP_WYSP.size()));
    }

    /** Prawdziwa wyspa gracza albo null - nigdy nie zmyślona, patrz komentarz klasy. */
    static IslandSummary pobierzWlasnaWyspe(UUID playerUUID) {
        IslandService islandService = znajdzIslandService();
        return islandService != null ? islandService.getIslandOf(playerUUID) : null;
    }

    private static IslandService znajdzIslandService() {
        RegisteredServiceProvider<IslandService> rsp = Bukkit.getServicesManager().getRegistration(IslandService.class);
        return rsp != null ? rsp.getProvider() : null;
    }
}