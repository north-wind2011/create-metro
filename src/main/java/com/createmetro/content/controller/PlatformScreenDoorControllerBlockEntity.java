package com.createmetro.content.controller;

import com.createmetro.content.linker.PlatformScreenDoorLinkerItem;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlock;
import com.createmetro.content.platformdoor.TrainDoorBlocklist;
import com.createmetro.registry.CMBlockEntities;
import com.simibubi.create.Create;
import com.simibubi.create.content.decoration.slidingDoor.DoorControl;
import com.simibubi.create.content.decoration.slidingDoor.DoorControlBehaviour;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 屏蔽门联控器方块实体。
 *
 * <p>连接方式为纯手动：玩家手持 {@code createmetro:screen_door_linker} 右键一扇屏蔽门，
 * 这一扇（连同它的配对门）会加入选择；再右键地面放置联控器或右键已放置的联控器，
 * 把那批门写进本方块实体的 NBT。本方块实体<b>不做任何半径扫描/自动登记</b>，
 * 门数只反映实际写入且仍有效的门。</p>
 *
 * <p>写入时会去重、只接受下半格坐标，并校验每扇门到联控器的距离不超过
 * {@link #MAX_DOOR_DISTANCE} 格；持久化时同时记录维度，加载到其它维度会丢弃旧数据。</p>
 *
 * <p>GUI 与服务端都以「门位（slot）」为单位：一个门位是一整对门（左向 + 右向），
 * 没有配对门的那一扇单独成一个门位。批量开合按门位下发，维护中的门位被整体跳过；
 * 维护标记按门位主坐标（{@code long[]}）存 NBT。状态一律读服务端世界的实际方块状态，
 * 不保存缓存副本。</p>
 *
 * <p>当前版本支持两种控制方式：GUI 里的「全部打开 / 全部关闭」手动开关，以及按门位勾选的
 * 列车联动。列车联动为每行一个开关，默认不勾；勾选的门位在<b>联控器附近的车站被列车占用</b>
 * 时打开——判定复用 Create 自己的 {@code GlobalStation.getPresentTrain()}（其内部就是
 * “最近列车且该车 {@code getCurrentStation() == 本站}”），与站台上 Create 滑动门的开门时机
 * 一致，不要求速度归零。拿不到车站信息时退化为“速度≈0 且距离近”的兜底判定。
 * 列车离站后只关闭由联动打开的门位。未勾选的门位、维护中的门位不参与列车联动，
 * 玩家手动开关始终有效。</p>
 */
public class PlatformScreenDoorControllerBlockEntity extends BlockEntity implements MenuProvider {

    public enum Mode {
        MANUAL,
        TRAIN_AUTO
    }

    /** 单扇门到联控器的最大允许距离（格）。 */
    public static final double MAX_DOOR_DISTANCE = 256.0;

    /**
     * 联控器到 Create 车站方块的最大距离（格）。48 格 ≈ 站台长度量级（Create 单节车厢约 6–8 格，
     * 常见 4–6 节编组），用于把联控器与车站关联起来；列车是否停靠由 Create 的 GlobalStation
     * 停靠状态判定，不再用速度/距离猜。
     */
    public static final double TRAIN_LINK_RADIUS = 48.0;

    /**
     * 兜底判定用的速度阈值：只有在联控器 {@link #TRAIN_LINK_RADIUS} 格内找不到任何已注册车站时，
     * 才退回“|speed| ≤ 0.05 且列车在半径内”的近似。实测 Create 停车阶段 speed 会短暂停在
     * 很小的非零值（不是精确 0），故不再使用 0.01。
     */
    private static final double TRAIN_STOP_SPEED = 0.05;

    /** 无列车时，每 30 秒打印一次实际读到的车站/列车观测值，便于排查。 */
    private static final int TRAIN_SCAN_LOG_INTERVAL = 600;

    private static final String KEY_DOORS = "Doors";
    /** 维护中的门位主坐标（long[]，存下半格坐标）。 */
    private static final String KEY_MAINTENANCE = "MaintenanceSlots";
    /** 旧版本按 Doors 索引存的 int[] 维护标记，仅用于兼容老存档读取。 */
    private static final String KEY_MAINTENANCE_LEGACY = "Maintenance";
    private static final String KEY_DIMENSION = "Dim";
    private static final String KEY_MODE = "Mode";
    private static final String KEY_OPEN = "Open";
    /** 勾选列车联动的门位主坐标（long[]）。老存档没有该字段 = 全部不勾。 */
    private static final String KEY_TRAIN_LINKAGE = "TrainLinkage";
    /** 由列车联动打开、需要在列车离站时收回的门位主坐标（long[]）。 */
    private static final String KEY_LINKED_OPEN = "LinkedOpen";
    /** 被本联控器强制设为 NONE 的车站方块位置/维度，以及被覆盖前的 DoorControl 名称。 */
    private static final String KEY_DOOR_CONTROL_POS = "DoorCtlPos";
    private static final String KEY_DOOR_CONTROL_DIM = "DoorCtlDim";
    private static final String KEY_DOOR_CONTROL_MODE = "DoorCtlMode";
    private static final String KEY_DOOR_CONTROL_STATION = "DoorCtlStation";
    private static final String KEY_DOOR_CONTROL_APPLIED = "DoorCtlApplied";

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateMetro/Linker");

    /** 门位主坐标选取顺序：让同一对门永远得到同一个主坐标，NBT 键稳定。 */
    private static final Comparator<BlockPos> PRIMARY_ORDER = Comparator
        .<BlockPos>comparingInt(BlockPos::getZ)
        .thenComparingInt(BlockPos::getX)
        .thenComparingInt(BlockPos::getY);

    private final List<BlockPos> doors = new ArrayList<>();
    /** 维护中的门位（键为主坐标）。维护门位不参与联控器批量开合，也不参与列车自动开门。 */
    private final Set<BlockPos> maintenanceSlots = new LinkedHashSet<>();
    /** 勾选「列车联动」的门位（键为主坐标）。默认不勾。 */
    private final Set<BlockPos> trainLinkSlots = new LinkedHashSet<>();
    /** 由列车联动打开、需要在列车离站时收回的门位（键为主坐标）。 */
    private final Set<BlockPos> linkedOpenSlots = new LinkedHashSet<>();
    private Mode mode = Mode.MANUAL;
    private boolean groupOpen;
    /** 上一次检测到的是否为「列车停靠中」，用于只在到站/离站的瞬间动作。 */
    private boolean trainDocked;
    /** 无列车时的诊断日志节流计数。 */
    private int trainScanTimer;
    /** 联动过滤自检每次加载只跑一次。 */
    private boolean linkageSelfTestDone;
    /** 被本联控器强制设为 NONE 的车站；为空表示当前没有强锁。 */
    @Nullable
    private BlockPos doorControlStationPos;
    private String doorControlStationDim = "";
    private String doorControlStationName = "";
    /** 强锁前车站 DoorControl 的原值；非空即表示当前处于强锁。 */
    private String originalDoorControl = "";
    /** 本次加载后是否已把 NONE 真正写进车站（重载后需要重新写入）。 */
    private boolean doorControlApplied;
    /** 站点 mode 观测：用于发现被 Create/其它逻辑改回 ALL。 */
    private String lastObservedDoorControl = "";
    private String lastObservedStation = "";
    private int doorControlObserveTimer;
    /** 联锁状态快照的上一行内容；内容变化即打，无变化时限频。 */
    private String lastDoorControlStateLog = "";
    private int doorControlStateTimer;
    private boolean doorControlSelfTestDone;
    /** 上一 tick 已发布的“禁止开”门位；用于把解除禁止的门位从列车门拦截名单里移除。 */
    private java.util.Set<BlockPos> lastBlockedDoorPositions = java.util.Set.of();
    /** 到站期逐次诊断日志（限流，不每 tick 刷屏）。 */
    private String lastArrivalLog = "";
    private int arrivalTimer;
    private static final int ARRIVAL_LOG_INTERVAL = 200;
    private static final int DOOR_CONTROL_OBSERVE_INTERVAL = 600;
    /** 持久化时记录的门列表所属维度。 */
    private String dimensionId = "";

    /**
     * GUI 用的门位快照：一行为一整对门（{@code second} 为 {@code null} 表示没有配对门）。
     * 开合状态是调用时从服务端世界读出的实际方块状态，不是缓存副本。
     */
    public record DoorSlot(BlockPos first, @Nullable BlockPos second,
                           boolean firstOpen, boolean secondOpen, boolean maintenance,
                           boolean trainLinked) {

        public boolean isPair() {
            return second != null;
        }

        public boolean fullyOpen() {
            return firstOpen && (second == null || secondOpen);
        }

        public boolean anyOpen() {
            return firstOpen || (second != null && secondOpen);
        }
    }

    public PlatformScreenDoorControllerBlockEntity(BlockPos pos, BlockState state) {
        super(CMBlockEntities.PLATFORM_SCREEN_DOOR_CONTROLLER.get(), pos, state);
    }

    // ---------------------------------------------------------------- 连接写入

    /** 用屏蔽器写入的门坐标（下半格）整体替换当前连接，并立刻套用当前开合状态。 */
    public void bindDoors(Collection<BlockPos> doorPositions) {
        doors.clear();
        maintenanceSlots.clear();
        trainLinkSlots.clear();
        linkedOpenSlots.clear();
        trainDocked = false;
        if (level == null || level.isClientSide) {
            setChanged();
            return;
        }
        Set<BlockPos> unique = new LinkedHashSet<>();
        for (BlockPos pos : doorPositions) {
            if (isValidDoor(pos)) {
                unique.add(pos.immutable());
            }
        }
        doors.addAll(unique);
        setChanged();
        // 立刻生效：新连接的门与联控器当前的开/关状态保持一致。
        setGroupOpen(groupOpen);
        LOGGER.info("[Controller] {} bound {} door(s) -> {} slot(s) (submitted {})",
            worldPosition.toShortString(), doors.size(), getDoorSlots().size(), doorPositions.size());
    }

    /** 移除已经被破坏/换成其它方块、超出距离上限、或不属于当前维度的门。 */
    public void prune() {
        if (level == null || level.isClientSide) {
            return;
        }
        String current = level.dimension().location().toString();
        if (!dimensionId.isEmpty() && !dimensionId.equals(current)) {
            LOGGER.warn("[Controller] {} door list belongs to {}, controller is in {}; discarding",
                worldPosition.toShortString(), dimensionId, current);
            doors.clear();
            maintenanceSlots.clear();
            trainLinkSlots.clear();
            linkedOpenSlots.clear();
            dimensionId = current;
            setChanged();
            return;
        }
        // 门被拆掉/换成别的方块/超出距离：对应的行（门位）自动剔除。
        boolean changed = false;
        Iterator<BlockPos> iterator = doors.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (!isValidDoor(pos)) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) {
            // 门位已经消失：同步丢弃它的联动与「联动已打开」标记，避免 NBT 里留下孤儿坐标。
            trainLinkSlots.removeIf(pos -> !doors.contains(pos));
            linkedOpenSlots.removeIf(pos -> !doors.contains(pos));
            setChanged();
        }
    }

    private boolean isValidDoor(BlockPos pos) {
        if (level == null || level.isClientSide) {
            return false;
        }
        if (pos.distSqr(worldPosition) > MAX_DOOR_DISTANCE * MAX_DOOR_DISTANCE) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PlatformScreenDoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    /** 已连接的单扇门数量（屏蔽器的提示与日志使用）。 */
    public int getDoorCount() {
        prune();
        return doors.size();
    }

    /** 已连接的门位数量：一对门算 1，没有配对门的单扇也算 1。 */
    public int getSlotCount() {
        return getDoorSlots().size();
    }

    /** 维护中的门位数量。 */
    public int getMaintenanceCount() {
        int count = 0;
        for (DoorSlot slot : getDoorSlots()) {
            if (slot.maintenance()) {
                count++;
            }
        }
        return count;
    }

    /**
     * GUI 用的门位快照：先按距离（再按坐标）排序，再把同一对门合并成一行。
     * OPEN 直接读服务端世界的实际方块状态；已被拆掉的门在 {@link #prune()} 里剔除。
     */
    public List<DoorSlot> getDoorSlots() {
        prune();
        List<BlockPos> sorted = new ArrayList<>(doors);
        sorted.sort(Comparator
            .comparingDouble((BlockPos pos) -> pos.distSqr(worldPosition))
            .thenComparingInt(BlockPos::getZ)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY));

        Set<BlockPos> consumed = new LinkedHashSet<>();
        List<DoorSlot> slots = new ArrayList<>();
        for (BlockPos pos : sorted) {
            if (consumed.contains(pos)) {
                continue;
            }
            BlockPos partner = null;
            if (level != null) {
                BlockState state = level.getBlockState(pos);
                BlockPos candidate = PlatformScreenDoorLinkerItem.findPartnerLower(level, pos, state);
                if (candidate != null && isValidDoor(candidate)) {
                    partner = candidate.immutable();
                }
            }
            // 先把这一对的两扇都标记为已处理，避免配对门再单独成一行。
            if (partner != null) {
                consumed.add(partner);
            }
            consumed.add(pos);
            BlockPos first = pos;
            BlockPos second = partner;
            if (second != null && PRIMARY_ORDER.compare(second, first) < 0) {
                BlockPos swap = first;
                first = second;
                second = swap;
            }
            slots.add(new DoorSlot(first, second, readOpen(first),
                second != null && readOpen(second),
                isFlagged(maintenanceSlots, first, second),
                isFlagged(trainLinkSlots, first, second)));
        }
        slots.sort(Comparator
            .comparingDouble((DoorSlot slot) -> slot.first().distSqr(worldPosition))
            .thenComparingInt(slot -> slot.first().getZ())
            .thenComparingInt(slot -> slot.first().getX())
            .thenComparingInt(slot -> slot.first().getY()));
        return slots;
    }

    private boolean readOpen(BlockPos pos) {
        if (level == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PlatformScreenDoorBlock && state.getValue(DoorBlock.OPEN);
    }

    private static boolean isFlagged(Set<BlockPos> keys, BlockPos first, @Nullable BlockPos second) {
        return keys.contains(first) || (second != null && keys.contains(second));
    }

    /**
     * 把标记规范到门位主坐标：先清掉整对门上的旧键，需要时再写 {@code first}。
     * 这样即使配对关系/主坐标发生变化，勾选与维护也不会“漂”到另一扇门。
     */
    private static void normalizeFlag(Set<BlockPos> keys, DoorSlot slot, boolean present) {
        keys.remove(slot.first());
        if (slot.second() != null) {
            keys.remove(slot.second());
        }
        if (present) {
            keys.add(slot.first());
        }
    }

    /**
     * 把 GUI 行号解析成当前服务端列表里的门位。GUI 显示与服务端写入都必须走这一份
     * {@link #getDoorSlots()} 排序结果，避免“界面排序、后台另一份顺序”造成勾选串行。
     */
    @Nullable
    public DoorSlot resolveSlot(int index) {
        List<DoorSlot> slots = getDoorSlots();
        if (index < 0 || index >= slots.size()) {
            return null;
        }
        return slots.get(index);
    }

    /** 按门位主坐标（而不是行号）解析：门被拆掉/重连导致行号变化时，坐标仍是稳定键。 */
    @Nullable
    public DoorSlot resolveSlotByKey(BlockPos key) {
        for (DoorSlot slot : getDoorSlots()) {
            if (slot.first().equals(key)) {
                return slot;
            }
        }
        return null;
    }

    public boolean isTrainLinked(BlockPos key) {
        return trainLinkSlots.contains(key);
    }

    public boolean isMaintenance(BlockPos key) {
        return maintenanceSlots.contains(key);
    }

    /**
     * 切换 GUI 列表里第 {@code index} 个门位（顺序与 {@link #getDoorSlots()} 一致）的维护标记。
     * 整对门共用同一个维护标记；维护只把它们排除在批量开合之外，当前开合状态保持不动。
     */
    public boolean toggleDoorMaintenance(int index) {
        DoorSlot slot = resolveSlot(index);
        return slot != null && toggleDoorMaintenance(slot.first());
    }

    /** 按门位主坐标切换维护标记（服务端权威入口，供菜单使用）。 */
    public boolean toggleDoorMaintenance(BlockPos key) {
        DoorSlot slot = resolveSlotByKey(key);
        if (slot == null) {
            LOGGER.warn("[Controller] {} maintenance toggle rejected: slot key {} not found",
                worldPosition.toShortString(), key.toShortString());
            return false;
        }
        boolean now = !isFlagged(maintenanceSlots, slot.first(), slot.second());
        normalizeFlag(maintenanceSlots, slot, now);
        setChanged();
        LOGGER.info("[Controller] {} slot [{}] maintenance={} (pair={}, open={}/{}, maintenance {}/{} slot(s))",
            worldPosition.toShortString(), describeSlot(slot), now, slot.isPair(),
            slot.firstOpen(), slot.secondOpen(), getMaintenanceCount(), getDoorSlots().size());
        return true;
    }

    /**
     * 切换 GUI 列表里第 {@code index} 个门位的「列车联动」开关（默认不勾）。
     * 开关作用于整对门；只是记录意愿，真正的开门由 {@link #tick()} 在列车停靠时执行。
     */
    public boolean toggleDoorTrainLink(int index) {
        DoorSlot slot = resolveSlot(index);
        return slot != null && toggleDoorTrainLink(slot.first());
    }

    /** 按门位主坐标切换列车联动（服务端权威入口，供菜单使用）。 */
    public boolean toggleDoorTrainLink(BlockPos key) {
        DoorSlot slot = resolveSlotByKey(key);
        if (slot == null) {
            LOGGER.warn("[Controller] {} train-link toggle rejected: slot key {} not found",
                worldPosition.toShortString(), key.toShortString());
            return false;
        }
        boolean now = !isFlagged(trainLinkSlots, slot.first(), slot.second());
        normalizeFlag(trainLinkSlots, slot, now);
        setChanged();
        LOGGER.info("[Controller] {} slot [{}] trainLink={} (pair={}, linked {}/{} slot(s))",
            worldPosition.toShortString(), describeSlot(slot), now, slot.isPair(),
            trainLinkSlots.size(), getDoorSlots().size());
        return true;
    }

    public int getTrainLinkedCount() {
        return trainLinkSlots.size();
    }

    private static String describeSlot(DoorSlot slot) {
        return slot.second() == null
            ? slot.first().toShortString()
            : slot.first().toShortString() + "+" + slot.second().toShortString();
    }

    // ---------------------------------------------------------------- 开合控制

    public boolean isGroupOpen() {
        return groupOpen;
    }

    public void toggle() {
        setGroupOpen(!groupOpen);
    }

    /** 服务端权威地设置整组门位的开合状态；维护中的门位整体跳过。 */
    public void setGroupOpen(boolean open) {
        groupOpen = open;
        setChanged();
        if (level == null || level.isClientSide) {
            return;
        }
        List<DoorSlot> slots = getDoorSlots();
        int applied = 0;
        int skipped = 0;
        List<String> appliedSlots = new ArrayList<>();
        List<String> skippedSlots = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            DoorSlot slot = slots.get(i);
            if (slot.maintenance()) {
                skipped++;
                skippedSlots.add(describeSlot(slot));
                continue;
            }
            // 整对下发：先第一扇，再补第二扇（门自身也会同步配对门，这里保证不漏）。
            setDoorOpen(slot.first(), open, "controller", slot.trainLinked(), false, i + 1);
            if (slot.second() != null) {
                setDoorOpen(slot.second(), open, "controller", slot.trainLinked(), false, i + 1);
            }
            applied++;
            appliedSlots.add(describeSlot(slot));
        }
        LOGGER.info("[Controller] {} group open={} applied to {} slot(s) {}, skipped {} maintenance slot(s) {}",
            worldPosition.toShortString(), open, applied, appliedSlots, skipped, skippedSlots);
    }

    private void setDoorOpen(BlockPos pos, boolean open, String by, boolean trainLinked,
                             boolean maintenance, int slotIndex) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PlatformScreenDoorBlock door)) {
            return;
        }
        if (state.getValue(DoorBlock.OPEN) == open) {
            return;
        }
        // setOpen 会播放音效，并把同一对门的另一扇一起带动；日志在 block#setOpen 里统一打印。
        door.setOpen(null, level, state, pos, open, by, trainLinked, maintenance, slotIndex);
    }

    // ---------------------------------------------------------------- 列车联动

    /**
     * 服务端每 tick 由方块的 ticker 转发。
     *
     * <p>存在勾选「列车联动」的门位（或有待收回的联动门位）时<b>每 tick</b>检测一次，
     * 避免列车停站时间短被限频漏掉；空闲时每 30 秒打印一次观测日志。
     * 开门/关门只在“停靠中 ↔ 未停靠”跳变时下发。</p>
     */
    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (!linkageSelfTestDone) {
            linkageSelfTestDone = true;
            runLinkageSelfTest();
        }
        updateStationDoorControl();
        guardDisallowedSlots();
        boolean active = !trainLinkSlots.isEmpty() || !linkedOpenSlots.isEmpty();
        if (!active) {
            trainDocked = false;
            if (++trainScanTimer >= TRAIN_SCAN_LOG_INTERVAL) {
                trainScanTimer = 0;
                logTrainScan("idle", detectDockedTrain());
            }
            return;
        }
        TrainStop stop = detectDockedTrain();
        if (stop != null) {
            trainScanTimer = 0;
            if (!trainDocked) {
                trainDocked = true;
                openLinkedSlots(stop);
            }
        } else {
            if (trainDocked || !linkedOpenSlots.isEmpty()) {
                // 列车离站，或区块重载后没有列车：收回由联动打开的门位。
                trainDocked = false;
                closeLinkedSlots();
            }
            if (++trainScanTimer >= TRAIN_SCAN_LOG_INTERVAL) {
                trainScanTimer = 0;
                logTrainScan("linked", null);
            }
        }
    }

    /**
     * 判定「本联控器旁的车站是否被列车占用」，直接复用 Create 的停靠状态：
     * 遍历 {@code Create.RAILWAYS.sided(level).trackNetworks} 中每条 {@link TrackGraph} 的
     * {@link EdgePointType#STATION} 点，取同维度、距联控器 ≤ {@link #TRAIN_LINK_RADIUS} 格的
     * 最近车站，其 {@link GlobalStation#getPresentTrain()} 非空即视为停靠中。
     *
     * <p>依据（Create 6.0.11 字节码）：{@code GlobalStation.getPresentTrain()} 就是
     * {@code nearestTrain != null && nearestTrain.getCurrentStation() == this}；而
     * {@code Train.arriveAt(GlobalStation)} 在列车接近站点时就 {@code setCurrentStation(station)}
     * （此时速度往往还没归零）。站台上的 Create 滑动门（{@code SlidingDoorMovementBehaviour#
     * getTrainStationDoorControl}）也是读 {@code carriage.train.getCurrentStation()} 决定开门，
     * 所以这里用同一个状态，开门时机与 Create 站台门一致。</p>
     *
     * <p>{@code nearestTrain} 可能被别的列车占用而返回 null，因此额外按
     * {@code Train.currentStation == station.id} 找占用车（与 Create 车门路径等价）。
     * 半径内完全没有已注册车站时，才退回“|speed| ≤ {@link #TRAIN_STOP_SPEED} 且距离近”的兜底。</p>
     */
    @Nullable
    private TrainStop detectDockedTrain() {
        GlobalRailwayManager railways = Create.RAILWAYS.sided(level);
        if (railways == null) {
            return null;
        }
        GlobalStation bestStation = null;
        Train bestTrain = null;
        boolean bestPresentMatched = false;
        double bestDistSqr = Double.MAX_VALUE;
        boolean foundStation = false;
        for (TrackGraph graph : railways.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (!isStationInThisDimension(station)) {
                    continue;
                }
                BlockPos stationPos = station.getBlockEntityPos();
                if (stationPos == null) {
                    continue;
                }
                double distSqr = stationPos.distSqr(worldPosition);
                if (distSqr > TRAIN_LINK_RADIUS * TRAIN_LINK_RADIUS) {
                    continue;
                }
                foundStation = true;
                Train present = station.getPresentTrain();
                boolean matched = present != null && !present.invalid && !present.derailed;
                Train occupant = matched ? present : findTrainByCurrentStation(railways, station);
                if (occupant == null || occupant.invalid || occupant.derailed) {
                    continue;
                }
                if (distSqr < bestDistSqr) {
                    bestDistSqr = distSqr;
                    bestStation = station;
                    bestTrain = occupant;
                    bestPresentMatched = matched && occupant == present;
                }
            }
        }
        if (bestTrain != null) {
            return new TrainStop(bestTrain.name.getString(), Math.abs(bestTrain.speed),
                Math.sqrt(bestDistSqr), bestStation.name, bestPresentMatched,
                bestPresentMatched ? "station-present" : "station-current");
        }
        if (foundStation) {
            // 附近有车站但没车占用：不启用速度兜底，避免“车停在站外”被误判为停靠。
            return null;
        }
        return detectStoppedTrainFallback();
    }

    private boolean isStationInThisDimension(GlobalStation station) {
        return station.getBlockEntityDimension() != null
            && station.getBlockEntityDimension().location().equals(level.dimension().location());
    }

    @Nullable
    private static Train findTrainByCurrentStation(GlobalRailwayManager railways, GlobalStation station) {
        for (Train train : railways.trains.values()) {
            if (train.currentStation != null && train.currentStation.equals(station.id)) {
                return train;
            }
        }
        return null;
    }

    /** 半径内没有已注册车站时的兜底：|speed| ≤ {@link #TRAIN_STOP_SPEED} 且在半径内的最近列车。 */
    @Nullable
    private TrainStop detectStoppedTrainFallback() {
        GlobalRailwayManager railways = Create.RAILWAYS.sided(level);
        if (railways == null) {
            return null;
        }
        Vec3 center = Vec3.atCenterOf(worldPosition);
        Train nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (Train train : railways.trains.values()) {
            if (train.invalid || train.derailed || Math.abs(train.speed) > TRAIN_STOP_SPEED) {
                continue;
            }
            float distSqr = train.distanceToLocationSqr(level, center);
            if (distSqr > TRAIN_LINK_RADIUS * TRAIN_LINK_RADIUS || distSqr >= nearestSqr) {
                continue;
            }
            nearestSqr = distSqr;
            nearest = train;
        }
        if (nearest == null) {
            return null;
        }
        GlobalStation station = nearest.getCurrentStation();
        return new TrainStop(nearest.name.getString(), Math.abs(nearest.speed),
            Math.sqrt(nearestSqr), station == null ? "-" : station.name, false, "fallback-speed");
    }

    /**
     * 打出实际观测值：最近车站、站内是否有车占用、最近列车与速度/脱轨/非法状态、
     * {@code getCurrentStation()} 以及最终判定。没有列车时也会周期打印，用于证明值确实被读到。
     */
    private void logTrainScan(String reason, @Nullable TrainStop hit) {
        GlobalRailwayManager railways = Create.RAILWAYS.sided(level);
        if (railways == null) {
            LOGGER.info("[TrainScan] {} reason={} result=no-railway-manager",
                worldPosition.toShortString(), reason);
            return;
        }
        GlobalStation nearestStation = null;
        double nearestStationSqr = Double.MAX_VALUE;
        for (TrackGraph graph : railways.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (!isStationInThisDimension(station) || station.getBlockEntityPos() == null) {
                    continue;
                }
                double distSqr = station.getBlockEntityPos().distSqr(worldPosition);
                if (distSqr < nearestStationSqr) {
                    nearestStationSqr = distSqr;
                    nearestStation = station;
                }
            }
        }
        Vec3 center = Vec3.atCenterOf(worldPosition);
        Train nearestTrain = null;
        double nearestTrainSqr = Double.MAX_VALUE;
        for (Train train : railways.trains.values()) {
            float distSqr = train.distanceToLocationSqr(level, center);
            if (distSqr < nearestTrainSqr) {
                nearestTrainSqr = distSqr;
                nearestTrain = train;
            }
        }
        Train present = nearestStation == null ? null : nearestStation.getPresentTrain();
        LOGGER.info("[TrainScan] {} reason={} station={} stationDist={} stationInRadius={} "
                + "presentTrain={} nearestTrain={} trainDist={} speed={} derailed={} invalid={} "
                + "currentStation={} verdict={}",
            worldPosition.toShortString(), reason,
            nearestStation == null ? "none" : nearestStation.name,
            nearestStation == null ? "-" : String.format("%.1f", Math.sqrt(nearestStationSqr)),
            nearestStation != null && nearestStationSqr <= TRAIN_LINK_RADIUS * TRAIN_LINK_RADIUS,
            present == null ? "none" : present.name.getString(),
            nearestTrain == null ? "none" : nearestTrain.name.getString(),
            nearestTrain == null ? "-" : String.format("%.1f", Math.sqrt(nearestTrainSqr)),
            nearestTrain == null ? "-" : String.format("%.3f", nearestTrain.speed),
            nearestTrain != null && nearestTrain.derailed,
            nearestTrain != null && nearestTrain.invalid,
            nearestTrain == null || nearestTrain.getCurrentStation() == null
                ? "none" : nearestTrain.getCurrentStation().name,
            hit != null ? "DOCKED" : "not-docked");
    }

    /** 联动开门的唯一裁决：维护优先排除，其次要求勾选。GUI、自检、实际开门都调用它。 */
    private static boolean isLinkageAllowed(DoorSlot slot) {
        return !slot.maintenance() && slot.trainLinked();
    }

    /** 只打开「已勾选列车联动且非维护」的门位；未勾选与维护门位保持不动，并逐门打印裁决。 */
    private void openLinkedSlots(TrainStop stop) {
        List<String> opened = new ArrayList<>();
        List<String> unlinked = new ArrayList<>();
        List<String> maintenance = new ArrayList<>();
        List<DoorSlot> slots = getDoorSlots();
        for (int i = 0; i < slots.size(); i++) {
            DoorSlot slot = slots.get(i);
            int slotNo = i + 1;
            if (!isLinkageAllowed(slot)) {
                String reason = slot.maintenance() ? "maintenance" : "not-linked";
                for (BlockPos pos : doorPositions(slot)) {
                    LOGGER.info("[Train] skip {} slot={} key={} trainLinked={} maintenance={} "
                            + "by=train allowed=false reason={}",
                        pos.toShortString(), slotNo, slot.first().toShortString(),
                        slot.trainLinked(), slot.maintenance(), reason);
                }
                (slot.maintenance() ? maintenance : unlinked).add(describeSlot(slot));
                continue;
            }
            for (BlockPos pos : doorPositions(slot)) {
                setDoorOpen(pos, true, "train", true, false, slotNo);
                LOGGER.info("[Train] open {} slot={} key={} trainLinked=true maintenance=false "
                    + "by=train allowed=true", pos.toShortString(), slotNo, slot.first().toShortString());
            }
            linkedOpenSlots.add(slot.first());
            opened.add(describeSlot(slot));
        }
        // 实测自检（真实世界断言）：允许门位开门后，被禁门位（维护/未勾选）的每一扇门都必须仍是关闭的。
        // 配对递归曾是绕过过滤的隐患，这里逐扇读回实际方块状态并给出 pass/fail，而不是只看裁决结果。
        int disallowedDoors = 0;
        int disallowedOpen = 0;
        for (int i = 0; i < slots.size(); i++) {
            DoorSlot slot = slots.get(i);
            if (isLinkageAllowed(slot)) {
                continue;
            }
            for (BlockPos pos : doorPositions(slot)) {
                disallowedDoors++;
                if (readOpen(pos)) {
                    disallowedOpen++;
                    LOGGER.warn("[Train][Assert] VIOLATION door {} slot={} open={} after allowed-only open",
                        pos.toShortString(), i + 1, true);
                }
            }
        }
        LOGGER.info("[Train][Assert] pass={} disallowed-closed={}/{} disallowed-open={}",
            disallowedOpen == 0, disallowedDoors - disallowedOpen, disallowedDoors, disallowedOpen);
        setChanged();
        LOGGER.info("[Controller] {} train '{}' docked via={} station='{}' stationDist={}m speed={} "
                + "presentTrainMatches={}: opened {} linked slot(s) {}, excluded {} unlinked {}, "
                + "{} maintenance {}",
            worldPosition.toShortString(), stop.name(), stop.via(), stop.station(),
            String.format("%.1f", stop.distance()), String.format("%.3f", stop.speed()),
            stop.presentTrainMatched(), opened.size(), opened,
            unlinked.size(), unlinked, maintenance.size(), maintenance);
    }

    private static List<BlockPos> doorPositions(DoorSlot slot) {
        return slot.second() == null ? List.of(slot.first()) : List.of(slot.first(), slot.second());
    }

    /**
     * 最终兜底：被禁门位（维护 / 未勾选列车联动）只要实际仍是 {@code OPEN=true}，就立即关回并记录来源。
     *
     * <p>为什么必须有这一步：前两轮只堵了“新的开门请求”（外部 5 参 {@code setOpen} 拒绝、整对下发
     * 禁用配对递归）。但 {@code OPEN} 是写进存档的持久状态：旧版本/旧会话把门打开后会原样保存，
     * 重载后没有任何事件会把它关回去（Create 只在列车离站时补发一次 close，见 00:19:17 日志：
     * 4 次 open 被拒绝后 56ms 又收到 close 且 {@code from=false}——证明门在加载时本来就是开着的）。
     * 同理，玩家在门还开着的时侯把门位改成维护/取消勾选，也没有任何逻辑会收回。</p>
     *
     * <p>豁免 {@code by=controller}：那是玩家在联控器上显式“全部打开”，属于手动操作，不应被自动收回。
     * 其余来源（train / external / redstone / unknown-persisted）一律关回并打 {@code [DoorGuard]} 日志。</p>
     */
    private void guardDisallowedSlots() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (trainLinkSlots.isEmpty() && maintenanceSlots.isEmpty()) {
            return;
        }
        List<DoorSlot> slots = getDoorSlots();
        int closed = 0;
        for (int i = 0; i < slots.size(); i++) {
            DoorSlot slot = slots.get(i);
            if (isLinkageAllowed(slot)) {
                continue;
            }
            for (BlockPos pos : doorPositions(slot)) {
                if (!readOpen(pos)) {
                    continue;
                }
                String opener = PlatformScreenDoorBlock.lastOpener(pos);
                if (opener.startsWith("by=controller")) {
                    continue;
                }
                LOGGER.warn("[DoorGuard] {} slot={} key={} maintenance={} trainLinked={} force-close "
                        + "actualOpen=true source={}",
                    pos.toShortString(), i + 1, slot.first().toShortString(), slot.maintenance(),
                    slot.trainLinked(), opener);
                setDoorOpen(pos, false, "guard", slot.trainLinked(), slot.maintenance(), i + 1);
                closed++;
            }
        }
        if (closed > 0) {
            LOGGER.info("[DoorGuard] reconcile: closed {} open door(s) on maintenance/not-linked slot(s)",
                closed);
            setChanged();
        }
    }

    /**
     * 联动过滤自检：构造 3 个门位（仅第 2 个勾选联动、第 3 个维护），断言只有第 2 个被允许。
     * 只做裁决级断言，不触碰世界方块；每次加载后打一次日志。
     */
    private void runLinkageSelfTest() {
        List<DoorSlot> sample = List.of(
            new DoorSlot(BlockPos.ZERO, null, false, false, false, false),
            new DoorSlot(BlockPos.ZERO.above(), null, false, false, false, true),
            new DoorSlot(BlockPos.ZERO.east(), null, false, false, true, false));
        boolean a0 = isLinkageAllowed(sample.get(0));
        boolean a1 = isLinkageAllowed(sample.get(1));
        boolean a2 = isLinkageAllowed(sample.get(2));
        boolean pass = !a0 && a1 && !a2;
        LOGGER.info("[Train][Selftest] pass={} expected=[false, true, false] actual=[{}, {}, {}] "
                + "case=3slots-only#2-linked", pass, a0, a1, a2);
    }

    /** 只关闭由列车联动打开的门位（不碰玩家手动打开的门）。 */
    private void closeLinkedSlots() {
        List<String> closed = new ArrayList<>();
        List<DoorSlot> slots = getDoorSlots();
        for (int i = 0; i < slots.size(); i++) {
            DoorSlot slot = slots.get(i);
            if (!linkedOpenSlots.contains(slot.first())) {
                continue;
            }
            setDoorOpen(slot.first(), false, "train", true, slot.maintenance(), i + 1);
            if (slot.second() != null) {
                setDoorOpen(slot.second(), false, "train", true, slot.maintenance(), i + 1);
            }
            closed.add(describeSlot(slot));
        }
        linkedOpenSlots.clear();
        setChanged();
        LOGGER.info("[Controller] {} train no longer docked: closed {} linkage-opened slot(s) {}",
            worldPosition.toShortString(), closed.size(), closed);
    }

    // ------------------------------------------------------------ 车站级车门联锁

    /**
     * 车站级车门联锁：本联控器配置了联动（至少一个勾选）且存在“维护/未勾选”的门位时，
     * 把半径内最近车站的 {@link DoorControlBehaviour#mode} 设为 {@link DoorControl#NONE}，
     * 让 Create 的列车车门也不开；所有门位都允许后恢复原值。
     *
     * <p>Create 只提供整站级门控（ALL / NONE / 四个方向），没有“第 N 扇车门”这种粒度，
     * 因此这里只能整站开关。未配置联动的联控器完全不碰车站，避免影响同站其它设备。</p>
     */
    private void updateStationDoorControl() {
        if (level == null || level.isClientSide) {
            return;
        }
        runDoorControlRestoreSelfTest();
        if (doors.isEmpty() && originalDoorControl.isEmpty() && lastBlockedDoorPositions.isEmpty()) {
            return;
        }
        GlobalRailwayManager railways = Create.RAILWAYS.sided(level);
        GlobalStation station = railways == null ? null : findDoorControlStation(railways);
        if (railways != null && station != null) {
            Train arrivalTrain = station.getPresentTrain();
            if (arrivalTrain == null) {
                arrivalTrain = findTrainByCurrentStation(railways, station);
            }
            logArrival(station, arrivalTrain);
        }
        observeStationDoorControl(station);

        // 旧版本会把车站 mode 强写成 NONE；新方案不再碰车站 mode（列车门改用
        // TrainDoorBlocklist + mixin 按“对应屏蔽门门位”拦截）。这里把历史强锁一次性还原，
        // 避免存档一直卡在 NONE 导致列车门永远不开。
        if (doorControlApplied) {
            restoreStationDoorControl(station, "legacy-forced-none-cleared");
        }

        List<DoorSlot> slots = getDoorSlots();
        List<String> blocked = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        java.util.Set<BlockPos> blockedDoorPositions = new java.util.HashSet<>();
        for (DoorSlot slot : slots) {
            if (isLinkageAllowed(slot)) {
                allowed.add(describeSlot(slot));
            } else {
                blocked.add(describeSlot(slot) + (slot.maintenance() ? "(maintenance)" : "(not-linked)"));
                blockedDoorPositions.addAll(doorPositions(slot));
            }
        }
        publishTrainDoorBlocklist(blockedDoorPositions);

        DoorControlBehaviour behaviour = station == null ? null : stationDoorControl(station);
        String mode = behaviour == null || behaviour.mode == null ? "n/a" : behaviour.mode.name();
        logDoorControlState(station, mode, "keep-user-value", blocked, allowed,
            countOpenDisallowedDoors(slots));
    }

    /** 对外发布“这些屏蔽门门位当前禁止开”，供列车门 mixin 按位置拦截。 */
    private void publishTrainDoorBlocklist(java.util.Set<BlockPos> blockedPositions) {
        for (BlockPos pos : lastBlockedDoorPositions) {
            if (!blockedPositions.contains(pos)) {
                TrainDoorBlocklist.unmark(level, pos);
            }
        }
        for (BlockPos pos : blockedPositions) {
            TrainDoorBlocklist.mark(level, pos);
        }
        lastBlockedDoorPositions = blockedPositions;
    }

    /**
     * 实际读回被禁门位（维护 / 未勾选）里仍处于 OPEN 的门扇数量。
     * 用于 {@code [DoorControl] state} 快照：正常情况下应恒为 0；非 0 说明还有绕过路径。
     */
    private int countOpenDisallowedDoors(List<DoorSlot> slots) {
        int open = 0;
        for (DoorSlot slot : slots) {
            if (isLinkageAllowed(slot)) {
                continue;
            }
            for (BlockPos pos : doorPositions(slot)) {
                if (readOpen(pos)) {
                    open++;
                }
            }
        }
        return open;
    }

    /**
     * 恢复目标值清洗：{@code NONE} 永远不是合法“原值”（它可能来自另一个联控器的整站联锁，
     * 或旧存档第一次锁定时的观测值），按 {@link DoorControl#ALL} 兜底；未知枚举名同样兜底 ALL。
     */
    private static String restoreModeName(String saved) {
        if (saved == null || saved.isEmpty() || DoorControl.NONE.name().equals(saved)) {
            return DoorControl.ALL.name();
        }
        try {
            return DoorControl.valueOf(saved).name();
        } catch (IllegalArgumentException e) {
            return DoorControl.ALL.name();
        }
    }

    /** 存档里的“原值”是否可信：必须是存在的枚举且不是 {@code NONE}。 */
    private static boolean isRestorableMode(String saved) {
        if (saved == null || saved.isEmpty()) {
            return false;
        }
        try {
            return DoorControl.valueOf(saved) != DoorControl.NONE;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 有效且有方向的值（WEST/EAST/NORTH/SOUTH）。 */
    private static boolean isDirectionalMode(String mode) {
        return isRestorableMode(mode) && !DoorControl.ALL.name().equals(mode);
    }

    /** 恢复逻辑自检（每个 BE 只打一次）：证明 NONE 原值会被清洗成 ALL，有效方向值保持原样。 */
    private void runDoorControlRestoreSelfTest() {
        if (doorControlSelfTestDone) {
            return;
        }
        doorControlSelfTestDone = true;
        String noneTarget = restoreModeName(DoorControl.NONE.name());
        String allTarget = restoreModeName(DoorControl.ALL.name());
        String westTarget = restoreModeName(DoorControl.WEST.name());
        boolean noneValid = isRestorableMode(DoorControl.NONE.name());
        boolean allValid = isRestorableMode(DoorControl.ALL.name());
        boolean westValid = isRestorableMode(DoorControl.WEST.name());
        boolean pass = DoorControl.ALL.name().equals(noneTarget)
            && DoorControl.ALL.name().equals(allTarget)
            && DoorControl.WEST.name().equals(westTarget)
            && !noneValid && allValid && westValid;
        LOGGER.info("[DoorControl][Selftest] saved-NONE->{} saved-ALL->{} saved-WEST->{} "
                + "valid(NONE)={} valid(ALL)={} valid(WEST)={} overall={}",
            noneTarget, allTarget, westTarget, noneValid, allValid, westValid, pass);
    }

    /**
     * 每次检测后输出的联锁状态快照（内容变化时必打；无变化时每 30 秒打一次，
     * 避免每 tick 刷屏）。一次就能看清“当前 mode / 目标 mode / 哪些门位在阻断”。
     */
    private void logDoorControlState(GlobalStation station, String mode, String desired,
                                     List<String> blocked, List<String> allowed, int disallowedOpen) {
        String line = "controller=" + worldPosition.toShortString()
            + " station=" + (station == null ? "none" : station.name)
            + " mode=" + mode + " desired=" + desired
            + " blockedSlots=" + blocked.size() + blocked
            + " allowedSlots=" + allowed.size() + allowed
            + " disallowedOpen=" + disallowedOpen;
        boolean changed = !line.equals(lastDoorControlStateLog);
        if (!changed && ++doorControlStateTimer < DOOR_CONTROL_OBSERVE_INTERVAL) {
            return;
        }
        doorControlStateTimer = 0;
        lastDoorControlStateLog = line;
        LOGGER.info("[DoorControl] state {}", line);
    }

    /** 半径内最近的同维度车站；没有则 null。多个车站时只取最近的一个。 */
    @Nullable
    private GlobalStation findNearestStation(GlobalRailwayManager railways) {
        GlobalStation nearest = null;
        double nearestSqr = TRAIN_LINK_RADIUS * TRAIN_LINK_RADIUS;
        for (TrackGraph graph : railways.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (!isStationInThisDimension(station) || station.getBlockEntityPos() == null) {
                    continue;
                }
                double distSqr = station.getBlockEntityPos().distSqr(worldPosition);
                if (distSqr <= nearestSqr) {
                    nearestSqr = distSqr;
                    nearest = station;
                }
            }
        }
        return nearest;
    }

    /**
     * 选择要写入/恢复 doorControls 的车站：Create 的列车门只读
     * {@code train.getCurrentStation()} 指向的那个车站（不是“离控制器最近”）。所以只要有列车
     * 当前站落在联控半径内，就必须优先用那个车站，避免站台两侧各有一个车站时写错对象。
     */
    @Nullable
    private GlobalStation findDoorControlStation(GlobalRailwayManager railways) {
        GlobalStation occupied = null;
        double nearestSqr = TRAIN_LINK_RADIUS * TRAIN_LINK_RADIUS;
        for (Train train : railways.trains.values()) {
            if (train.invalid || train.derailed || train.currentStation == null) {
                continue;
            }
            GlobalStation current = findStationById(railways, train.currentStation);
            if (current == null || !isStationInThisDimension(current)
                || current.getBlockEntityPos() == null) {
                continue;
            }
            double distSqr = current.getBlockEntityPos().distSqr(worldPosition);
            if (distSqr <= nearestSqr) {
                nearestSqr = distSqr;
                occupied = current;
            }
        }
        return occupied != null ? occupied : findNearestStation(railways);
    }

    @Nullable
    private static GlobalStation findStationById(GlobalRailwayManager railways, java.util.UUID id) {
        for (TrackGraph graph : railways.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station.getId().equals(id)) {
                    return station;
                }
            }
        }
        return null;
    }

    /**
     * 到站期间的逐次诊断（限流：内容不变时每 {@link #ARRIVAL_LOG_INTERVAL} tick 打一次）。
     * 关键是 {@code sameTarget}：它对比“我们写入的车站 pos”和“列车 currentStation 的车站 pos”，
     * 若为 false 说明我们写错了对象，Create 读到的仍是另一个车站的 mode。
     */
    private void logArrival(GlobalStation station, @Nullable Train train) {
        if (train == null || station.getBlockEntityPos() == null) {
            return;
        }
        DoorControlBehaviour behaviour = stationDoorControl(station);
        if (behaviour == null) {
            return;
        }
        BlockPos stationPos = station.getBlockEntityPos();
        GlobalStation current = train.getCurrentStation();
        BlockPos currentPos = current == null ? null : current.getBlockEntityPos();
        String mode = behaviour.mode == null ? "null" : behaviour.mode.name();
        String line = "station=" + station.name + " id=" + station.getId()
            + " pos=" + stationPos.toShortString()
            + " mode=" + mode
            + " train=" + train.name.getString()
            + " currentStation=" + (current == null ? "none" : current.getId().toString())
            + " currentStationPos=" + (currentPos == null ? "none" : currentPos.toShortString())
            + " sameTarget=" + (currentPos != null && currentPos.equals(stationPos))
            + " dist=" + String.format("%.1f", Math.sqrt(stationPos.distSqr(worldPosition)))
            + " presentTrain=" + (station.getPresentTrain() != null);
        boolean changed = !line.equals(lastArrivalLog);
        if (!changed && ++arrivalTimer < ARRIVAL_LOG_INTERVAL) {
            return;
        }
        arrivalTimer = 0;
        lastArrivalLog = line;
        LOGGER.info("[DoorControl] arrival {}", line);
    }

    /**
     * 观测最近车站当前的 DoorControl：mode 变化立即打印，否则每 30 秒打印一次，
     * 用于确认 Create 是否在列车到站流程里把 NONE 又改回 ALL。
     */
    private void observeStationDoorControl(@Nullable GlobalStation station) {
        doorControlObserveTimer++;
        if (station == null || station.getBlockEntityPos() == null) {
            return;
        }
        DoorControlBehaviour behaviour = stationDoorControl(station);
        if (behaviour == null) {
            return;
        }
        String name = station.name;
        String mode = behaviour.mode == null ? "null" : behaviour.mode.name();
        boolean changed = !mode.equals(lastObservedDoorControl) || !name.equals(lastObservedStation);
        if (!changed && doorControlObserveTimer < DOOR_CONTROL_OBSERVE_INTERVAL) {
            return;
        }
        doorControlObserveTimer = 0;
        lastObservedDoorControl = mode;
        lastObservedStation = name;
        Train present = station.getPresentTrain();
        LOGGER.info("[DoorControl] observe station={} dist={} mode={} applied={} presentTrain={}",
            name, String.format("%.1f", Math.sqrt(station.getBlockEntityPos().distSqr(worldPosition))),
            mode, doorControlApplied,
            present == null ? "none" : present.name.getString());
    }

    /**
     * 让车站 BE 标脏，确保我们对 {@code doorControls} 的写入进入存档；Create 的 GUI 保存走同一个
     * {@link StationBlockEntity}，两者不会各存一份，冲突只可能来自“谁最后写”，因此这里只写一次。
     */
    private void markStationChanged(BlockPos stationPos) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(stationPos) instanceof StationBlockEntity stationBe) {
            stationBe.setChanged();
        }
    }

    /** 恢复被本联控器覆盖的车站门控；{@code fallbackStation} 仅在缺少记录坐标时用于定位。 */
    private void restoreStationDoorControl(@Nullable GlobalStation fallbackStation, String reason) {
        if (originalDoorControl.isEmpty()) {
            return;
        }
        String saved = originalDoorControl;
        String targetName = restoreModeName(saved);
        String stationName = doorControlStationName;
        BlockPos target = doorControlStationPos;
        if (target == null && fallbackStation != null) {
            target = fallbackStation.getBlockEntityPos();
        }
        String dim = doorControlStationDim;
        if (level == null || level.isClientSide || target == null) {
            // 定位信息不完整：保留记录，等待下一个 tick / 下次加载重试，不能清掉原值。
            LOGGER.warn("[DoorControl] station={} restore deferred reason={} saved={} target={}",
                stationName, reason, saved, target == null ? "none" : target.toShortString());
            return;
        }
        if (!dim.isEmpty() && !dim.equals(level.dimension().location().toString())) {
            LOGGER.warn("[DoorControl] station={} in dimension {}; dropping restore (now {}) saved={}",
                stationName, dim, level.dimension().location(), saved);
            clearDoorControlState();
            setChanged();
            return;
        }
        if (level.getBlockEntity(target) instanceof StationBlockEntity stationBe
            && stationBe.doorControls != null) {
            DoorControlBehaviour behaviour = stationBe.doorControls;
            String currentName = behaviour.mode == null ? "null" : behaviour.mode.name();
            boolean currentNone = DoorControl.NONE.name().equals(currentName);
            boolean currentAll = DoorControl.ALL.name().equals(currentName);
            boolean writeSaved = isRestorableMode(saved)
                && (currentNone || (currentAll && isDirectionalMode(saved)));
            boolean fallbackAll = !isRestorableMode(saved) && currentNone;
            if (writeSaved) {
                // 只在当前是 NONE（我们旧版强锁留下的值），或当前是 ALL 而原值是有方向的值时才写回；
                // 当前已经有方向值时一律保持，避免多联控器互相把用户设置冲掉。
                DoorControl restored = DoorControl.valueOf(saved);
                behaviour.set(restored);
                markStationChanged(target);
                LOGGER.info("[DoorControl] station={} mode={} saved={} reason={}",
                    stationName, restored, saved, reason);
            } else if (fallbackAll) {
                // 原值无效（多半是另一个联控器已把整站写成 NONE）：只有当前确实是 NONE 才用 ALL 兜底。
                behaviour.set(DoorControl.ALL);
                markStationChanged(target);
                LOGGER.info("[DoorControl] station={} mode=ALL saved={} invalid-target=ALL reason={} "
                    + "(was NONE)", stationName, saved, reason);
            } else {
                // 当前值已经是有效方向：不动它，避免把用户手动设置的方向覆盖成别的值。
                LOGGER.info("[DoorControl] station={} mode={} saved={} reason={} (keep current)",
                    stationName, currentName, saved, reason);
            }
            clearDoorControlState();
        } else {
            // 车站方块暂时不可用（区块未加载/已拆除）：保留记录以便重试，而不是把原值丢掉。
            LOGGER.warn("[DoorControl] station={} missing at {}; restore deferred reason={} saved={} target={}",
                stationName, target.toShortString(), reason, saved, targetName);
        }
        setChanged();
    }

    private void clearDoorControlState() {
        doorControlStationPos = null;
        doorControlStationDim = "";
        doorControlStationName = "";
        originalDoorControl = "";
        doorControlApplied = false;
    }

    @Nullable
    private DoorControlBehaviour stationDoorControl(GlobalStation station) {
        BlockPos pos = station.getBlockEntityPos();
        if (pos == null || level == null || !isStationInThisDimension(station)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof StationBlockEntity stationBe
            ? stationBe.doorControls : null;
    }

    /**
     * 联控器方块确实被拆掉/替换时恢复车站门控。由
     * {@link PlatformScreenDoorControllerBlock#onRemove} 调用——不能在 {@link #setRemoved()} 里做：
     * 区块卸载/关世界同样会走 {@code setRemoved}，而那时对 {@code level.getBlockState()} 的查询
     * 会强制重新加载区块，触发 unload → load → unload 的死循环（实测关窗时刷了 1.4 万条日志）。
     */
    public void restoreOnBlockRemoved() {
        publishTrainDoorBlocklist(java.util.Set.of());
        restoreStationDoorControl(null, "controller-removed");
    }

    /**
     * 一次命中记录。
     *
     * @param presentTrainMatched 是否由 {@code GlobalStation.getPresentTrain()} 直接命中该车
     *                           （false 表示走了等价的 {@code Train.currentStation == station.id} 或速度兜底）
     * @param via                 命中路径：station-present / station-current / fallback-speed
     */
    private record TrainStop(String name, double speed, double distance, String station,
                             boolean presentTrainMatched, String via) {
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        setChanged();
    }

    // ---------------------------------------------------------------- 维度校验

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        String current = level.dimension().location().toString();
        if (!dimensionId.isEmpty() && !dimensionId.equals(current)) {
            LOGGER.warn("[Controller] {} door list belongs to {}, loading in {}; discarding",
                worldPosition.toShortString(), dimensionId, current);
            doors.clear();
            maintenanceSlots.clear();
            trainLinkSlots.clear();
            linkedOpenSlots.clear();
            setChanged();
        }
        dimensionId = current;
    }

    // ---------------------------------------------------------------- 持久化

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        long[] positions = new long[doors.size()];
        for (int i = 0; i < doors.size(); i++) {
            positions[i] = doors.get(i).asLong();
        }
        tag.putLongArray(KEY_DOORS, positions);
        // 维护标记按门位主坐标存 long[]：与 Doors 同一坐标索引，去重天然成立。
        long[] maintenanceKeys = new long[maintenanceSlots.size()];
        int i = 0;
        for (BlockPos pos : maintenanceSlots) {
            maintenanceKeys[i++] = pos.asLong();
        }
        tag.putLongArray(KEY_MAINTENANCE, maintenanceKeys);
        tag.remove(KEY_MAINTENANCE_LEGACY);
        tag.putLongArray(KEY_TRAIN_LINKAGE, keysOf(trainLinkSlots));
        tag.putLongArray(KEY_LINKED_OPEN, keysOf(linkedOpenSlots));
        if (level != null) {
            dimensionId = level.dimension().location().toString();
        }
        tag.putString(KEY_DIMENSION, dimensionId);
        tag.putString(KEY_MODE, mode.name());
        tag.putBoolean(KEY_OPEN, groupOpen);
        if (!originalDoorControl.isEmpty() && doorControlStationPos != null) {
            tag.putLong(KEY_DOOR_CONTROL_POS, doorControlStationPos.asLong());
            tag.putString(KEY_DOOR_CONTROL_DIM, doorControlStationDim);
            tag.putString(KEY_DOOR_CONTROL_MODE, originalDoorControl);
            tag.putString(KEY_DOOR_CONTROL_STATION, doorControlStationName);
            tag.putBoolean(KEY_DOOR_CONTROL_APPLIED, doorControlApplied);
        } else {
            tag.remove(KEY_DOOR_CONTROL_POS);
            tag.remove(KEY_DOOR_CONTROL_DIM);
            tag.remove(KEY_DOOR_CONTROL_MODE);
            tag.remove(KEY_DOOR_CONTROL_STATION);
            tag.remove(KEY_DOOR_CONTROL_APPLIED);
        }
    }

    private static long[] keysOf(Set<BlockPos> positions) {
        long[] keys = new long[positions.size()];
        int i = 0;
        for (BlockPos pos : positions) {
            keys[i++] = pos.asLong();
        }
        return keys;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 区块加载后立刻发布一次拦截名单，避免列车实体先 tick 时名单还是空的（会先开一次门）。
        java.util.Set<BlockPos> blockedPositions = new java.util.HashSet<>();
        for (DoorSlot slot : getDoorSlots()) {
            if (!isLinkageAllowed(slot)) {
                blockedPositions.addAll(doorPositions(slot));
            }
        }
        publishTrainDoorBlocklist(blockedPositions);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        doors.clear();
        maintenanceSlots.clear();
        trainLinkSlots.clear();
        linkedOpenSlots.clear();
        trainDocked = false;
        trainScanTimer = 0;
        linkageSelfTestDone = false;
        String savedMode = tag.getString(KEY_DOOR_CONTROL_MODE);
        originalDoorControl = savedMode.isEmpty() ? "" : restoreModeName(savedMode);
        if (!savedMode.isEmpty() && !savedMode.equals(originalDoorControl)) {
            LOGGER.warn("[DoorControl] {} loaded original mode '{}' is not restorable; using {}",
                worldPosition.toShortString(), savedMode, originalDoorControl);
        }
        doorControlStationDim = tag.getString(KEY_DOOR_CONTROL_DIM);
        doorControlStationName = tag.getString(KEY_DOOR_CONTROL_STATION);
        doorControlStationPos = originalDoorControl.isEmpty() || !tag.contains(KEY_DOOR_CONTROL_POS)
            ? null : BlockPos.of(tag.getLong(KEY_DOOR_CONTROL_POS));
        // 老存档没有 DoorCtlApplied：若带着原值就按“仍在阻断”处理，避免重载后把 NONE 又写一遍。
        doorControlApplied = !originalDoorControl.isEmpty();
        long[] positions = tag.getLongArray(KEY_DOORS);
        for (long packed : positions) {
            BlockPos pos = BlockPos.of(packed);
            if (!doors.contains(pos)) {
                doors.add(pos);
            }
        }
        long[] maintenanceKeys = tag.getLongArray(KEY_MAINTENANCE);
        for (long packed : maintenanceKeys) {
            maintenanceSlots.add(BlockPos.of(packed));
        }
        if (maintenanceKeys.length == 0) {
            // 老存档只有按索引的 int[] 标记；缺失字段等价于“全部非维护”。
            int[] legacyFlags = tag.getIntArray(KEY_MAINTENANCE_LEGACY);
            for (int i = 0; i < doors.size() && i < legacyFlags.length; i++) {
                if (legacyFlags[i] != 0) {
                    maintenanceSlots.add(doors.get(i));
                }
            }
        }
        dimensionId = tag.getString(KEY_DIMENSION);
        // 老存档没有这两个字段：默认全部不勾、没有联动打开的门位。
        for (long packed : tag.getLongArray(KEY_TRAIN_LINKAGE)) {
            trainLinkSlots.add(BlockPos.of(packed));
        }
        for (long packed : tag.getLongArray(KEY_LINKED_OPEN)) {
            linkedOpenSlots.add(BlockPos.of(packed));
        }
        try {
            mode = Mode.valueOf(tag.getString(KEY_MODE));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.MANUAL;
        }
        groupOpen = tag.getBoolean(KEY_OPEN);
    }

    // ---------------------------------------------------------------- GUI

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.createmetro.platform_screen_door_controller");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PlatformScreenDoorControllerMenu(containerId, inventory, worldPosition, this);
    }
}
