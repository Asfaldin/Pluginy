package elo.mainplugins.advancements;

import elo.mainplugins.advancements.config.AchievementsConfig;
import elo.mainplugins.advancements.datapack.NativeAdvancementBridge;
import elo.mainplugins.advancements.model.AchievementDef;
import elo.mainplugins.advancements.model.Reward;
import elo.mainplugins.advancements.notify.AchievementNotifier;
import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serce systemu: trzyma stan każdego gracza (co ZDOBYTE, co ODEBRANE) i wykonuje
 * wręczanie nagród. Treść osiągnięć (co istnieje, jakie ma nagrody) siedzi w
 * {@link AchievementsConfig} i jest podmieniana przy {@code /@reloadosiagniecia} -
 * stan graczy jest od niej niezależny, więc reload nie kasuje postępu.
 *
 * Dane graczy: osiagniecia-gracze.yml, zapis buforowany (patrz {@link AsyncConfigSaver},
 * ten sam wzorzec co w mainplugins-quests).
 */
public final class AchievementManager {

    /** Wynik próby odbioru nagrody - pod komunikat w GUI. */
    public enum WynikOdbioru { ODEBRANO, NIE_UKONCZONE, JUZ_ODEBRANE, BRAK_NAGROD }

    private static final class Postep {
        final Set<String> ukonczone = ConcurrentHashMap.newKeySet();
        final Set<String> odebrane = ConcurrentHashMap.newKeySet();
        /** Zaległe przedmioty (Base64 z ItemStack#serializeAsBytes) - nagrody, które nie zmieściły się w ekwipunku. */
        final List<String> oczekujace = Collections.synchronizedList(new ArrayList<>());
    }

    private final Plugin plugin;
    private final AchievementNotifier notifier;
    private final NativeAdvancementBridge natywne;
    private final YamlConfiguration daneGraczy;
    private final AsyncConfigSaver saver;
    private final Map<UUID, Postep> postepy = new ConcurrentHashMap<>();

    private volatile AchievementsConfig config;

    public AchievementManager(Plugin plugin, AchievementsConfig config, AchievementNotifier notifier, NativeAdvancementBridge natywne) {
        this.plugin = plugin;
        this.config = config;
        this.notifier = notifier;
        this.natywne = natywne;

        File plik = new File(plugin.getDataFolder(), "osiagniecia-gracze.yml");
        this.daneGraczy = YamlConfiguration.loadConfiguration(plik);
        this.saver = new AsyncConfigSaver(plugin, daneGraczy, plik, 30);
        wczytajWszystkich();
    }

    // ---------- cykl życia ----------

    public AchievementsConfig config() {
        return config;
    }

    public void przeladuj(AchievementsConfig nowy) {
        this.config = nowy;
    }

    public void zamknij() {
        saver.zamknij();
    }

    private void wczytajWszystkich() {
        for (String klucz : daneGraczy.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(klucz);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("osiagniecia-gracze.yml: pomijam nie-UUID klucz '" + klucz + "'.");
                continue;
            }
            Postep p = new Postep();
            p.ukonczone.addAll(daneGraczy.getStringList(klucz + ".ukonczone"));
            p.odebrane.addAll(daneGraczy.getStringList(klucz + ".odebrane"));
            p.oczekujace.addAll(daneGraczy.getStringList(klucz + ".oczekujace"));
            postepy.put(uuid, p);
        }
    }

    private Postep postep(UUID uuid) {
        return postepy.computeIfAbsent(uuid, u -> new Postep());
    }

    private void zapiszGracza(UUID uuid) {
        Postep p = postep(uuid);
        daneGraczy.set(uuid + ".ukonczone", new ArrayList<>(p.ukonczone));
        daneGraczy.set(uuid + ".odebrane", new ArrayList<>(p.odebrane));
        daneGraczy.set(uuid + ".oczekujace", p.oczekujace.isEmpty() ? null : new ArrayList<>(p.oczekujace));
        saver.oznaczZmiane();
    }

    // ---------- odczyt stanu ----------

    public boolean ukonczone(UUID uuid, String id) {
        return postep(uuid).ukonczone.contains(id);
    }

    public boolean odebrane(UUID uuid, String id) {
        return postep(uuid).odebrane.contains(id);
    }

    public int liczbaUkonczonych(UUID uuid) {
        return postep(uuid).ukonczone.size();
    }

    public int liczbaWszystkich() {
        return config.osiagniecia().size();
    }

    // ---------- zdobycie ----------

    /** Jak {@link #oznaczUkonczone(Player, AchievementDef, boolean)} z powiadomieniem. */
    public void oznaczUkonczone(Player player, AchievementDef def) {
        oznaczUkonczone(player, def, true);
    }

    /**
     * Woła VanillaAdvancementListener i CustomTriggerChecker. Idempotentne -
     * drugie wywołanie dla już zdobytego osiągnięcia nic nie robi. Gdy
     * {@code def.auto()} - od razu wręcza nagrody bez klikania w GUI.
     *
     * @param powiadamiaj false przy cichym wyrównaniu na wejściu (gracz zdobył
     *                    vanilla advancement, zanim osiągnięcie w ogóle istniało) -
     *                    nagroda dalej czeka w GUI, ale bez tytułu/dźwięku
     */
    public void oznaczUkonczone(Player player, AchievementDef def, boolean powiadamiaj) {
        Postep p = postep(player.getUniqueId());
        if (!p.ukonczone.add(def.id())) {
            return;
        }
        zapiszGracza(player.getUniqueId());

        // Natywny toast/drzewko w ESC → Postępy (jeśli datapack włączony i wczytany).
        boolean natywnyToast = natywne.datapackWczytany();
        natywne.przyznaj(player, def);

        if (powiadamiaj) {
            notifier.powiadom(player, def, def.maNagrody() && !def.auto(), natywnyToast);
            // Most do mainplugins-announcer (events.achievement) - broadcast serwerowy zależy
            // wyłącznie od ogloszenia.yml; bez announcera event po prostu przelatuje.
            Bukkit.getPluginManager().callEvent(new elo.mainplugins.core.api.ServerAnnounceEvent(
                    "achievement", player, Map.of("achievement", nazwaOsiagniecia(def))));
        }

        if (def.auto() && def.maNagrody()) {
            wreczNagrody(player, def.nagrody(), nazwaOsiagniecia(def));
            p.odebrane.add(def.id());
            zapiszGracza(player.getUniqueId());
        }
    }

    private static String nazwaOsiagniecia(AchievementDef def) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(def.nazwa());
    }

    /** Wyrównuje natywne advancementy datapacka do stanu z YAML - woła się po wejściu gracza (po ewentualnym /reload). */
    public void synchronizujNatywne(Player player) {
        if (!natywne.datapackWczytany()) {
            return;
        }
        for (AchievementDef def : config.osiagniecia()) {
            if (postep(player.getUniqueId()).ukonczone.contains(def.id())) {
                natywne.przyznaj(player, def);
            }
        }
    }

    // ---------- odbiór nagród z GUI ----------

    public WynikOdbioru odbierz(Player player, AchievementDef def) {
        UUID uuid = player.getUniqueId();
        Postep p = postep(uuid);
        if (!p.ukonczone.contains(def.id())) {
            return WynikOdbioru.NIE_UKONCZONE;
        }
        if (!def.maNagrody()) {
            return WynikOdbioru.BRAK_NAGROD;
        }
        if (!p.odebrane.add(def.id())) {
            return WynikOdbioru.JUZ_ODEBRANE;
        }
        wreczNagrody(player, def.nagrody(), nazwaOsiagniecia(def));
        zapiszGracza(uuid);
        return WynikOdbioru.ODEBRANO;
    }

    // ---------- admin ----------

    /** Ręczne oznaczenie jako zdobyte (bez patrzenia na warunek) - komenda /@osiagniecia grant. */
    public void adminNadaj(Player player, AchievementDef def) {
        oznaczUkonczone(player, def);
    }

    /** Pełny reset postępu gracza - komenda /@osiagniecia reset. */
    public void adminReset(UUID uuid) {
        postepy.remove(uuid);
        daneGraczy.set(uuid.toString(), null);
        saver.oznaczZmiane();

        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            for (AchievementDef def : config.osiagniecia()) {
                natywne.odbierz(online, def);
            }
        }
    }

    // ---------- wręczanie nagród ----------

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /**
     * Wręcza nagrody: kasa i komendy lecą od razu, przedmioty trafiają do ekwipunku.
     * Gdy w ekwipunku brak miejsca - przedmioty NIE są upuszczane, tylko wędrują do
     * kolejki (per gracz, w osiagniecia-gracze.yml) i są dostarczane automatycznie,
     * gdy miejsce się zwolni (patrz {@link #sprobujWydacOczekujace}).
     */
    private void wreczNagrody(Player player, List<Reward> nagrody, String nazwaOsiagniecia) {
        List<String> podsumowanie = new ArrayList<>();
        List<ItemStack> doEkwipunku = new ArrayList<>();

        for (Reward r : nagrody) {
            switch (r) {
                case Reward.Money m -> {
                    ekonomia().dodajKase(player.getUniqueId(), m.amount());
                    podsumowanie.add(((int) m.amount()) + "$");
                }
                case Reward.Item it -> {
                    doEkwipunku.add(new ItemStack(it.material(), it.amount()));
                    podsumowanie.add(it.amount() + "x " + ladnaNazwa(it.material()));
                }
                case Reward.CustomItem ci -> {
                    CustomItemService svc = CoreAPI.getCustomItemService();
                    ItemStack is = svc != null ? svc.create(ci.id(), ci.amount()) : null;
                    if (is != null) {
                        doEkwipunku.add(is);
                        podsumowanie.add(ci.amount() + "x " + ci.id());
                    } else {
                        plugin.getLogger().warning("Nagroda CUSTOM_ITEM '" + ci.id() + "' niedostepna (brak w custom-items.yml) - pomijam.");
                    }
                }
                case Reward.Crate cr -> {
                    CrateService svc = CoreAPI.getCrateService();
                    if (svc != null) {
                        doEkwipunku.add(svc.stworzSkrzynke(cr.tier()));
                        doEkwipunku.add(svc.stworzKlucz());
                        podsumowanie.add("Tajemnicza Skrzynka T" + cr.tier());
                    } else {
                        plugin.getLogger().warning("Nagroda CRATE pominieta - mainplugins-crates nie jest wgrany.");
                    }
                }
                case Reward.Command c -> {
                    String kom = c.command().replace("%gracz%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), kom);
                }
            }
        }

        if (!podsumowanie.isEmpty()) {
            player.sendMessage(LEGACY.deserialize("&aNagroda: &f" + String.join("&7, &f", podsumowanie)));
        }

        List<ItemStack> niezmieszczone = dodajDoEkwipunku(player, doEkwipunku);
        if (!niezmieszczone.isEmpty()) {
            int ile = zakolejkuj(player.getUniqueId(), niezmieszczone);
            player.sendMessage(LEGACY.deserialize("&eBrak miejsca w ekwipunku - &f" + ile
                    + "&e przedmiot(y) za \"" + nazwaOsiagniecia + "\" czekają. Zrób miejsce, dostaniesz je automatycznie."));
        }
    }

    private EconomyService ekonomia() {
        return CoreAPI.getEconomyService();
    }

    /** Dokłada przedmioty do ekwipunku; zwraca to, co się nie zmieściło (nic nie upuszcza). */
    private List<ItemStack> dodajDoEkwipunku(Player player, List<ItemStack> itemy) {
        List<ItemStack> reszta = new ArrayList<>();
        for (ItemStack is : itemy) {
            if (is == null || is.getType().isAir()) continue;
            reszta.addAll(player.getInventory().addItem(is).values());
        }
        return reszta;
    }

    /** Dopisuje przedmioty do kolejki gracza (Base64 z serializeAsBytes) i zapisuje. Zwraca liczbę dopisanych. */
    private int zakolejkuj(UUID uuid, List<ItemStack> itemy) {
        Postep p = postep(uuid);
        int przed = p.oczekujace.size();
        for (ItemStack is : itemy) {
            p.oczekujace.add(Base64.getEncoder().encodeToString(is.serializeAsBytes()));
        }
        zapiszGracza(uuid);
        return p.oczekujace.size() - przed;
    }

    /**
     * Próba dostarczenia zaległych nagród. Woła się przy wejściu gracza, po zamknięciu
     * dowolnego ekwipunku i cyklicznie. Bezpieczne do częstego wywoływania - gdy kolejka
     * pusta, nic nie robi.
     */
    public void sprobujWydacOczekujace(Player player) {
        Postep p = postep(player.getUniqueId());
        if (p.oczekujace.isEmpty()) return;

        List<String> pozostale = new ArrayList<>();
        int wydane = 0;
        for (String b64 : List.copyOf(p.oczekujace)) {
            ItemStack is;
            try {
                is = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
            } catch (Exception e) {
                plugin.getLogger().warning("osiagniecia-gracze.yml: uszkodzony wpis w kolejce nagrod gracza " + player.getName() + " - pomijam.");
                continue;
            }
            Map<Integer, ItemStack> nieweszlo = player.getInventory().addItem(is);
            if (nieweszlo.isEmpty()) {
                wydane++;
            } else {
                for (ItemStack r : nieweszlo.values()) {
                    pozostale.add(Base64.getEncoder().encodeToString(r.serializeAsBytes()));
                }
            }
        }
        p.oczekujace.clear();
        p.oczekujace.addAll(pozostale);
        zapiszGracza(player.getUniqueId());

        if (wydane > 0) {
            player.sendMessage(LEGACY.deserialize("&aZaległe nagrody (&f" + wydane + "&a) trafiły do ekwipunku."
                    + (pozostale.isEmpty() ? "" : " &7Reszta (&f" + pozostale.size() + "&7) dalej czeka.")));
        }
    }

    /** Cykliczny przebieg po wszystkich online - woła MainpluginsAdvancements razem z CustomTriggerChecker. */
    public void retryOczekujaceWszyscy() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            sprobujWydacOczekujace(p);
        }
    }

    public int liczbaOczekujacych(UUID uuid) {
        return postep(uuid).oczekujace.size();
    }

    private static String ladnaNazwa(Material m) {
        String[] czesci = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String c : czesci) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(c.charAt(0))).append(c.substring(1));
        }
        return sb.toString();
    }

    /** Ilu osiągnięć danej kategorii gracz już dobił - pod licznik "x / y" w GUI. */
    public int ukonczoneWKategorii(UUID uuid, String kategoriaId) {
        int n = 0;
        Set<String> u = postep(uuid).ukonczone;
        for (AchievementDef d : config.wgKategorii(kategoriaId)) {
            if (u.contains(d.id())) n++;
        }
        return n;
    }
}
