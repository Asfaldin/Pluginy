package elo.mainplugins.core.placeholder;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.PlaceholderService;
import elo.mainplugins.core.util.MoneyFormat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Implementacja {@link PlaceholderService} + wbudowane placeholdery core (money, money_short). */
public final class PlaceholderManager implements PlaceholderService, Listener {

    private final PlaceholderRegistry<Plugin> registry;
    private final boolean papi;

    /** economy przez Supplier - ekonomia (własna lub Vault) powstaje w core później niż ten serwis. */
    public PlaceholderManager(Plugin core, Supplier<EconomyService> economy) {
        this.registry = new PlaceholderRegistry<>(core.getLogger()::warning);
        this.papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

        registry.register(core, (player, name) -> {
            if (player == null) return null;
            return switch (name) {
                case "money" -> MoneyFormat.pelna(economy.get().getKasa(player.getUniqueId()));
                case "money_short" -> MoneyFormat.kompaktowo(economy.get().getKasa(player.getUniqueId()));
                default -> null;
            };
        });

        if (papi) {
            PapiHook.registerExpansion(core, registry);
            core.getLogger().info("PlaceholderAPI found - %mainplugins_...% placeholders are available.");
        }
    }

    @Override
    public void register(Plugin owner, BiFunction<OfflinePlayer, String, String> resolver) {
        registry.register(owner, resolver);
    }

    @Override
    public String apply(Player player, String text) {
        return papi && player != null && text.indexOf('%') >= 0 ? PapiHook.setPlaceholders(player, text) : text;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        registry.unregister(event.getPlugin());
    }
}
