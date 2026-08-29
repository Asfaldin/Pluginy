package elo.mainplugins.menu;

import elo.mainplugins.menu.gui.TrybGuiContent;
import elo.mainplugins.menu.gui.TrybGuiLoader;
import elo.mainplugins.menu.gui.TrybOpcja;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Ekran wyboru trybu gry (Skyblock Classic+ / Skyblock MMO+). Celowo NIE
 * teleportuje/zmienia lokalizacji gracza w żaden sposób - to czysta nakładka na start
 * sesji: "Skyblock Classic+" po prostu zamyka okno i gracz zostaje dokładnie tam, gdzie
 * serwer go już wstawił (własna wyspa/łóżko/spawn - o to dba SpawnManager z
 * mainplugins-spawn, całkowicie niezależnie od tego GUI). "Skyblock MMO+" jest na razie
 * zablokowany (nie istnieje) - kliknięcie NIE zamyka okna, tylko pokazuje komunikat
 * "wkrótce dostępne" (patrz tryb-gui.yml).
 *
 * KIEDY się otwiera - to jest sedno: NIE na PlayerJoinEvent, jeśli serwer ma AuthMe.
 * Gracz jest w tym momencie jeszcze "zamrożony"/niezalogowany - AuthMe zwykle blokuje
 * albo natychmiast zamyka dowolne obce GUI otwarte przed zalogowaniem (stąd zgłoszone
 * "nic się nie dzieje"). Zamiast twardej zależności Maven od AuthMe (niepewne
 * współrzędne repo dla konkretnej wersji) podpinamy się pod jego event logowania przez
 * REFLEKSJĘ w {@link #podlaczAuthMeAlboJoin()} - działa, jeśli AuthMe jest
 * zainstalowane, bez kompilowania przeciwko jego API. Gdy AuthMe NIE jest zainstalowane
 * (np. serwer testowy) - normalny fallback na PlayerJoinEvent (1 tick później - otwarcie
 * GUI SYNCHRONICZNIE w trakcie samego joina bywa niestabilne po stronie klienta).
 */
public final class TrybManager implements Listener {

    private static final String TYTUL = "Wybierz Tryb Gry";

    /** Kolejne kandydatury na klasę eventu logowania AuthMe - różne wersje 5.x trzymały go w różnych pakietach. */
    private static final String[] AUTHME_EVENT_KLASY = {
            "fr.xephi.authme.events.LoginEvent",
            "fr.xephi.authme.api.v3.events.LoginEvent"
    };

    private final Plugin plugin;
    private TrybGuiContent gui;
    private boolean authMeAktywny = false;

    public TrybManager(Plugin plugin) {
        this.plugin = plugin;
        this.gui = TrybGuiLoader.load(plugin);
        podlaczAuthMeAlboJoin();
    }

    /** Wywoływane przez /@reloadtryb - podmienia cały ekran bez restartu serwera. */
    public void przeladujKonfiguracje() {
        this.gui = TrybGuiLoader.load(plugin);
    }

    private void podlaczAuthMeAlboJoin() {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) {
            plugin.getLogger().info("[TrybManager] AuthMe nie wykryte - ekran wyboru trybu otworzy się przy zwykłym wejściu na serwer.");
            return;
        }

        for (String nazwaKlasy : AUTHME_EVENT_KLASY) {
            if (sprobujPodlaczycAuthMe(nazwaKlasy)) {
                authMeAktywny = true;
                plugin.getLogger().info("[TrybManager] AuthMe wykryte, podłączono pod " + nazwaKlasy + " - ekran wyboru trybu otworzy się PO zalogowaniu.");
                return;
            }
        }

        plugin.getLogger().warning("[TrybManager] AuthMe jest zainstalowane, ale żadna znana klasa eventu logowania nie została znaleziona - "
                + "używam fallbacku na zwykłe wejście na serwer (może nie zadziałać poprawnie z zamrożeniem AuthMe).");
    }

    @SuppressWarnings("unchecked")
    private boolean sprobujPodlaczycAuthMe(String nazwaKlasy) {
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(nazwaKlasy);
            Method getPlayer = eventClass.getMethod("getPlayer");

            Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR, (listener, event) -> {
                try {
                    Object gracz = getPlayer.invoke(event);
                    if (gracz instanceof Player player) otworzEkranTrybu(player);
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().warning("[TrybManager] Nie udało się odczytać gracza z eventu logowania AuthMe: " + e.getMessage());
                }
            }, plugin);

            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (authMeAktywny) return; // AuthMe samo zdecyduje kiedy - patrz podlaczAuthMeAlboJoin
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) otworzEkranTrybu(player);
        });
    }

    public void otworzEkranTrybu(Player player) {
        Inventory inv = Bukkit.createInventory(null, gui.size(), Component.text(TYTUL, NamedTextColor.GOLD, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(gui.tlo());
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 0; i < gui.size(); i++) {
            inv.setItem(i, tlo);
        }

        inv.setItem(gui.classic().slot(), stworzIkone(gui.classic()));
        inv.setItem(gui.mmo().slot(), stworzIkone(gui.mmo()));

        player.openInventory(inv);
    }

    private ItemStack stworzIkone(TrybOpcja opcja) {
        ItemStack item = new ItemStack(opcja.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(opcja.nazwa());
        meta.lore(opcja.lore());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().toString().contains(TYTUL)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        TrybOpcja opcja = gui.naSlocie(event.getRawSlot());
        if (opcja == null) return; // tło albo pusty slot

        if (opcja.zablokowany()) {
            if (opcja.komunikatZablokowany() != null) {
                player.sendActionBar(opcja.komunikatZablokowany());
            }
            return; // NIE zamykamy okna - gracz moze kliknac dostepna opcje
        }

        // "Skyblock Classic+" - zamykamy i nic wiecej. Gracz zostaje dokladnie tam,
        // gdzie serwer go juz wstawil (wraca do faktycznego stanu, patrz javadoc klasy).
        player.closeInventory();
    }
}
