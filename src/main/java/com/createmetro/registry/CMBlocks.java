package com.createmetro.registry;

import com.createmetro.CreateMetro;
import com.createmetro.content.controller.PlatformScreenDoorControllerBlock;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册。两个变体共享同一套方块实体/渲染/碰撞逻辑，只在模型与贴图上区分
 * “左向箭头 / 右向箭头”导向标识。
 */
public final class CMBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(CreateMetro.MODID);

    public static final DeferredBlock<PlatformScreenDoorBlock> PLATFORM_SCREEN_DOOR_LEFT =
        registerDoor("platform_screen_door_left", false);

    public static final DeferredBlock<PlatformScreenDoorBlock> PLATFORM_SCREEN_DOOR_RIGHT =
        registerDoor("platform_screen_door_right", true);

    /** 屏蔽门联控器。 */
    public static final DeferredBlock<PlatformScreenDoorControllerBlock> PLATFORM_SCREEN_DOOR_CONTROLLER =
        BLOCKS.register("platform_screen_door_controller", () -> new PlatformScreenDoorControllerBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE)
                .strength(2.0F, 6.0F)
                .sound(SoundType.METAL)));

    private CMBlocks() {
    }

    private static DeferredBlock<PlatformScreenDoorBlock> registerDoor(String name, boolean slideRight) {
        return BLOCKS.register(name, () -> new PlatformScreenDoorBlock(
            PlatformScreenDoorBlock.GLASS_SET_TYPE,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(1.5F, 3.0F)
                .sound(SoundType.GLASS)
                .noOcclusion()
                .isValidSpawn((state, level, pos, entityType) -> false)
                .isRedstoneConductor((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false),
            slideRight
        ));
    }

    /** 供物品/创造模式标签页使用。 */
    public static Block[] allDoors() {
        return new Block[] {PLATFORM_SCREEN_DOOR_LEFT.get(), PLATFORM_SCREEN_DOOR_RIGHT.get()};
    }
}
