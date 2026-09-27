package elo.mainplugins.shop;

import elo.mainplugins.core.api.LangService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Placeholdery Sklepu dla HUD i PlaceholderAPI (%mainplugins_<nazwa>%):
 * reset_cen_dni, event_info (te same nazwy co dawniej w HUD) i shop_trend ("co teraz warto sprzedać").
 */
final class ShopPlaceholders implements BiFunction<OfflinePlayer, String, String> {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final LangService lang;
    private final DynamicPriceManager prices;

    ShopPlaceholders(Plugin plugin, LangService lang, DynamicPriceManager prices) {
        this.plugin = plugin;
        this.lang = lang;
        this.prices = prices;
    }

    private String text(String key, Map<String, String> ph) {
        return SER.serialize(lang.msg(plugin, key, ph));
    }

    @Override
    public String apply(OfflinePlayer player, String name) {
        return switch (name) {
            case "reset_cen_dni" -> prices.enabled() && prices.resetWlaczony() ? String.valueOf(prices.dniDoResetu()) : "-";
            case "event_info" -> {
                if (!prices.enabled()) yield "";
                List<String> locked = prices.getZablokowaneNazwy();
                yield locked.isEmpty() ? "" : text("hud.event-info", Map.of("count", String.valueOf(locked.size())));
            }
            case "shop_trend" -> {
                DynamicPriceManager.Deviation top = prices.najwiekszeOdchylenie();
                if (top == null) yield "";
                int percent = (int) Math.round((top.multiplier() - 1.0) * 100);
                yield text(top.multiplier() > 1.0 ? "hud.trend-up" : "hud.trend-down",
                        Map.of("item", top.name(), "percent", String.valueOf(percent)));
            }
            default -> null;
        };
    }
}
