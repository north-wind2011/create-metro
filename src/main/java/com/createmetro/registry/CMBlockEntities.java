package com.createmetro.registry;

import com.createmetro.CreateMetro;
import com.createmetro.content.controller.PlatformScreenDoorControllerBlockEntity;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class CMBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateMetro.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlatformScreenDoorBlockEntity>>
        PLATFORM_SCREEN_DOOR = BLOCK_ENTITIES.register("platform_screen_door",
            () -> BlockEntityType.Builder.of(PlatformScreenDoorBlockEntity::new,
                CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get(),
                CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get())
                .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlatformScreenDoorControllerBlockEntity>>
        PLATFORM_SCREEN_DOOR_CONTROLLER = BLOCK_ENTITIES.register("platform_screen_door_controller",
            () -> BlockEntityType.Builder.of(PlatformScreenDoorControllerBlockEntity::new,
                CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get())
                .build(null));

    private CMBlockEntities() {
    }
}
