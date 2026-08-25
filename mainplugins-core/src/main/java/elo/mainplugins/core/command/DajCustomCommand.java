package elo.mainplugins.core.command;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.ToolsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /@dajcustom <id> [gracz] [ilość] - JEDYNA komenda do wydawania/testowania dowolnego
 * "custom itemu" na serwerze, niezależnie z którego rejestru pochodzi - zamiast osobnej,
 * ręcznie pisanej komendy Javy dla każdego nowego przedmiotu (co z czasem zrobiło się
 * bałaganem w mainplugins-tools: @dajkilofa/@dajsniezny/@addcustompickaxe/@dajewoluujace,
 * każda robiąca dokładnie to samo dla jednego, na sztywno wpisanego id) sprawdza po
 * kolei TRZY źródła:
 *   1. {@link CustomItemService} - statyczny rejestr custom-items.yml (mainplugins-core)
 *   2. {@link ToolsService#ewoluujaceIds()} - silnik ewoluujących narzędzi z poziomami/
 *      milestone'ami (ewoluujace-narzedzia.yml, mainplugins-tools)
 *   3. {@link ToolsService#NIFLHEIM_ID} - Kilof Niflheim (PickaxeSkillManager, WŁASNY,
 *      świadomie nietknięty silnik kart/milestone'ów - jedyny przedmiot na serwerze,
 *      który nadal przypisuje się duszami do gracza)
 * Dodanie NOWEGO testowego przedmiotu do dowolnego z tych trzech rejestrów automatycznie
 * czyni go dostępnym pod tą komendą (i w tab-completion) - bez dotykania Javy/plugin.yml.
 *
 * ToolsService pobierany świeżo z {@link CoreAPI} przy KAŻDYM wywołaniu (nie w konstruktorze) -
 * mainplugins-tools ładuje się PO mainplugins-core, więc w momencie budowy tej komendy
 * (MainpluginsCore#onEnable) serwis jeszcze nie istnieje w ServicesManager.
 */
public class DajCustomCommand implements CommandExecutor, TabCompleter {

    private final CustomItemService customItemService;

    public DajCustomCommand(CustomItemService customItemService) {
        this.customItemService = customItemService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Użycie: /@dajcustom <id> [gracz] [ilość]", NamedTextColor.RED));
            return true;
        }

        String id = args[0].toUpperCase(Locale.ROOT);
        ToolsService toolsService = CoreAPI.getToolsService();
        boolean znanyCustom = customItemService.exists(id);
        boolean znanyEwoluujacy = !znanyCustom && toolsService != null && toolsService.ewoluujaceIds().contains(id);
        boolean niflheim = !znanyCustom && !znanyEwoluujacy && id.equals(ToolsService.NIFLHEIM_ID) && toolsService != null;

        if (!znanyCustom && !znanyEwoluujacy && !niflheim) {
            sender.sendMessage(Component.text("Nieznany custom item: " + id + ". Dostępne: "
                    + String.join(", ", wszystkieId(toolsService)), NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + args[1] + ".", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Użycie z konsoli: /@dajcustom <id> <gracz> [ilość]", NamedTextColor.RED));
            return true;
        }

        int ilosc = 1;
        if (args.length >= 3) {
            try {
                ilosc = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Ilość musi być liczbą.", NamedTextColor.RED));
                return true;
            }
        }

        if (znanyCustom) {
            wydaj(target, customItemService.create(id, ilosc));
        } else {
            // Narzędzia (ewoluujące i Niflheim) się nie stackują - każda sztuka to osobny ItemStack.
            for (int i = 0; i < ilosc; i++) {
                ItemStack item = niflheim ? toolsService.stworzKilofNiflheim(target) : toolsService.stworzEwoluujaceNarzedzie(id);
                if (item != null) wydaj(target, item);
            }
        }

        sender.sendMessage(Component.text("Wydano " + ilosc + "x " + id + " dla " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    private void wydaj(Player target, ItemStack item) {
        var leftover = target.getInventory().addItem(item);
        leftover.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
    }

    private Set<String> wszystkieId(ToolsService toolsService) {
        Set<String> wynik = new LinkedHashSet<>(customItemService.ids());
        if (toolsService != null) {
            wynik.addAll(toolsService.ewoluujaceIds());
            wynik.add(ToolsService.NIFLHEIM_ID);
        }
        return wynik;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> wynik = new ArrayList<>();
            for (String id : wszystkieId(CoreAPI.getToolsService())) {
                if (id.startsWith(args[0].toUpperCase(Locale.ROOT))) wynik.add(id);
            }
            return wynik;
        }
        if (args.length == 2) {
            return null; // domyślna podpowiedź nicków online od Bukkita
        }
        return List.of();
    }
}
