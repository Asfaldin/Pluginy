package elo.mainplugins.core;

import elo.mainplugins.core.api.EconomyService;
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
}