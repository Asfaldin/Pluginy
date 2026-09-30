package elo.mainplugins.mobs.model;

import java.util.List;
import java.util.Map;

/**
 * Mob z Kreatora mobów (assets/mainplugins/mobs/<id>.json) + jak go wyświetlić
 * (<id>.display.json: przedmiot z modelem i skala każdej części, przedmioty wariantów wyglądu).
 */
public record MobDef(String id, String name, List<Bone> bones, Map<String, Anim> animations, List<Effect> effects,
                     double hitboxWidth, double hitboxHeight, Map<String, Variant> variants) {

    public MobDef(String id, String name, List<Bone> bones, Map<String, Anim> animations, List<Effect> effects,
                  double hitboxWidth, double hitboxHeight) {
        this(id, name, bones, animations, effects, hitboxWidth, hitboxHeight, Map.of());
    }

    /**
     * Część moba. pivot/rotation/scale jak w grze: jednostki modelu (16 = blok), oś Y w dół, stopnie.
     * boxes - pudełka części (od, rozmiar: 6 liczb) - do hitboxów części i końca nogi;
     * variantItems - przedmiot tej części w wariantach wyglądu (id wariantu -> przedmiot).
     */
    public record Bone(String id, String name, String parent, float[] pivot, float[] rotation, float[] scale, boolean glow,
                       String item, float modelScale, List<float[]> boxes, Map<String, String> variantItems) {
        public Bone(String id, String parent, float[] pivot, float[] rotation, float[] scale, boolean glow, String item, float modelScale) {
            this(id, id, parent, pivot, rotation, scale, glow, item, modelScale, List.of(), Map.of());
        }

        public boolean visible() {
            return item != null;
        }
    }

    /** Animacja: kość -> ścieżki (obrót, przesunięcie, skala) względem pozy spoczynkowej; przełączenia wariantu w czasie. */
    public record Anim(String id, String name, float length, boolean loop, Map<String, Track> tracks, List<VariantKey> variants) {
        public Anim(String name, float length, boolean loop, Map<String, Track> tracks) {
            this(name, name, length, loop, tracks, List.of());
        }
    }

    public record Track(List<Key> rotation, List<Key> position, List<Key> scale) {}

    /** Klatka kluczowa: czas w sekundach, wartość, przejście do następnej (linear, smooth, step, in, out, back, elastic, bounce). */
    public record Key(float t, float[] v, String easing) {}

    /** Przełączenie wariantu wyglądu w chwili t animacji ("base" = podstawowy). */
    public record VariantKey(float t, String variant) {}

    /** Wariant wyglądu: czy gasi świecenie części. */
    public record Variant(String id, boolean noGlow) {}

    /**
     * Cząsteczki przy części (bone null = środek moba). anim/from/to - tylko w tej animacji (id) i w tym
     * oknie czasu; variant - tylko w tym wyglądzie; velocity - lot w układzie części (jednostki/s); rise - w górę.
     */
    public record Effect(String bone, String particle, float[] offset, float rate, float spread,
                         String anim, float from, float to, String variant, float[] velocity, float rise) {
        public Effect(String bone, String particle, float[] offset, float rate, float spread) {
            this(bone, particle, offset, rate, spread, null, -1, -1, null, null, 0);
        }
    }
}
