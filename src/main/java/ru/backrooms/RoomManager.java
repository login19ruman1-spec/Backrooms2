package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class RoomManager {

    private final BackroomsPlugin plugin;

    private final Queue<Long> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Random random = new Random();

    RoomManager(BackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    private long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    void enqueue(Chunk chunk) {
        long key = key(chunk.getX(), chunk.getZ());

        if (queued.add(key)) {
            queue.add(key);
        }
    }

    void tick() {
        Long key = queue.poll();

        if (key == null) {
            return;
        }

        queued.remove(key);

        int x = (int) (key >> 32);
        int z = (int) (long) key;

        if (plugin.backrooms.isChunkLoaded(x, z)) {
            ensure(plugin.backrooms.getChunkAt(x, z));
        }
    }

    void ensure(Chunk chunk) {
        int level = BackroomsGenerator.level(chunk.getX());

        if (!BackroomsGenerator.valid(level)) {
            return;
        }

        var pdc = chunk.getPersistentDataContainer();

        if (pdc.has(
                plugin.decoratedKey,
                PersistentDataType.BYTE)) {
            return;
        }

        // Не украшать комнату поверх ранних действий игрока.
        if (pdc.has(plugin.editedKey, PersistentDataType.BYTE)) {
            pdc.set(plugin.decoratedKey,
                    PersistentDataType.BYTE, (byte) 1);
            return;
        }

        var theme = BackroomsGenerator.theme(level);
        long seed = BackroomsGenerator.hash(
                chunk.getX(), chunk.getZ(),
                plugin.backrooms.getSeed());

        decorate(chunk, theme);

        door(chunk, 0, 7, Material.OAK_DOOR, BlockFace.EAST);

        boolean entryRoom =
                chunk.getX() == level * 256
                        && chunk.getZ() == 0;

        if (entryRoom || Math.floorMod(seed, 7L) == 0) {
            for (int x = 11; x <= 13; x++) {
                for (int y = 65; y <= 68; y++) {
                    set(chunk, x, y, 12,
                            Material.POLISHED_BLACKSTONE);
                }
            }

            door(chunk, 12, 12,
                    Material.WARPED_DOOR, BlockFace.SOUTH);

            int destination = (level + 1) % 24;

            pdc.set(plugin.portalKey,
                    PersistentDataType.INTEGER, destination);

            set(chunk, 12, 68, 12, Material.SEA_LANTERN);
        }

        set(chunk, 2, 65, 13, Material.CHEST);

        Chest chest = (Chest) chunk.getBlock(2, 65, 13)
                .getState();

        Random loot = new Random(seed ^ 0x7a19L);

        Material[] table = switch (theme) {
            case OFFICE -> new Material[]{
                    Material.PAPER,
                    Material.REDSTONE,
                    Material.BREAD,
                    Material.COPPER_INGOT
            };

            case LIBRARY -> new Material[]{
                    Material.PAPER,
                    Material.BOOK,
                    Material.INK_SAC,
                    Material.EXPERIENCE_BOTTLE
            };

            case STORAGE -> new Material[]{
                    Material.IRON_INGOT,
                    Material.COAL,
                    Material.STRING,
                    Material.TORCH
            };

            case PARKING -> new Material[]{
                    Material.COAL,
                    Material.IRON_NUGGET,
                    Material.REDSTONE,
                    Material.RAIL
            };

            case HOME -> new Material[]{
                    Material.APPLE,
                    Material.BREAD,
                    Material.CARROT,
                    Material.BAKED_POTATO
            };

            case POOL -> new Material[]{
                    Material.PRISMARINE_SHARD,
                    Material.KELP,
                    Material.COD,
                    Material.SPONGE
            };

            case GRASS -> new Material[]{
                    Material.WHEAT_SEEDS,
                    Material.BONE_MEAL,
                    Material.MELON_SLICE,
                    Material.OAK_SAPLING
            };

            case MINIATURE -> new Material[]{
                    Material.RED_CONCRETE,
                    Material.BLUE_CONCRETE,
                    Material.GREEN_CONCRETE,
                    Material.AMETHYST_SHARD
            };
        };

        for (int i = 0; i < 5; i++) {
            Material material = table[loot.nextInt(table.length)];

            chest.getBlockInventory().setItem(
                    i * 3,
                    new ItemStack(
                            material,
                            1 + loot.nextInt(3 + level % 3)));
        }

        chest.getBlockInventory().setItem(
                19, Archive.book(false,
                        1 + loot.nextInt(24)));

        chest.getBlockInventory().setItem(
                22, Archive.book(true,
                        1 + loot.nextInt(24)));

        pdc.set(plugin.decoratedKey,
                PersistentDataType.BYTE, (byte) 1);
    }

    private void decorate(
            Chunk chunk,
            BackroomsGenerator.Theme theme) {

        switch (theme) {
            case OFFICE -> {
                for (int z : new int[]{3, 10}) {
                    set(chunk, 2, 65, z, Material.QUARTZ_STAIRS);
                    set(chunk, 3, 65, z, Material.SMOOTH_QUARTZ);
                    set(chunk, 3, 66, z, Material.BLACK_CONCRETE);
                    set(chunk, 12, 65, z, Material.BOOKSHELF);
                }

                set(chunk, 13, 65, 4, Material.FLOWER_POT);
            }

            case LIBRARY -> {
                for (int z = 2; z <= 11; z++) {
                    for (int y = 65; y <= 68; y++) {
                        set(chunk, 2, y, z, Material.BOOKSHELF);
                        set(chunk, 13, y, z, Material.BOOKSHELF);
                    }
                }

                set(chunk, 3, 65, 6, Material.LECTERN);
                set(chunk, 12, 65, 6, Material.LECTERN);
            }

            case STORAGE -> {
                for (int z : new int[]{3, 6, 10}) {
                    set(chunk, 2, 65, z, Material.BARREL);
                    set(chunk, 2, 66, z, Material.BARREL);
                    set(chunk, 3, 65, z, Material.OAK_PLANKS);
                    set(chunk, 12, 65, z, Material.SCAFFOLDING);
                    set(chunk, 13, 65, z, Material.HAY_BLOCK);
                }
            }

            case PARKING -> {
                for (int z = 2; z <= 13; z++) {
                    set(chunk, 4, 64, z, Material.WHITE_CONCRETE);
                    set(chunk, 11, 64, z, Material.WHITE_CONCRETE);
                }

                for (int z : new int[]{3, 10}) {
                    set(chunk, 2, 65, z, Material.BLACK_CONCRETE);
                    set(chunk, 3, 65, z, Material.GRAY_CONCRETE);
                    set(chunk, 3, 66, z, Material.GLASS);
                    set(chunk, 13, 65, z, Material.GRAY_CONCRETE);
                }
            }

            case HOME -> {
                set(chunk, 2, 65, 3, Material.RED_BED);
                set(chunk, 3, 65, 3, Material.OAK_PLANKS);
                set(chunk, 3, 66, 3, Material.LANTERN);

                set(chunk, 2, 65, 8, Material.FURNACE);
                set(chunk, 3, 65, 8, Material.CRAFTING_TABLE);
                set(chunk, 12, 65, 5, Material.BOOKSHELF);

                set(chunk, 12, 65, 9, Material.WHITE_WOOL);
                set(chunk, 13, 65, 9, Material.WHITE_WOOL);
            }

            case POOL -> {
                // Неглубокие бассейны по бокам.
                for (int x : new int[]{2, 3, 4, 11, 12, 13}) {
                    for (int z = 3; z <= 9; z++) {
                        set(chunk, x, 62, z,
                                Material.PRISMARINE_BRICKS);
                        set(chunk, x, 63, z, Material.WATER);
                        set(chunk, x, 64, z, Material.WATER);
                    }
                }

                set(chunk, 3, 65, 11,
                        Material.SMOOTH_QUARTZ_STAIRS);
                set(chunk, 12, 65, 11,
                        Material.SMOOTH_QUARTZ_STAIRS);
            }

            case GRASS -> {
                for (int x : new int[]{2, 4, 11, 13}) {
                    for (int z : new int[]{3, 6, 10}) {
                        set(chunk, x, 65, z,
                                Material.SHORT_GRASS);
                    }
                }

                set(chunk, 3, 65, 4, Material.OAK_LOG);
                set(chunk, 3, 66, 4, Material.OAK_LOG);
                set(chunk, 3, 67, 4, Material.OAK_LEAVES);

                set(chunk, 12, 65, 9, Material.MOSS_BLOCK);
                set(chunk, 12, 66, 9, Material.AZALEA);
            }

            case MINIATURE -> plugin.miniature.draw(chunk);
        }
    }

    private void door(
            Chunk chunk, int x, int z,
            Material material, BlockFace facing) {

        for (Bisected.Half half : Bisected.Half.values()) {
            Door data = (Door) Bukkit.createBlockData(material);

            data.setFacing(facing);
            data.setHalf(half);
            data.setOpen(false);

            int y = half == Bisected.Half.BOTTOM ? 65 : 66;

            chunk.getBlock(x, y, z).setBlockData(data, false);
        }
    }

    private void set(
            Chunk chunk,
            int x, int y, int z,
            Material material) {

        chunk.getBlock(x, y, z).setType(material, false);
    }

    private boolean nearPlayer(Chunk chunk, double distance) {
        Location center = new Location(
                plugin.backrooms,
                chunk.getX() * 16 + 8,
                65,
                chunk.getZ() * 16 + 8);

        for (Player player : plugin.backrooms.getPlayers()) {
            if (plugin.isReal(player)
                    && player.getLocation().distanceSquared(center)
                    < distance * distance) {
                return true;
            }
        }

        return false;
    }

    void shiftOne() {
        Chunk[] loaded = plugin.backrooms.getLoadedChunks();

        if (loaded.length == 0) {
            return;
        }

        double safe = Math.max(32,
                plugin.getConfig().getDouble(
                        "shifting.safe-distance", 48));

        // Ограничиваем работу одной попытки.
        for (int attempt = 0; attempt < 16; attempt++) {
            Chunk chunk = loaded[random.nextInt(loaded.length)];
            int level = BackroomsGenerator.level(chunk.getX());

            if (!BackroomsGenerator.valid(level)
                    || BackroomsGenerator.theme(level).ordinal() >= 6
                    || nearPlayer(chunk, safe)) {
                continue;
            }

            var pdc = chunk.getPersistentDataContainer();

            if (!pdc.has(plugin.decoratedKey,
                    PersistentDataType.BYTE)
                    || pdc.has(plugin.editedKey,
                    PersistentDataType.BYTE)) {
                continue;
            }

            int gap = 4 + random.nextInt(7);
            Material wall = BackroomsGenerator.theme(level).wall;

            // Две стены оставляют между собой коридор шириной 2 блока.
            for (int x : new int[]{6, 9}) {
                for (int z = 3; z <= 12; z++) {
                    Material material =
                            z == gap || z == gap + 1
                                    ? Material.AIR : wall;

                    for (int y = 65;
                         y < BackroomsGenerator.roof(level);
                         y++) {

                        set(chunk, x, y, z, material);
                    }
                }
            }

            return;
        }
    }
}
