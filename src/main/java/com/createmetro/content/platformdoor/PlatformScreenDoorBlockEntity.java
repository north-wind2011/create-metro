package com.createmetro.content.platformdoor;

import com.createmetro.registry.CMBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 屏蔽门的动画/状态方块实体。逻辑与 Create 的 {@code SlidingDoorBlockEntity} 一致：
 * 客户端仅负责推进 0..1 的滑动进度；服务端在“关门动画播完”时把方块切换回静态模型
 * （VISIBLE=true），并补上关门音效。
 */
public class PlatformScreenDoorBlockEntity extends BlockEntity {

    /** 0 = 完全关闭，1 = 完全打开。用于渲染平移量。 */
    public float animation;
    /** 上一 tick 的值，配合 partialTick 做插值，避免 20tps 的顿挫。 */
    public float previousAnimation;

    /** 放置后第一 tick 不响应红石，避免被脚下的电源立刻关上。 */
    public boolean deferUpdate;

    private static final float STEP = 1.0f / 20.0f; // 约 1 秒完成开/关

    public PlatformScreenDoorBlockEntity(BlockPos pos, BlockState state) {
        super(CMBlockEntities.PLATFORM_SCREEN_DOOR.get(), pos, state);
        this.animation = state.getValue(DoorBlock.OPEN) ? 1.0f : 0.0f;
        this.previousAnimation = this.animation;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, PlatformScreenDoorBlockEntity door) {
        if (door.deferUpdate && !level.isClientSide) {
            door.deferUpdate = false;
            state.handleNeighborChanged(level, pos, net.minecraft.world.level.block.Blocks.AIR, pos, false);
        }

        door.previousAnimation = door.animation;

        boolean open = state.getValue(DoorBlock.OPEN);
        float target = open ? 1.0f : 0.0f;
        if (door.animation < target) {
            door.animation = Math.min(target, door.animation + STEP);
        } else if (door.animation > target) {
            door.animation = Math.max(target, door.animation - STEP);
        }

        if (!level.isClientSide && !open && door.animation <= 0.0f
            && !PlatformScreenDoorBlock.isVisible(state)) {
            door.showBlockModel();
        }
    }

    private void showBlockModel() {
        if (level == null || level.isClientSide) {
            return;
        }
        level.setBlock(worldPosition, getBlockState().setValue(PlatformScreenDoorBlock.VISIBLE, true),
            Block.UPDATE_ALL);
        level.playSound(null, worldPosition, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.55f, 1.0f);
    }

    public float getAnimationValue(float partialTick) {
        return Mth.lerp(partialTick, previousAnimation, animation);
    }

    public boolean shouldRenderSpecial(BlockState state) {
        return !PlatformScreenDoorBlock.isVisible(state);
    }
}
