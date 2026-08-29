package elo.mainplugins.advancements.command;

import elo.mainplugins.advancements.AchievementManager;
import elo.mainplugins.advancements.datapack.DatapackGenerator;
import elo.mainplugins.advancements.datapack.NativeAdvancementBridge;
import elo.mainplugins.advancements.model.AchievementDef;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /@osiagniecia <info|grant|reset|datapack> ...} - administracyjny podgląd,
 * ręczna korekta postępu i diagnostyka datapacka.
 * <ul>
 *   <li>{@code info <gracz>} - lista osiągnięć gracza i ich stan,</li>
 *   <li>{@code grant <gracz> <id>} - nadaje osiągnięcie bez patrzenia na warunek,</li>
 *   <li>{@code reset <gracz>} - czyści cały postęp gracza,</li>
 *   <li>{@code datapack} - ścieżka, stan i regeneracja datapacka (ESC → Postępy).</li>
 * </ul>
 */
public final class AdminAchievementsCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final AchievementManager manager;
    private final DatapackGenerator datapackGenerator;
    private final NativeAdvancementBridge natywne;

    public AdminAchievementsCommand(AchievementManager manager, DatapackGenerator datapackGenerator, NativeAdvancementBridge natywne) {
        this.manager = manager;
        this.datapackGenerator = datapackGenerator;
        this.natywne = natywne;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("datapack")) {
            diagnostykaDatapacka(sender);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(SER.deserialize("&cUżycie: /@osiagniecia <info|grant|reset|datapack> [gracz] [id]"));
            return true;
        }
        String akcja = args[0].toLowerCase();
        OfflinePlayer cel = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (cel == null && Bukkit.getPlayerExact(args[1]) == null) {
            sender.sendMessage(SER.deserialize("&cNie znam gracza '" + args[1] + "'."));
            return true;
        }
        if (cel == null) {
            cel = Bukkit.getPlayerExact(args[1]);
        }

        switch (akcja) {
            case "info" -> {
                sender.sendMessage(SER.deserialize("&6" + cel.getName() + "&7: zdobyte &f"
                        + manager.liczbaUkonczonych(cel.getUniqueId()) + "&7/&f" + manager.liczbaWszystkich()));
                for (AchievementDef def : manager.config().osiagniecia()) {
                    boolean u = manager.ukonczone(cel.getUniqueId(), def.id());
                    boolean o = manager.odebrane(cel.getUniqueId(), def.id());
                    String stan = !u ? "&8—" : (o ? "&a✔ (odebrane)" : "&e✔ (nagroda czeka)");
                    sender.sendMessage(SER.deserialize("  &7" + def.id() + ": " + stan));
                }
            }
            case "grant" -> {
                if (args.length < 3) {
                    sender.sendMessage(SER.deserialize("&cPodaj id osiągnięcia: /@osiagniecia grant <gracz> <id>"));
                    return true;
                }
                AchievementDef def = manager.config().wgId(args[2]);
                if (def == null) {
                    sender.sendMessage(SER.deserialize("&cNie ma osiągnięcia o id '" + args[2] + "'."));
                    return true;
                }
                Player online = cel.getPlayer();
                if (online == null) {
                    sender.sendMessage(SER.deserialize("&cGracz musi być online, żeby nadać osiągnięcie (wręczenie nagród)."));
                    return true;
                }
                manager.adminNadaj(online, def);
                sender.sendMessage(SER.deserialize("&aNadano '" + def.id() + "' graczowi " + online.getName() + "."));
            }
            case "reset" -> {
                manager.adminReset(cel.getUniqueId());
                sender.sendMessage(SER.deserialize("&aWyczyszczono postęp osiągnięć gracza " + cel.getName() + "."));
            }
            default -> sender.sendMessage(SER.deserialize("&cNieznana akcja. Użyj: info | grant | reset | datapack"));
        }
        return true;
    }

    private void diagnostykaDatapacka(CommandSender sender) {
        var dp = manager.config().datapack();
        sender.sendMessage(SER.deserialize("&6=== Datapack osiągnięć ==="));
        sender.sendMessage(SER.deserialize("&7wlaczony w configu: &f" + dp.wlaczony() + "&7, namespace: &f" + dp.namespace()));

        File datapacks = DatapackGenerator.datapacksDir();
        if (datapacks == null) {
            sender.sendMessage(SER.deserialize("&cBrak wczytanego świata - nie mogę ustalić ścieżki."));
            return;
        }
        File base = new File(datapacks, DatapackGenerator.DIR_NAME);
        sender.sendMessage(SER.deserialize("&7folder: &f" + base.getAbsolutePath()));
        sender.sendMessage(SER.deserialize("&7  folder istnieje: &f" + base.isDirectory()
                + "&7, pack.mcmeta: &f" + new File(base, "pack.mcmeta").isFile()));

        File dataNs = new File(base, "data/" + dp.namespace());
        sender.sendMessage(SER.deserialize("&7  data/" + dp.namespace() + "/advancement (l.poj.): &f"
                + new File(dataNs, "advancement").isDirectory()
                + "&7  |  advancements (l.mn.): &f" + new File(dataNs, "advancements").isDirectory()));

        sender.sendMessage(SER.deserialize("&7serwer wczytał nasz datapack: "
                + (natywne.datapackWczytany() ? "&aTAK" : "&cNIE") + "&7 (sprawdzam root pierwszej kategorii)"));

        // Co widzi sam serwer (Paper DatapackManager) - odpowiednik /datapack list
        try {
            var mgr = Bukkit.getDatapackManager();
            boolean znaleziono = false;
            for (var pack : mgr.getPacks()) {
                if (pack.getName().toLowerCase().contains("mainplugins-osiagniecia") || pack.getName().toLowerCase().contains(DatapackGenerator.DIR_NAME)) {
                    znaleziono = true;
                    sender.sendMessage(SER.deserialize("&7Paper widzi pack: &f" + pack.getName()
                            + "&7 enabled=&f" + pack.isEnabled()));
                }
            }
            if (!znaleziono) {
                sender.sendMessage(SER.deserialize("&cPaper NIE widzi naszego packa na liście - pack.mcmeta odrzucony (format?)."));
            }
        } catch (Throwable t) {
            sender.sendMessage(SER.deserialize("&7(Paper DatapackManager niedostępny: " + t.getClass().getSimpleName() + ")"));
        }

        // Podgląd wygenerowanych plików - żeby zobaczyć, czy schemat JSON pasuje do MC 26.2
        wypiszPlik(sender, new File(base, "pack.mcmeta"));
        if (!manager.config().kategorie().isEmpty()) {
            String kat = manager.config().kategorie().get(0).id().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
            wypiszPlik(sender, new File(base, "data/" + dp.namespace() + "/advancement/" + kat + "/root.json"));
        }

        DatapackGenerator.Wynik w = datapackGenerator.wygeneruj(manager.config());
        sender.sendMessage(SER.deserialize("&7regeneracja: &f" + w.info()));
        if (w.trzebaReload()) {
            sender.sendMessage(SER.deserialize("&e» Pliki zapisane/zmienione. Wpisz teraz &f/reload&e (lub zrestartuj serwer)."));
        } else {
            sender.sendMessage(SER.deserialize("&7» Pliki bez zmian. Jeśli serwer i tak nie wczytał - zrób /reload."));
        }
    }

    private static void wypiszPlik(CommandSender sender, File plik) {
        sender.sendMessage(SER.deserialize("&8--- " + plik.getName() + " ---"));
        if (!plik.isFile()) {
            sender.sendMessage(SER.deserialize("&c(brak pliku: " + plik.getAbsolutePath() + ")"));
            return;
        }
        try {
            for (String linia : java.nio.file.Files.readAllLines(plik.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                sender.sendMessage(SER.deserialize("&7" + linia.replace('§', '&')));
            }
        } catch (Exception e) {
            sender.sendMessage(SER.deserialize("&c(nie mogę odczytać: " + e.getMessage() + ")"));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String a : List.of("info", "grant", "reset", "datapack")) {
                if (a.startsWith(args[0].toLowerCase())) out.add(a);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("datapack")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("grant")) {
            for (AchievementDef def : manager.config().osiagniecia()) {
                if (def.id().startsWith(args[2].toLowerCase())) out.add(def.id());
            }
        }
        return out;
    }
}
