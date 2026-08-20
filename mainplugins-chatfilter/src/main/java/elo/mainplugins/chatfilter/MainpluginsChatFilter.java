package elo.mainplugins.chatfilter;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ochrona czatu - na razie tylko anty-spam (cooldown między wiadomościami), docelowo
 * dojdzie tu też filtr przekleństw (osobna funkcjonalność, do ustalenia). Świadomie
 * własny, niezależny plugin zamiast wrzucania tego do Core - to nie jest usługa
 * współdzielona między innymi pluginami, tylko samodzielna ochrona czatu.
 */
public final class MainpluginsChatFilter extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new ChatSpamManager(), this);
        getServer().getPluginManager().registerEvents(new ChatLengthManager(), this);
        getServer().getPluginManager().registerEvents(new AntiAdManager(), this);
        getServer().getPluginManager().registerEvents(new RepeatMessageManager(), this);
        getServer().getPluginManager().registerEvents(new CapsLockManager(), this);
        getServer().getPluginManager().registerEvents(new RepeatedCharsManager(), this);

        MuteManager muteManager = new MuteManager(this);
        getServer().getPluginManager().registerEvents(muteManager, this);
        if (getCommand("wycisz") != null) {
            getCommand("wycisz").setExecutor(new MuteCommand(muteManager));
        }
    }
}
