package elo.mainplugins.skyblock;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.MoneyFormat;
import org.bukkit.command.CommandSender;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.SpawnService;
import elo.mainplugins.core.world.VoidGenerator;
import elo.mainplugins.skyblock.config.IslandConfigLoader;
import elo.mainplugins.skyblock.config.IslandTuning;
import elo.mainplugins.skyblock.config.SpawnerTyp;
import elo.mainplugins.skyblock.event.IslandBankDepositEvent;
import elo.mainplugins.skyblock.event.IslandCreatedEvent;
import elo.mainplugins.skyblock.event.IslandMemberJoinedEvent;
import elo.mainplugins.skyblock.event.IslandUpgradeEvent;
import elo.mainplugins.skyblock.gui.IslandGuiButton;
import elo.mainplugins.skyblock.gui.IslandGuiContent;
import elo.mainplugins.skyblock.gui.IslandGuiHolder;
import elo.mainplugins.skyblock.gui.IslandGuiLoader;
import elo.mainplugins.skyblock.gui.IslandScreen;
import elo.mainplugins.skyblock.template.IslandTemplate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Teksty wysp z lang (lang/pl.yml, lang/en.yml), kwoty z walutą serwera, odmiana jednostek czasu. */
final class IslandTexts {

    private final Plugin plugin;

    IslandTexts(Plugin plugin) {
        this.plugin = plugin;
    }

    private String plainText(String key) {
        return PlainTextComponentSerializer.plainText().serialize(CoreAPI.getLangService().msg(plugin, key));
    }

    /** Formatuje pozostały czas oczekiwania jedną, największą pasującą jednostką ("X minut" itd.), z grubsza poprawną polską odmianą. */
    String formatujCzasOczekiwania(long millis) {
        long sekundy = (millis + 999) / 1000; // w górę, żeby nie pokazać "0 sekund" tuż przed końcem
        if (sekundy >= 86400) return odmianaLiczby(sekundy / 86400, "day");
        if (sekundy >= 3600) return odmianaLiczby(sekundy / 3600, "hour");
        if (sekundy >= 60) return odmianaLiczby(sekundy / 60, "minute");
        return odmianaLiczby(sekundy, "second");
    }

    /** Liczba z jednostką odmienioną z lang (time.<jednostka>-one/-few/-many). */
    private String odmianaLiczby(long n, String jednostka) {
        boolean pasujeKilka = n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20);
        String forma = n == 1 ? "one" : pasujeKilka ? "few" : "many";
        return n + " " + plainText("time." + jednostka + "-" + forma);
    }

    static Map<String, String> pary(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Kwota z walutą serwera (np. "100$", "100 zł") - waluta z ustawień Core. */
    static String kasa(double kwota) {
        return MoneyFormat.zWaluta(kwota);
    }

    /** Porównanie tekstu z czatu bez względu na wielkość liter i polskie znaki ("sie" = "się"). */
    static String bezOgonkow(String t) {
        String z = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", na = "acelnoszzACELNOSZZ";
        StringBuilder b = new StringBuilder();
        for (char c : t.trim().toCharArray()) {
            int i = z.indexOf(c);
            b.append(i >= 0 ? na.charAt(i) : c);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }
}
