package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

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

    long ticks;

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        decoratedKey = new NamespacedKey(this, "decorated");
        editedKey = new NamespacedKey(this, "edited");
        portalKey = new NamespacedKey(this, "portal");

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
}
