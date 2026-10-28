package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import java.util.*;

public final class BackroomsPlugin extends JavaPlugin
        implements Listener, CommandExecutor {

    World backrooms;
    RoomManager rooms;
    EchoManager echoes;
    Miniature miniature;

    NamespacedKey decoratedKey;
    NamespacedKey editedKey;
    NamespacedKey portalKey;
    private NamespacedKey lootMarker;
    private NamespacedKey transitionRoomKey;

    long ticks;

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        decoratedKey = new NamespacedKey(this, "decorated");
        editedKey = new NamespacedKey(this, "edited");
        portalKey = new NamespacedKey(this, "portal");
        lootMarker = new NamespacedKey(this, "loot_populated");
        transitionRoomKey = new NamespacedKey(this, "transition_room");

        String name = getConfig().getString(
                "world-name", "backrooms_shift");
        String source = getConfig().getString(
                "miniature.source-world", "world");

        if (name.equals(source)) {
            getLogger().severe(
                    "Мир Backrooms не должен совпадать с исходным миром.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        World existing = Bukkit.getWorld(name);

        if (existing != null
                && !(existing.getGenerator()
                instanceof BackroomsGenerator)) {
            getLogger().severe(
                    "Указанное имя занято миром с другим генератором.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        backrooms = existing != null ? existing :
                new WorldCreator(name)
                        .seed(getConfig().getLong("seed", 778013))
                        .generator(new BackroomsGenerator())
                        .generateStructures(false)
                        .createWorld();

        if (backrooms == null) {
            getLogger().severe("Не удалось открыть мир Backrooms.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        backrooms.setSpawnLocation(8, 65, 1);
        backrooms.setTime(18000);

        backrooms.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        backrooms.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        backrooms.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        backrooms.setGameRule(GameRule.DO_FIRE_TICK, false);
        backrooms.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);

        miniature = new Miniature(this);
        rooms = new RoomManager(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("br")).setExecutor(this);

        for (Chunk chunk : backrooms.getLoadedChunks()) {
            rooms.enqueue(chunk);
        }

        // Инициализация после включения зависимостей.
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                echoes = new EchoManager(this);

                for (Player player : Bukkit.getOnlinePlayers()) {
                    rememberVisitor(player);
                }

                miniature.capture(null);
            } catch (Exception ex) {
                getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Ошибка запуска NPC или миниатюры", ex);
                Bukkit.getPluginManager().disablePlugin(this);
            }
        });

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            ticks++;
            rooms.tick();

            int shiftInterval = Math.max(20,
                    getConfig().getInt(
                            "shifting.interval-ticks", 200));

            if (getConfig().getBoolean("shifting.enabled", true)
                    && ticks % shiftInterval == 0) {
                rooms.shiftOne();
            }

            if (echoes != null) {
                if (ticks % 5 == 0) {
                    echoes.tick();
                }

                int interval = Math.max(100,
                        getConfig().getInt(
                                "echoes.spawn-interval-ticks", 600));

                if (getConfig().getBoolean("echoes.enabled", true)
                        && ticks % interval == 0) {
                    echoes.trySpawn();
                }
            }

            if (ticks % 1200 == 0) {
                cooldowns.entrySet().removeIf(
                        entry -> entry.getValue() < ticks);
            }
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (echoes != null) {
            echoes.close();
        }
        saveConfig();
    }

    boolean isReal(Player player) {
        return !player.hasMetadata("NPC");
    }

    boolean isBackrooms(World world) {
        return backrooms != null && backrooms.equals(world);
    }

    private void rememberVisitor(Player player) {
        if (!isReal(player)) {
            return;
        }

        getConfig().set(
                "visitors." + player.getUniqueId(),
                player.getName());
        saveConfig();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rememberVisitor(event.getPlayer());
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent event) {
        if (isBackrooms(event.getWorld())) {
            rooms.enqueue(event.getChunk());
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {

        if (!sender.hasPermission("backrooms.use")) {
            sender.sendMessage("Нет разрешения.");
            return true;
        }

        String sub = args.length == 0
                ? "levels" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("levels")) {
            for (int i = 0; i < 24; i++) {
                sender.sendMessage(i + " — "
                        + BackroomsGenerator.title(i));
            }
            sender.sendMessage(
                    "/br enter <номер>, /br leave, /br snapshot");
            return true;
        }

        if (sub.equals("snapshot")) {
            if (!sender.hasPermission("backrooms.admin")) {
                sender.sendMessage("Нужны права администратора.");
                return true;
            }
            miniature.capture(sender);
            return true;
        }

        if (!(sender instanceof Player player)
                || !isReal(player)) {
            sender.sendMessage("Эта команда предназначена для игрока.");
            return true;
        }

        if (sub.equals("leave")) {
            leave(player);
            return true;
        }

        if (sub.equals("enter")) {
            int level = 0;

            if (args.length > 1) {
                try {
                    level = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    player.sendMessage("Укажи номер от 0 до 23.");
                    return true;
                }
            }

            if (level < 0 || level >= 24) {
                player.sendMessage("Укажи номер от 0 до 23.");
                return true;
            }

            enter(player, level);
            return true;
        }

        player.sendMessage(
                "/br enter <0-23>, /br leave, /br levels");
        return true;
    }

    void enter(Player player, int level) {
        if (!isBackrooms(player.getWorld())) {
            getConfig().set(
                    "returns." + player.getUniqueId(),
                    player.getLocation());
            saveConfig();
        }

        cooldowns.put(player.getUniqueId(), ticks + 60);

        // Центр отдельной полосы мира, отведённой уровню.
        int blockX = level * 4096 + 8;

        backrooms.getChunkAtAsync(blockX >> 4, 0)
                .thenAccept(chunk ->
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (!player.isOnline()) {
                                return;
                            }

                            rooms.ensure(chunk);

                            Location destination = new Location(
                                    backrooms,
                                    blockX + 0.5,
                                    65,
                                    1.5);

                            if (player.teleport(destination)) {
                                player.sendMessage("§7Уровень "
                                        + level + ": "
                                        + BackroomsGenerator.title(level));
                                player.playSound(
                                        destination,
                                        Sound.BLOCK_PORTAL_TRAVEL,
                                        0.25f, 0.6f);
                            }
                        }))
                .exceptionally(error -> {
                    getLogger().warning(
                            "Не удалось загрузить вход уровня: "
                                    + error.getMessage());

                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(
                                    "Не удалось открыть уровень.");
                        }
                    });

                    return null;
                });
    }

    private void leave(Player player) {
        Location target = getConfig().getLocation(
                "returns." + player.getUniqueId());

        if (target == null || target.getWorld() == null
                || isBackrooms(target.getWorld())) {

            World source = Bukkit.getWorld(
                    getConfig().getString(
                            "miniature.source-world", "world"));

            if (source == null) {
                player.sendMessage(
                        "Мир возвращения недоступен. "
                                + "Обратись к администратору.");
                return;
            }

            target = source.getSpawnLocation();
        }

        cooldowns.put(player.getUniqueId(), ticks + 60);

        if (player.teleport(target)) {
            player.sendMessage("§7Ты покидаешь Backrooms.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (!isReal(player) || to == null
                || !isBackrooms(to.getWorld())) {
            return;
        }

        if (event.getFrom().getBlockX() == to.getBlockX()
                && event.getFrom().getBlockZ() == to.getBlockZ()
                && event.getFrom().getBlockY() == to.getBlockY()) {
            return;
        }

        if (cooldowns.getOrDefault(
                player.getUniqueId(), 0L) > ticks) {
            return;
        }

        // Специальные двери расположены в локальной точке 12,12.
        if (Math.floorMod(to.getBlockX(), 16) != 12
                || Math.floorMod(to.getBlockZ(), 16) != 12
                || to.getBlockY() < 65
                || to.getBlockY() > 66) {
            return;
        }

        Chunk chunk = to.getChunk();

        Integer destination =
                chunk.getPersistentDataContainer().get(
                        portalKey, PersistentDataType.INTEGER);

        if (destination != null) {
            cooldowns.put(player.getUniqueId(), ticks + 100);
            enter(player, destination);
        }
    }

    private boolean portalBlock(Block block) {
        return block.getChunk().getPersistentDataContainer().has(
                portalKey, PersistentDataType.INTEGER)
                && Math.floorMod(block.getX(), 16) >= 11
                && Math.floorMod(block.getX(), 16) <= 13
                && Math.floorMod(block.getZ(), 16) == 12
                && block.getY() >= 65
                && block.getY() <= 68;
    }

    private void edited(Block block) {
        block.getChunk().getPersistentDataContainer().set(
                editedKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isBackrooms(event.getBlock().getWorld())) {
            return;
        }

        if (portalBlock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        edited(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isBackrooms(event.getBlock().getWorld())) {
            return;
        }

        if (portalBlock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        edited(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isBackrooms(event.getBlock().getWorld())) {
            edited(event.getBlock());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isBackrooms(event.getBlock().getWorld())) {
            edited(event.getBlock());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (isBackrooms(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (isBackrooms(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (isBackrooms(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (isBackrooms(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (echoes != null && echoes.owns(event.getEntity())
                && event.getCause()
                == EntityDamageEvent.DamageCause.SUFFOCATION) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (echoes != null && echoes.owns(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    // =========================================================
    // НОВЫЕ МЕТОДЫ ИЗ ЗАПРОСА
    // =========================================================

    private void buildPortal(World world, int x, int z, int level) {
        /*
         * Комната размером 5 × 6 блоков.
         *
         * Вход находится с северной стороны:
         *
         * #######
         *   D       D — обычная деревянная дверь
         * #   #
         * # - #     - — невидимая линия перехода внутри комнаты
         * #   #
         * #####
         *
         * В текущем buildChunk() x и z выбираются внутри чанка,
         * поэтому эта небольшая конструкция помещается в него.
         */

        int doorY = floorY + 1;
        int doorZ = z - 2;

        int roomMinX = x - 2;
        int roomMaxX = x + 2;
        int roomMinZ = z - 2;
        int roomMaxZ = z + 3;

        Material roomWall = wallForLevel(level);
        Material roomFloor = floorForLevel(level);
        Material roomCeiling = ceilingForLevel(level);

        /*
         * Для переходной комнаты нужен твёрдый безопасный пол.
         * Например, на водном или магмовом уровне не используем
         * материал основного пола внутри самого перехода.
         */
        if (!roomFloor.isSolid()
                || roomFloor == Material.MAGMA_BLOCK
                || roomFloor == Material.CAMPFIRE
                || roomFloor == Material.SOUL_CAMPFIRE) {
            roomFloor = Material.SMOOTH_STONE;
        }

        for (int bx = roomMinX; bx <= roomMaxX; bx++) {
            for (int bz = roomMinZ; bz <= roomMaxZ; bz++) {
                world.getBlockAt(bx, floorY, bz)
                        .setType(roomFloor, false);

                world.getBlockAt(bx, ceilingY, bz)
                        .setType(roomCeiling, false);

                boolean perimeter =
                        bx == roomMinX
                                || bx == roomMaxX
                                || bz == roomMinZ
                                || bz == roomMaxZ;

                for (int by = doorY; by < ceilingY; by++) {
                    world.getBlockAt(bx, by, bz)
                            .setType(
                                    perimeter ? roomWall : Material.AIR,
                                    false
                            );
                }
            }
        }

        // Освещение внутри помещения.
        world.getBlockAt(x, ceilingY, z + 1)
                .setType(Material.SEA_LANTERN, false);

        // Свободный подход к двери со стороны основного помещения.
        for (int by = doorY; by <= doorY + 1; by++) {
            world.getBlockAt(x, by, doorZ - 1)
                    .setType(Material.AIR, false);
        }

        Block approachFloor = world.getBlockAt(
                x, floorY, doorZ - 1
        );

        if (!approachFloor.getType().isSolid()) {
            approachFloor.setType(Material.SMOOTH_STONE, false);
        }

        // Обычная дверь, которая не выглядит порталом.
        for (Bisected.Half half : Bisected.Half.values()) {
            Door door = (Door) Bukkit.createBlockData(
                    Material.OAK_DOOR
            );

            door.setFacing(BlockFace.SOUTH);
            door.setHalf(half);
            door.setOpen(false);

            int by = half == Bisected.Half.BOTTOM
                    ? doorY
                    : doorY + 1;

            world.getBlockAt(x, by, doorZ)
                    .setBlockData(door, false);
        }

        /*
         * Стабильное назначение конкретной комнаты.
         * Сохраняем существующую нумерацию уровней 0–22.
         * Текущий уровень исключаем.
         */
        Random routeRandom = new Random(
                hash(x, doorZ, level + 5031)
        );

        int destination = routeRandom.nextInt(22);

        if (destination >= level) {
            destination++;
        }

        /*
         * Храним координаты двери и назначение в данных чанка.
         * В существующем генераторе создаётся максимум
         * одна переходная комната на чанк.
         */
        String encoded =
                x + ";"
                        + doorY + ";"
                        + doorZ + ";"
                        + destination;

        world.getBlockAt(x, doorY, doorZ)
                .getChunk()
                .getPersistentDataContainer()
                .set(
                        transitionRoomKey,
                        PersistentDataType.STRING,
                        encoded
                );
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalStep(PlayerMoveEvent event) {
        if (!generationEnabled || event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();

        // Citizens-NPC не должны запускать переходы игроков.
        if (player.hasMetadata("NPC")) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        World world = to.getWorld();

        if (world == null
                || !world.getName().equalsIgnoreCase(worldName)
                || !world.equals(from.getWorld())) {
            return;
        }

        // Поворот головы без перемещения не обрабатываем.
        if (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) {
            return;
        }

        long now = System.currentTimeMillis();

        Long lastTransition = portalCooldowns.get(
                player.getUniqueId()
        );

        if (lastTransition != null
                && now - lastTransition < 2500L) {
            return;
        }

        /*
         * Проверяем только уже загруженные ближайшие чанки.
         * Сам поиск двери не загружает новую область.
         */
        int centerChunkX = to.getBlockX() >> 4;
        int centerChunkZ = to.getBlockZ() >> 4;

        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                int chunkX = centerChunkX + offsetX;
                int chunkZ = centerChunkZ + offsetZ;

                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                Chunk chunk = world.getChunkAt(chunkX, chunkZ);

                String encoded = chunk
                        .getPersistentDataContainer()
                        .get(
                                transitionRoomKey,
                                PersistentDataType.STRING
                        );

                if (encoded == null) {
                    continue;
                }

                int[] entry = decodeTransitionRoom(encoded);

                if (entry == null) {
                    continue;
                }

                int doorX = entry[0];
                int doorY = entry[1];
                int doorZ = entry[2];
                int destination = entry[3];

                Block doorBlock = world.getBlockAt(
                        doorX, doorY, doorZ
                );

                if (!(doorBlock.getBlockData() instanceof Door door)
                        || door.getHalf() != Bisected.Half.BOTTOM
                        || !door.isOpen()) {
                    continue;
                }

                /*
                 * Линия находится на один блок глубже центра двери.
                 * Переход возможен только при движении снаружи внутрь.
                 */
                double thresholdZ = doorZ + 1.5;

                if (from.getZ() >= thresholdZ
                        || to.getZ() < thresholdZ) {
                    continue;
                }

                double deltaZ = to.getZ() - from.getZ();

                if (deltaZ <= 0.0) {
                    continue;
                }

                // Точка фактического пересечения линии.
                double fraction =
                        (thresholdZ - from.getZ()) / deltaZ;

                double crossingX = from.getX()
                        + (to.getX() - from.getX()) * fraction;

                double crossingY = from.getY()
                        + (to.getY() - from.getY()) * fraction;

                // Переход только через проход шириной в один блок.
                if (crossingX < doorX
                        || crossingX >= doorX + 1.0
                        || crossingY < doorY
                        || crossingY >= doorY + 2.0) {
                    continue;
                }

                portalCooldowns.put(player.getUniqueId(), now);

                Location target = findTransitionArrival(
                        world, destination, to
                );

                if (target == null) {
                    player.sendMessage(
                            ChatColor.GRAY
                                    + "За дверью пока не удаётся "
                                    + "найти безопасный проход."
                    );
                    return;
                }

                target.setYaw(to.getYaw());
                target.setPitch(to.getPitch());

                /*
                 * Меняем конечную точку текущего перемещения,
                 * вместо отдельного телепорта прямо внутри события.
                 */
                event.setTo(target);

                if (player.isOp()) {
                    player.sendMessage(
                            ChatColor.DARK_GRAY
                                    + "[Backrooms] Переход: "
                                    + levelName(destination)
                    );
                }

                return;
            }
        }
    }

    private int[] decodeTransitionRoom(String encoded) {
        String[] parts = encoded.split(";", -1);

        if (parts.length != 4) {
            return null;
        }

        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            int destination = Integer.parseInt(parts[3]);

            if (destination < 0 || destination > 22) {
                return null;
            }

            return new int[]{x, y, z, destination};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Location findTransitionArrival(
            World world,
            int destination,
            Location near
    ) {
        Location region = findLevelLocation(
                world, destination, near
        );

        if (region == null) {
            return null;
        }

        int centerChunkX = region.getBlockX() >> 4;
        int centerChunkZ = region.getBlockZ() >> 4;

        /*
         * Временно используем существующий генератор.
         * Сначала проверяем центральный чанк,
         * затем соседние при отсутствии безопасного места.
         */
        int[][] offsets = {
                {0, 0},
                {1, 0},
                {-1, 0},
                {0, 1},
                {0, -1}
        };

        for (int[] offset : offsets) {
            int chunkX = centerChunkX + offset[0];
            int chunkZ = centerChunkZ + offset[1];

            int sampleX = chunkX * 16 + 8;
            int sampleZ = chunkZ * 16 + 8;

            if (getLevelAt(sampleX, sampleZ) != destination) {
                continue;
            }

            String chunkKey =
                    world.getName() + ":"
                            + chunkX + ":" + chunkZ;

            if (builtChunks.add(chunkKey)) {
                buildChunk(world, chunkX, chunkZ);
            }

            Chunk chunk = world.getChunkAt(chunkX, chunkZ);

            int[] transition = null;

            String encoded = chunk
                    .getPersistentDataContainer()
                    .get(
                            transitionRoomKey,
                            PersistentDataType.STRING
                    );

            if (encoded != null) {
                transition = decodeTransitionRoom(encoded);
            }

            for (int localX = 2; localX <= 13; localX++) {
                for (int localZ = 2; localZ <= 13; localZ++) {
                    int x = chunkX * 16 + localX;
                    int z = chunkZ * 16 + localZ;

                    /*
                     * Не ставим прибывшего игрока
                     * внутрь другой переходной комнаты.
                     */
                    if (transition != null
                            && Math.abs(x - transition[0]) <= 2
                            && z >= transition[2]
                            && z <= transition[2] + 5) {
                        continue;
                    }

                    Block floor = world.getBlockAt(x, floorY, z);
                    Block feet = world.getBlockAt(x, floorY + 1, z);
                    Block head = world.getBlockAt(x, floorY + 2, z);

                    Material ground = floor.getType();

                    if (!ground.isSolid()
                            || ground == Material.MAGMA_BLOCK
                            || ground == Material.CACTUS
                            || ground == Material.CAMPFIRE
                            || ground == Material.SOUL_CAMPFIRE) {
                        continue;
                    }

                    if (!feet.getType().isAir()
                            || !head.getType().isAir()) {
                        continue;
                    }

                    return new Location(
                            world,
                            x + 0.5,
                            floorY + 1,
                            z + 0.5
                    );
                }
            }
        }

        return null;
    }
}
