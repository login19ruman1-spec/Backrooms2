package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

public final class BackroomsPlugin extends JavaPlugin
        implements Listener, CommandExecutor {

    final Map<UUID, Integer> worldLevels = new HashMap<>();
    final Map<Integer, World> worlds = new HashMap<>();
    final Set<String> names =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> travelling = new HashSet<>();

    private NamespacedKey lootKey;
    private NamespacedKey editedKey;
    private YamlConfiguration players;
    private File playersFile;

    private String prefix;
    private long seed;
    private boolean active;
    private boolean capturing;
    private int clock;
    private EchoManager echoes;

    BackroomsGenerator.Mini mini;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        prefix = getConfig().getString(
                "world-prefix", "backrooms_v4");

        if (prefix == null ||
                !prefix.matches("[a-zA-Z0-9_-]+")) {
            getLogger().severe("Некорректный world-prefix.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        seed = getConfig().getLong("seed", 778013L);
        active = getConfig().getBoolean(
                "generation-enabled", false);

        lootKey = new NamespacedKey(this, "loot_v4");
        editedKey = new NamespacedKey(this, "edited_v4");

        if (!getDataFolder().exists() &&
                !getDataFolder().mkdirs()) {
            getLogger().severe("Не удалось создать папку плагина.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playersFile = new File(getDataFolder(), "players.yml");
        players = YamlConfiguration.loadConfiguration(playersFile);
        names.addAll(players.getStringList("seen-player-names"));
        names.addAll(getConfig().getStringList("clones.names"));

        loadMini();

        echoes = new EchoManager(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(echoes, this);

        Objects.requireNonNull(
                getCommand("backrooms")).setExecutor(this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            remember(p);
        }

        Bukkit.getScheduler().runTaskTimer(
                this, this::tick, 1L, 1L);

        getLogger().info(
                "Backrooms 0.4.0. Новые миры: " + prefix);
    }

    @Override
    public void onDisable() {
        if (echoes != null) echoes.close();
        savePlayers();
    }

    private void tick() {
        clock++;
        if (!active) return;

        if (clock % 2 == 0) echoes.tick();

        int interval = Math.max(40,
                getConfig().getInt(
                        "clones.spawn-interval-ticks", 300));

        if (clock % interval == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isVisitor(p)) {
                    echoes.spawn(p);
                    break;
                }
            }
        }

        int shift = Math.max(40,
                getConfig().getInt(
                        "shifting.interval-ticks", 240));

        if (getConfig().getBoolean("shifting.enabled", true)
                && clock % shift == 0) {
            shiftOne();
        }
    }

    boolean isVisitor(Player p) {
        return !p.hasMetadata("NPC")
                && worldLevels.containsKey(
                        p.getWorld().getUID());
    }

    private void remember(Player p) {
        if (!p.hasMetadata("NPC")
                && getConfig().getBoolean(
                        "clones.auto-track-players", true)) {
            if (names.add(p.getName())) savePlayers();
        }
    }

    private void savePlayers() {
        if (players == null || playersFile == null) return;

        players.set("seen-player-names", new ArrayList<>(names));
        try {
            players.save(playersFile);
        } catch (IOException ex) {
            getLogger().log(
                    Level.WARNING, "Не сохранён players.yml", ex);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        remember(e.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        String sub = args.length == 0
                ? "help" : args[0].toLowerCase(Locale.ROOT);

        // Разговор и выход доступны обычным игрокам.
        if (sub.equals("leave")) {
            if (sender instanceof Player p) leave(p);
            return true;
        }

        if (sub.equals("talk")) {
            if (sender instanceof Player p) {
                echoes.talk(p, args.length > 1
                        ? String.join(" ",
                        Arrays.copyOfRange(args, 1, args.length))
                        : "привет");
            }
            return true;
        }

        if (!sender.hasPermission("backrooms.admin")) {
            sender.sendMessage("§cНужны права backrooms.admin.");
            return true;
        }

        switch (sub) {
            case "generate" -> {
                active = true;
                getConfig().set("generation-enabled", true);
                saveConfig();
                sender.sendMessage(
                        "§aГенерация включена. Вход: /br level 0");
            }

            case "stop" -> {
                active = false;
                getConfig().set("generation-enabled", false);
                saveConfig();
                sender.sendMessage(
                        "§eNPC, переходы и перестройки остановлены.");
            }

            case "levels" -> {
                for (int i = 0;
                     i < BackroomsGenerator.COUNT; i++) {
                    sender.sendMessage("§7" + code(i)
                            + " — " + BackroomsGenerator.TITLES[i]);
                }
            }

            case "info" -> sender.sendMessage(
                    "§7Активен: " + active
                    + "; миров: " + worlds.size()
                    + "; NPC: " + echoes.size()
                    + "; Citizens: "
                    + Bukkit.getPluginManager()
                            .isPluginEnabled("Citizens")
                    + "; миниатюра: " + (mini != null));

            case "level", "enter" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Нужен игрок.");
                    break;
                }
                if (!active) {
                    sender.sendMessage(
                            "§eСначала /br generate");
                    break;
                }
                try {
                    enter(p, args.length > 1
                            ? parse(args[1]) : 0);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(
                            "§cУровни: 0–10, 0.1–10.1,"
                            + " homes, parking, world, exit.");
                }
            }

            case "npc" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Нужен игрок.");
                } else if (!active) {
                    sender.sendMessage("§eСначала /br generate");
                } else {
                    sender.sendMessage(echoes.spawn(p));
                }
            }

            case "shift" -> {
                boolean changed = shiftOne();
                sender.sendMessage(changed
                        ? "§aОдна перегородка изменена."
                        : "§eБезопасный участок не найден."
                        + " Нужны загруженные комнаты"
                        + " вдали от игроков.");
            }

            case "snapshot" -> captureMini(sender);

            default -> {
                sender.sendMessage(
                        "§e/br generate | stop | info | levels");
                sender.sendMessage(
                        "§e/br level <код> | leave");
                sender.sendMessage(
                        "§e/br npc | talk <текст> | shift");
                sender.sendMessage("§e/br snapshot");
            }
        }
        return true;
    }

    private static int parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "homes", "home" -> BackroomsGenerator.HOMES;
            case "world", "mini" -> BackroomsGenerator.MINI;
            case "exit" -> BackroomsGenerator.EXIT;
            case "parking" -> BackroomsGenerator.PARKING;
            default -> {
                String[] a = s.split("\\.", -1);
                if (a.length > 2) {
                    throw new IllegalArgumentException();
                }
                int n = Integer.parseInt(a[0]);
                if (n < 0 || n > 10 ||
                        (a.length == 2 && !a[1].equals("1"))) {
                    throw new IllegalArgumentException();
                }
                yield n * 2 + (a.length == 2 ? 1 : 0);
            }
        };
    }

    static String code(int n) {
        return switch (n) {
            case BackroomsGenerator.HOMES -> "homes";
            case BackroomsGenerator.MINI -> "world";
            case BackroomsGenerator.EXIT -> "exit";
            case BackroomsGenerator.PARKING -> "parking";
            default -> n / 2 + (n % 2 == 1 ? ".1" : "");
        };
    }

    private World open(int id) {
        World known = worlds.get(id);
        if (known != null) return known;

        if (id == BackroomsGenerator.MINI && mini == null) {
            throw new IllegalStateException(
                    "Миниатюра не готова. Сначала /br snapshot.");
        }

        String name = prefix + "_l" + id;
        World loaded = Bukkit.getWorld(name);

        if (loaded != null &&
                !(loaded.getGenerator()
                        instanceof BackroomsGenerator)) {
            throw new IllegalStateException(
                    "Мир уже загружен другим генератором: " + name);
        }

        World w = loaded != null ? loaded :
                new WorldCreator(name)
                        .seed(seed + id * 9973L)
                        .generator(new BackroomsGenerator(
                                id, seed, mini))
                        .generateStructures(false)
                        .createWorld();

        if (w == null) {
            throw new IllegalStateException(
                    "Не удалось создать мир " + name);
        }

        worlds.put(id, w);
        worldLevels.put(w.getUID(), id);

        w.setSpawnLocation(0, 65, 0);
        w.setTime(18000);
        w.setStorm(false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);

        // Прямоугольник дополнительно ограничен обработчиком движения.
        w.getWorldBorder().setCenter(0, 0);
        w.getWorldBorder().setSize(1900);

        for (Chunk c : w.getLoadedChunks()) populate(c);
        return w;
    }

    private void enter(Player p, int id) {
        if (!travelling.add(p.getUniqueId())) return;

        try {
            if (!isVisitor(p)) {
                players.set("returns." + p.getUniqueId(),
                        p.getLocation());
                savePlayers();
            }

            World w = open(id);
            cooldowns.put(p.getUniqueId(),
                    System.currentTimeMillis() + 3000);

            w.getChunkAtAsync(0, 0).whenComplete((c, error) -> {
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (error != null) {
                            getLogger().log(
                                    Level.WARNING,
                                    "Ошибка загрузки входа", error);
                            if (p.isOnline()) {
                                p.sendMessage(
                                        "§cНе удалось загрузить вход.");
                            }
                            return;
                        }
                        if (!p.isOnline()) return;

                        populate(c);
                        Location safe = safe(w, 0, 0, 7);

                        if (safe == null) {
                            p.sendMessage(
                                    "§cВход повреждён: места нет.");
                            return;
                        }

                        p.teleport(safe);
                        p.sendMessage("§7" + code(id)
                                + " — "
                                + BackroomsGenerator.TITLES[id]);
                    } finally {
                        travelling.remove(p.getUniqueId());
                    }
                });
            });
        } catch (RuntimeException ex) {
            travelling.remove(p.getUniqueId());
            p.sendMessage("§c" + ex.getMessage());
            getLogger().log(
                    Level.WARNING, "Не выполнен вход", ex);
        }
    }

    private void leave(Player p) {
        Location loc = players.getLocation(
                "returns." + p.getUniqueId());

        if (loc == null || loc.getWorld() == null
                || worldLevels.containsKey(
                        loc.getWorld().getUID())) {
            World w = Bukkit.getWorld(getConfig().getString(
                    "miniature.source-world", "world"));
            if (w == null) {
                p.sendMessage("§cИсходный мир не загружен.");
                return;
            }
            loc = w.getSpawnLocation();
        }

        cooldowns.put(p.getUniqueId(),
                System.currentTimeMillis() + 3000);
        p.teleport(loc);
    }

    Location safe(World w, int x, int z, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx),
                            Math.abs(dz)) != r) continue;

                    int bx = x + dx, bz = z + dz;
                    if (!BackroomsGenerator.inside(bx, bz)
                            || !w.isChunkLoaded(
                                    bx >> 4, bz >> 4)) continue;

                    for (int y = 65; y <= 108; y++) {
                        Block feet = w.getBlockAt(bx, y, bz);
                        Material ground = feet.getRelative(
                                0, -1, 0).getType();

                        if (ground.isSolid()
                                && ground != Material.MAGMA_BLOCK
                                && feet.getType().isAir()
                                && feet.getRelative(
                                        0, 1, 0).getType().isAir()) {
                            return new Location(
                                    w, bx + 0.5, y, bz + 0.5);
                        }
                    }
                }
            }
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to == null || !isVisitor(p)) return;

        if (!BackroomsGenerator.inside(
                to.getBlockX(), to.getBlockZ())) {
            e.setTo(e.getFrom());
            return;
        }

        if (!active || travelling.contains(p.getUniqueId())
                || cooldowns.getOrDefault(
                        p.getUniqueId(), 0L)
                > System.currentTimeMillis()) return;

        if (!Objects.equals(
                e.getFrom().getWorld(), to.getWorld())) return;

        BackroomsGenerator g =
                (BackroomsGenerator) p.getWorld().getGenerator();

        if (g == null) return;

        for (BackroomsGenerator.Gate gate : g.gates()) {
            double plane = gate.z() + 1.35;

            if (e.getFrom().getZ() >= plane
                    || to.getZ() < plane
                    || to.getY() < 65 || to.getY() >= 67) continue;

            double dz = to.getZ() - e.getFrom().getZ();
            if (dz <= 0) continue;

            double fraction =
                    (plane - e.getFrom().getZ()) / dz;
            double x = e.getFrom().getX()
                    + (to.getX() - e.getFrom().getX()) * fraction;

            if (x < gate.x() || x >= gate.x() + 1) continue;

            Block b = p.getWorld().getBlockAt(
                    gate.x(), 65, gate.z());

            if (!(b.getBlockData() instanceof Door door)
                    || !door.isOpen()) continue;

            cooldowns.put(p.getUniqueId(),
                    System.currentTimeMillis() + 3000);

            Bukkit.getScheduler().runTask(this, () -> {
                if (gate.target() < 0) leave(p);
                else enter(p, gate.target());
            });
            return;
        }
    }

    private boolean protectedBlock(Block b) {
        if (b.getY() <= 64
                || Math.abs(b.getX()) >= 948
                || Math.abs(b.getZ()) >= 848) return true;

        if (!(b.getWorld().getGenerator()
                instanceof BackroomsGenerator g)) return false;

        for (BackroomsGenerator.Gate gate : g.gates()) {
            if (Math.abs(b.getX() - gate.x()) <= 2
                    && b.getZ() >= gate.z()
                    && b.getZ() <= gate.z() + 5
                    && b.getY() <= 69) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!worldLevels.containsKey(
                e.getBlock().getWorld().getUID())) return;

        if (protectedBlock(e.getBlock())) {
            e.setCancelled(true);
        } else {
            e.getBlock().getChunk().getPersistentDataContainer()
                    .set(editedKey,
                            PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!worldLevels.containsKey(
                e.getBlock().getWorld().getUID())) return;

        if (protectedBlock(e.getBlock())) {
            e.setCancelled(true);
        } else {
            e.getBlock().getChunk().getPersistentDataContainer()
                    .set(editedKey,
                            PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock().getRelative(e.getBlockFace());
        if (!worldLevels.containsKey(b.getWorld().getUID())) return;
        if (protectedBlock(b)) e.setCancelled(true);
        else b.getChunk().getPersistentDataContainer().set(
                editedKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        if (!worldLevels.containsKey(b.getWorld().getUID())) return;
        if (protectedBlock(b)) e.setCancelled(true);
        else b.getChunk().getPersistentDataContainer().set(
                editedKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        if (worldLevels.containsKey(
                e.getBlock().getWorld().getUID())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonBack(BlockPistonRetractEvent e) {
        if (worldLevels.containsKey(
                e.getBlock().getWorld().getUID())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent e) {
        if (worldLevels.containsKey(
                e.getLocation().getWorld().getUID())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent e) {
        if (worldLevels.containsKey(
                e.getBlock().getWorld().getUID())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!isVisitor(e.getEntity())) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!worldLevels.containsKey(
                e.getPlayer().getWorld().getUID())) return;

        Location back = players.getLocation(
                "returns." + e.getPlayer().getUniqueId());

        if (back != null && back.getWorld() != null) {
            e.setRespawnLocation(back);
        }
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        if (!worldLevels.containsKey(
                e.getWorld().getUID())) return;

        Chunk c = e.getChunk();
        Bukkit.getScheduler().runTask(this, () -> {
            if (c.isLoaded()) populate(c);
        });
    }

    private void populate(Chunk c) {
        Integer level = worldLevels.get(c.getWorld().getUID());
        if (level == null) return;

        for (BlockState s : c.getTileEntities()) {
            if (!(s instanceof Container box)) continue;

            if (box.getPersistentDataContainer().has(
                    lootKey, PersistentDataType.BYTE)) continue;

            if (box.getInventory().isEmpty()) {
                Random r = new Random(
                        BackroomsGenerator.hash(
                                box.getX(), box.getZ(),
                                seed + level));

                Material[] pool = {
                        Material.BREAD, Material.BAKED_POTATO,
                        Material.PAPER, Material.STRING,
                        Material.IRON_INGOT, Material.COPPER_INGOT,
                        Material.COAL, Material.BOOK
                };

                List<Integer> slots = new ArrayList<>();
                for (int i = 0;
                     i < box.getInventory().getSize(); i++) {
                    slots.add(i);
                }
                Collections.shuffle(slots, r);

                for (int i = 0; i < 6; i++) {
                    box.getInventory().setItem(slots.get(i),
                            new ItemStack(pool[r.nextInt(pool.length)],
                                    1 + r.nextInt(4)));
                }

                box.getInventory().setItem(
                        slots.get(6), story(level, r));
            }

            box.getPersistentDataContainer().set(
                    lootKey, PersistentDataType.BYTE, (byte) 1);
            box.update();
        }
    }

    private ItemStack story(int level, Random r) {
        boolean driver = r.nextBoolean();
        ItemStack b = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m = (BookMeta) b.getItemMeta();
        if (m == null) return b;

        m.setTitle(driver
                ? "ДРАЙВЕР // KERNEL"
                : "АНТИВИРУС // QUARANTINE");
        m.setAuthor(driver
                ? "BACKROOMS SYSTEM" : "UNKNOWN PROCESS");

        m.setPages(
                "Сектор " + code(level) + "\n\n"
                + (driver
                ? "Загрузка комнаты завершена."
                + "\nКоличество посетителей не совпало."
                : "Обнаружена копия посетителя."
                + "\nКарантин не распространяется на стены."),
                driver
                ? "LOAD ROOM\nCHECK DOOR\n"
                + "ERROR: обратный адрес отсутствует."
                : "SCAN WALL\n"
                + "ERROR: объект наблюдает за проверкой."
        );
        b.setItemMeta(m);
        return b;
    }

    private boolean shiftOne() {
        Random r = new Random();
        for (World w : worlds.values()) {
            if (!(w.getGenerator()
                    instanceof BackroomsGenerator g)
                    || g.id >= BackroomsGenerator.HOMES) continue;

            for (Player p : w.getPlayers()) {
                if (!isVisitor(p)) continue;

                for (int n = 0; n < 60; n++) {
                    int x = p.getLocation().getBlockX()
                            + r.nextInt(81) - 40;
                    int z = p.getLocation().getBlockZ()
                            + r.nextInt(81) - 40;

                    if (!w.isChunkLoaded(x >> 4, z >> 4)
                            || !w.isChunkLoaded((x + 2) >> 4, z >> 4)
                            || !g.canShift(x, z)) continue;

                    Chunk c = w.getChunkAt(x >> 4, z >> 4);
                    Chunk other = w.getChunkAt(
                            (x + 2) >> 4, z >> 4);

                    if (c.getPersistentDataContainer().has(
                            editedKey, PersistentDataType.BYTE)
                            || other.getPersistentDataContainer().has(
                            editedKey, PersistentDataType.BYTE)) continue;

                    Location at = new Location(w, x, 65, z);
                    boolean near = false;

                    for (Player viewer : w.getPlayers()) {
                        if (viewer.getLocation()
                                .distanceSquared(at) < 16 * 16) {
                            near = true;
                            break;
                        }
                    }
                    if (near) continue;

                    boolean air = true, wall = true;
                    boolean protectedArea = false;

                    for (int dx = 0; dx < 3; dx++) {
                        for (int dy = 65; dy <= 67; dy++) {
                            Block b = w.getBlockAt(x + dx, dy, z);
                            air &= b.getType().isAir();
                            wall &= b.getType() == g.wall();
                            protectedArea |= protectedBlock(b);
                        }
                    }

                    if (protectedArea || (!air && !wall)) continue;

                    Material replacement =
                            air ? g.wall() : Material.AIR;

                    for (int dx = 0; dx < 3; dx++) {
                        for (int dy = 65; dy <= 67; dy++) {
                            w.getBlockAt(x + dx, dy, z)
                                    .setType(replacement, false);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private File miniFile() {
        return new File(getDataFolder(), "miniature-v4.dat");
    }

    private void loadMini() {
        if (!miniFile().isFile()) return;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(
                        new FileInputStream(miniFile())))) {
            if (in.readInt() != 0x42523401) {
                throw new IOException("Неизвестный формат.");
            }

            BackroomsGenerator.Mini m =
                    new BackroomsGenerator.Mini();

            for (int i = 0; i < m.height.length; i++) {
                m.height[i] = in.readUnsignedByte();
                m.color[i] = in.readByte();
                if (m.height[i] > 40
                        || Byte.toUnsignedInt(m.color[i]) >= 8) {
                    throw new IOException("Некорректная миниатюра.");
                }
            }
            mini = m;
        } catch (IOException ex) {
            getLogger().log(
                    Level.WARNING, "Миниатюра не прочитана", ex);
        }
    }

    private void captureMini(CommandSender sender) {
        if (capturing) {
            sender.sendMessage("§eСнимок уже создаётся.");
            return;
        }

        if (new File(Bukkit.getWorldContainer(),
                prefix + "_l" + BackroomsGenerator.MINI).exists()) {
            sender.sendMessage(
                    "§eМир миниатюры уже существует."
                    + " Для другого снимка нужен новый world-prefix.");
            return;
        }

        World source = Bukkit.getWorld(getConfig().getString(
                "miniature.source-world", "world"));

        if (source == null
                || worldLevels.containsKey(source.getUID())) {
            sender.sendMessage("§cОбычный исходный мир не загружен.");
            return;
        }

        int sx = getConfig().getInt("miniature.center-x", 0) - 950;
        int sz = getConfig().getInt("miniature.center-z", 0) - 850;

        capturing = true;
        sender.sendMessage(
                "§7Создание карты высот существующих чанков."
                + " Отсутствующие чанки будут пропущены.");

        captureNext(sender, source,
                new BackroomsGenerator.Mini(),
                sx, sz, Math.floorDiv(sx, 16),
                Math.floorDiv(sz, 16));
    }

    private void captureNext(CommandSender sender, World source,
                             BackroomsGenerator.Mini result,
                             int sx, int sz, int cx, int cz) {

        int maxX = Math.floorDiv(sx + 1899, 16);
        int maxZ = Math.floorDiv(sz + 1699, 16);

        if (cz > maxZ) {
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            new FileOutputStream(miniFile())))) {
                out.writeInt(0x42523401);
                for (int i = 0; i < result.height.length; i++) {
                    out.writeByte(result.height[i]);
                    out.writeByte(result.color[i]);
                }
                mini = result;
                sender.sendMessage("§aСнимок сохранён: /br level world");
            } catch (IOException ex) {
                sender.sendMessage("§cСнимок сохранить не удалось.");
                getLogger().log(Level.WARNING,
                        "Ошибка сохранения миниатюры", ex);
            } finally {
                capturing = false;
            }
            return;
        }

        source.getChunkAtAsync(cx, cz, false)
                .whenComplete((chunk, error) -> {
                    if (!isEnabled()) return;

                    Bukkit.getScheduler().runTask(this, () -> {
                        if (error != null) {
                            capturing = false;
                            sender.sendMessage(
                                    "§cЧтение исходного мира прервано.");
                            getLogger().log(Level.WARNING,
                                    "Ошибка снимка", error);
                            return;
                        }

                        if (chunk != null) {
                            int firstX = Math.max(0,
                                    Math.floorDiv(
                                            cx * 16 - sx + 3, 4));
                            int firstZ = Math.max(0,
                                    Math.floorDiv(
                                            cz * 16 - sz + 3, 4));

                            int base = getConfig().getInt(
                                    "miniature.base-y", 64);

                            for (int z = firstZ;
                                 z < BackroomsGenerator.Mini.D; z++) {
                                int wz = sz + z * 4;
                                if (wz >= cz * 16 + 16) break;

                                for (int x = firstX;
                                     x < BackroomsGenerator.Mini.W;
                                     x++) {
                                    int wx = sx + x * 4;
                                    if (wx >= cx * 16 + 16) break;

                                    int y = source.getHighestBlockYAt(
                                            wx, wz);
                                    int index = z
                                            * BackroomsGenerator.Mini.W
                                            + x;

                                    result.height[index] =
                                            Math.max(0, Math.min(
                                                    40, (y - base) / 4));

                                    result.color[index] =
                                            BackroomsGenerator.surfaceColor(
                                                    source.getBlockAt(
                                                            wx, y, wz)
                                                            .getType());
                                }
                            }
                        }

                        int nx = cx < maxX ? cx + 1
                                : Math.floorDiv(sx, 16);
                        int nz = cx < maxX ? cz : cz + 1;

                        Bukkit.getScheduler().runTaskLater(
                                this,
                                () -> captureNext(sender, source,
                                        result, sx, sz, nx, nz),
                                1L);
                    });
                });
    }
}
