package com.createmetro;

import com.createmetro.client.CMClientEvents;
import com.createmetro.registry.CMBlockEntities;
import com.createmetro.registry.CMBlocks;
import com.createmetro.registry.CMCreativeTabs;
import com.createmetro.registry.CMItems;
import com.createmetro.registry.CMMenus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CreateMetro.MODID)
public class CreateMetro {
    public static final String MODID = "createmetro";

    public CreateMetro(IEventBus modEventBus) {
        CMBlocks.BLOCKS.register(modEventBus);
        CMItems.ITEMS.register(modEventBus);
        CMBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        CMCreativeTabs.TABS.register(modEventBus);
        CMMenus.MENUS.register(modEventBus);

        // 把屏蔽门追加到 Create 原版创造物品栏（Create 未安装时该事件不会出现 create:base，自动跳过）
        modEventBus.addListener(CMCreativeTabs::addToCreateTab);

        // 客户端专用事件（方块实体渲染器等）只在客户端注册，专用服务端不会加载这些类。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CMClientEvents.register(modEventBus);
        }
    }
}
