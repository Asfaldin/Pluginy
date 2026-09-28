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

/** Pytania na czacie: potwierdzenie usunięcia wyspy, nick zapraszanego gracza, nowa nazwa wyspy. */
final class IslandChat implements Listener {

    private final IslandManager m;

    IslandChat(IslandManager m) {
        this.m = m;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Nie łapiemy tych trzech promptów wcale, jeśli gracz akurat na żaden nie czeka -
        // event.message() do String tylko wtedy, gdy faktycznie trzeba go przeczytać.
        if (!m.pendingDeleteConfirmation.contains(uuid) && !m.pendingInviteChat.contains(uuid) && !m.pendingNameChat.contains(uuid)) {
            return;
        }
        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Potwierdzenie usunięcia wyspy przez czat - wspólne dla kliknięcia w GUI (kosz)
        // i komendy /is usun, obie tylko uzbrajają pendingDeleteConfirmation (patrz
        // ustawOczekiwanieNaPotwierdzenie). Cokolwiek innego niż dokładnie "Tak zgadzam się"
        // anuluje. Akceptujemy też wersję bez polskich znaków ("sie") - część graczy nie
        // ma wygodnego sposobu wpisania "ę" na czacie.
        if (m.pendingDeleteConfirmation.contains(uuid)) {
            event.setCancelled(true);

            boolean potwierdzone = IslandTexts.bezOgonkow(wiadomosc).equals(IslandTexts.bezOgonkow(m.plain("delete.confirm-phrase")));
            if (potwierdzone) {
                // NIE usuwamy tu z m.pendingDeleteConfirmation - to robi samo m.potwierdzUsuniecie()
                // po swoim guardzie (patrz niżej). Usuwanie TUTAJ było prawdziwą przyczyną, dla
                // której usuwanie przez czat nigdy nie działało: m.potwierdzUsuniecie() wywoływane
                // chwilę później (na głównym wątku, przez runTask) widziało że wpis już zniknął
                // i przerywało się natychmiast na własnym guardzie - zero usunięcia, zero błędu.
                //
                // AsyncChatEvent leci na wątku czatu, nie na głównym wątku serwera -
                // m.potwierdzUsuniecie() teleportuje graczy i czyści chunki wyspy, więc musi
                // wrócić na główny wątek.
                Bukkit.getScheduler().runTask(m.plugin, () -> m.potwierdzUsuniecie(player));
            } else {
                m.pendingDeleteConfirmation.remove(uuid);
                m.msg(player, "delete.cancelled");
            }
            return;
        }

        if (m.pendingInviteChat.contains(uuid)) {
            event.setCancelled(true);
            m.pendingInviteChat.remove(uuid);

            String targetName = wiadomosc;
            if (IslandTexts.bezOgonkow(targetName).equals(IslandTexts.bezOgonkow(m.plain("common.cancel-word")))) {
                m.msg(player, "invite.chat-cancelled");
                return;
            }

            // Ponowna walidacja uprawnień - stan mógł się zmienić w czasie, gdy okno czatu było otwarte.
            IslandData data = m.wlasnaWyspaJakoZarzadca(player);
            if (data == null) return;

            Player target = Bukkit.getPlayer(targetName);
            if (target == null || !target.isOnline()) {
                m.msg(player, "common.player-not-found");
                return;
            }

            m.wykonajZaproszenie(player, target);
            return;
        }

        if (m.pendingNameChat.contains(uuid)) {
            event.setCancelled(true);
            m.pendingNameChat.remove(uuid);
            ustawNazweWyspyZCzatu(player, wiadomosc);
        }
    }

    /** Otwiera czatowy prompt zmiany nazwy - wołane z przycisku "Nazwa Wyspy" w Ustawieniach Wyspy. */
    public void rozpocznijZmianeNazwyWyspy(Player player) {
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        player.closeInventory();
        m.pendingNameChat.add(player.getUniqueId());
        m.msg(player, "name.prompt", "max", String.valueOf(m.tuning.maxDlugoscNazwyWyspy()), "cancel", m.plain("common.cancel-word"));
    }

    /** Rdzeń zmiany nazwy z czatu - patrz otworzMenuUstawienWyspy (przycisk "Nazwa Wyspy"). */
    void ustawNazweWyspyZCzatu(Player player, String wiadomosc) {
        if (IslandTexts.bezOgonkow(wiadomosc).equals(IslandTexts.bezOgonkow(m.plain("common.cancel-word")))) {
            m.msg(player, "name.cancelled");
            return;
        }

        // Ponowna walidacja uprawnień - stan mógł się zmienić w czasie, gdy okno czatu było otwarte.
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        String nazwa = wiadomosc.trim();
        if (nazwa.isEmpty() || nazwa.length() > m.tuning.maxDlugoscNazwyWyspy()) {
            m.msg(player, "name.length", "max", String.valueOf(m.tuning.maxDlugoscNazwyWyspy()));
            return;
        }

        data.setCustomName(nazwa);
        m.storage.zapiszWyspy();
        m.msg(player, "name.set", "name", nazwa);
    }
}
