package elo.mainplugins.announcer.send;

import elo.mainplugins.announcer.render.TextRenderer;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Ocena warunku {@code condition:} z wpisu wiadomości. Format:
 * {@code <lewa> <operator> <prawa>}, gdzie operator to jeden z
 * {@code == != >= <= > <}. Obie strony przechodzą przez podstawienia
 * (w tym PlaceholderAPI). Liczby porównywane numerycznie, reszta jako tekst.
 * Pusty / nieparsowalny warunek = przechodzi (fail-open, żeby literówka w
 * YAML-u nie wyciszała ogłoszeń po cichu na zawsze).
 */
final class ConditionEval {

    private ConditionEval() { }

    static boolean passes(String condition, Player viewer, TextRenderer renderer) {
        if (condition == null || condition.isBlank()) return true;
        String[] ops = {">=", "<=", "==", "!=", ">", "<"};
        for (String op : ops) {
            int idx = condition.indexOf(op);
            if (idx < 0) continue;
            String lhsRaw = condition.substring(0, idx).trim();
            String rhsRaw = condition.substring(idx + op.length()).trim();
            String lhs = renderer.substitute(lhsRaw, viewer, Map.of());
            String rhs = renderer.substitute(rhsRaw, viewer, Map.of());

            Double ln = parse(lhs), rn = parse(rhs);
            if (ln != null && rn != null) {
                int cmp = Double.compare(ln, rn);
                return switch (op) {
                    case ">=" -> cmp >= 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    case "<" -> cmp < 0;
                    case "==" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    default -> true;
                };
            }
            return switch (op) {
                case "==" -> lhs.equalsIgnoreCase(rhs);
                case "!=" -> !lhs.equalsIgnoreCase(rhs);
                default -> true; // porównania relacyjne na tekście - nie wspieramy, przepuść
            };
        }
        return true;
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.replace(",", ".").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
