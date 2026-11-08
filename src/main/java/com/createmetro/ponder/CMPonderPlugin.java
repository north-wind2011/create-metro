package com.createmetro.ponder;

import com.createmetro.CreateMetro;
import com.createmetro.registry.CMItems;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端专用：把自己的思索场景挂进 Create 的思索索引。
 * 由 {@code CMClientEvents} 在客户端初始化阶段调用 {@code PonderIndex.addPlugin}。
 */
public class CMPonderPlugin implements PonderPlugin {

    /** 思索索引里的分组标签。 */
    public static final ResourceLocation TAG =
        ResourceLocation.fromNamespaceAndPath(CreateMetro.MODID, "platform_screen_doors");

    @Override
    public String getModId() {
        return CreateMetro.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        CMPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(TAG)
            .title("站台屏蔽门")
            .description("两格高的滑动屏蔽门，以及连接它们的屏蔽器与联控器")
            .addToIndex()
            .item(CMItems.PLATFORM_SCREEN_DOOR_LEFT.get())
            .register();

        helper.addTagToComponent(id("platform_screen_door_left"), TAG);
        helper.addTagToComponent(id("platform_screen_door_right"), TAG);
        helper.addTagToComponent(id("platform_screen_door_controller"), TAG);
        helper.addTagToComponent(id("screen_door_linker"), TAG);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateMetro.MODID, path);
    }
}
