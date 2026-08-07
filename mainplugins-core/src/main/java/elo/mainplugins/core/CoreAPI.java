package elo.mainplugins.core;

import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.RankService;
import elo.mainplugins.core.api.ToolsService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Jedyny "oficjalny" punkt wejścia do usług Core dla pozostałych pluginów.
 * Reszta ekosystemu nie powinna nigdy tworzyć własnej instancji EconomyManager
 * ani grzebać w ServicesManager ręcznie - wystarczy CoreAPI.getEconomyService().
 *
 * Wymaga, żeby MainpluginsCore był wpisany jako "depend" w plugin.yml pluginu,
 * który z tego korzysta (Bukkit gwarantuje wtedy kolejność ładowania i dostęp
 * do klas Core przez classloader zależności).
 */
public final class CoreAPI {

    private CoreAPI() {}

    public static EconomyService getEconomyService() {
        RegisteredServiceProvider<EconomyService> rsp = Bukkit.getServicesManager().getRegistration(EconomyService.class);
        if (rsp == null) {
            throw new IllegalStateException("MainpluginsCore nie jest włączony lub nie zarejestrował jeszcze EconomyService - sprawdź plugin.yml (depend: [MainpluginsCore]).");
        }
        return rsp.getProvider();
    }

    /**
     * W przeciwieństwie do EconomyService - opcjonalny. Zwraca null, jeśli
     * MainpluginsSkyblock nie jest wgrany/włączony; wołający musi mieć na to
     * sensowny fallback (patrz javadoc {@link IslandService}).
     */
    public static IslandService getIslandService() {
        RegisteredServiceProvider<IslandService> rsp = Bukkit.getServicesManager().getRegistration(IslandService.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    /** Opcjonalny jak {@link #getIslandService()} - zwraca null, jeśli mainplugins-tools nie jest wgrany/włączony. */
    public static ToolsService getToolsService() {
        RegisteredServiceProvider<ToolsService> rsp = Bukkit.getServicesManager().getRegistration(ToolsService.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    /** Opcjonalny jak {@link #getIslandService()} - zwraca null, jeśli mainplugins-ranks nie jest wgrany/włączony. */
    public static RankService getRankService() {
        RegisteredServiceProvider<RankService> rsp = Bukkit.getServicesManager().getRegistration(RankService.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    /** Opcjonalny jak {@link #getIslandService()} - zwraca null, jeśli mainplugins-crates nie jest wgrany/włączony. */
    public static CrateService getCrateService() {
        RegisteredServiceProvider<CrateService> rsp = Bukkit.getServicesManager().getRegistration(CrateService.class);
        return rsp != null ? rsp.getProvider() : null;
    }
}