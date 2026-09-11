package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardHandler;
import elo.mainplugins.core.api.RewardService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Implementacja {@link RewardService} + rejestr typów nagród pluginów. */
public final class RewardManager implements RewardService, Listener {

    private record Registration(Plugin owner, RewardHandler handler) {}

    private static final Set<String> BUILT_IN = Set.of(MONEY, ITEM, CUSTOM, COMMAND);

    private final Plugin core;
    private final EconomyService economy;
    private final CustomItemService items;
    private final LangService lang;
    private final RewardParser parser;
    private final RewardGiver giver;
    private final Map<String, Registration> handlers = new HashMap<>();

    public RewardManager(Plugin core, EconomyService economy, CustomItemService items, LangService lang) {
        this.core = core;
        this.economy = economy;
        this.items = items;
        this.lang = lang;
        Consumer<String> warn = core.getLogger()::warning;
        this.parser = new RewardParser(name -> Material.matchMaterial(name) != null, warn);
        this.giver = new RewardGiver(warn);
    }

    @Override
    public List<Reward> parse(List<?> entries, String source) {
        return parser.parse(entries, source);
    }

    @Override
    public void give(Player player, List<Reward> rewards) {
        giver.give(rewards, new BukkitRewardSink(core, player, economy, items, lang, this::handlerFor));
    }

    @Override
    public void registerType(Plugin owner, String type, RewardHandler handler) {
        String key = type.toLowerCase(Locale.ROOT);
        if (BUILT_IN.contains(key) || RewardParser.RESERVED.contains(key)) {
            throw new IllegalArgumentException("'" + type + "' is a built-in reward word and cannot be registered.");
        }
        Registration old = handlers.put(key, new Registration(owner, handler));
        if (old != null && !old.owner().equals(owner)) {
            core.getLogger().warning("Reward type '" + key + "' of " + old.owner().getName()
                    + " was replaced by " + owner.getName() + ".");
        }
    }

    private RewardHandler handlerFor(String type) {
        Registration registration = handlers.get(type);
        return registration == null ? null : registration.handler();
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        handlers.values().removeIf(r -> r.owner().equals(event.getPlugin()));
    }
}
