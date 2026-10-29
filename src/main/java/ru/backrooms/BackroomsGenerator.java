package ru.backrooms;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.*;

public final class BackroomsGenerator extends ChunkGenerator {
    static final int HOMES = 22;
    static final int MINI = 23;
    static final int EXIT = 24;
    static final int PARKING = 25;
    static final int COUNT = 26;

    private static final int W = 190;
    private static final int D = 170;

    final int id;
    private final long seed;
    private final Mini mini;
    private final byte[] plan = new byte[W * D];

    static final String[] TITLES = {
            "Жёлтые офисы",
            "Сырые служебные проходы",
            "Складские ангары",
            "Медные коммуникации",
            "Заросшие помещения",
            "Чёрные кольцевые галереи",
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
            "Диагональные галереи",
            "Подвесные переходы",
            "Белые выставочные помещения",
            "Грязевые технические камеры",
            "Глубинные катакомбы",
            "Перегретая спираль",
            "Жилой район",
            "Миниатюра поверхности",
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

    private static final Material[] MINI_COLORS = {
            Material.GRAY_CONCRETE,
            Material.GREEN_CONCRETE,
            Material.BLUE_CONCRETE,
            Material.BROWN_CONCRETE,
            Material.WHITE_CONCRETE,
            Material.YELLOW_CONCRETE,
            Material.BLACK_CONCRETE,
            Material.RED_CONCRETE
    };

    record Gate(int x, int z, int target) {}

    static final class Mini {
        static final int W = 475;
        static final int D = 425;
        final int[] height = new int[W * D];
        final byte[] color = new byte[W * D];
    }

    BackroomsGenerator(int id, long seed, Mini mini) {
        this.id = id;
        this.seed = seed + id * 104729L;
        this.mini = mini;
        buildPlan();
    }

    Material wall() {
        return WALLS[id];
    }

    static boolean inside(int x, int z) {
        return x >= -950 && x < 950
                && z >= -850 && z < 850;
    }

    static long hash(int x, int z, long salt) {
        long v = salt
                ^ x * 341873128712L
                ^ z * 132897987541L;
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }

    List<Gate> gates() {
        if (id == EXIT) {
            return List.of(new Gate(24, 12, -1));
        }

        int next = (id + 1) % COUNT;
        if (next == MINI && mini == null) next = 0;

        int special = mini == null ? 8 : MINI;

        return List.of(
                new Gate(24, 12, next),
                new Gate(320, 12,
                        id == HOMES ? 0 : HOMES),
                new Gate(-320, 12, PARKING),
                new Gate(24, 420, special),
                new Gate(24, -420, EXIT)
        );
    }

    private void put(int x, int z, int type) {
        if (x < 1 || z < 1 || x >= W - 1 || z >= D - 1) {
            return;
        }
        int i = z * W + x;
        plan[i] = (byte) Math.max(plan[i], type);
    }

    private int cell(int x, int z) {
        if (x < 0 || z < 0 || x >= W || z >= D) return 0;
        return plan[z * W + x];
    }

    private void rect(int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                put(x, z, 2);
            }
        }
    }

    private void line(int x1, int z1, int x2, int z2) {
        int x = x1, z = z1;
        put(x, z, 1);
        while (x != x2) {
            x += Integer.signum(x2 - x);
            put(x, z, 1);
        }
        while (z != z2) {
            z += Integer.signum(z2 - z);
            put(x, z, 1);
        }
    }

    private void rooms(Random r, int count,
                       int min, int max, boolean offices) {
        List<int[]> centers = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int width = min + r.nextInt(max - min + 1);
            int depth = min + r.nextInt(max - min + 1);
            int x = 2 + r.nextInt(W - width - 4);
            int z = 2 + r.nextInt(D - depth - 4);

            rect(x, z, x + width, z + depth);

            int mx = x + width / 2;
            int mz = z + depth / 2;

            if (offices) {
                // Кабинеты отделены внутренними перегородками.
                for (int px = x + 3; px < x + width; px += 4) {
                    for (int pz = z + 1;
                         pz < z + depth; pz++) {
                        if (pz == mz) continue;
                        plan[pz * W + px] = 0;
                    }
                }
                line(x, mz, x + width, mz);
            }

            if (!centers.isEmpty()) {
                int[] nearest = centers.get(0);
                long best = Long.MAX_VALUE;

                for (int[] c : centers) {
                    long dx = c[0] - mx, dz = c[1] - mz;
                    long distance = dx * dx + dz * dz;
                    if (distance < best) {
                        best = distance;
                        nearest = c;
                    }
                }
                line(nearest[0], nearest[1], mx, mz);
            }
            centers.add(new int[]{mx, mz});
        }

        if (!centers.isEmpty()) {
            line(W / 2, D / 2,
                    centers.get(0)[0], centers.get(0)[1]);
        }
    }

    private void maze(Random r) {
        boolean[] visited = new boolean[W * D];
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{3, 3});
        visited[3 * W + 3] = true;
        put(3, 3, 1);

        int[][] directions = {
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        while (!stack.isEmpty()) {
            int[] current = stack.peek();
            List<int[]> choices = new ArrayList<>();

            for (int[] d : directions) {
                int x = current[0] + d[0];
                int z = current[1] + d[1];

                if (x > 1 && z > 1
                        && x < W - 2 && z < D - 2
                        && !visited[z * W + x]) {
                    choices.add(new int[]{x, z});
                }
            }

            if (choices.isEmpty()) {
                stack.pop();
                continue;
            }

            int[] next = choices.get(r.nextInt(choices.size()));
            visited[next[1] * W + next[0]] = true;
            line(current[0], current[1], next[0], next[1]);
            stack.push(next);
        }
    }

    private void bays(int step, int depth, boolean cells) {
        line(4, D / 2, W - 5, D / 2);

        for (int x = 5; x < W - step; x += step) {
            if (cells) {
                for (int z = 5; z < D - 7; z += 8) {
                    rect(x, z, x + step - 3, z + 5);
                    line(x + step / 2, z + 2,
                            x + step / 2, D / 2);
                }
            } else {
                rect(x, 5, x + step - 3, D / 2 - depth);
                rect(x, D / 2 + depth,
                        x + step - 3, D - 6);
                line(x + step / 2, 5,
                        x + step / 2, D - 6);
            }
        }
    }

    private void rings(boolean spiral) {
        for (int inset = 5; inset < D / 2 - 4; inset += 7) {
            int right = W - inset - 1;
            int bottom = D - inset - 1;

            line(inset, inset, right, inset);
            line(right, inset, right, bottom);
            line(right, bottom, inset, bottom);
            line(inset, bottom, inset,
                    spiral ? inset + 7 : inset);

            if (spiral) {
                line(inset, inset + 7,
                        inset + 7, inset + 7);
            } else {
                line(W / 2, inset, W / 2, D / 2);
            }
        }
        line(W / 2, D / 2, W / 2, 5);
    }

    private void snake(int spacing) {
        int previousX = 4, previousZ = 4;
        boolean east = true;

        for (int z = 4; z < D - 4; z += spacing) {
            int start = east ? 4 : W - 5;
            int end = east ? W - 5 : 4;

            line(previousX, previousZ, start, z);
            line(start, z, end, z);

            previousX = end;
            previousZ = z;
            east = !east;
        }
    }

    private void caves(Random r, int steps, int radius) {
        int x = W / 2, z = D / 2;

        for (int i = 0; i < steps; i++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius) {
                        put(x + dx, z + dz, 2);
                    }
                }
            }
            x = Math.max(radius + 2, Math.min(
                    W - radius - 3, x + r.nextInt(7) - 3));
            z = Math.max(radius + 2, Math.min(
                    D - radius - 3, z + r.nextInt(7) - 3));
        }
    }

    private void radial() {
        int cx = W / 2, cz = D / 2;
        rect(cx - 5, cz - 5, cx + 5, cz + 5);

        for (int i = 0; i < 20; i++) {
            double angle = i * Math.PI / 10;
            int x = cx + (int) (Math.cos(angle) * 70);
            int z = cz + (int) (Math.sin(angle) * 60);
            line(cx, cz, x, z);
            rect(x - 3, z - 3, x + 3, z + 3);
        }
    }

    private void buildPlan() {
        Random r = new Random(seed);

        switch (id) {
            case 0 -> rooms(r, 170, 3, 10, true);
            case 1 -> maze(r);
            case 2 -> bays(18, 3, false);
            case 3 -> snake(6);
            case 4 -> {
                rooms(r, 90, 5, 13, false);
                caves(r, 1500, 2);
            }
            case 5 -> rings(false);
            case 6 -> rooms(r, 100, 7, 17, true);
            case 7 -> bays(10, 2, true);
            case 8 -> {
                bays(22, 9, false);
                rect(70, 58, 120, 112);
            }
            case 9 -> snake(4);
            case 10 -> bays(12, 5, true);
            case 11 -> caves(r, 14000, 2);
            case 12 -> {
                rooms(r, 100, 8, 18, false);
                rect(70, 60, 120, 110);
            }
            case 13 -> {
                for (int x = 8; x < W - 8; x += 12) {
                    line(x, 5, x, D - 6);
                }
                for (int z = 8; z < D - 8; z += 15) {
                    line(5, z, W - 6, z);
                }
            }
            case 14 -> radial();
            case 15 -> {
                rooms(r, 45, 12, 25, false);
                snake(18);
            }
            case 16 -> {
                for (int offset = -D;
                     offset < W; offset += 12) {
                    for (int z = 3; z < D - 3; z++) {
                        int x = offset + z;
                        line(x, z, x + 1, z + 1);
                    }
                }
                line(4, D / 2, W - 5, D / 2);
            }
            case 17 -> {
                snake(15);
                for (int i = 0; i < 35; i++) {
                    int x = 6 + r.nextInt(W - 15);
                    int z = 6 + r.nextInt(D - 15);
                    rect(x, z, x + 4, z + 4);
                    line(x, z, W / 2, z);
                }
                line(W / 2, 4, W / 2, D - 5);
            }
            case 18 -> rooms(r, 65, 10, 23, false);
            case 19 -> {
                caves(r, 10000, 1);
                rooms(r, 40, 3, 7, false);
            }
            case 20 -> {
                maze(r);
                for (int i = 0; i < 60; i++) {
                    int x = 5 + r.nextInt(W - 12);
                    int z = 5 + r.nextInt(D - 12);
                    rect(x, z, x + 3, z + 3);
                }
            }
            case 21 -> rings(true);
            default -> Arrays.fill(plan, (byte) 2);
        }
   
