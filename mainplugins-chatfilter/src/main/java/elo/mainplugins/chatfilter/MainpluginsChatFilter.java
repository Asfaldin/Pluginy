package elo.mainplugins.chatfilter;

import elo.mainplugins.chatfilter.config.ChatFilterConfig;
import elo.mainplugins.chatfilter.config.ChatFilterConfigLoader;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ochrona czatu - anty-spam, anty-caps, anty-reklama, limit dlugosci, powtorzenia znakow
 * i wiadomosci, osobiste wyciszenia. Progi/wlacz-wylacz kazdego filtra sa w pelni
 * konfigurowalne z chatfilter-config.yml (patrz ChatFilterConfigLoader), przeladowanie
 * na zywo: /@reloadchatfilter. Swiadomie wlasny, niezalezny plugin zamiast wrzucania
 * tego do Core - to nie jest usluga wspoldzielona miedzy innymi pluginami, tylko
 * samodzielna ochrona czatu.
 */
public final class MainpluginsChatFilter extends JavaPlugin {

    private ChatSpamManager chatSpamManager;
    private ChatLengthManager chatLengthManager;
    private AntiAdManager antiAdManager;
    private RepeatMessageManager repeatMessageManager;
    private CapsLockManager capsLockManager;
    private RepeatedCharsManager repeatedCharsManager;

    @Override
    public void onEnable() {
        ChatFilterConfig cfg = ChatFilterConfigLoader.load(this);

        chatSpamManager = new ChatSpamManager(cfg.antySpam());
        chatLengthManager = new ChatLengthManager(cfg.dlugoscWiadomosci());
        antiAdManager = new AntiAdManager(cfg.antyReklama());
        repeatMessageManager = new RepeatMessageManager(cfg.powtorzonaWiadomosc());
        capsLockManager = new CapsLockManager(cfg.antyCaps());
        repeatedCharsManager = new RepeatedCharsManager(cfg.powtarzajaceZnaki());

        getServer().getPluginManager().registerEvents(chatSpamManager, this);
        getServer().getPluginManager().registerEvents(chatLengthManager, this);
        getServer().getPluginManager().registerEvents(antiAdManager, this);
        getServer().getPluginManager().registerEvents(repeatMessageManager, this);
        getServer().getPluginManager().registerEvents(capsLockManager, this);
        getServer().getPluginManager().registerEvents(repeatedCharsManager, this);

        MuteManager muteManager = new MuteManager(this);
        getServer().getPluginManager().registerEvents(muteManager, this);
        if (getCommand("wycisz") != null) {
            MuteCommand muteCommand = new MuteCommand(muteManager);
            getCommand("wycisz").setExecutor(muteCommand);
            getCommand("wycisz").setTabCompleter(muteCommand);
        }

        if (getCommand("@reloadchatfilter") != null) {
            getCommand("@reloadchatfilter").setExecutor((sender, command, label, args) -> {
                ChatFilterConfig nowa = ChatFilterConfigLoader.load(this);
                chatSpamManager.aktualizujKonfiguracje(nowa.antySpam());
                chatLengthManager.aktualizujKonfiguracje(nowa.dlugoscWiadomosci());
                antiAdManager.aktualizujKonfiguracje(nowa.antyReklama());
                repeatMessageManager.aktualizujKonfiguracje(nowa.powtorzonaWiadomosc());
                capsLockManager.aktualizujKonfiguracje(nowa.antyCaps());
                repeatedCharsManager.aktualizujKonfiguracje(nowa.powtarzajaceZnaki());
                sender.sendMessage("§aChatfilter-config.yml zostało przeładowane.");
                return true;
            });
        }
    }
}
