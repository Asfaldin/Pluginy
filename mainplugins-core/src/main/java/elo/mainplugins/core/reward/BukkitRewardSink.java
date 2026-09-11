package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;

/** RewardSink dla prawdziwego gracza. Pełny ekwipunek = reszta wypada pod nogi. */
final class BukkitRewardSink implements RewardSink {

    private final Plugin core;
    private final Player player;
    private final EconomyService economy;
    private final CustomItemService items;
    private final LangService lang;
    private final Function<String, RewardHandler> handlers;

    BukkitRewardSink(Plugin core, Player player, EconomyService economy, CustomItemService items,
                     LangService lang, Function<String, RewardHandler> handlers) {
        this.core = core;
        this.player = player;
        this.economy = economy;
        this.items = items;
        this.lang = lang;
        this.handlers = handlers;
    }

    @Override
    public String playerName() {
        return player.getName();
    }

    @Override
    public void giveMoney(double amount) {
        economy.dodajGrosze(player.getUniqueId(), Math.round(amount * 100));
    }

    @Override
    public boolean giveItem(String material, int amount) {
        Material m = Material.matchMaterial(material);
        if (m == null || !m.isItem()) return false;
        giveStack(new ItemStack(m, amount));
        return true;
    }

    @Override
    public boolean giveCustom(String id, int amount) {
        ItemStack item = items.create(id, amount, player);
        if (item == null) return false;
        giveStack(item);
        return true;
    }

    @Override
    public void runCommand(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public boolean giveExternal(Reward reward) {
        RewardHandler handler = handlers.apply(reward.type());
        if (handler == null) return false;
        try {
            return handler.give(player, reward);
        } catch (RuntimeException e) {
            core.getLogger().log(Level.WARNING, "Reward handler for '" + reward.type() + "' failed.", e);
            return false;
        }
    }

    @Override
    public void message(String key, Map<String, String> placeholders) {
        lang.send(player, core, key, placeholders);
    }

    private void giveStack(ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }
}
