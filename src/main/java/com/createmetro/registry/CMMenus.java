package com.createmetro.registry;

import com.createmetro.CreateMetro;
import com.createmetro.content.controller.PlatformScreenDoorControllerMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, CreateMetro.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<PlatformScreenDoorControllerMenu>>
        PLATFORM_SCREEN_DOOR_CONTROLLER = MENUS.register("platform_screen_door_controller",
            () -> IMenuTypeExtension.create(PlatformScreenDoorControllerMenu::new));

    private CMMenus() {
    }
}
