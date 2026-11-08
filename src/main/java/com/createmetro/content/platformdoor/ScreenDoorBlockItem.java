package com.createmetro.content.platformdoor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** 屏蔽门方块物品：在物品提示里说明「只能红石或联控器开关」。 */
public class ScreenDoorBlockItem extends BlockItem {

    public ScreenDoorBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.createmetro.platform_screen_door.tooltip")
            .withStyle(ChatFormatting.GRAY));
    }
}
