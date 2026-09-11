package elo.mainplugins.announcer.model;

import java.util.List;

/**
 * Opcjonalna część wiadomości: "kliknij aby odebrać". Gdy obecna, dispatcher
 * dokleja klikalny przycisk, a ClaimManager pilnuje limitu, okna czasowego i
 * tego, że każdy gracz odbiera najwyżej raz. Nagroda = lista komend z konsoli
 * z podmianą %player% na nick.
 */
public record ClaimSpec(
        int limit,              // ilu pierwszych graczy może odebrać (<=0 = bez limitu)
        int windowSeconds,      // ile sekund oferta jest ważna (<=0 = do następnego ogłoszenia z tej grupy)
        String buttonLegacy,    // tekst przycisku w formacie "&a&l[ODBIERZ]"
        String alreadyLegacy,   // komunikat, gdy gracz już odebrał
        String fullLegacy,      // komunikat, gdy limit wyczerpany / okno zamknięte
        String claimedLegacy,   // komunikat sukcesu (po odbiorze)
        List<String> commands   // komendy z konsoli, %player% -> nick
) {
    public static ClaimSpec fromMap(java.util.Map<?, ?> m) {
        if (m == null) return null;
        int limit = intOr(m.get("limit"), 0);
        int window = intOr(m.get("window-seconds"), 0);
        String button = strOr(m.get("button"), "&a&l[ODBIERZ]");
        String already = strOr(m.get("already"), "&7Już odebrałeś tę nagrodę.");
        String full = strOr(m.get("full"), "&cWszystkie nagrody zostały już rozdane!");
        String claimed = strOr(m.get("claimed"), "&aNagroda odebrana!");
        List<String> cmds = new java.util.ArrayList<>();
        Object c = m.get("commands");
        if (c instanceof List<?> l) for (Object o : l) if (o != null) cmds.add(o.toString());
        if (cmds.isEmpty()) return null; // bez komend nie ma czego odbierać
        return new ClaimSpec(limit, window, button, already, full, claimed, cmds);
    }

    private static int intOr(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static String strOr(Object o, String def) { return o == null ? def : o.toString(); }
}
