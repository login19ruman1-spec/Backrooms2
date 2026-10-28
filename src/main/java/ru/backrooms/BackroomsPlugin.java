package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BackroomsPlugin extends JavaPlugin
        implements Listener, CommandExecutor {

    private static final int FLOOR = 64;
    private static final int CELL = 96;
    private static final int HOMES = 22;
    private static final int MINI = 23;
    private static final int EXIT = 24;
    private static final int PARKING = 25;
    private static final int LEVEL_COUNT = 26;

    private static final int SOURCE_WIDTH = 1900;
    private static final int SOURCE_DEPTH = 1700;

    private static final String[] TITLES = {
            "Жёлтые офисы",
            "Сырые служебные проходы",
            "Складские ангары",
            "Медные коммуникационные тоннели",
            "Заросшие помещения",
            "Чёрные подземные галереи",
            "Заброшенные кабинеты",
            "Изолятор",
            "Библиотечный архив",
            "Красные аварийные коридоры",
            "Холодильные склады",
            "Ледяные пещеры",
            "Бассейны",
            "Резервуары и мостки",
            "Костяные залы",
            "Котельная",
            "Геометрические залы",
            "Подвесные галереи",
            "Белые выставочные помещения",
            "Грязевые технические камеры",
            "Глубинные катакомбы",
            "Перегретые коммуникации",
            "Жилой район-переход",
            "Миниатюра world",
            "EXIT",
            "Подземная парковка"
    };

    private static final Material[] WALLS = {
            Material.YELLOW_TERRACOTTA,
            Material.MOSSY_COBBLESTONE,
            Material.SMOOTH_STONE,
            Material.CUT_COPPER,
            Material.MOSS_BLOCK,
            Material.POLISHED_BLACKSTONE_BRICKS,
            Material.OAK_PLANKS,
            Material.IRON_BLOCK,
            Material.BOOKSHELF,
            Material.RED_NETHER_BRICKS,
            Material.PACKED_ICE,
            Material.BLUE_ICE,
            Material.PRISMARINE_BRICKS,
            Material.DARK_PRISMARINE,
            Material.BONE_BLOCK,
            Material.NETHER_BRICKS,
            Material.END_STONE_BRICKS,
            Material.PURPUR_BLOCK,
            Material.CALCITE,
            Material.TUFF_BRICKS,
            Material.DEEPSLATE_BRICKS,
            Material.CRYING_OBSIDIAN,
            Material.BRICKS,
            Material.BLACK_CONCRETE,
            Material.QUARTZ_BLOCK,
            Material.GRAY_CONCRETE
    };

    private static final Material[] FLOORS = {
            Material.YELLOW_WOOL,
            Material.MOSSY_STONE_BRICKS,
            Material.GRAY_CONCRETE,
            Material.EXPOSED_COPPER,
            Material.MOSS_BLOCK,
            Material.BLACKSTONE,
            Material.SPRUCE_PLANKS,
            Material.IRON_BLOCK,
            Material.OAK_PLANKS,
            Material.RED_CONCRETE,
            Material.SNOW_BLOCK,
            Material.BLUE_ICE,
            Material.WHITE_CONCRETE,
            Material.DARK_PRISMARINE,
            Material.BONE_BLOCK,
            Material.SOUL_SOIL,
            Material.END_STONE,
            Material.PURPUR_BLOCK,
            Material.CALCITE,
            Material.MUD_BRICKS,
            Material.POLISHED_DEEPSLATE,
            Material.POLISHED_BASALT,
            Material.STONE_BRICKS,
            Material.BLACK_CONCRETE,
            Material.SMOOTH_QUARTZ,
            Material.GRAY_CONCRETE
    };

    private static final Material[] COLORS = {
            Material.WHITE_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE,
            Material.GRAY_CONCRETE,
            Material.BLACK_CONCRETE,
            Material.BROWN_CONCRETE,
            Material.GREEN_CONCRETE,
            Material.LIME_CONCRETE,
            Material.BLUE_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE,
            Material.CYAN_CONCRETE,
            Material.YELLOW_CONCRETE,
            Material.ORANGE_CONCRETE,
            Material.RED_CONCRETE,
            Material.PINK_CONCRETE,
            Material.PURPLE_CONCRETE,
            Material.MAGENTA_CONCRETE
    };

    private final Map<UUID, Integer> worldLevels = new HashMap<>();
    private final Map<Integer, World> levelWorlds = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> travelling = new HashSet<>();

    private final Deque<Chunk> lootQueue = new ArrayDeque<>();
    private final Set<String> queuedChunks = new HashSet<>();

    private final Set<String> trackedPlayerNames =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private final List<Echo> echoes = new ArrayList<>();
    private final Map<UUID, Echo> echoEntities = new HashMap<>();

    private final Random random = new Random();

    private NamespacedKey lootKey;
    private NamespacedKey editedKey;
    private NamespacedKey readyKey;

    private File playersFile;
    private YamlConfiguration playersData;

    private String prefix;
    private String sourceName;
    private long seed;
    private long ticks;
    private boolean enabled;
    private boolean capturing;
    private boolean citizensFailed;

    private Object npcRegistry;
    private volatile Snapshot snapshot;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        prefix = getConfig().getString(
                "world-prefix", "backrooms_v3");
        sourceName = getConfig().getString(
                "miniature.source-world", "world");
        seed = getConfig().getLong("seed", 778013L);
        enabled = getConfig().getBoolean(
                "generation-enabled", false);

        if (prefix == null
                || !prefix.matches("[a-zA-Z0-9_-]+")) {
            getLogger().severe(
                    "world-prefix должен содержать только "
                            + "латинские буквы, цифры, _ и -.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        lootKey = new NamespacedKey(this, "loot_v3");
        editedKey = new NamespacedKey(this, "edited_v3");
        readyKey = new NamespacedKey(this, "ready_v3");

        if (!getDataFolder().exists()
                && !getDataFolder().mkdirs()) {
            getLogger().severe(
                    "Не удалось создать папку плагина.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playersFile = new File(
                getDataFolder(), "players.yml");
        playersData =
                YamlConfiguration.loadConfiguration(playersFile);

        trackedPlayerNames.addAll(
                playersData.getStringList("seen-player-names"));

        for (String name :
                getConfig().getStringList("clones.names")) {
            if (name != null && !name.isBlank()) {
                trackedPlayerNames.add(name.trim());
            }
        }

        loadSnapshot();

        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("backrooms");
        if (command != null) {
            command.setExecutor(this);
        }

        PluginCommand shortCommand = getCommand("br");
        if (shortCommand != null) {
            shortCommand.setExecutor(this);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            rememberPlayer(player);
        }

        Bukkit.getScheduler().runTaskTimer(
                this, this::tick, 1L, 1L);

        getLogger().info(
                "Backrooms: используются отдельные миры "
                        + prefix + "_l<номер>.");
    }

    @Override
    public void onDisable() {
        for (Echo echo : echoes) {
            if (echo.npc != null) {
                try {
                    invoke(echo.npc, "destroy");
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }

        echoes.clear();
        echoEntities.clear();

        if (npcRegistry != null) {
            try {
                Class<?> api = Class.forName(
                        "net.citizensnpcs.api.CitizensAPI");
                api.getMethod(
                        "removeNamedNPCRegistry", String.class
                ).invoke(null, registryName());
            } catch (ReflectiveOperationException ignored) {
            }
        }

        savePlayers();
        lootQueue.clear();
        queuedChunks.clear();
    }

    private String registryName() {
        return getName() + "-" + prefix;
    }

    private void tick() {
        ticks++;

        if (!lootQueue.isEmpty()) {
            Chunk chunk = lootQueue.removeFirst();
            queuedChunks.remove(chunkKey(chunk));

            if (chunk.isLoaded()) {
                populateContainers(chunk);
            }
        }

        if (!enabled) {
            return;
        }

        if (ticks % 10 == 0) {
            tickEchoes();
        }

        int interval = Math.max(
                100,
                getConfig().getInt(
                        "clones.spawn-interval-ticks", 300));

        if (ticks % interval == 0) {
            spawnEcho();
        }

        int shiftInterval = Math.max(
                100,
                getConfig().getInt(
                        "shifting.interval-ticks", 400));

        if (getConfig().getBoolean(
                "shifting.enabled", true)
                && ticks % shiftInterval == 0) {
            shiftOne();
        }

        if (ticks % 1200 == 0) {
            cooldowns.entrySet().removeIf(
                    entry -> entry.getValue() <= ticks);
        }
    }

    private void rememberPlayer(Player player) {
        if (player.hasMetadata("NPC")
                || !getConfig().getBoolean(
                "clones.auto-track-players", true)) {
            return;
        }

        if (trackedPlayerNames.add(player.getName())) {
            savePlayers();
        }
    }

    private void savePlayers() {
        if (playersData == null) {
            return;
        }

        playersData.set(
                "seen-player-names",
                new ArrayList<>(trackedPlayerNames));

        try {
            playersData.save(playersFile);
        } catch (IOException ex) {
            getLogger().log(
                    Level.WARNING,
                    "Не удалось сохранить players.yml", ex);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rememberPlayer(event.getPlayer());
    }

    private int parseLevel(String text) {
        String value = text.toLowerCase(Locale.ROOT);

        return switch (value) {
            case "homes", "home", "жилой" -> HOMES;
            case "world", "mini", "miniature" -> MINI;
            case "exit" -> EXIT;
            case "parking", "park" -> PARKING;
            default -> {
                String[] parts = value.split("\\.", -1);

                if (parts.length > 2) {
                    throw new IllegalArgumentException();
                }

                int main = Integer.parseInt(parts[0]);

                if (main < 0 || main > 10
                        || (parts.length == 2
                        && !parts[1].equals("1"))) {
                    throw new IllegalArgumentException();
                }

                yield main * 2
                        + (parts.length == 2 ? 1 : 0);
            }
        };
    }

    private static String levelCode(int id) {
        if (id == HOMES) return "homes";
        if (id == MINI) return "world";
        if (id == EXIT) return "exit";
        if (id == PARKING) return "parking";
        return (id / 2) + (id % 2 == 1 ? ".1" : "");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {

        if (!sender.hasPermission("backrooms.admin")) {
            sender.sendMessage("§cНужны права backrooms.admin.");
            return true;
        }

        String sub = args.length == 0
                ? "help"
                : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "generate" -> {
                enabled = true;
                getConfig().set("generation-enabled", true);
                saveConfig();

                sender.sendMessage(
                        "§aBackrooms включён. Вход: /br level 0");
            }

            case "stop" -> {
                enabled = false;
                getConfig().set("generation-enabled", false);
                saveConfig();

                sender.sendMessage(
                        "§eПереходы, NPC и перестройки приостановлены."
                                + " Геометрия уже открытых миров"
                                + " продолжает генерироваться"
                                + " при загрузке новых чанков.");
            }

            case "levels" -> {
                for (int id = 0; id < LEVEL_COUNT; id++) {
                    sender.sendMessage(
                            "§7" + levelCode(id)
                                    + " — " + TITLES[id]);
                }
            }

            case "info" -> sender.sendMessage(
                    "§7Активен: " + enabled
                            + "; открытых миров: "
                            + levelWorlds.size()
                            + "; NPC: " + echoes.size()
                            + "; миниатюра: "
                            + (snapshot == null
                            ? "не подготовлена"
                            : snapshot.width + " × "
                            + snapshot.depth));

            case "snapshot" -> captureSnapshot(sender);

            case "level", "enter" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(
                            "Команда предназначена для игрока.");
                    return true;
                }

                if (!enabled) {
                    sender.sendMessage(
                            "§eСначала: /backrooms generate");
                    return true;
                }

                try {
                    int id = args.length < 2
                            ? 0 : parseLevel(args[1]);
                    enter(player, id);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(
                            "§cПример: /br level 0.1"
                                    + ", homes, world, parking, exit");
                }
            }

            case "leave" -> {
                if (sender instanceof Player player) {
                    leave(player);
                }
            }

            case "talk" -> {
                if (sender instanceof Player player) {
                    String text = args.length > 1
                            ? String.join(" ",
                            Arrays.copyOfRange(
                                    args, 1, args.length))
                            : "привет";
                    talkToNearest(player, text);
                }
            }

            default -> {
                sender.sendMessage(
                        "§e/backrooms generate | stop | info");
                sender.sendMessage(
                        "§e/br levels | level <уровень> | leave");
                sender.sendMessage(
                        "§e/br snapshot | talk <сообщение>");
            }
        }

        return true;
    }

    private World openWorld(int id) {
        World known = levelWorlds.get(id);
        if (known != null) {
            return known;
        }

        if (id == MINI && snapshot == null) {
            throw new IllegalStateException(
                    "Сначала выполни /br snapshot.");
        }

        String name = prefix + "_l" + id;

        if (name.equalsIgnoreCase(sourceName)) {
            throw new IllegalStateException(
                    "Исходный мир нельзя использовать как Backrooms.");
        }

        World loaded = Bukkit.getWorld(name);

        if (loaded != null
                && !(loaded.getGenerator()
                instanceof BackroomsGenerator)) {
            throw new IllegalStateException(
                    "Мир " + name
                            + " уже загружен другим генератором.");
        }

        World world = loaded != null ? loaded :
                new WorldCreator(name)
                        .seed(seed + id * 9973L)
                        .generator(new BackroomsGenerator(
                                id, seed, snapshot))
                        .generateStructures(false)
                        .createWorld();

        if (world == null) {
            throw new IllegalStateException(
                    "Не удалось открыть " + name);
        }

        levelWorlds.put(id, world);
        worldLevels.put(world.getUID(), id);

        world.setSpawnLocation(8, FLOOR + 1, 4);
        world.setTime(18000);
        world.setStorm(false);
        world.setThundering(false);

        world.setGameRule(
                GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(
                GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(
                GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(
                GameRule.DO_FIRE_TICK, false);
        world.setGameRule(
                GameRule.RANDOM_TICK_SPEED, 0);

        WorldBorder border = world.getWorldBorder();

        if (id == HOMES) {
            border.setCenter(96, 80);
            border.setSize(192);
        } else if (id == EXIT) {
            border.setCenter(48, 48);
            border.setSize(96);
        } else if (id == MINI) {
            int size = Math.max(
                    snapshot.width + 48,
                    snapshot.depth + 32);
            border.setCenter(
                    (snapshot.width + 16) / 2.0,
                    snapshot.depth / 2.0);
            border.setSize(size);
        } else {
            border.setCenter(0, 0);
            border.setSize(Math.max(
                    512,
                    getConfig().getInt(
                            "level-border-size", 8192)));
        }

        for (Chunk chunk : world.getLoadedChunks()) {
            enqueueLoot(chunk);
        }

        return world;
    }

    private void enter(Player player, int id) {
        if (id < 0 || id >= LEVEL_COUNT
                || !travelling.add(player.getUniqueId())) {
            return;
        }

        try {
            if (!worldLevels.containsKey(
                    player.getWorld().getUID())) {
                playersData.set(
                        "returns." + player.getUniqueId(),
                        player.getLocation());
                savePlayers();
            }

            World world = openWorld(id);
            cooldowns.put(player.getUniqueId(), ticks + 100);

            world.getChunkAtAsync(0, 0).thenAccept(chunk -> {
                if (!isEnabled()) {
                    return;
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (!player.isOnline()) {
                            return;
                        }

                        populateContainers(chunk);

                        Location destination =
                                findSafe(world, 8, 4, 6);

                        if (destination == null) {
                            player.sendMessage(
                                    "§cВход повреждён:"
                                            + " свободного места нет.");
                            return;
                        }

                        destination.setYaw(0);
                        destination.setPitch(0);

                        if (player.teleport(destination)) {
                            player.sendMessage(
                                    "§7" + levelCode(id)
                                            + " — " + TITLES[id]);
                        }
                    } finally {
                        travelling.remove(
                                player.getUniqueId());
                    }
                });
            }).exceptionally(error -> {
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        travelling.remove(
                                player.getUniqueId());
                        player.sendMessage(
                                "§cНе удалось загрузить уровень.");
                        getLogger().log(
                                Level.WARNING,
                                "Ошибка входа", error);
                    });
                }
                return null;
            });

        } catch (RuntimeException ex) {
            travelling.remove(player.getUniqueId());
            player.sendMessage("§c" + ex.getMessage());
            getLogger().log(
                    Level.WARNING, "Ошибка открытия уровня", ex);
        }
    }

    private void leave(Player player) {
        Location target = playersData.getLocation(
                "returns." + player.getUniqueId());

        if (target == null
                || target.getWorld() == null
                || worldLevels.containsKey(
                target.getWorld().getUID())) {

            World source = Bukkit.getWorld(sourceName);

            if (source == null) {
                player.sendMessage(
                        "§cИсходный мир не загружен.");
                return;
            }

            target = source.getSpawnLocation();
        }

        cooldowns.put(player.getUniqueId(), ticks + 100);
        player.teleport(target);
    }

    private Location findSafe(
            World world, int x, int z, int radius) {

        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(
                            Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }

                    int bx = x + dx;
                    int bz = z + dz;

                    if (!world.isChunkLoaded(
                            bx >> 4, bz >> 4)) {
                        continue;
                    }

                    for (int y = FLOOR + 1;
                         y <= FLOOR + 82; y++) {

                        Block ground =
                                world.getBlockAt(bx, y - 1, bz);
                        Block feet =
                                world.getBlockAt(bx, y, bz);
                        Block head =
                                world.getBlockAt(bx, y + 1, bz);

                        if (safeGround(ground.getType())
                                && feet.getType().isAir()
                                && head.getType().isAir()) {
                            return new Location(
                                    world,
                                    bx + 0.5, y, bz + 0.5);
                        }
                    }
                }
            }
        }

        return null;
    }

    private static boolean safeGround(Material material) {
        return material.isSolid()
                && material != Material.MAGMA_BLOCK
                && material != Material.CACTUS
                && material != Material.CAMPFIRE
                && material != Material.SOUL_CAMPFIRE;
    }

    private String chunkKey(Chunk chunk) {
        return chunk.getWorld().getUID()
                + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private void enqueueLoot(Chunk chunk) {
        if (!worldLevels.containsKey(
                chunk.getWorld().getUID())) {
            return;
        }

        if (chunk.getPersistentDataContainer().has(
                readyKey, PersistentDataType.BYTE)) {
            return;
        }

        if (queuedChunks.add(chunkKey(chunk))) {
            lootQueue.addLast(chunk);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        enqueueLoot(event.getChunk());
    }

    private void populateContainers(Chunk chunk) {
        Integer id = worldLevels.get(
                chunk.getWorld().getUID());

        if (id == null) {
            return;
        }

        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Container container) {
                fillLoot(container, id);
            }
        }

        chunk.getPersistentDataContainer().set(
                readyKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void fillLoot(Container container, int level) {
        if (container.getPersistentDataContainer().has(
                lootKey, PersistentDataType.BYTE)) {
            return;
        }

        Inventory inventory = container.getInventory();

        if (inventory.isEmpty()) {
            Random lootRandom = new Random(hash(
                    container.getX(),
                    container.getZ(),
                    seed + level * 731L));

            Material[] pool = switch (level) {
                case 0, 1, 6, HOMES -> new Material[]{
                        Material.BREAD, Material.APPLE,
                        Material.BAKED_POTATO, Material.PAPER,
                        Material.STRING, Material.OAK_PLANKS
                };

                case 8, 9, 18 -> new Material[]{
                        Material.BOOK, Material.PAPER,
                        Material.INK_SAC, Material.FEATHER,
                        Material.CLOCK, Material.BREAD
                };

                case 2, 3, 7, 10, 15, PARKING ->
                        new Material[]{
                                Material.IRON_INGOT,
                                Material.COPPER_INGOT,
                                Material.COAL, Material.REDSTONE,
                                Material.RAIL, Material.BREAD
                        };

                case 4, 12, 13, 19 -> new Material[]{
                        Material.CARROT, Material.BREAD,
                        Material.CLAY_BALL, Material.STRING,
                        Material.GLASS_BOTTLE,
                        Material.COPPER_INGOT
                };

                default -> new Material[]{
                        Material.BONE, Material.COAL,
                        Material.GOLD_NUGGET,
                        Material.AMETHYST_SHARD,
                        Material.ENDER_PEARL, Material.BREAD
                };
            };

            List<Integer> slots = new ArrayList<>();

            for (int slot = 0;
                 slot < inventory.getSize(); slot++) {
                slots.add(slot);
            }

            Collections.shuffle(slots, lootRandom);

            int entries = Math.min(
                    slots.size() - 1,
                    4 + lootRandom.nextInt(5));

            for (int i = 0; i < entries; i++) {
                Material material =
                        pool[lootRandom.nextInt(pool.length)];

                inventory.setItem(
                        slots.get(i),
                        new ItemStack(
                                material,
                                1 + lootRandom.nextInt(4)));
            }

            inventory.setItem(
                    slots.get(slots.size() - 1),
                    makeStoryBook(
                            lootRandom.nextBoolean(),
                            1 + lootRandom.nextInt(8),
                            level));
        }

        container.getPersistentDataContainer().set(
                lootKey, PersistentDataType.BYTE, (byte) 1);
        container.update();
    }

    private ItemStack makeStoryBook(
            boolean driver, int volume, int level) {

        ItemStack item =
                new ItemStack(Material.WRITTEN_BOOK);

        BookMeta meta = (BookMeta) item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setTitle(
                (driver ? "Драйверы" : "Антивирус")
                        + " — том " + volume);

        meta.setAuthor(
                driver ? "BACKROOMS SYSTEM" : "UNKNOWN PROCESS");

        String[] driverLines = {
                "Загрузка помещения завершена."
                        + "\nКоличество дверей не совпало.",
                "Если свет погас, не повторяйте маршрут."
                        + "\nОн уже занят другой копией.",
                "Имена посетителей загружаются раньше лиц.",
                "У соседней комнаты нет обратного адреса.",
                "Карта поверхности помещается на столе."
                        + "\nСам мир от этого не становится ближе.",
                "Жилые дома используются как маршрутизаторы.",
                "Стену разрешено удалить."
                        + "\nТо, что за ней, удалить нельзя.",
                "Сохранение выполнено."
                        + "\nКто-то сохранился вместе с вами."
        };

        String[] antivirusLines = {
                "Обнаружено совпадение двух посетителей.",
                "Карантин не распространяется на двери.",
                "Не отвечайте голосу из технического шкафа.",
                "Подпись процесса верна."
                        + "\nСам процесс не существует.",
                "Копия научилась ждать ответа.",
                "Красный коридор снова изменил адрес.",
                "Проверка стен пропустила один проход.",
                "Удаление невозможно: объект наблюдает."
        };

        String fragment = driver
                ? driverLines[volume - 1]
                : antivirusLines[volume - 1];

        meta.setPages(
                (driver ? "[ДРАЙВЕРЫ]" : "[АНТИВИРУС]")
                        + "\nТом: " + volume
                        + "\nСектор: " + levelCode(level)
                        + "\n\n" + fragment,

                "// архивный псевдокод\n"
                        + "node = room[" + volume + "]\n"
                        + "if door.isOpen:\n"
                        + "  destination = UNKNOWN\n"
                        + "assert visitor.count <= 1\n\n"
                        + "ERROR: получено 2 совпадения."
        );

        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------
    // Переходы: только конкретная дверь и пересечение её порога.
    // ------------------------------------------------------------

    private BackroomsGenerator generator(World world) {
        return world.getGenerator()
                instanceof BackroomsGenerator generator
                ? generator : null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (!enabled || to == null
                || player.hasMetadata("NPC")
                || !worldLevels.containsKey(
                to.getWorld().getUID())
                || !Objects.equals(
                event.getFrom().getWorld(),
                to.getWorld())
                || travelling.contains(
                player.getUniqueId())
                || cooldowns.getOrDefault(
                player.getUniqueId(), 0L) > ticks) {
            return;
        }

        BackroomsGenerator generator =
                generator(to.getWorld());

        if (generator == null) {
            return;
        }

        for (Gateway gate : generator.nearbyGates(
                to.getBlockX(), to.getBlockZ())) {

            if (event.getFrom().getZ() >= gate.z + 1.35
                    || to.getZ() < gate.z + 1.35) {
                continue;
            }

            double dz = to.getZ()
                    - event.getFrom().getZ();

            if (dz <= 0) {
                continue;
            }

            double fraction = (gate.z + 1.35
                    - event.getFrom().getZ()) / dz;

            double crossingX = event.getFrom().getX()
                    + (to.getX()
                    - event.getFrom().getX()) * fraction;

            if (crossingX < gate.x
                    || crossingX >= gate.x + 1
                    || to.getY() < gate.y
                    || to.getY() >= gate.y + 2) {
                continue;
            }

            Block block = to.getWorld().getBlockAt(
                    gate.x, gate.y, gate.z);

            if (!(block.getBlockData() instanceof Door door)
                    || !door.isOpen()
                    || door.getHalf()
                    != Bisected.Half.BOTTOM) {
                continue;
            }

            cooldowns.put(
                    player.getUniqueId(), ticks + 100);

            if (gate.target < 0) {
                Bukkit.getScheduler().runTask(
                        this, () -> leave(player));
            } else {
                Bukkit.getScheduler().runTask(
                        this, () -> enter(player, gate.target));
            }

            return;
        }
    }

    private boolean protectedGate(Block block) {
        BackroomsGenerator generator =
                generator(block.getWorld());

        if (generator == null) {
            return false;
        }

        for (Gateway gate : generator.nearbyGates(
                block.getX(), block.getZ())) {
            if (block.getX() >= gate.x - 2
                    && block.getX() <= gate.x + 2
                    && block.getZ() >= gate.z
                    && block.getZ() <= gate.z + 5
                    && block.getY() >= gate.y - 1
                    && block.getY() <= gate.y + 4) {
                return true;
            }
        }

        return false;
    }

    private void markEdited(Block block) {
        block.getChunk().getPersistentDataContainer().set(
                editedKey,
                PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (!worldLevels.containsKey(
                block.getWorld().getUID())) {
            return;
        }

        if (block.getY() <= FLOOR
                || protectedGate(block)) {
            event.setCancelled(true);
            return;
        }

        markEdited(block);

        // За стенами уже существует связный план.
        // Новые комнаты не вырезаются поверх старых.
        int cx = block.getX() >> 4;
        int cz = block.getZ() >> 4;

        for (int[] offset : new int[][]{
                {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            block.getWorld().getChunkAtAsync(
                    cx + offset[0], cz + offset[1]);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        if (!worldLevels.containsKey(
                block.getWorld().getUID())) {
            return;
        }

        if (protectedGate(block)) {
            event.setCancelled(true);
        } else {
            markEdited(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block affected = event.getBlock()
                .getRelative(event.getBlockFace());

        if (worldLevels.containsKey(
                affected.getWorld().getUID())) {
            if (protectedGate(affected)) {
                event.setCancelled(true);
            } else {
                markEdited(affected);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (worldLevels.containsKey(
                event.getBlock().getWorld().getUID())) {
            markEdited(event.getBlock());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (worldLevels.containsKey(
                event.getBlock().getWorld().getUID())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(
            BlockPistonRetractEvent event) {
        if (worldLevels.containsKey(
                event.getBlock().getWorld().getUID())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (worldLevels.containsKey(
                event.getLocation().getWorld().getUID())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(
            BlockExplodeEvent event) {
        if (worldLevels.containsKey(
                event.getBlock().getWorld().getUID())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (player.hasMetadata("NPC")
                || !worldLevels.containsKey(
                player.getWorld().getUID())) {
            return;
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!worldLevels.containsKey(
                event.getPlayer().getWorld().getUID())) {
            return;
        }

        Location target = playersData.getLocation(
                "returns." + event.getPlayer().getUniqueId());

        if (target != null && target.getWorld() != null) {
            event.setRespawnLocation(target);
        }
    }

    private void shiftOne() {
        List<World> worlds =
                new ArrayList<>(levelWorlds.values());

        Collections.shuffle(worlds, random);

        for (World world : worlds) {
            int id = worldLevels.get(world.getUID());

            if (id >= HOMES) {
                continue;
            }

            Chunk[] loaded = world.getLoadedChunks();

            if (loaded.length == 0) {
                continue;
            }

            Chunk chunk = loaded[
                    random.nextInt(loaded.length)];

            if (chunk.getPersistentDataContainer().has(
                    editedKey, PersistentDataType.BYTE)) {
                continue;
            }

            int x = chunk.getX() * 16
                    + 4 + random.nextInt(8);
            int z = chunk.getZ() * 16
                    + 4 + random.nextInt(8);

            Location point = new Location(
                    world, x, FLOOR + 1, z);

            boolean occupied = world.getPlayers()
                    .stream()
                    .anyMatch(player ->
                            !player.hasMetadata("NPC")
                                    && player.getLocation()
                                    .distanceSquared(point)
                                    < 48 * 48);

            if (occupied) {
                continue;
            }

            BackroomsGenerator generator = generator(world);

            if (generator == null
                    || generator.onMainRoute(x, z)) {
                continue;
            }

            Material wall = WALLS[id];
            boolean allAir = true;
            boolean allWall = true;
            boolean allowed = true;

            for (int dx = 0; dx < 3; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    Block block = world.getBlockAt(
                            x + dx, FLOOR + dy, z);

                    Material type = block.getType();

                    if (protectedGate(block)) {
                        allowed = false;
                    }

                    allAir &= type.isAir();
                    allWall &= type == wall;
                }
            }

            if (!allowed || (!allAir && !allWall)) {
                continue;
            }

            // Меняется короткая перегородка.
            // Основные сквозные маршруты не перекрываются.
            Material replacement =
                    allAir ? wall : Material.AIR;

            for (int dx = 0; dx < 3; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(
                            x + dx, FLOOR + dy, z
                    ).setType(replacement, false);
                }
            }

            return;
        }
    }

    // ------------------------------------------------------------
    // Citizens: отдельный реестр, только собственные NPC.
    // ------------------------------------------------------------

    private static Object invoke(
            Object object,
            String name,
            Class<?>[] types,
            Object... arguments)
            throws ReflectiveOperationException {

        Method method = object.getClass()
                .getMethod(name, types);
        return method.invoke(object, arguments);
    }

    private static Object invoke(
            Object object, String name)
            throws ReflectiveOperationException {

        return invoke(
                object, name, new Class<?>[0]);
    }

    private boolean prepareCitizens() {
        if (citizensFailed
                || !getConfig().getBoolean(
                "clones.enabled", true)
                || !Bukkit.getPluginManager()
                .isPluginEnabled("Citizens")) {
            return false;
        }

        if (npcRegistry != null) {
            return true;
        }

        try {
            Class<?> api = Class.forName(
                    "net.citizensnpcs.api.CitizensAPI");

            npcRegistry = api.getMethod(
                    "createInMemoryNPCRegistry",
                    String.class
            ).invoke(null, registryName());

            return true;

        } catch (ReflectiveOperationException ex) {
            citizensFailed = true;
            getLogger().log(
                    Level.WARNING,
                    "Не удалось подключить Citizens."
                            + " Генератор работает без NPC.", ex);
            return false;
        }
    }

    private void spawnEcho() {
        if (!prepareCitizens()
                || trackedPlayerNames.isEmpty()) {
            return;
        }

        int maximum = Math.max(
                1,
                getConfig().getInt("clones.max-npcs", 12));

        if (echoes.size() >= maximum) {
            return;
        }

        List<Player> players = Bukkit.getOnlinePlayers()
                .stream()
                .filter(player ->
                        !player.hasMetadata("NPC")
                                && worldLevels.containsKey(
                                player.getWorld().getUID()))
                .toList();

        if (players.isEmpty()) {
            return;
        }

        Player player = players.get(
                random.nextInt(players.size()));

        Location spawn = nearbySafe(
                player.getLocation(), 9, 20);

        if (spawn == null) {
            return;
        }

        List<String> names =
                new ArrayList<>(trackedPlayerNames);
        String name = names.get(random.nextInt(names.size()));

        try {
            Object npc = invoke(
                    npcRegistry, "createNPC",
                    new Class<?>[]{
                            EntityType.class, String.class},
                    EntityType.PLAYER, name);

            invoke(
                    npc, "setProtected",
                    new Class<?>[]{boolean.class}, false);

            try {
                Class<?> skin = Class.forName(
                        "net.citizensnpcs.trait.SkinTrait");

                Object trait = invoke(
                        npc, "getOrAddTrait",
                        new Class<?>[]{Class.class}, skin);

                invoke(
                        trait, "setSkinName",
                        new Class<?>[]{String.class}, name);

            } catch (ReflectiveOperationException ex) {
                getLogger().fine(
                        "Скин NPC не назначен: "
                                + ex.getMessage());
            }

            Echo echo = new Echo(npc, name);
            echoes.add(echo);

            invoke(
                    npc, "spawn",
                    new Class<?>[]{Location.class}, spawn);

            echo.until = ticks + 40;

        } catch (ReflectiveOperationException ex) {
            getLogger().log(
                    Level.WARNING,
                    "Не удалось создать двойника", ex);
        }
    }

    private Location nearbySafe(
            Location origin, int minimum, int maximum) {

        World world = origin.getWorld();

        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble()
                    * Math.PI * 2;

            int distance = minimum
                    + random.nextInt(
                    Math.max(1, maximum - minimum + 1));

            int x = origin.getBlockX()
                    + (int) Math.round(
                    Math.cos(angle) * distance);
            int z = origin.getBlockZ()
                    + (int) Math.round(
                    Math.sin(angle) * distance);

            Location location = findSafe(world, x, z, 2);

            if (location != null) {
                return location;
            }
        }

        return null;
    }

    private void navigate(Echo echo, Location target)
            throws ReflectiveOperationException {

        Object navigator = invoke(
                echo.npc, "getNavigator");

        invoke(
                navigator, "setTarget",
                new Class<?>[]{Location.class}, target);
    }

    private void face(Echo echo, Player player)
            throws ReflectiveOperationException {

        invoke(
                echo.npc, "faceLocation",
                new Class<?>[]{Location.class},
                player.getEyeLocation());
    }

    private void cancelNavigation(Echo echo)
            throws ReflectiveOperationException {

        invoke(
                invoke(echo.npc, "getNavigator"),
                "cancelNavigation");
    }

    private void tickEchoes() {
        for (Iterator<Echo> iterator =
             echoes.iterator(); iterator.hasNext();) {

            Echo echo = iterator.next();

            try {
                boolean spawned = Boolean.TRUE.equals(
                        invoke(echo.npc, "isSpawned"));

                if (!spawned) {
                    if (echo.entityId != null) {
                        echoEntities.remove(echo.entityId);
                        echo.entityId = null;
                    }

                    if (echo.respawnAt == 0) {
                        echo.respawnAt = ticks + 900
                                + random.nextInt(1800);
                    }

                    if (ticks < echo.respawnAt) {
                        continue;
                    }

                    Player player = Bukkit.getOnlinePlayers()
                            .stream()
                            .filter(p ->
                                    !p.hasMetadata("NPC")
                                            && worldLevels.containsKey(
                                            p.getWorld().getUID()))
                            .findAny().orElse(null);

                    if (player == null) {
                        continue;
                    }

                    Location spawn = nearbySafe(
                            player.getLocation(), 10, 22);

                    if (spawn != null) {
                        invoke(
                                echo.npc, "spawn",
                                new Class<?>[]{Location.class},
                                spawn);

                        echo.respawnAt = 0;
                        echo.until = ticks + 40;
                        echo.mode = "wander";
                    }

                    continue;
                }

                Object object = invoke(
                        echo.npc, "getEntity");

                if (!(object instanceof Entity entity)) {
                    continue;
                }

                echo.entityId = entity.getUniqueId();
                echoEntities.put(echo.entityId, echo);

                Location location = entity.getLocation();

                Player nearest = location.getWorld()
                        .getPlayers().stream()
                        .filter(p -> !p.hasMetadata("NPC"))
                        .min(Comparator.comparingDouble(
                                p -> p.getLocation()
                                        .distanceSquared(location)))
                        .orElse(null);

                if (nearest == null) {
                    continue;
                }

                double distance = nearest.getLocation()
                        .distanceSquared(location);

                if (distance < 24 * 24) {
                    face(echo, nearest);
                }

                if ("hide".equals(echo.mode)) {
                    if (echo.target != null
                            && (location.distanceSquared(
                            echo.target) < 3
                            || ticks >= echo.until)) {

                        invoke(echo.npc, "despawn");
                        echo.respawnAt = ticks + 240
                                + random.nextInt(600);
                        echo.mode = "hidden";
                    }
                    continue;
                }

                if (ticks < echo.until) {
                    continue;
                }

                if (distance < 4 * 4
                        && ticks >= echo.speakAt
                        && random.nextInt(3) == 0) {

                    say(echo, nearest, randomLine());
                    echo.speakAt = ticks + 300;
                }

                int action = random.nextInt(6);

                switch (action) {
                    case 0 -> {
                        cancelNavigation(echo);
                        echo.mode = "stare";
                    }

                    case 1 -> {
                        if (distance > 3 * 3
                                && distance < 24 * 24) {
                            navigate(
                                    echo,
                                    nearest.getLocation());
                        } else {
                            cancelNavigation(echo);
                        }
                        echo.mode = "follow";
                    }

                    case 2 -> {
                        Location target = nearbySafe(
                                location, 5, 13);

                        if (target != null) {
                            navigate(echo, target);
                        }

                        echo.mode = "wander";
                    }

                    case 3 -> {
                        Location wall = findWallEdge(location);

                        if (wall != null) {
                            navigate(echo, wall);
                            echo.target = wall;
                            echo.mode = "hide";
                        }
                    }

                    case 4 -> {
                        Location away = location.clone()
                                .add(location.toVector()
                                        .subtract(nearest.getLocation()
                                                .toVector())
                                        .normalize()
                                        .multiply(7));

                        Location safe = findSafe(
                                location.getWorld(),
                                away.getBlockX(),
                                away.getBlockZ(), 2);

                        if (safe != null) {
                            navigate(echo, safe);
                        }

                        echo.mode = "retreat";
                    }

                    default -> {
                        cancelNavigation(echo);
                        echo.mode = "wait";
                    }
                }

                echo.until = ticks + 60
                        + random.nextInt(100);

            } catch (ReflectiveOperationException
                     | RuntimeException ex) {

                getLogger().fine(
                        "Двойник удалён после ошибки: "
                                + ex.getMessage());

                if (echo.entityId != null) {
                    echoEntities.remove(echo.entityId);
                }

                try {
                    invoke(echo.npc, "destroy");
                } catch (ReflectiveOperationException ignored) {
                }

                iterator.remove();
            }
        }
    }

    private Location findWallEdge(Location location) {
        World world = location.getWorld();

        for (int radius = 2; radius <= 10; radius++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                int x = location.getBlockX()
                        + random.nextInt(radius * 2 + 1)
                        - radius;
                int z = location.getBlockZ()
                        + random.nextInt(radius * 2 + 1)
                        - radius;

                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                for (BlockFace face : new BlockFace[]{
                        BlockFace.NORTH, BlockFace.SOUTH,
                        BlockFace.EAST, BlockFace.WEST}) {

                    Block feet = world.getBlockAt(
                            x, location.getBlockY(), z);

                    if (feet.getType().isAir()
                            && feet.getRelative(
                            BlockFace.UP).getType().isAir()
                            && feet.getRelative(
                            face).getType().isSolid()
                            && safeGround(feet.getRelative(
                            BlockFace.DOWN).getType())) {
                        return feet.getLocation()
                                .add(0.5, 0, 0.5);
                    }
                }
            }
        }

        return null;
    }

    private String randomLine() {
        String[] lines = {
                "Ты тоже слышишь, как комнаты переставляют?",
                "Через жилые дома иногда можно пройти дальше.",
                "Не все двери возвращают обратно.",
                "Кажется, я уже видел тебя здесь.",
                "В библиотеке кто-то дописывает книги.",
                "Я ждал другого посетителя.",
                "Если найдёшь маленький мир, не наступай на дом.",
                "Пойдём. Хотя я уже не помню куда."
        };

        return lines[random.nextInt(lines.length)];
    }

    private void say(
            Echo echo, Player player, String message) {
        player.sendMessage(
                "§8[§7" + echo.name + "§8] §7" + message);
    }

    private void reply(
            Echo echo, Player player, String message) {

        String text = message.toLowerCase(Locale.ROOT);

        String answer;

        if (text.contains("выход")
                || text.contains("выйти")) {
            answer = "Ищи дверь в EXIT."
                    + " Я не уверен, что она появится рядом.";
        } else if (text.contains("привет")) {
            answer = "Привет. Мы знакомы?"
                    + " Твоё имя здесь уже встречалось.";
        } else if (text.contains("кто")) {
            answer = "Имя у меня есть."
                    + " Остальное пока не загрузилось.";
        } else if (text.contains("дом")
                || text.contains("жил")) {
            answer = "В жилом районе проверяй внутренние двери."
                    + " Дом снаружи не объясняет,"
                    + " куда ведут его комнаты.";
        } else if (text.contains("книг")) {
            answer = "Драйверы и Антивирус противоречат друг другу."
                    + " Возможно, оба говорят правду.";
        } else {
            answer = randomLine();
        }

        say(echo, player, answer);
        echo.speakAt = ticks + 100;
        echo.until = ticks + 100;

        try {
            cancelNavigation(echo);
            face(echo, player);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void talkToNearest(
            Player player, String message) {

        Echo nearest = null;
        double best = 8 * 8;

        for (Echo echo : echoes) {
            if (echo.entityId == null) {
                continue;
            }

            Entity entity = Bukkit.getEntity(echo.entityId);

            if (entity == null
                    || !entity.getWorld().equals(
                    player.getWorld())) {
                continue;
            }

            double distance = entity.getLocation()
                    .distanceSquared(player.getLocation());

            if (distance < best) {
                best = distance;
                nearest = echo;
            }
        }

        if (nearest == null) {
            player.sendMessage(
                    "§7Поблизости никто не отвечает.");
        } else {
            reply(nearest, player, message);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Echo echo = echoEntities.get(
                event.getRightClicked().getUniqueId());

        if (echo != null) {
            event.setCancelled(true);

            if (ticks >= echo.speakAt) {
                reply(echo, event.getPlayer(), "привет");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Echo echo = echoEntities.get(
                event.getEntity().getUniqueId());

        if (echo == null
                || !(event.getDamager()
                instanceof Player player)) {
            return;
        }

        echo.until = ticks;
        echo.mode = "retreat";

        if (ticks >= echo.speakAt) {
            say(echo, player,
                    "Подожди. Я не тот, кого ты ищешь.");
            echo.speakAt = ticks + 100;
        }

        Location safe = nearbySafe(
                event.getEntity().getLocation(), 6, 12);

        if (safe != null) {
            try {
                navigate(echo, safe);
                echo.until = ticks + 100;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSuffocation(EntityDamageEvent event) {
        if (echoEntities.containsKey(
                event.getEntity().getUniqueId())
                && event.getCause()
                == EntityDamageEvent.DamageCause.SUFFOCATION) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEchoDeath(EntityDeathEvent event) {
        Echo echo = echoEntities.remove(
                event.getEntity().getUniqueId());

        if (echo != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            echo.entityId = null;
            echo.respawnAt = ticks + 900
                    + random.nextInt(1800);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        travelling.remove(event.getPlayer().getUniqueId());
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private static final class Echo {
        final Object npc;
        final String name;

        UUID entityId;
        Location target;

        String mode = "wander";

        long until;
        long speakAt;
        long respawnAt;

        Echo(Object npc, String name) {
            this.npc = npc;
            this.name = name;
        }
    }

    // ------------------------------------------------------------
    // Единая миниатюра области исходного мира.
    // ------------------------------------------------------------

    private static final class Snapshot {
        final int width;
        final int depth;
        final int scale;
        final short[] heights;
        final byte[] colors;

        Snapshot(int scale) {
            this.scale = scale;
            width = (SOURCE_WIDTH + scale - 1) / scale;
            depth = (SOURCE_DEPTH + scale - 1) / scale;
            heights = new short[width * depth];
            colors = new byte[width * depth];
        }

        int index(int x, int z) {
            return z * width + x;
        }
    }

    private File snapshotFile() {
        return new File(getDataFolder(), "miniature-v3.dat");
    }

    private void loadSnapshot() {
        File file = snapshotFile();

        if (!file.isFile()) {
            return;
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(
                        new FileInputStream(file)))) {

            if (in.readInt() != 0x42523301) {
                throw new IOException(
                        "Неизвестный формат миниатюры.");
            }

            int scale = in.readInt();

            if (scale < 1 || scale > 16) {
                throw new IOException(
                        "Некорректный масштаб.");
            }

            Snapshot loaded = new Snapshot(scale);

            for (int i = 0;
                 i < loaded.heights.length; i++) {
                loaded.heights[i] = in.readShort();
                loaded.colors[i] = in.readByte();

                if (loaded.heights[i] < 0
                        || loaded.heights[i] > 80
                        || loaded.colors[i] < 0
                        || loaded.colors[i] >= COLORS.length) {
                    throw new IOException(
                            "Некорректные данные миниатюры.");
                }
            }

            snapshot = loaded;

        } catch (IOException ex) {
            getLogger().log(
                    Level.WARNING,
                    "Миниатюра не прочитана", ex);
        }
    }

    private void captureSnapshot(CommandSender sender) {
        if (capturing) {
            sender.sendMessage(
                    "§eКопирование области уже выполняется.");
            return;
        }

        File miniatureWorld = new File(
                Bukkit.getWorldContainer(),
                prefix + "_l" + MINI);

        if (miniatureWorld.exists()
                || levelWorlds.containsKey(MINI)) {
            sender.sendMessage(
                    "§eМир миниатюры уже существует."
                            + " Чтобы не смешивать старые"
                            + " и новые чанки, снимок не заменён."
                            + " Для новой генерации используй"
                            + " новый world-prefix после резервной копии.");
            return;
        }

        World source = Bukkit.getWorld(sourceName);

        if (source == null
                || worldLevels.containsKey(source.getUID())) {
            sender.sendMessage(
                    "§cИсходный обычный мир не загружен.");
            return;
        }

        int scale = Math.max(
                1, Math.min(
                16,
                getConfig().getInt(
                        "miniature.scale", 4)));

        int centerX = getConfig().getInt(
                "miniature.center-x", 0);
        int centerZ = getConfig().getInt(
                "miniature.center-z", 0);
        int baseY = getConfig().getInt(
                "miniature.base-y", 64);

        int startX = centerX - SOURCE_WIDTH / 2;
        int startZ = centerZ - SOURCE_DEPTH / 2;

        Snapshot building = new Snapshot(scale);

        int minChunkX = Math.floorDiv(startX, 16);
        int minChunkZ = Math.floorDiv(startZ, 16);
        int maxChunkX = Math.floorDiv(
                startX + SOURCE_WIDTH - 1, 16);
        int maxChunkZ = Math.floorDiv(
                startZ + SOURCE_DEPTH - 1, 16);

        capturing = true;

        sender.sendMessage(
                "§7Начато копирование области "
                        + SOURCE_WIDTH + " × " + SOURCE_DEPTH
                        + ". Масштаб 1:" + scale
                        + ". Исходный мир не изменяется.");

        captureNext(
                sender, source, building,
                startX, startZ, baseY,
                minChunkX, minChunkZ,
                maxChunkX, maxChunkZ,
                minChunkX, minChunkZ);
    }

    private void captureNext(
            CommandSender sender,
            World source,
            Snapshot building,
            int startX, int startZ, int baseY,
            int minCx, int minCz,
            int maxCx, int maxCz,
            int cx, int cz) {

        if (!isEnabled()) {
            return;
        }

        if (cz > maxCz) {
            finishSnapshot(sender, building);
            return;
        }

        source.getChunkAtAsync(cx, cz).thenAccept(chunk -> {
            if (!isEnabled()) {
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    int firstX = Math.max(
                            0,
                            Math.floorDiv(
                                    cx * 16 - startX
                                            + building.scale - 1,
                                    building.scale));

                    int firstZ = Math.max(
                            0,
                            Math.floorDiv(
                                    cz * 16 - startZ
                                            + building.scale - 1,
                                    building.scale));

                    for (int mz = firstZ;
                         mz < building.depth; mz++) {

                        int sourceZ =
                                startZ + mz * building.scale;

                        if (sourceZ >= cz * 16 + 16) {
                            break;
                        }

                        for (int mx = firstX;
                             mx < building.width; mx++) {

                            int sourceX =
                                    startX + mx * building.scale;

                            if (sourceX >= cx * 16 + 16) {
                                break;
                            }

                            int height =
                                    source.getHighestBlockYAt(
                                            sourceX, sourceZ);

                            Material material = source.getBlockAt(
                                    sourceX, height, sourceZ
                            ).getType();

                            int index = building.index(mx, mz);

                            building.heights[index] =
                                    (short) Math.max(
                                            0,
                                            Math.min(
                                                    80,
                                                    Math.round(
                                                            (height - baseY)
                                                                    / (float)
                                                                    building.scale)));

                            building.colors[index] =
                                    colorFor(material);
                        }
                    }

                    int nextX = cx + 1;
                    int nextZ = cz;

                    if (nextX > maxCx) {
                        nextX = minCx;
                        nextZ++;
                    }

                    final int nx = nextX;
                    final int nz = nextZ;

                    Bukkit.getScheduler().runTaskLater(
                            this,
                            () -> captureNext(
                                    sender, source, building,
                                    startX, startZ, baseY,
                                    minCx, minCz, maxCx, maxCz,
                                    nx, nz),
                            1L);

                } catch (RuntimeException ex) {
                    capturing = false;
                    sender.sendMessage(
                            "§cКопирование прервано."
                                    + " Подробности в консоли.");
                    getLogger().log(
                            Level.WARNING,
                            "Ошибка миниатюры", ex);
                }
            });

        }).exceptionally(error -> {
            if (isEnabled()) {
                Bukkit.getScheduler().runTask(this, () -> {
                    capturing = false;
                    sender.sendMessage(
                            "§cНе удалось загрузить"
                                    + " участок исходного мира.");
                    getLogger().log(
                            Level.WARNING,
                            "Ошибка чтения исходного мира",
                            error);
                });
            }

            return null;
        });
    }

    private void finishSnapshot(
            CommandSender sender, Snapshot building) {

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File temporary = new File(
                    getDataFolder(), "miniature-v3.tmp");

            try {
                try (DataOutputStream out =
                     new DataOutputStream(
                             new BufferedOutputStream(
                                     new FileOutputStream(
                                            

