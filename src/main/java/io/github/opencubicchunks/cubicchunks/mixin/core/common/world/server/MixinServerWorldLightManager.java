package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.server;

import com.mojang.datafixers.util.Pair;
import io.github.opencubicchunks.cubicchunks.chunk.IChunkManager;
import io.github.opencubicchunks.cubicchunks.chunk.IBigCube;
import io.github.opencubicchunks.cubicchunks.chunk.ticket.CubeTaskPriorityQueueSorter;
import io.github.opencubicchunks.cubicchunks.chunk.util.CubePos;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.lighting.MixinWorldLightManager;
import io.github.opencubicchunks.cubicchunks.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.server.IServerWorldLightManager;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.Util;
import net.minecraft.util.concurrent.ITaskExecutor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.SectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.server.ChunkManager;
import net.minecraft.world.server.ServerWorldLightManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

@Mixin(ServerWorldLightManager.class)
public abstract class MixinServerWorldLightManager extends MixinWorldLightManager implements IServerWorldLightManager {

    private ITaskExecutor<CubeTaskPriorityQueueSorter.FunctionEntry<Runnable>> taskExecutor;

    @Shadow @Final private ChunkManager chunkManager;

    @Shadow @Final private ObjectList<Pair<ServerWorldLightManager.Phase, Runnable>> lightTasks;

    @Shadow private volatile int taskPerBatch;

    @Shadow protected abstract void runUpdate();

    @Override public void postConstructorSetup(CubeTaskPriorityQueueSorter sorter,
            ITaskExecutor<CubeTaskPriorityQueueSorter.FunctionEntry<Runnable>> taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * @author NotStirred
     * @reason lambdas
     */
    @Overwrite
    public void checkBlock(BlockPos blockPosIn)
    {
        BlockPos blockpos = blockPosIn.immutable();
        this.schedulePhaseTask(
                Coords.blockToCube(blockPosIn.getX()),
                Coords.blockToCube(blockPosIn.getY()),
                Coords.blockToCube(blockPosIn.getZ()),
                ServerWorldLightManager.Phase.POST_UPDATE,
                Util.name(() -> super.checkBlock(blockpos),
                () -> "checkBlock " + blockpos)
        );
    }

    // func_215586_a, addTask
    private void schedulePhaseTask(int cubePosX, int cubePosY, int cubePosZ, ServerWorldLightManager.Phase phase, Runnable runnable) {
        this.schedulePhaseTask(cubePosX, cubePosY, cubePosZ, ((IChunkManager)this.chunkManager).getCompletedLevel(CubePos.of(cubePosX, cubePosY,
                cubePosZ).asLong()), phase, runnable);
    }

    // func_215600_a, addTask
    private void schedulePhaseTask(int cubePosX, int cubePosY, int cubePosZ, IntSupplier getCompletedLevel, ServerWorldLightManager.Phase phase,
            Runnable runnable) {
        this.taskExecutor.tell(CubeTaskPriorityQueueSorter.createMsg(() -> {
            this.lightTasks.add(Pair.of(phase, runnable));
            if (this.lightTasks.size() >= this.taskPerBatch) {
                this.runUpdate();
            }

        }, CubePos.asLong(cubePosX, cubePosY, cubePosZ), getCompletedLevel));
    }

    // updateChunkStatus
    public void setCubeStatusEmpty(CubePos cubePos) {
        this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), () -> {
            return 0;
        }, ServerWorldLightManager.Phase.PRE_UPDATE, Util.name(() -> {
            super.retainData(cubePos, false);
            super.enableLightSources(cubePos, false);


            for(int i = 0; i < IBigCube.SECTION_COUNT; ++i) {
                super.setData(LightType.BLOCK, Coords.sectionPosByIndex(cubePos, i), (NibbleArray)null, true);
                super.setData(LightType.SKY, Coords.sectionPosByIndex(cubePos, i), (NibbleArray)null, true);
            }

            for(int j = 0; j < IBigCube.SECTION_COUNT; ++j) {
                super.updateSectionStatus(Coords.sectionPosByIndex(cubePos, j), true);
            }

        }, () -> "setCubeStatusEmpty " + cubePos + " " + true));
    }

    // lightChunk
    @Override
    public CompletableFuture<IBigCube> lightCube(IBigCube icube, boolean flagIn) {
        CubePos cubePos = icube.getCubePos();
        icube.setCubeLight(false);
        this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ServerWorldLightManager.Phase.PRE_UPDATE, Util.name(() -> {
            for(int i = 0; i < IBigCube.SECTION_COUNT; ++i) {
                ChunkSection chunksection = icube.getCubeSections()[i];
                if (!ChunkSection.isEmpty(chunksection)) {
                    super.updateSectionStatus(Coords.sectionPosByIndex(cubePos, i), false);
                }
            }

            super.enableLightSources(cubePos, true);
            if (!flagIn) {
                icube.getCubeLightSources().forEach((blockPos) -> {
                    super.onBlockEmissionIncrease(blockPos, icube.getLightEmission(blockPos));
                });
            }

            ((IChunkManager)this.chunkManager).releaseLightTicket(cubePos);
        }, () -> "lightCube " + cubePos + " " + flagIn));
        return CompletableFuture.supplyAsync(() -> {
            icube.setCubeLight(true);
            super.retainData(cubePos, false);
            return icube;
        }, (runnable) -> {
            this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ServerWorldLightManager.Phase.POST_UPDATE, runnable);
        });
    }

    /**
     * @author NotStirred
     * @reason Vanilla lighting is gone
     */
    @Overwrite
    public void updateSectionStatus(SectionPos pos, boolean isEmpty) {
        CubePos cubePos = CubePos.from(pos);
        this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), () -> 0, ServerWorldLightManager.Phase.PRE_UPDATE, Util.name(() -> {
            super.updateSectionStatus(pos, isEmpty);
        }, () -> "updateSectionStatus " + pos + " " + isEmpty));
    }

    @Override
    public void enableLightSources(CubePos cubePos, boolean flag) {
        this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ServerWorldLightManager.Phase.PRE_UPDATE, Util.name(() -> {
            super.enableLightSources(cubePos, flag);
        }, () -> "enableLight " + cubePos + " " + flag));
    }

    /**
     * @author NotStirred
     * @reason Vanilla lighting is gone
     */
    @Overwrite
    public void setData(LightType type, SectionPos pos, @Nullable NibbleArray array, boolean flag) {
        CubePos cubePos = CubePos.from(pos);
        this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), () -> 0, ServerWorldLightManager.Phase.PRE_UPDATE, Util.name(() -> {
            super.setData(type, pos, array, flag);
        }, () -> "queueData " + pos));
    }

    //retainData(ChunkPos, bool)
    @Override
    public void retainData(CubePos cubePos, boolean retain) {
        this.schedulePhaseTask(cubePos.getX(), cubePos.getY(), cubePos.getZ(), () -> 0, ServerWorldLightManager.Phase.PRE_UPDATE, Util.name(() -> {
            super.retainData(cubePos, retain);
        }, () -> "retainData " + cubePos));
    }
}