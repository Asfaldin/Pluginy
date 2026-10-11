package elo.mainplugins.coreminer.config;

import org.bukkit.Material;

import java.util.Locale;

/** Rodzaj narzędzia po nazwie przedmiotu - działa też dla custom narzędzi (to zwykłe kilofy/siekiery z modelem). */
public enum ToolKind {
    KILOF("_PICKAXE"), SIEKIERA("_AXE"), LOPATA("_SHOVEL"), MOTYKA("_HOE"), NOZYCE("SHEARS"), MIECZ("_SWORD");

    private final String koncowka;

    ToolKind(String koncowka) {
        this.koncowka = koncowka;
    }

    public boolean pasuje(Material m) {
        return m != null && m.name().endsWith(koncowka);
    }

    public static ToolKind z(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "kilof", "pickaxe" -> KILOF;
            case "siekiera", "axe" -> SIEKIERA;
            case "lopata", "łopata", "shovel" -> LOPATA;
            case "motyka", "hoe" -> MOTYKA;
            case "nozyce", "nożyce", "shears" -> NOZYCE;
            case "miecz", "sword" -> MIECZ;
            default -> null;
        };
    }

    public String klucz() {
        return switch (this) {
            case KILOF -> "pickaxe";
            case SIEKIERA -> "axe";
            case LOPATA -> "shovel";
            case MOTYKA -> "hoe";
            case NOZYCE -> "shears";
            case MIECZ -> "sword";
        };
    }
}
