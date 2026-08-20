package elo.mainplugins.shop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /@statsklep — podgląd bieżących statystyk sprzedaży bez wychodzenia z gry.
 * Pokazuje ruch z bieżącej doby, więc odpowiada na pytanie "co się dzieje teraz",
 * a nie "jak było przez ostatni miesiąc".
 */
public class StatSklepCommand implements CommandExecutor {

    private static final int ILE_POZYCJI = 10;

    private final ShopManager shopManager;

    public StatSklepCommand(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        DynamicPriceManager ceny = shopManager.getCeny();
        StatystykiSklepu stat = ceny.getStatystyki();

        if (args.length > 0 && args[0].equalsIgnoreCase("snapshot")) {
            stat.wymusSnapshot();
            sender.sendMessage(Component.text("Snapshot zapisany do archiwum-statystyk/",
                    NamedTextColor.GREEN));
            return true;
        }

        List<StatystykiSklepu.PozycjaTopki> top =
                stat.getTopDzis(ILE_POZYCJI, ceny.getWszystkieMnozniki());

        sender.sendMessage(Component.text("=== Sprzedaz w tej dobie ===",
                NamedTextColor.GOLD, TextDecoration.BOLD));

        if (top.isEmpty()) {
            sender.sendMessage(Component.text("Jeszcze nic dzis nie sprzedano.", NamedTextColor.GRAY));
            return true;
        }

        int nr = 1;
        for (StatystykiSklepu.PozycjaTopki p : top) {
            // Strzałka pokazuje, czy cena tego itemu jest teraz wyższa czy niższa
            // od bazowej — czyli od razu widać, co gracze przehandlowali w dół.
            String strzalka = p.mnoznik() > 1.02 ? " ▲"
                            : p.mnoznik() < 0.98 ? " ▼" : "";
            NamedTextColor kolorStrzalki = p.mnoznik() > 1.02 ? NamedTextColor.GREEN
                                         : NamedTextColor.RED;

            sender.sendMessage(Component.text(nr++ + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(p.item(), NamedTextColor.YELLOW))
                    .append(Component.text("  " + p.sztuk() + " szt.", NamedTextColor.WHITE))
                    .append(Component.text("  " + p.wyplacono() + " $", NamedTextColor.AQUA))
                    .append(Component.text(strzalka, kolorStrzalki)));
        }

        sender.sendMessage(Component.text("Lacznie wyplacono dzis: ", NamedTextColor.GRAY)
                .append(Component.text(stat.getWyplaconoDzis() + " $", NamedTextColor.GOLD)));
        return true;
    }
}
