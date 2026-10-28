package ru.backrooms;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

final class EchoManager {

    private final BackroomsPlugin plugin;
    private final NPCRegistry registry;

    private final List<Echo> active = new ArrayList<>();
    private final Random random = new Random();

    private static final class Echo {
        final NPC npc;
        final Location start;

        int age;
        int exitStep = -1;
        Location exitFrom;

        Echo(NPC npc, Location start) {
            this.npc = npc;
            this.start = start;
        }
    }

    EchoManager(BackroomsPlugin plugin) {
        this.plugin = plugin;

        if (CitizensAPI.getNPCRegistry() == null) {
            throw new IllegalStateException(
                    "Citizens ещё не готов.");
        }

        registry = CitizensAPI.createInMemoryNPCRegistry(
                "backrooms-" + System.nanoTime());
    }

    boolean owns(Entity entity) {
        for (Echo echo : active) {
            if (echo.npc.isSpawned()
                    && echo.npc.getEntity().getUniqueId()
                    .equals(entity.getUniqueId())) {
                return true;
            }
        }

        return false;
    }

    void trySpawn() {
        int limit = Math.max(0,
                plugin.getConfig().getInt(
                        "echoes.max-active", 6));

        if (active.size() >= limit) {
            return;
        }

        List<Player> players = plugin.backrooms.getPlayers()
                .stream()
                .filter(plugin::isReal)
                .toList();

        ConfigurationSection visitors =
                plugin.getConfig().getConfigurationSection(
                        "visitors");

        if (players.isEmpty() || visitors == null) {
            return;
        }

        List<String> names = visitors.getValues(false)
                .values()
                .stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .toList();

        if (names.isEmpty()) {
            return;
        }

        Player observer = players.get(random.nextInt(players.size()));
        Chunk chunk = observer.getLocation().getChunk();

        int level = BackroomsGenerator.level(chunk.getX());

        if (!BackroomsGenerator.valid(level)
                || BackroomsGenerator.theme(level)
                == BackroomsGenerator.Theme.MINIATURE) {
            return;
        }

        // В изменённых игроками комнатах не предполагаем,
        // что исходная стена и проходы ещё существуют.
        if (chunk.getPersistentDataContainer().has(
                plugin.editedKey,
                org.bukkit.persistence.PersistentDataType.BYTE)) {
            return;
        }

        plugin.rooms.ensure(chunk);

        int baseX = chunk.getX() * 16;
        int baseZ = chunk.getZ() * 16;

        Location start = new Location(
                plugin.backrooms,
                baseX + 0.5,
                65,
                baseZ + 4.5,
                -90,
                0);

        String name = names.get(random.nextInt(names.size()));

        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.setProtected(false);

        if (plugin.getConfig().getBoolean(
                "echoes.skins", true)) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(name);
        }

        if (!npc.spawn(start)) {
            npc.destroy();
            return;
        }

        active.add(new Echo(npc, start.clone()));
    }

    // Вызывается каждые пять серверных тиков.
    void tick() {
        Iterator<Echo> iterator = active.iterator();

        while (iterator.hasNext()) {
            Echo echo = iterator.next();

            if (!echo.npc.isSpawned()) {
                echo.npc.destroy();
                iterator.remove();
                continue;
            }

            echo.age++;

            if (echo.age <= 8) {
                double progress = echo.age / 8.0;

                echo.npc.getEntity().teleport(
                        echo.start.clone().add(
                                2.0 * progress, 0, 0));

                continue;
            }

            if (echo.age == 9) {
                echo.npc.getNavigator()
                        .getDefaultParameters()
                        .speedModifier(0.7f);

                echo.npc.getNavigator().setTarget(
                        echo.start.clone().add(2, 0, 7));
            }

            // Даже застрявший NPC не остаётся навсегда.
            if (echo.age >= 80 && echo.exitStep < 0) {
                echo.npc.getNavigator().cancelNavigation();
                echo.exitFrom =
                        echo.npc.getEntity().getLocation().clone();
                echo.exitStep = 0;
            }

            if (echo.exitStep >= 0) {
                echo.exitStep++;

                double progress = Math.min(
                        1, echo.exitStep / 8.0);

                Location position = echo.exitFrom.clone();
                double wallX = echo.start.getX();

                position.setX(
                        echo.exitFrom.getX()
                                + (wallX - echo.exitFrom.getX())
                                * progress);

                echo.npc.getEntity().teleport(position);

                if (echo.exitStep >= 8) {
                    echo.npc.destroy();
                    iterator.remove();
                }
            }
        }
    }

    void close() {
        for (Echo echo : active) {
            echo.npc.destroy();
        }

        active.clear();
    }
}
