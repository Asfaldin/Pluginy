package elo.mainplugins.mobs.model;

import java.util.List;
import java.util.Map;

/**
 * Mob z Kreatora mobów (assets/mainplugins/mobs/<id>.json) + jak go wyświetlić
 * (<id>.display.json: przedmiot z modelem i skala każdej części).
 */
public record MobDef(String id, String name, List<Bone> bones, Map<String, Anim> animations, List<Effect> effects,
                     double hitboxWidth, double hitboxHeight) {

    /** Część moba. pivot/rotation/scale jak w grze: jednostki modelu (16 = blok), oś Y w dół, stopnie. */
    public record Bone(String id, String parent, float[] pivot, float[] rotation, float[] scale, boolean glow,
                       String item, float modelScale) {
        public boolean visible() {
            return item != null;
        }
    }

    /** Animacja: kość -> ścieżki (obrót, przesunięcie, skala) względem pozy spoczynkowej. */
    public record Anim(String name, float length, boolean loop, Map<String, Track> tracks) {}

    public record Track(List<Key> rotation, List<Key> position, List<Key> scale) {}

    /** Klatka kluczowa: czas w sekundach, wartość, przejście do następnej (linear, smooth, step). */
    public record Key(float t, float[] v, String easing) {}

    /** Cząsteczki przy części (bone null = środek moba). */
    public record Effect(String bone, String particle, float[] offset, float rate, float spread) {}
}
