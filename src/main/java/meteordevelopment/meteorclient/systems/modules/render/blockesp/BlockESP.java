/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.blockesp;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import static org.lwjgl.glfw.GLFW.*;
import net.minecraft.block.Block;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class BlockESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // General

    private final Setting<List<Block>> blocks1 = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks-1")
        .description("Blocks to search for in group 1.")
        .onChanged(blocks -> {
            if (isActive() && Utils.canUpdate()) onActivate();
        })
        .build()
    );

    private final Setting<List<Block>> blocks2 = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks-2")
        .description("Blocks to search for in group 2.")
        .onChanged(blocks -> {
            if (isActive() && Utils.canUpdate()) onActivate();
        })
        .build()
    );

    private final Setting<ESPBlockData> defaultBlockConfig = sgGeneral.add(new GenericSetting.Builder<ESPBlockData>()
        .name("default-block-config")
        .description("Default block config.")
        .defaultValue(
            new ESPBlockData(
                ShapeMode.Lines,
                new SettingColor(132, 186, 180, 205),
                new SettingColor(132, 186, 180, 22),
                false,
                new SettingColor(132, 186, 180, 145)
            )
        )
        .build()
    );

    private final Setting<Map<Block, ESPBlockData>> blockConfigs = sgGeneral.add(new BlockDataSetting.Builder<ESPBlockData>()
        .name("block-configs")
        .description("Config for each block.")
        .defaultData(defaultBlockConfig)
        .build()
    );

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Render tracer lines.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> vanillaBlend = sgGeneral.add(new BoolSetting.Builder()
        .name("vanilla-blend")
        .description("Softens BlockESP color and alpha for a more vanilla-like look.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> vanillaSaturation = sgGeneral.add(new DoubleSetting.Builder()
        .name("vanilla-saturation")
        .description("Lower values reduce color saturation.")
        .defaultValue(0.68)
        .range(0.2, 1.0)
        .sliderRange(0.2, 1.0)
        .visible(vanillaBlend::get)
        .build()
    );

    private final Setting<Double> blendStrength = sgGeneral.add(new DoubleSetting.Builder()
        .name("blend-strength")
        .description("How strongly BlockESP fades into the scene.")
        .defaultValue(0.5)
        .range(0.2, 0.95)
        .sliderRange(0.2, 0.95)
        .visible(vanillaBlend::get)
        .build()
    );

    private final Setting<Integer> vanillaMaxAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("vanilla-max-alpha")
        .description("Caps maximum BlockESP alpha in blend mode.")
        .defaultValue(220)
        .range(50, 255)
        .sliderRange(50, 255)
        .visible(vanillaBlend::get)
        .build()
    );

    private final Setting<Double> fadeStartDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("fade-start-distance")
        .description("Distance where BlockESP starts fading.")
        .defaultValue(7)
        .range(1, 64)
        .sliderRange(1, 64)
        .visible(vanillaBlend::get)
        .build()
    );

    private final Setting<Double> fadeEndDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("fade-end-distance")
        .description("Distance where BlockESP reaches minimum alpha.")
        .defaultValue(28)
        .range(4, 128)
        .sliderRange(4, 128)
        .visible(vanillaBlend::get)
        .build()
    );

    // Group keybinds

    public final Setting<Boolean> enableGroupKeybinds = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-group-keybinds")
        .description("Enable keybinds to toggle different groups.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Keybind> group1Key = sgGeneral.add(new KeybindSetting.Builder()
        .name("group-1-key")
        .description("Key to activate group 1 (mutually exclusive with group 2).")
        .defaultValue(Keybind.fromKey(GLFW_KEY_1))
        .visible(enableGroupKeybinds::get)
        .build()
    );

    public final Setting<Keybind> group2Key = sgGeneral.add(new KeybindSetting.Builder()
        .name("group-2-key")
        .description("Key to activate group 2 (mutually exclusive with group 1).")
        .defaultValue(Keybind.fromKey(GLFW_KEY_2))
        .visible(enableGroupKeybinds::get)
        .build()
    );

    // 初始状态：只显示 group 1
    public boolean showGroup1 = true;
    public boolean showGroup2 = false;
    private boolean wasGroup1KeyPressed = false;
    private boolean wasGroup2KeyPressed = false;

    // Fix #2: 不再使用 Mutable 成员变量跨线程传递，改为在 onBlockUpdate 里捕获局部 int
    private final Long2ObjectMap<ESPChunk> chunks = new Long2ObjectOpenHashMap<>();

    // Fix #6: 统一使用 chunks 作为锁对象，消除 chunks/groups 双锁不一致问题
    private final Set<ESPGroup> groups = new ReferenceOpenHashSet<>();
    private Set<Block> group1Blocks = Set.of();
    private Set<Block> group2Blocks = Set.of();

    // 批处理方块更新
    private static class BlockUpdateInfo {
        final int x, y, z;
        final Block oldBlock;
        final Block newBlock;
        BlockUpdateInfo(int x, int y, int z, Block oldBlock, Block newBlock) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldBlock = oldBlock;
            this.newBlock = newBlock;
        }
    }
    private final Set<BlockUpdateInfo> pendingBlockUpdates = ConcurrentHashMap.newKeySet();

    private volatile ExecutorService workerThread;
    private int group1Counter = 0;
    private int group2Counter = 0;

    private DimensionType lastDimension;

    public BlockESP() {
        super(Categories.Render, "block-esp", "Renders specified blocks through walls.", "search");

        RainbowColors.register(this::onTickRainbow);
    }

    @Override
    public void onActivate() {
        refreshTrackedBlocks();

        // Fix #5: 每次激活时创建新的线程池（防止上次 deactivate 后残留关闭状态）
        if (workerThread == null || workerThread.isShutdown()) {
            workerThread = Executors.newSingleThreadExecutor();
        }

        synchronized (chunks) {
            chunks.clear();
            groups.clear();
        }

        for (Chunk chunk : Utils.chunks()) {
            searchChunk(chunk);
        }

        lastDimension = mc.world.getDimension();
    }

    @Override
    public void onDeactivate() {
        synchronized (chunks) {
            chunks.clear();
            groups.clear();
        }

        // Fix #5: 关闭线程池，释放资源，等待最多 2 秒让任务安全结束
        if (workerThread != null && !workerThread.isShutdown()) {
            workerThread.shutdown();
            try {
                if (!workerThread.awaitTermination(2, TimeUnit.SECONDS)) {
                    workerThread.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerThread.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        workerThread = null;
    }

    private void onTickRainbow() {
        if (!isActive()) return;

        defaultBlockConfig.get().tickRainbow();
        for (ESPBlockData blockData : blockConfigs.get().values()) blockData.tickRainbow();
    }

    ESPBlockData getBlockData(Block block) {
        ESPBlockData blockData = blockConfigs.get().get(block);
        return blockData == null ? defaultBlockConfig.get() : blockData;
    }

    public Color styleColor(SettingColor source, Color out, double x, double y, double z) {
        out.set(source);
        if (!vanillaBlend.get()) return out;

        int gray = (out.r + out.g + out.b) / 3;
        double saturation = vanillaSaturation.get();
        out.r = (int) MathHelper.lerp(saturation, gray, out.r);
        out.g = (int) MathHelper.lerp(saturation, gray, out.g);
        out.b = (int) MathHelper.lerp(saturation, gray, out.b);

        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double start = fadeStartDistance.get();
        double end = Math.max(start + 0.01, fadeEndDistance.get());
        double t = MathHelper.clamp((dist - start) / (end - start), 0.0, 1.0);
        double minAlphaFactor = 1.0 - blendStrength.get();
        double alphaFactor = MathHelper.lerp(t, 1.0, minAlphaFactor);

        // 反转一下，近处更亮，远处淡出
        alphaFactor = 1.0 + minAlphaFactor - alphaFactor;

        int cappedAlpha = Math.min(out.a, vanillaMaxAlpha.get());
        out.a = MathHelper.clamp((int) Math.round(cappedAlpha * alphaFactor), 8, 255);
        return out;
    }

    private void updateChunk(int x, int z) {
        ESPChunk chunk = chunks.get(ChunkPos.toLong(x, z));
        if (chunk != null) chunk.update();
    }

    private void updateBlock(int x, int y, int z) {
        ESPChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
        if (chunk != null) chunk.update(x, y, z);
    }

    public ESPBlock getBlock(int x, int y, int z) {
        ESPChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
        return chunk == null ? null : chunk.get(x, y, z);
    }

    // Fix #6: 统一用 chunks 锁
    public ESPGroup newGroup(Block block) {
        synchronized (chunks) {
            if (isInGroup1(block)) {
                ESPGroup group = new ESPGroup(++group1Counter, block, 1);
                groups.add(group);
                return group;
            } else {
                ESPGroup group = new ESPGroup(-(++group2Counter), block, 2);
                groups.add(group);
                return group;
            }
        }
    }

    public List<Block> getBlocks1() {
        return blocks1.get();
    }

    public List<Block> getBlocks2() {
        return blocks2.get();
    }

    public boolean isInGroup1(Block block) {
        return group1Blocks.contains(block);
    }

    public boolean isInGroup2(Block block) {
        return group2Blocks.contains(block);
    }

    public boolean isGroupVisible(int groupNumber) {
        if (!enableGroupKeybinds.get()) return true;
        if (groupNumber == 1) return showGroup1;
        if (groupNumber == 2) return showGroup2;
        return true;
    }

    private void refreshTrackedBlocks() {
        group1Blocks = blocks1.get().isEmpty() ? Set.of() : new ReferenceOpenHashSet<>(blocks1.get());
        group2Blocks = blocks2.get().isEmpty() ? Set.of() : new ReferenceOpenHashSet<>(blocks2.get());
    }

    private void submitWorker(Runnable task) {
        ExecutorService executor = workerThread;
        if (executor == null || executor.isShutdown()) return;

        try {
            executor.submit(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    // Fix #6: 统一用 chunks 锁（原来用的是 chunks 但注释写的 groups，保持一致）
    public void removeGroup(ESPGroup group) {
        synchronized (chunks) {
            groups.remove(group);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive()) return;
        searchChunk(event.chunk());
    }

    private void searchChunk(Chunk chunk) {
        submitWorker(() -> {
            if (!isActive()) return;

            ESPChunk schunk = ESPChunk.searchChunk(chunk, group1Blocks, group2Blocks);

            if (schunk.size() > 0) {
                synchronized (chunks) {
                    chunks.put(chunk.getPos().toLong(), schunk);
                    schunk.update();

                    ChunkPos pos = chunk.getPos();
                    updateChunk(pos.x - 1, pos.z);
                    updateChunk(pos.x + 1, pos.z);
                    updateChunk(pos.x, pos.z - 1);
                    updateChunk(pos.x, pos.z + 1);
                }
            }
        });
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!isActive()) return;
        // 在主线程立刻捕获坐标为局部 int，避免 Mutable 成员变量跨线程竞态
        final int bx = event.pos.getX();
        final int by = event.pos.getY();
        final int bz = event.pos.getZ();

        Block newBlock = event.newState.getBlock();
        Block oldBlock = event.oldState.getBlock();

        boolean newInGroup1 = isInGroup1(newBlock);
        boolean newInGroup2 = isInGroup2(newBlock);
        boolean oldInGroup1 = isInGroup1(oldBlock);
        boolean oldInGroup2 = isInGroup2(oldBlock);

        boolean newInAny = newInGroup1 || newInGroup2;
        boolean oldInAny = oldInGroup1 || oldInGroup2;

        final boolean added = newInAny && !oldInAny;
        final boolean removed = !newInAny && oldInAny;

        if (!added && !removed) return;

        // 加入 pending 队列
        pendingBlockUpdates.add(new BlockUpdateInfo(bx, by, bz, oldBlock, newBlock));
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (!isActive()) return;
        if (mc.world == null) return;
        DimensionType dimension = mc.world.getDimension();
        if (lastDimension != dimension) onActivate();
        lastDimension = dimension;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive()) return;
        
        // 处理批处理方块更新
        if (!pendingBlockUpdates.isEmpty()) {
            Set<BlockUpdateInfo> batch = new HashSet<>(pendingBlockUpdates);
            pendingBlockUpdates.clear();
            
            submitWorker(() -> processBatch(batch));
        }

        if (enableGroupKeybinds.get()) {
            boolean isGroup1KeyPressed = group1Key.get().isPressed();
            boolean isGroup2KeyPressed = group2Key.get().isPressed();

            // 单向激活 + 互斥：按下时切换到该组（如果已在该组则无反应）
            if (isGroup1KeyPressed && !wasGroup1KeyPressed && !showGroup1) {
                showGroup1 = true;
                showGroup2 = false;
            }

            if (isGroup2KeyPressed && !wasGroup2KeyPressed && !showGroup2) {
                showGroup2 = true;
                showGroup1 = false;
            }

            wasGroup1KeyPressed = isGroup1KeyPressed;
            wasGroup2KeyPressed = isGroup2KeyPressed;
        } else {
            wasGroup1KeyPressed = false;
            wasGroup2KeyPressed = false;
        }
    }

    private void processBatch(Set<BlockUpdateInfo> batch) {
        synchronized (chunks) {
            for (BlockUpdateInfo info : batch) {
                final int bx = info.x;
                final int by = info.y;
                final int bz = info.z;
                
                final int chunkX = bx >> 4;
                final int chunkZ = bz >> 4;
                final long key = ChunkPos.toLong(chunkX, chunkZ);

                boolean newInGroup1 = isInGroup1(info.newBlock);
                boolean newInGroup2 = isInGroup2(info.newBlock);
                boolean oldInGroup1 = isInGroup1(info.oldBlock);
                boolean oldInGroup2 = isInGroup2(info.oldBlock);

                boolean newInAny = newInGroup1 || newInGroup2;
                boolean oldInAny = oldInGroup1 || oldInGroup2;

                final boolean added = newInAny && !oldInAny;
                final boolean removed = !newInAny && oldInAny;

                ESPChunk chunk = chunks.get(key);

                if (chunk == null) {
                    chunk = new ESPChunk(chunkX, chunkZ);
                    if (chunk.shouldBeDeleted()) continue;
                    chunks.put(key, chunk);
                }

                if (added) chunk.add(bx, by, bz, true);
                else chunk.remove(bx, by, bz);

                for (int x = -1; x < 2; x++) {
                    for (int z = -1; z < 2; z++) {
                        for (int y = -1; y < 2; y++) {
                            if (x == 0 && y == 0 && z == 0) continue;
                            updateBlock(bx + x, by + y, bz + z);
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive()) return;
        List<ESPChunk> staleChunks = null;

        synchronized (chunks) {
            for (Iterator<ESPChunk> it = chunks.values().iterator(); it.hasNext();) {
                ESPChunk chunk = it.next();

                if (chunk.shouldBeDeleted()) {
                    if (staleChunks == null) staleChunks = new ArrayList<>();
                    staleChunks.add(chunk);
                    it.remove();
                } else {
                    chunk.render(event);
                }
            }

            if (tracers.get()) {
                for (ESPGroup group : groups) {
                    group.render(event);
                }
            }
        }

        if (staleChunks != null) {
            List<ESPChunk> chunksToCleanup = staleChunks;
            submitWorker(() -> {
                for (ESPChunk chunk : chunksToCleanup) {
                    if (chunk.blocks == null) continue;

                    for (ESPBlock block : chunk.blocks.values()) {
                        if (block.group != null) block.group.remove(block, false);
                        block.loaded = false;
                    }
                }
            });
        }
    }

    // Fix #7: getInfoString 在渲染线程调用，加锁保护 groups 读取
    @Override
    public String getInfoString() {
        synchronized (chunks) {
            return "%s groups".formatted(groups.size());
        }
    }
}
