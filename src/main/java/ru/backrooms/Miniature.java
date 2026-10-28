package ru.backrooms;

import org.bukkit.*;
import org.bukkit.command.CommandSender;

import java.util.*;
import java.util.concurrent.CompletableFuture;

final class Miniature {

    private final BackroomsPlugin plugin;

    private final int[] heights = new int[100];
    private final Material[] colors = new Material[100];

    private boolean capturing;

    Miniature(BackroomsPlugin plugin) {
        this.plugin = plugin;

        List<Integer> savedHeights =
                plugin.getConfig().getIntegerList(
                        "miniature.snapshot-heights");

        List<String> savedColors =
                plugin.getConfig().getStringList(
                        "miniature.snapshot-colors");

        for (int i = 0; i < 100; i++) {
            heights[i] = savedHeights.size() == 100
                    ? Math.clamp(savedHeights.get(i), 1, 5)
                    : 1;

            Material saved = savedColors.size() == 100
                    ? Material.matchMaterial(savedColors.get(i))
                    : null;

            colors[i] = saved != null && saved.isBlock()
                    ? saved : Material.LIME_CONCRETE;
        }
    }

    void capture(CommandSender sender) {
        if (capturing) {
            tell(sender, "Снимок уже создаётся.");
            return;
        }

        String name = plugin.getConfig().getString(
                "miniature.source-world", "world");

        World source = Bukkit.getWorld(name);

        if (source == null
                || source.equals(plugin.backrooms)) {
            tell(sender,
                    "Исходный мир миниатюры недоступен.");
            return;
        }

        capturing = true;

        Location spawn = source.getSpawnLocation();
        int startX = spawn.getBlockX() - 20;
        int startZ = spawn.getBlockZ() - 20;

        List<CompletableFuture<Chunk>> chunks =
                new ArrayList<>();

        int minCX = Math.floorDiv(startX, 16);
        int maxCX = Math.floorDiv(startX + 39, 16);
        int minCZ = Math.floorDiv(startZ, 16);
        int maxCZ = Math.floorDiv(startZ + 39, 16);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                chunks.add(source.getChunkAtAsync(cx, cz));
            }
        }

        CompletableFuture.allOf(
                chunks.toArray(CompletableFuture[]::new)
        ).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (error != null) {
                            tell(sender,
                                    "Не удалось загрузить участок world.");
                            plugin.getLogger().warning(
                                    "Ошибка снимка: "
                                            + error.getMessage());
                            return;
                        }

                        int[] surface = new int[100];
                        Material[] sampled = new Material[100];
                        int lowest = Integer.MAX_VALUE;

                        for (int z = 0; z < 10; z++) {
                            for (int x = 0; x < 10; x++) {
                                int index = z * 10 + x;

                                var block = source.getHighestBlockAt(
                                        startX + x * 4,
                                        startZ + z * 4);

                                surface[index] = block.getY();
                                sampled[index] = color(block.getType());

                                lowest = Math.min(
                                        lowest, block.getY());
                            }
                        }

                        List<Integer> saveHeights = new ArrayList<>();
                        List<String> saveColors = new ArrayList<>();

                        for (int i = 0; i < 100; i++) {
                            heights[i] = Math.clamp(
                                    1 + (surface[i] - lowest) / 4,
                                    1, 5);

                            colors[i] = sampled[i];

                            saveHeights.add(heights[i]);
                            saveColors.add(colors[i].name());
                        }

                        plugin.getConfig().set(
                                "miniature.snapshot-heights",
                                saveHeights);

                        plugin.getConfig().set(
                                "miniature.snapshot-colors",
                                saveColors);

                        plugin.saveConfig();

                        tell(sender,
                                "Снимок поверхности 40×40 обновлён. "
                                        + "Он появится в новых комнатах "
                                        + "уровней 21–23.");
                    } finally {
                        capturing = false;
                    }
                }));
    }

    void draw(Chunk chunk) {
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 10; x++) {
                int index = z * 10 + x;

                for (int y = 0; y < heights[index]; y++) {
                    chunk.getBlock(
                            x + 2, 65 + y, z + 2
                    ).setType(colors[index], false);
                }
            }
        }
    }

    private Material color(Material material) {
        String name = material.name();

        if (name.contains("WATER")
                || name.contains("ICE")) {
            return Material.LIGHT_BLUE_CONCRETE;
        }

        if (name.contains("LEAVES")
                || name.contains("MOSS")) {
            return Material.GREEN_CONCRETE;
        }

        if (name.contains("GRASS")
                || name.contains("FERN")) {
            return Material.LIME_CONCRETE;
        }

        if (name.contains("SAND")) {
            return Material.YELLOW_CONCRETE;
        }

        if (name.contains("SNOW")
                || name.contains("QUARTZ")) {
            return Material.WHITE_CONCRETE;
        }

        if (name.contains("LOG")
                || name.contains("PLANK")
                || name.contains("DIRT")) {
            return Material.BROWN_CONCRETE;
        }

        if (name.contains("BRICK")) {
            return Material.RED_CONCRETE;
        }

        return Material.GRAY_CONCRETE;
    }

    private void tell(
            CommandSender sender, String message) {

        if (sender != null) {
            sender.sendMessage(message);
        } else {
            plugin.getLogger().info(message);
        }
    }
}
