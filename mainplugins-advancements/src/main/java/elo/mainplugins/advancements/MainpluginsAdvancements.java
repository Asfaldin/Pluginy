package elo.mainplugins.advancements;

import elo.mainplugins.advancements.command.AchievementsCommand;
import elo.mainplugins.advancements.command.AdminAchievementsCommand;
import elo.mainplugins.advancements.command.ResetCommand;
import elo.mainplugins.advancements.config.AchievementsConfig;
import elo.mainplugins.advancements.config.AchievementsConfigLoader;
import elo.mainplugins.advancements.datapack.DatapackGenerator;
import elo.mainplugins.advancements.datapack.NativeAdvancementBridge;
import elo.mainplugins.advancements.gui.AchievementsGui;
import elo.mainplugins.advancements.gui.AchievementsGuiListener;
import elo.mainplugins.advancements.listener.AdvancementListeners;
import elo.mainplugins.advancements.notify.AchievementNotifier;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * System osiągnięć zbudowany NA wbudowanych advancementach Minecrafta:
 * <ul>
 *   <li>każdy vanilla advancement może dostać własną nagrodę do ODEBRANIA w GUI
 *       ({@code /osiagniecia}) - Minecraft pokazuje swój natywny toast, my dokładamy
 *       nagrodę + wpis w panelu,</li>
 *   <li>obok nich WŁASNE osiągnięcia (kasa, ranga, czas gry, statystyki, liczba
 *       advancementów) sprawdzane cyklicznie - z bezpiecznym efektem Bukkit API,</li>
 *   <li>przy {@code datapack.wlaczony: true} własne osiągnięcia trafiają też do
 *       natywnego ekranu ESC → Postępy (auto-generowany datapack, patrz
 *       {@link DatapackGenerator}) - wymaga {@code /reload} po zmianach,</li>
 *   <li>wszystko w osiagniecia.yml, przeładowanie na żywo: {@code /@reloadosiagniecia}.</li>
 * </ul>
 * Świadomie własny, niezależny plugin - to samodzielny system, a nie usługa
 * współdzielona przez inne moduły (jak ekonomia w Core).
 */
public final class MainpluginsAdvancements extends JavaPlugin {

    private AchievementManager manager;
    private AchievementNotifier notifier;
    private NativeAdvancementBridge natywne;
    private DatapackGenerator datapackGenerator;
    private CustomTriggerChecker checker;
    private BukkitTask zadanieSprawdzania;
    private int aktualnyInterwal;

    @Override
    public void onEnable() {
        AchievementsConfig cfg = AchievementsConfigLoader.load(this);

        notifier = new AchievementNotifier(this, cfg.powiadomienia());
        natywne = new NativeAdvancementBridge(cfg);
        datapackGenerator = new DatapackGenerator(this);
        manager = new AchievementManager(this, cfg, notifier, natywne);
        checker = new CustomTriggerChecker(manager);

        AchievementsGui gui = new AchievementsGui(manager, checker);

        getServer().getPluginManager().registerEvents(new AdvancementListeners(this, manager, checker), this);
        getServer().getPluginManager().registerEvents(new AchievementsGuiListener(manager, gui), this);

        if (getCommand("osiagniecia") != null) {
            getCommand("osiagniecia").setExecutor(new AchievementsCommand(gui));
        }
        if (getCommand("@osiagniecia") != null) {
            AdminAchievementsCommand admin = new AdminAchievementsCommand(manager, datapackGenerator, natywne);
            getCommand("@osiagniecia").setExecutor(admin);
            getCommand("@osiagniecia").setTabCompleter(admin);
        }
        if (getCommand("resetzadania") != null) {
            ResetCommand reset = new ResetCommand(manager);
            getCommand("resetzadania").setExecutor(reset);
            getCommand("resetzadania").setTabCompleter(reset);
        }
        if (getCommand("@reloadosiagniecia") != null) {
            getCommand("@reloadosiagniecia").setExecutor((sender, command, label, args) -> {
                AchievementsConfig nowa = AchievementsConfigLoader.load(this);
                manager.przeladuj(nowa);
                notifier.aktualizujKonfiguracje(nowa.powiadomienia());
                natywne.aktualizujKonfiguracje(nowa);
                zaplanujSprawdzanie(nowa.sprawdzanieCoSekund());
                DatapackGenerator.Wynik w = datapackGenerator.wygeneruj(nowa);
                sender.sendMessage("§aosiagniecia.yml zostało przeładowane (" + nowa.osiagniecia().size() + " osiągnięć).");
                sender.sendMessage((w.trzebaReload() ? "§e" : "§7") + "Datapack: " + w.info());
                return true;
            });
        }

        DatapackGenerator.Wynik w = datapackGenerator.wygeneruj(cfg);
        getLogger().info("Datapack osiągnięć: " + w.info());
        if (w.trzebaReload()) {
            getLogger().warning("Wpisz /reload (lub zrestartuj serwer), aby własne osiągnięcia pojawiły się w ESC → Postępy.");
        }

        zaplanujSprawdzanie(cfg.sprawdzanieCoSekund());
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.zamknij();
        }
    }

    /** (Prze)planowuje cykliczne sprawdzanie własnych warunków - restart tylko gdy interwał się zmienił. */
    private void zaplanujSprawdzanie(int sekundy) {
        if (zadanieSprawdzania != null && sekundy == aktualnyInterwal) {
            return;
        }
        if (zadanieSprawdzania != null) {
            zadanieSprawdzania.cancel();
        }
        long ticki = sekundy * 20L;
        zadanieSprawdzania = getServer().getScheduler().runTaskTimer(this, () -> {
            checker.run();
            manager.retryOczekujaceWszyscy();
        }, ticki, ticki);
        aktualnyInterwal = sekundy;
    }
}
