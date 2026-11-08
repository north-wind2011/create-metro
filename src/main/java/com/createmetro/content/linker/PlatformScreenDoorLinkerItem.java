package com.createmetro.content.linker;

import com.createmetro.content.controller.PlatformScreenDoorControllerBlock;
import com.createmetro.content.controller.PlatformScreenDoorControllerBlockEntity;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlock;
import com.createmetro.registry.CMBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏蔽器（屏蔽门连接工具）。
 *
 * <p>玩法：</p>
 * <ul>
 *   <li>右键任意一扇本模组屏蔽门 → 把这扇门连同它的配对门一起加入选择列表；
 *       附近没有配对门时只加入这一扇（始终会有动作条反馈）；</li>
 *   <li>再次右键已在列表里的任意一扇 → 这一对一起从列表移出；</li>
 *   <li>配对判定：左右相邻、朝向相同、铰链相反（宽松判定，不要求开合状态一致），
 *       上下半格都认，命中上半格自动归一到下半格；</li>
 *   <li>右键地面 → 放置联控器并把当前选择写入；右键已放置的联控器 → 覆盖写入；</li>
 *   <li>潜行 + 右键 → 清空本次选择。</li>
 * </ul>
 *
 * <p>选择的门坐标与所在维度保存在物品的 {@link DataComponents#CUSTOM_DATA} 里，
 * 跟物品/玩家走。写入联控器时校验维度一致、每扇门距离联控器不超过
 * {@link #MAX_CONTROLLER_DISTANCE} 格，并去重；最终数据由服务端写入方块实体。</p>
 */
public class PlatformScreenDoorLinkerItem extends Item {

    /** 远距离瞄准射线长度（格）。 */
    public static final double LINK_RANGE = 256.0;
    /** 单次最多选择的门数。 */
    public static final int MAX_DOORS = 128;
    /** 写入联控器时，单扇门到联控器的最大距离（格）。 */
    public static final double MAX_CONTROLLER_DISTANCE = 256.0;

    private static final String KEY_DOORS = "Doors";
    private static final String KEY_DIMENSION = "Dim";
    private static final String KEY_LEGACY_GROUPS = "Groups";

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateMetro/Linker");

    public PlatformScreenDoorLinkerItem(Properties properties) {
        super(properties);
    }

    // ---------------------------------------------------------------- 物品使用

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();

        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) {
                clearSelection(player, stack);
            } else {
                BlockPos clicked = context.getClickedPos();
                BlockState state = level.getBlockState(clicked);
                if (state.getBlock() instanceof PlatformScreenDoorBlock) {
                    toggleDoor(player, stack, clicked, state);
                } else if (state.getBlock() instanceof PlatformScreenDoorControllerBlock) {
                    writeToController(player, stack, clicked);
                } else {
                    placeController(player, stack, context, clicked, state);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) {
                clearSelection(player, stack);
            } else {
                BlockHitResult hit = findTargetedDoor(level, player);
                if (hit != null) {
                    toggleDoor(player, stack, hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
                } else {
                    message(player, "item.createmetro.screen_door_linker.no_target", true);
                    playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.7f);
                }
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** 未完成选择就把屏蔽器切走：提示并清空。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }
        if (!hasSelection(stack)) {
            return;
        }
        if (player.getMainHandItem() == stack || player.getOffhandItem() == stack) {
            return;
        }
        int doors = getSelectedDoors(stack).size();
        setSelection(stack, List.of(), "");
        message(player, "item.createmetro.screen_door_linker.cancelled_switch", true, doors);
        playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.7f);
    }

    // ---------------------------------------------------------------- 选择逻辑

    private void toggleDoor(Player player, ItemStack stack, BlockPos clicked, BlockState clickedState) {
        Level level = player.level();
        BlockPos lower = normalizeLower(clicked, clickedState);
        BlockState lowerState = lower.equals(clicked) ? clickedState : level.getBlockState(lower);
        if (!(lowerState.getBlock() instanceof PlatformScreenDoorBlock)
            || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            message(player, "item.createmetro.screen_door_linker.not_door", true);
            playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            return;
        }

        String dimension = dimensionOf(level);
        List<BlockPos> doors = getSelectedDoors(stack);
        String selectedDimension = getSelectedDimension(stack);
        if (!selectedDimension.isEmpty() && !selectedDimension.equals(dimension)) {
            // 旧选择属于另一个维度，直接换成本维度的新选择。
            doors.clear();
        }

        BlockPos partner = findPartnerLower(level, lower, lowerState);
        boolean removed = doors.contains(lower);
        if (removed) {
            doors.remove(lower);
            boolean partnerRemoved = partner != null && doors.remove(partner);
            setSelection(stack, doors, dimension);
            message(player, partnerRemoved
                ? "item.createmetro.screen_door_linker.removed_pair"
                : "item.createmetro.screen_door_linker.removed_single", true, doors.size());
            playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.7f);
            LOGGER.info("[Linker] removed door {} partner={} partnerRemoved={} -> selection={} ({}) dimension={}",
                lower.toShortString(), partner == null ? "none" : partner.toShortString(), partnerRemoved,
                doors.size(), describeDoors(doors), dimension);
        } else {
            boolean addPartner = partner != null && !doors.contains(partner);
            int needed = addPartner ? 2 : 1;
            if (doors.size() + needed > MAX_DOORS) {
                message(player, "item.createmetro.screen_door_linker.limit", true, MAX_DOORS);
                playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
                return;
            }
            doors.add(lower);
            if (addPartner) {
                doors.add(partner);
            }
            setSelection(stack, doors, dimension);
            String key = addPartner
                ? "item.createmetro.screen_door_linker.added_pair"
                : partner == null
                    ? "item.createmetro.screen_door_linker.added_single"
                    : "item.createmetro.screen_door_linker.added_single_paired";
            if (addPartner || partner != null) {
                message(player, key, true, doors.size());
            } else {
                message(player, key, true);
            }
            playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 1.4f);
            LOGGER.info("[Linker] added door {} partner={} pairAdded={} -> selection={} ({}) dimension={}",
                lower.toShortString(), partner == null ? "none" : partner.toShortString(), addPartner,
                doors.size(), describeDoors(doors), dimension);
        }
    }

    private void clearSelection(Player player, ItemStack stack) {
        int doors = getSelectedDoors(stack).size();
        if (doors == 0) {
            message(player, "item.createmetro.screen_door_linker.cancel_empty", true);
            return;
        }
        setSelection(stack, List.of(), "");
        message(player, "item.createmetro.screen_door_linker.cancelled", true, doors);
        playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 0.8f);
        LOGGER.info("[Linker] cleared selection of {} door(s) ({})", doors, player.getName().getString());
    }

    private void placeController(Player player, ItemStack stack, UseOnContext context,
                                 BlockPos clicked, BlockState clickedState) {
        Level level = player.level();
        List<BlockPos> doors = getSelectedDoors(stack);
        if (doors.isEmpty()) {
            message(player, "item.createmetro.screen_door_linker.empty_selection", true);
            playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            return;
        }

        BlockPos placePos = clickedState.canBeReplaced() ? clicked : clicked.relative(context.getClickedFace());
        if (!level.isInWorldBounds(placePos)
            || !level.getBlockState(placePos).canBeReplaced()
            || !level.mayInteract(player, placePos)) {
            message(player, "item.createmetro.screen_door_linker.no_room", true);
            playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            return;
        }
        if (!validateSelection(player, stack, placePos)) {
            return;
        }

        BlockState controllerState = CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get().defaultBlockState()
            .setValue(PlatformScreenDoorControllerBlock.FACING, player.getDirection().getOpposite());
        level.setBlock(placePos, controllerState, 3);

        if (level.getBlockEntity(placePos) instanceof PlatformScreenDoorControllerBlockEntity controller) {
            controller.bindDoors(doors);
            setSelection(stack, List.of(), "");
            message(player, "item.createmetro.screen_door_linker.placed", false, controller.getDoorCount());
            playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.0f);
            LOGGER.info("[Linker] placed controller at {} ({}): bound {} door(s) submitted, {} valid",
                placePos, dimensionOf(level), doors.size(), controller.getDoorCount());
        } else {
            level.removeBlock(placePos, false);
            message(player, "item.createmetro.screen_door_linker.place_failed", true);
            LOGGER.warn("[Linker] placed controller block at {} but no block entity was created", placePos);
        }
    }

    private void writeToController(Player player, ItemStack stack, BlockPos controllerPos) {
        Level level = player.level();
        if (!(level.getBlockEntity(controllerPos) instanceof PlatformScreenDoorControllerBlockEntity controller)) {
            message(player, "item.createmetro.screen_door_linker.place_failed", true);
            LOGGER.warn("[Linker] right-clicked controller at {} but no block entity found", controllerPos);
            return;
        }
        List<BlockPos> doors = getSelectedDoors(stack);
        if (doors.isEmpty()) {
            message(player, "item.createmetro.screen_door_linker.empty_selection", true);
            playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            return;
        }
        if (!validateSelection(player, stack, controllerPos)) {
            return;
        }

        controller.bindDoors(doors);
        setSelection(stack, List.of(), "");
        message(player, "item.createmetro.screen_door_linker.rebound", false, controller.getDoorCount());
        playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.0f);
        LOGGER.info("[Linker] overwrote controller at {} ({}): bound {} door(s) submitted, {} valid",
            controllerPos, dimensionOf(level), doors.size(), controller.getDoorCount());
    }

    /** 校验选择的维度与距离；全部通过才允许写入。 */
    private boolean validateSelection(Player player, ItemStack stack, BlockPos controllerPos) {
        Level level = player.level();
        String selectedDimension = getSelectedDimension(stack);
        String dimension = dimensionOf(level);
        if (!selectedDimension.isEmpty() && !selectedDimension.equals(dimension)) {
            message(player, "item.createmetro.screen_door_linker.wrong_dimension", true);
            playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            return false;
        }
        List<BlockPos> doors = getSelectedDoors(stack);
        for (BlockPos door : doors) {
            if (door.distSqr(controllerPos) > MAX_CONTROLLER_DISTANCE * MAX_CONTROLLER_DISTANCE) {
                message(player, "item.createmetro.screen_door_linker.too_far", true, (int) MAX_CONTROLLER_DISTANCE);
                playSound(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 工具方法

    /** 把点击到的（可能是上半格的）门坐标归一到下半格。 */
    public static BlockPos normalizeLower(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof PlatformScreenDoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    /** 把点击到的（可能是上半格的）门坐标归一到下半格。 */
    public static BlockPos normalizeLower(Level level, BlockPos pos) {
        return normalizeLower(pos, level.getBlockState(pos));
    }

    /**
     * 找一扇门的配对门（返回下半格坐标）。
     *
     * <p>判定刻意放宽：只要对方是本模组屏蔽门、同为下半格、朝向相同、铰链相反，
     * 并且恰好位于 {@link PlatformScreenDoorBlock#partnerPos} 算出的相邻格，就算一对。
     * 不复用 {@code isDoubleDoor}，因为它还要求两扇的 OPEN 状态相反，两扇都关着时会漏判。</p>
     */
    @Nullable
    public static BlockPos findPartnerLower(Level level, BlockPos lower, BlockState lowerState) {
        if (!(lowerState.getBlock() instanceof PlatformScreenDoorBlock)
            || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        BlockPos other = PlatformScreenDoorBlock.partnerPos(lower, lowerState);
        BlockState otherState = level.getBlockState(other);
        if (otherState.getBlock() instanceof PlatformScreenDoorBlock
            && otherState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
            && otherState.getValue(DoorBlock.FACING) == lowerState.getValue(DoorBlock.FACING)
            && otherState.getValue(DoorBlock.HINGE) != lowerState.getValue(DoorBlock.HINGE)) {
            return other;
        }
        return null;
    }

    /** 日志用：把选择列表压成一段可读文本。 */
    public static String describeDoors(List<BlockPos> doors) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < doors.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(doors.get(i).toShortString());
        }
        return builder.toString();
    }

    /** 沿视线做远距离射线检测，只认屏蔽门（上下半格都算）。 */
    @Nullable
    public static BlockHitResult findTargetedDoor(Level level, Player player) {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getViewVector(1.0f).scale(LINK_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK
            && level.getBlockState(hit.getBlockPos()).getBlock() instanceof PlatformScreenDoorBlock) {
            return hit;
        }
        return null;
    }

    private static String dimensionOf(Level level) {
        return level.dimension().location().toString();
    }

    // ---------------------------------------------------------------- 物品数据

    /** 当前选择的门（下半格坐标，已去重）。 */
    public static List<BlockPos> getSelectedDoors(ItemStack stack) {
        List<BlockPos> doors = new ArrayList<>();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return doors;
        }
        long[] packed = data.copyTag().getLongArray(KEY_DOORS);
        for (long value : packed) {
            BlockPos pos = BlockPos.of(value);
            if (!doors.contains(pos)) {
                doors.add(pos);
            }
        }
        return doors;
    }

    /** 选择列表所属维度；空列表或旧数据返回空串。 */
    public static String getSelectedDimension(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString(KEY_DIMENSION);
    }

    public static int getSelectionCount(ItemStack stack) {
        return getSelectedDoors(stack).size();
    }

    private static boolean hasSelection(ItemStack stack) {
        return getSelectionCount(stack) > 0;
    }

    private static void setSelection(ItemStack stack, List<BlockPos> doors, String dimension) {
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA) == null
            ? new CompoundTag()
            : stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.remove(KEY_LEGACY_GROUPS);
        if (doors.isEmpty()) {
            tag.remove(KEY_DOORS);
            tag.remove(KEY_DIMENSION);
        } else {
            long[] packed = new long[doors.size()];
            for (int i = 0; i < doors.size(); i++) {
                packed[i] = doors.get(i).asLong();
            }
            tag.putLongArray(KEY_DOORS, packed);
            tag.putString(KEY_DIMENSION, dimension);
        }
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    // ---------------------------------------------------------------- 反馈

    private static void message(Player player, String key, boolean actionBar, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), actionBar);
    }

    private static void playSound(Player player, SoundEvent sound, float volume, float pitch) {
        player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.createmetro.screen_door_linker.tooltip")
            .withStyle(ChatFormatting.GRAY));
        int doors = getSelectionCount(stack);
        if (doors > 0) {
            tooltip.add(Component.translatable("item.createmetro.screen_door_linker.groups", doors)
                .withStyle(ChatFormatting.GOLD));
        }
    }
}
