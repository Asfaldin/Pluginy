package elo.mainplugins.announcer.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Tekst z YAML-a -> gotowy Adventure Component: podstawienia (%player%, %online%,
 * %max_players%, dodatkowe z eventu, opcjonalnie PlaceholderAPI), kolory (& albo
 * MiniMessage), klik/hover i ewentualne wyśrodkowanie na czacie.
 */
public final class TextRenderer {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final boolean placeholderApiEnabled;
    private final boolean placeholderApiPresent;

    public TextRenderer(boolean placeholderApiEnabled) {
        this.placeholderApiEnabled = placeholderApiEnabled;
        this.placeholderApiPresent = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public boolean placeholderApiActive() {
        return placeholderApiEnabled && placeholderApiPresent;
    }

    /** Podstaw wszystkie znane zmienne w surowym tekście (bez budowania Component). */
    public String substitute(String raw, Player viewer, Map<String, String> extra) {
        String s = raw == null ? "" : raw;
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                s = s.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
            }
        }
        s = s.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
             .replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()));
        if (viewer != null) {
            s = s.replace("%player%", viewer.getName())
                 .replace("%world%", viewer.getWorld().getName());
        }
        if (viewer != null && placeholderApiActive()) {
            s = applyPapi(viewer, s);
        }
        return s;
    }

    private static String applyPapi(Player viewer, String s) {
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(viewer, s);
        } catch (Throwable t) {
            return s;
        }
    }

    /** Pełne renderowanie linii: podstawienia + kolory + klik/hover (+ opcjonalny prefix, centrowanie). */
    public Component render(String raw, boolean miniMessage, Player viewer, Map<String, String> extra,
                           String prefixLegacy, String clickType, String clickValue, String hover, boolean center) {
        String body = substitute(raw, viewer, extra);
        String prefix = prefixLegacy == null || prefixLegacy.isEmpty()
                ? "" : substitute(prefixLegacy, viewer, extra);

        Component comp;
        if (miniMessage) {
            comp = MINI.deserialize(prefix.isEmpty() ? body : prefix + body);
        } else {
            comp = LEGACY.deserialize(prefix + body);
        }

        comp = withInteractivity(comp, clickType, clickValue, hover, viewer, extra, miniMessage);

        if (center) {
            String plain = PLAIN.serialize(comp);
            comp = Component.text(centeringPrefix(plain)).append(comp);
        }
        return comp;
    }

    public Component render(String raw, boolean miniMessage, Player viewer, Map<String, String> extra) {
        return render(raw, miniMessage, viewer, extra, "", "", "", "", false);
    }

    private Component withInteractivity(Component comp, String clickType, String clickValue, String hover,
                                       Player viewer, Map<String, String> extra, boolean miniMessage) {
        if (hover != null && !hover.isEmpty()) {
            String h = substitute(hover, viewer, extra);
            Component hc = miniMessage ? MINI.deserialize(h) : LEGACY.deserialize(h);
            comp = comp.hoverEvent(HoverEvent.showText(hc));
        }
        if (clickType != null && !clickType.isEmpty() && clickValue != null && !clickValue.isEmpty()) {
            String v = substitute(clickValue, viewer, extra);
            ClickEvent ce = switch (clickType) {
                case "RUN_COMMAND" -> ClickEvent.runCommand(v);
                case "SUGGEST_COMMAND" -> ClickEvent.suggestCommand(v);
                case "OPEN_URL" -> ClickEvent.openUrl(v);
                default -> null;
            };
            if (ce != null) comp = comp.clickEvent(ce);
        }
        return comp;
    }

    /** Czysty tekst bez kolorów - pod Discord i długość do centrowania. */
    public String plain(String raw, Player viewer, Map<String, String> extra, boolean miniMessage) {
        String body = substitute(raw, viewer, extra);
        Component c = miniMessage ? MINI.deserialize(body) : LEGACY.deserialize(body);
        return PLAIN.serialize(c);
    }

    public static String plainOf(Component c) {
        return PLAIN.serialize(c);
    }

    // --- proste centrowanie na czacie (szerokość okna ~320 px, spacja ~4 px) ---

    private static String centeringPrefix(String plainText) {
        int width = 0;
        boolean bold = false; // brak informacji o pogrubieniu po serializacji - przybliżenie
        for (char ch : plainText.toCharArray()) {
            width += charWidth(ch) + (bold ? 1 : 0) + 1; // +1 odstęp między znakami
        }
        int toCompensate = 160 - width / 2; // połowa 320
        int spaceWidth = 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toCompensate; i += spaceWidth) sb.append(' ');
        return sb.toString();
    }

    private static int charWidth(char c) {
        return switch (c) {
            case 'i', '.', ',', ':', ';', '|', '!', '\'' -> 1;
            case 'l' -> 2;
            case 't', 'I', '[', ']', ' ' -> 3;
            case 'f', 'k' -> 4;
            default -> 5;
        };
    }
}
