package elo.mainplugins.market;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import org.bukkit.plugin.java.JavaPlugin;

/** Targ graczy - działa z samym core. */
public final class MainpluginsMarket extends JavaPlugin {

    private MarketManager market;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("market")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-market - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: market: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        market = new MarketManager(this, lang, CoreAPI.getItemNameService(), CoreAPI.getEconomyService());
        getServer().getPluginManager().registerEvents(market, this);

        if (getCommand("targ") != null) {
            getCommand("targ").setExecutor(MarketCommand.player(this, market, lang));
            getCommand("targ").setTabCompleter(MarketCommand.playerTab());
        }
        if (getCommand("@market") != null) {
            MarketCommand.Admin admin = new MarketCommand.Admin(this, market, lang);
            getCommand("@market").setExecutor(admin);
            getCommand("@market").setTabCompleter(admin);
        }
    }

    @Override
    public void onDisable() {
        if (market != null) market.close();
    }
}
