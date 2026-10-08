package elo.mainplugins.guilds;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Czysta logika gildii (bez Bukkita): poprawność tagu i nazwy, ranking. */
public final class GuildRules {

    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9 _\\-']+");

    private GuildRules() {}

    /** null = w porządku, inaczej klucz komunikatu z pliku językowego. */
    public static String checkTag(String tag, GuildsConfig c) {
        if (tag == null || tag.length() < c.tagMin() || tag.length() > c.tagMax()) return "error.tag-length";
        if (!TAG.matcher(tag).matches()) return "error.tag-chars";
        return null;
    }

    public static String checkName(String name, GuildsConfig c) {
        if (name == null || name.isBlank() || name.length() > c.nameMax()) return "error.name-length";
        if (!NAME.matcher(name).matches()) return "error.name-chars";
        return null;
    }

    /** Ranking: najpierw liczba członków, potem bank, potem starsze gildie wyżej. */
    public static List<Guild> top(Collection<Guild> guilds, int limit) {
        return guilds.stream()
                .sorted(Comparator.<Guild>comparingInt(g -> g.members.size()).reversed()
                        .thenComparing(Comparator.<Guild>comparingDouble(g -> g.bank).reversed())
                        .thenComparingLong(g -> g.created))
                .limit(limit)
                .toList();
    }
}
