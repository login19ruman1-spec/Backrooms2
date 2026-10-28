package ru.backrooms;

import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

public final class BackroomsGenerator extends ChunkGenerator {

    static final int FLOOR = 64;

    enum Theme {
        OFFICE("Офисы",
                Material.SMOOTH_SANDSTONE,
                Material.YELLOW_TERRACOTTA),

        LIBRARY("Библиотека",
                Material.DARK_OAK_PLANKS,
                Material.SPRUCE_PLANKS),

        STORAGE("Склад",
                Material.STONE_BRICKS,
                Material.POLISHED_ANDESITE),

        PARKING("Парковка",
                Material.GRAY_CONCRETE,
                Material.BLACK_CONCRETE),

        HOME("Жилые комнаты",
                Material.WHITE_TERRACOTTA,
                Material.BIRCH_PLANKS),

        POOL("Бассейны",
                Material.QUARTZ_BLOCK,
                Material.PRISMARINE_BRICKS),

        GRASS("Травянистые пространства",
                Material.MOSS_BLOCK,
                Material.GRASS_BLOCK),

        MINIATURE("Цветная копия world",
                Material.LIGHT_BLUE_CONCRETE,
                Material.WHITE_CONCRETE);

        final String title;
        final Material wall;
        final Material floor;

        Theme(String title, Material wall, Material floor) {
            this.title = title;
            this.wall = wall;
            this.floor = floor;
        }
    }

    static int level(int chunkX) {
        return Math.floorDiv(chunkX + 128, 256);
    }

    static boolean valid(int level) {
        return level >= 0 && level < 24;
    }

    static Theme theme(int level) {
        return Theme.values()[Math.floorMod(level / 3, 8)];
    }

    static int roof(int level) {
        return 72 + Math.floorMod(level, 3) * 2;
    }

    static String title(int level) {
        return theme(level).title + " / подуровень "
                + Math.floorMod(level, 3);
    }

    static long hash(int x, int z, long seed) {
        long n = seed
                ^ (x * 341873128712L)
                ^ (z * 132897987541L);

        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;

        return n ^ (n >>> 31);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ChunkData generateChunkData(
            World world,
            Random random,
            int chunkX,
            int chunkZ,
            BiomeGrid biome) {

        ChunkData data = createChunkData(world);
        int level = level(chunkX);

        // За пределами полос уровней остаётся безопасная платформа.
        if (!valid(level)) {
            data.setRegion(
                    0, FLOOR, 0, 16, FLOOR + 1, 16,
                    Material.BEDROCK);
            return data;
        }

        Theme theme = theme(level);
        int roof = roof(level);

        data.setRegion(
                0, FLOOR - 2, 0,
                16, FLOOR, 16,
                Material.BEDROCK);

        data.setRegion(
                0, FLOOR, 0,
                16, FLOOR + 1, 16,
                theme.floor);

        data.setRegion(
                0, roof, 0,
                16, roof + 1, 16,
                Material.BEDROCK);

        // Северная и западная стены комнаты.
        data.setRegion(
                0, FLOOR + 1, 0,
                1, roof, 16,
                theme.wall);

        data.setRegion(
                0, FLOOR + 1, 0,
                16, roof, 1,
                theme.wall);

        // Постоянные проходы между соседними комнатами.
        data.setRegion(
                0, FLOOR + 1, 7,
                1, FLOOR + 4, 9,
                Material.AIR);

        data.setRegion(
                7, FLOOR + 1, 0,
                9, FLOOR + 4, 1,
                Material.AIR);

        long seed = hash(chunkX, chunkZ, world.getSeed());

        if (theme != Theme.GRASS
                && theme != Theme.MINIATURE) {

            int gap = 4 + (int) Math.floorMod(seed, 7L);

            for (int wallX : new int[]{6, 9}) {
                for (int z = 3; z <= 12; z++) {
                    if (z == gap || z == gap + 1) {
                        continue;
                    }

                    data.setRegion(
                            wallX, FLOOR + 1, z,
                            wallX + 1, roof, z + 1,
                            theme.wall);
                }
            }
        }

        for (int x : new int[]{3, 12}) {
            for (int z : new int[]{4, 11}) {
                data.setBlock(
                        x, roof - 1, z,
                        Material.SEA_LANTERN);
            }
        }

        return data;
    }

    @Override
    public Location getFixedSpawnLocation(
            World world, Random random) {
        return new Location(world, 8.5, 65, 1.5);
    }
}
