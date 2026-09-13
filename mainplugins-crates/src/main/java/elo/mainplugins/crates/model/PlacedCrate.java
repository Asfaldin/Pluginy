package elo.mainplugins.crates.model;

/** Skrzynka postawiona w świecie (placed.yml): która skrzynka i na którym bloku. */
public record PlacedCrate(String crate, String world, int x, int y, int z) {

    public boolean at(String world, int x, int y, int z) {
        return this.world.equals(world) && this.x == x && this.y == y && this.z == z;
    }
}
