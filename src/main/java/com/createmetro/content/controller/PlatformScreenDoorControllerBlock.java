package com.createmetro.content.controller;

import com.createmetro.registry.CMBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

/**
 * 屏蔽门联控器（Platform Screen Door Controller）。
 *
 * <p>放在站台上，右键打开机械动力风格的 GUI；GUI 内可手动开/关整组屏蔽门、
 * 显示已连接门数/状态。连接方式为纯手动：手持 {@code createmetro:screen_door_linker}
 * 右键一扇屏蔽门，它和配对门会自动成对加入选择，再右键地面放置本方块
 * （或右键已放置的联控器覆盖写入）。
 * 本方块不会自动扫描周围的门。</p>
 */
public class PlatformScreenDoorControllerBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public PlatformScreenDoorControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide
            && player instanceof ServerPlayer serverPlayer
            && level.getBlockEntity(pos) instanceof PlatformScreenDoorControllerBlockEntity controller) {
            serverPlayer.openMenu(controller, buffer -> buffer.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlatformScreenDoorControllerBlockEntity(pos, state);
    }

    /**
     * 只在方块真被拆掉/替换时恢复车站 DoorControl。区块卸载不会经过这里，
     * 因此不会像 {@code BlockEntity#setRemoved} 那样在关世界时反复强制性加载区块。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof PlatformScreenDoorControllerBlockEntity controller) {
            controller.restoreOnBlockRemoved();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 服务端每 tick 驱动列车联动检测；客户端不需要 ticker。 */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide
            || type != (BlockEntityType<?>) CMBlockEntities.PLATFORM_SCREEN_DOOR_CONTROLLER.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<PlatformScreenDoorControllerBlockEntity>)
            (clientLevel, pos, blockState, controller) -> controller.tick();
    }
}
