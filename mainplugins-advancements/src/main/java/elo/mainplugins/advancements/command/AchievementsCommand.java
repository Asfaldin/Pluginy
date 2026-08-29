package elo.mainplugins.advancements.command;

import elo.mainplugins.advancements.gui.AchievementsGui;
import elo.mainplugins.core.util.MenuBridge;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /osiagniecia} - otwiera panel osiągnięć. Rozpoznaje argument "zmenu" (patrz {@link MenuBridge}). */
public final class AchievementsCommand implements CommandExecutor {

    private final AchievementsGui gui;

    public AchievementsCommand(AchievementsGui gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tę komendę może wykonać tylko gracz.");
            return true;
        }
        gui.otworz(player, MenuBridge.isZMenu(args));
        return true;
    }
}
