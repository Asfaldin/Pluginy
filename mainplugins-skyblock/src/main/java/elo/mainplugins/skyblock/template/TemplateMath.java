package elo.mainplugins.skyblock.template;

/**
 * Czysta matematyka szablonu wyspy (bez serwera). Szablon = prostopadłościan od
 * narożnika "min" o rozmiarze "size" + "offset" = gdzie w nim stał admin przy zapisie
 * (punkt, na który trafia środek nowej wyspy - tak jak origin schematu w WorldEdit).
 */
public final class TemplateMath {

    public record Pos(int x, int y, int z) {}

    private TemplateMath() {}

    public static Pos min(Pos a, Pos b) {
        return new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
    }

    /** Rozmiar włącznie z oboma narożnikami. */
    public static Pos size(Pos a, Pos b) {
        return new Pos(Math.abs(a.x() - b.x()) + 1, Math.abs(a.y() - b.y()) + 1, Math.abs(a.z() - b.z()) + 1);
    }

    public static Pos offset(Pos origin, Pos min) {
        return new Pos(origin.x() - min.x(), origin.y() - min.y(), origin.z() - min.z());
    }

    /** Narożnik, od którego wkleić szablon, żeby jego origin wypadł dokładnie na środku wyspy. */
    public static Pos pasteCorner(Pos center, Pos offset) {
        return new Pos(center.x() - offset.x(), center.y() - offset.y(), center.z() - offset.z());
    }

    public static Pos plus(Pos a, Pos b) {
        return new Pos(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }
}
