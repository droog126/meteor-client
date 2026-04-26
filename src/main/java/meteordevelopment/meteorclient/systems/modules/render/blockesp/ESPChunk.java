/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.blockesp;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getRenderDistance;

public class ESPChunk {

    private final int x, z;
    public Long2ObjectMap<ESPBlock> blocks;

    public ESPChunk(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public ESPBlock get(int x, int y, int z) {
        return blocks == null ? null : blocks.get(ESPBlock.getKey(x, y, z));
    }

    public void add(int x, int y, int z, boolean update) {
        ESPBlock block = new ESPBlock(x, y, z);

        if (blocks == null) blocks = new Long2ObjectOpenHashMap<>(64);
        blocks.put(ESPBlock.getKey(x, y, z), block);

        if (update) block.update();
    }

    public void add(BlockPos blockPos, boolean update) {
        add(blockPos.getX(), blockPos.getY(), blockPos.getZ(), update);
    }

    public void add(BlockPos blockPos) {
        add(blockPos, true);
    }

    public void remove(BlockPos blockPos) {
        remove(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }

    public void remove(int x, int y, int z) {
        if (blocks != null) {
            ESPBlock block = blocks.remove(ESPBlock.getKey(x, y, z));
            if (block != null) block.group.remove(block);
        }
    }

    public void update() {
        if (blocks != null) {
            for (ESPBlock block : blocks.values()) block.update();
        }
    }

    public void update(int x, int y, int z) {
        if (blocks != null) {
            ESPBlock block = blocks.get(ESPBlock.getKey(x, y, z));
            if (block != null) block.update();
        }
    }

    public int size() {
        return blocks == null ? 0 : blocks.size();
    }

    public boolean shouldBeDeleted() {
        if (mc.player == null) return false;

        int viewDist = getRenderDistance() + 1;
        int chunkX = ChunkSectionPos.getSectionCoord(mc.player.getBlockPos().getX());
        int chunkZ = ChunkSectionPos.getSectionCoord(mc.player.getBlockPos().getZ());

        return x > chunkX + viewDist || x < chunkX - viewDist || z > chunkZ + viewDist || z < chunkZ - viewDist;
    }

    public void render(Render3DEvent event) {
        if (blocks != null) {
            for (ESPBlock block : blocks.values()) block.render(event);
        }
    }


    public static ESPChunk searchChunk(Chunk chunk, Set<Block> blocks1, Set<Block> blocks2) {
        ESPChunk schunk = new ESPChunk(chunk.getPos().x, chunk.getPos().z);
        if (schunk.shouldBeDeleted()) return schunk;

        boolean hasBlocks1 = blocks1 != null && !blocks1.isEmpty();
        boolean hasBlocks2 = blocks2 != null && !blocks2.isEmpty();

        if (!hasBlocks1 && !hasBlocks2) return schunk;

        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int bottomY = mc.world.getBottomY();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) continue;

            int startY = bottomY + (sectionIndex << 4);
            for (int localY = 0; localY < 16; localY++) {
                int y = startY + localY;
                for (int localX = 0; localX < 16; localX++) {
                    int x = startX + localX;
                    for (int localZ = 0; localZ < 16; localZ++) {
                        Block block = section.getBlockState(localX, localY, localZ).getBlock();
                        if ((hasBlocks1 && blocks1.contains(block)) || (hasBlocks2 && blocks2.contains(block))) {
                            schunk.add(x, y, startZ + localZ, false);
                        }
                    }
                }
            }
        }

        return schunk;
    }
}
