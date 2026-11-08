package com.createmetro.content.controller;

import com.createmetro.content.controller.PlatformScreenDoorControllerBlockEntity.DoorSlot;
import com.createmetro.registry.CMMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 联控器 GUI 的菜单。
 *
 * <p>GUI 以「门位」为单位：一行 = 一整对门（左向 + 右向），没有配对门的那一扇单独成一行。
 * 状态通过 {@link SimpleContainerData} 从服务端同步到客户端（服务端每次广播前从方块实体
 * 重新读实际方块状态），因此指示灯、维护标记都是服务端权威的。按钮点击走原版
 * {@code ServerboundContainerButtonClickPacket} → {@link #clickMenuButton}，不需要自定义网络包。</p>
 */
public class PlatformScreenDoorControllerMenu extends AbstractContainerMenu {

    public static final int DATA_OPEN = 0;
    public static final int DATA_SLOT_COUNT = 1;
    public static final int DATA_MAINT_COUNT = 2;
    public static final int DATA_ROWS = 3;

    /** GUI 同步的最大门位数；超过时只显示最近的门位，并在界面提示。 */
    public static final int MAX_ROWS = 32;
    /** 每个门位同步 11 个 int：first xyz、是否有配对、second xyz、两扇的 OPEN、维护、列车联动。 */
    public static final int ROW_STRIDE = 11;

    public static final int BUTTON_OPEN_ALL = 0;
    public static final int BUTTON_CLOSE_ALL = 1;
    /** 维护按钮：id = {@link #BUTTON_MAINTENANCE_BASE} + 门位行号。 */
    public static final int BUTTON_MAINTENANCE_BASE = 10;
    /** 列车联动按钮：id = {@link #BUTTON_TRAIN_LINK_BASE} + 门位行号。 */
    public static final int BUTTON_TRAIN_LINK_BASE = BUTTON_MAINTENANCE_BASE + MAX_ROWS;

    private static final double MAX_INTERACTION_DISTANCE_SQR = 64.0;

    private final SimpleContainerData data = new SimpleContainerData(DATA_ROWS + MAX_ROWS * ROW_STRIDE);
    private final BlockPos controllerPos;
    @Nullable
    private final PlatformScreenDoorControllerBlockEntity controller;
    /**
     * 最近一次同步给客户端的行 → 门位主坐标快照。客户端点击时只发行号，
     * 服务端用这份快照把行号还原成坐标，避免“列表在两次刷新之间变化”导致勾选串到别的门。
     */
    private final List<BlockPos> rowKeys = new ArrayList<>();

    /** 服务端构造。 */
    public PlatformScreenDoorControllerMenu(int containerId, Inventory inventory, BlockPos controllerPos,
                                            @Nullable PlatformScreenDoorControllerBlockEntity controller) {
        super(CMMenus.PLATFORM_SCREEN_DOOR_CONTROLLER.get(), containerId);
        this.controllerPos = controllerPos;
        this.controller = controller;
        addDataSlots(data);
        refreshData();
    }

    /** 客户端构造（位置由服务端通过 openMenu 的附加数据传入）。 */
    public PlatformScreenDoorControllerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos(), null);
    }

    private void refreshData() {
        if (controller == null || controller.isRemoved()) {
            return;
        }
        data.set(DATA_OPEN, controller.isGroupOpen() ? 1 : 0);
        List<DoorSlot> slots = controller.getDoorSlots();
        int maintenance = 0;
        for (DoorSlot slot : slots) {
            if (slot.maintenance()) {
                maintenance++;
            }
        }
        data.set(DATA_SLOT_COUNT, slots.size());
        data.set(DATA_MAINT_COUNT, maintenance);
        int rows = Math.min(slots.size(), MAX_ROWS);
        rowKeys.clear();
        for (int i = 0; i < rows; i++) {
            rowKeys.add(slots.get(i).first());
        }
        for (int i = 0; i < MAX_ROWS; i++) {
            int base = DATA_ROWS + i * ROW_STRIDE;
            if (i < rows) {
                DoorSlot slot = slots.get(i);
                BlockPos first = slot.first();
                BlockPos second = slot.second();
                data.set(base, first.getX());
                data.set(base + 1, first.getY());
                data.set(base + 2, first.getZ());
                data.set(base + 3, second != null ? 1 : 0);
                data.set(base + 4, second != null ? second.getX() : 0);
                data.set(base + 5, second != null ? second.getY() : 0);
                data.set(base + 6, second != null ? second.getZ() : 0);
                data.set(base + 7, slot.firstOpen() ? 1 : 0);
                data.set(base + 8, slot.secondOpen() ? 1 : 0);
                data.set(base + 9, slot.maintenance() ? 1 : 0);
                data.set(base + 10, slot.trainLinked() ? 1 : 0);
            } else {
                for (int k = 0; k < ROW_STRIDE; k++) {
                    data.set(base + k, 0);
                }
            }
        }
    }

    @Override
    public void broadcastChanges() {
        refreshData();
        super.broadcastChanges();
    }

    public boolean isGroupOpen() {
        return data.get(DATA_OPEN) != 0;
    }

    /** 已连接的门位数（一对门算 1，单扇也算 1）。 */
    public int getSlotCount() {
        return data.get(DATA_SLOT_COUNT);
    }

    public int getMaintenanceCount() {
        return data.get(DATA_MAINT_COUNT);
    }

    /** 实际同步到客户端的行数（最多 {@link #MAX_ROWS}）。 */
    public int getRowCount() {
        return Math.min(getSlotCount(), MAX_ROWS);
    }

    /** 已同步行里真正成对的门位数。 */
    public int getPairCount() {
        int pairs = 0;
        int rows = getRowCount();
        for (int i = 0; i < rows; i++) {
            if (hasRowSecond(i)) {
                pairs++;
            }
        }
        return pairs;
    }

    public boolean isTruncated() {
        return getSlotCount() > MAX_ROWS;
    }

    public BlockPos getRowFirstPos(int row) {
        int base = DATA_ROWS + row * ROW_STRIDE;
        return new BlockPos(data.get(base), data.get(base + 1), data.get(base + 2));
    }

    public boolean hasRowSecond(int row) {
        return data.get(DATA_ROWS + row * ROW_STRIDE + 3) != 0;
    }

    @Nullable
    public BlockPos getRowSecondPos(int row) {
        if (!hasRowSecond(row)) {
            return null;
        }
        int base = DATA_ROWS + row * ROW_STRIDE + 4;
        return new BlockPos(data.get(base), data.get(base + 1), data.get(base + 2));
    }

    public boolean isRowFirstOpen(int row) {
        return data.get(DATA_ROWS + row * ROW_STRIDE + 7) != 0;
    }

    public boolean isRowSecondOpen(int row) {
        return data.get(DATA_ROWS + row * ROW_STRIDE + 8) != 0;
    }

    public boolean isRowMaintenance(int row) {
        return data.get(DATA_ROWS + row * ROW_STRIDE + 9) != 0;
    }

    public boolean isRowTrainLinked(int row) {
        return data.get(DATA_ROWS + row * ROW_STRIDE + 10) != 0;
    }

    public BlockPos getControllerPos() {
        return controllerPos;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (controller == null || controller.isRemoved() || player.level().isClientSide) {
            return false;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(controllerPos)) > MAX_INTERACTION_DISTANCE_SQR) {
            return false;
        }
        if (id >= BUTTON_MAINTENANCE_BASE && id < BUTTON_MAINTENANCE_BASE + MAX_ROWS) {
            // 行号先还原成同步时的门位坐标，再由服务端按坐标解析当前门位。
            int row = id - BUTTON_MAINTENANCE_BASE;
            if (row >= rowKeys.size()) {
                return false;
            }
            if (!controller.toggleDoorMaintenance(rowKeys.get(row))) {
                refreshData();
                broadcastChanges();
                return false;
            }
            refreshData();
            broadcastChanges();
            return true;
        }
        if (id >= BUTTON_TRAIN_LINK_BASE && id < BUTTON_TRAIN_LINK_BASE + MAX_ROWS) {
            int row = id - BUTTON_TRAIN_LINK_BASE;
            if (row >= rowKeys.size()) {
                return false;
            }
            BlockPos key = rowKeys.get(row);
            if (!controller.toggleDoorTrainLink(key)) {
                refreshData();
                broadcastChanges();
                return false;
            }
            boolean linked = controller.isTrainLinked(key);
            player.displayClientMessage(Component.translatable(linked
                    ? "gui.createmetro.controller.train_link.on_msg"
                    : "gui.createmetro.controller.train_link.off_msg", row + 1), true);
            refreshData();
            broadcastChanges();
            return true;
        }
        switch (id) {
            case BUTTON_OPEN_ALL -> controller.setGroupOpen(true);
            case BUTTON_CLOSE_ALL -> controller.setGroupOpen(false);
            default -> {
                return false;
            }
        }
        refreshData();
        broadcastChanges();
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(Vec3.atCenterOf(controllerPos)) <= MAX_INTERACTION_DISTANCE_SQR
            && player.level().getBlockState(controllerPos).getBlock()
                instanceof PlatformScreenDoorControllerBlock;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
