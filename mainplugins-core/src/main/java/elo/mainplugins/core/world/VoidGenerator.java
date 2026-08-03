package elo.mainplugins.core.world;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

public class VoidGenerator extends ChunkGenerator {
    @Override
    public @NotNull ChunkData generateChunkData(@NotNull World world, @NotNull Random random, int x, int z, @NotNull BiomeGrid biome) {
        // Zwracamy pusty chunk - dzięki temu nie wygeneruje się ani jeden blok!
        return createChunkData(world);
    }
}