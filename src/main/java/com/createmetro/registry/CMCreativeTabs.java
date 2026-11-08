package com.createmetro.registry;

import com.createmetro.CreateMetro;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateMetro.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.createmetro"))
            .icon(() -> new ItemStack(CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get()))
            .displayItems((params, output) -> {
                output.accept(CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get());
                output.accept(CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get());
                output.accept(CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get());
                output.accept(CMItems.SCREEN_DOOR_LINKER.get());
            })
            .build());

    /** 安装 Create 时，把屏蔽门追加到 Create 原版创造物品栏（create:base）里。 */
    public static void addToCreateTab(BuildCreativeModeTabContentsEvent event) {
        ResourceLocation tab = event.getTabKey().location();
        if (!"create".equals(tab.getNamespace()) || !"base".equals(tab.getPath())) {
            return;
        }
        event.accept(new ItemStack(CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get()),
            CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get()),
            CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get()),
            CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        event.accept(new ItemStack(CMItems.SCREEN_DOOR_LINKER.get()),
            CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }

    private CMCreativeTabs() {
    }
}
