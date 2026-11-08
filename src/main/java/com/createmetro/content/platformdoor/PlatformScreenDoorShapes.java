package com.createmetro.content.platformdoor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 与 Create 的 {@code SlidingDoorShapes} 等价的碰撞/选取形状。
 * 关闭且静止时使用原版门的薄板形状；打开（或正在滑动）时使用靠向一侧的形状，
 * 让玩家可以从门洞通过。
 */
public final class PlatformScreenDoorShapes {

    private static final VoxelShape SE_AABB = Block.box(0.0D, 0.0D, -13.0D, 3.0D, 16.0D, 3.0D);
    private static final VoxelShape ES_AABB = Block.box(-13.0D, 0.0D, 0.0D, 3.0D, 16.0D, 3.0D);
    private static final VoxelShape NW_AABB = Block.box(13.0D, 0.0D, 13.0D, 16.0D, 16.0D, 29.0D);
    private static final VoxelShape WN_AABB = Block.box(13.0D, 0.0D, 13.0D, 29.0D, 16.0D, 16.0D);
    private static final VoxelShape SW_AABB = Block.box(13.0D, 0.0D, -13.0D, 16.0D, 16.0D, 3.0D);
    private static final VoxelShape WS_AABB = Block.box(13.0D, 0.0D, 0.0D, 29.0D, 16.0D, 3.0D);
    private static final VoxelShape NE_AABB = Block.box(0.0D, 0.0D, 13.0D, 3.0D, 16.0D, 29.0D);
    private static final VoxelShape EN_AABB = Block.box(-13.0D, 0.0D, 13.0D, 3.0D, 16.0D, 16.0D);

    private PlatformScreenDoorShapes() {
    }

    public static VoxelShape get(Direction facing, boolean hingeRight) {
        return switch (facing) {
            case SOUTH -> hingeRight ? ES_AABB : WS_AABB;
            case WEST -> hingeRight ? SW_AABB : NW_AABB;
            case NORTH -> hingeRight ? WN_AABB : EN_AABB;
            default -> hingeRight ? NE_AABB : SE_AABB;
        };
    }
}
