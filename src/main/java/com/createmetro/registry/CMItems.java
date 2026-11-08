package com.createmetro.registry;

import com.createmetro.CreateMetro;
import com.createmetro.content.linker.PlatformScreenDoorLinkerItem;
import com.createmetro.content.platformdoor.ScreenDoorBlockItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMItems {

    public static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CreateMetro.MODID);

    public static final DeferredItem<BlockItem> PLATFORM_SCREEN_DOOR_LEFT =
        ITEMS.register("platform_screen_door_left",
            () -> new ScreenDoorBlockItem(CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> PLATFORM_SCREEN_DOOR_RIGHT =
        ITEMS.register("platform_screen_door_right",
            () -> new ScreenDoorBlockItem(CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> PLATFORM_SCREEN_DOOR_CONTROLLER =
        ITEMS.register("platform_screen_door_controller",
            () -> new BlockItem(CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get(), new Item.Properties()));

    /** 屏蔽器（屏蔽门连接工具），单次绑定数据存在物品 NBT 中。 */
    public static final DeferredItem<Item> SCREEN_DOOR_LINKER =
        ITEMS.register("screen_door_linker",
            () -> new PlatformScreenDoorLinkerItem(new Item.Properties().stacksTo(1)));

    private CMItems() {
    }
}
