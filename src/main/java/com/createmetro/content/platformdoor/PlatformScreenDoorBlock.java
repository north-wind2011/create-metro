package com.createmetro.content.platformdoor;

import com.createmetro.registry.CMBlockEntities;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;

/**
 * 地铁站台屏蔽门（两格高、可滑动）。
 *
 * <p>实现方式对齐 Create 自带的 {@code SlidingDoorBlock}：方块本身是原版 {@link DoorBlock}
 * 的两格高门，额外增加 {@link #VISIBLE} 状态。关闭且静止时用区块静态模型渲染（模型 JSON
 * 声明 {@code render_type: translucent}）；一旦开始开合，方块切换为
 * {@link RenderShape#ENTITYBLOCK_ANIMATED}，由 {@code PlatformScreenDoorRenderer} 按动画进度
 * 平移渲染，BER 也显式使用 translucent，两条路径的混合方式保持一致。
 * 有两种外观变体：门玻璃上分别印有左向 / 右向箭头标识。</p>
 *
 * <p>{@link #useWithoutItem} 固定返回 {@link InteractionResult#PASS}：屏蔽门不允许手动开合，
 * 只能由红石信号（{@link #neighborChanged}）或联控器（<code>setOpen</code>）驱动。</p>
 */
public class PlatformScreenDoorBlock extends DoorBlock implements EntityBlock {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 是否使用静态方块模型渲染。false 时交给 BlockEntityRenderer 做滑动动画。 */
    public static final BooleanProperty VISIBLE = BooleanProperty.create("visible");

    /** 该变体滑开时朝观察者的右方（true）还是左方（false）。由方块变体决定，而不是放置时的点击位置。 */
    private final boolean slideRight;

    /** Create 玻璃门使用的门类型：玻璃材质 + 铁门开合音效。 */
    public static final BlockSetType GLASS_SET_TYPE = new BlockSetType(
        "createmetro:platform_glass",
        true, true, true,
        BlockSetType.PressurePlateSensitivity.EVERYTHING,
        SoundType.GLASS,
        SoundEvents.IRON_DOOR_CLOSE,
        SoundEvents.IRON_DOOR_OPEN,
        SoundEvents.IRON_TRAPDOOR_CLOSE,
        SoundEvents.IRON_TRAPDOOR_OPEN,
        SoundEvents.METAL_PRESSURE_PLATE_CLICK_OFF,
        SoundEvents.METAL_PRESSURE_PLATE_CLICK_ON,
        SoundEvents.STONE_BUTTON_CLICK_OFF,
        SoundEvents.STONE_BUTTON_CLICK_ON
    );

    public PlatformScreenDoorBlock(BlockSetType type, Properties properties, boolean slideRight) {
        super(type, properties);
        this.slideRight = slideRight;
    }

    /** 右向标识 → 右铰链（Create 渲染器据此朝 facing.getClockWise() 滑，即放置玩家视角的右方），左向标识 → 左铰链。 */
    public boolean slideRight() {
        return slideRight;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(VISIBLE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!state.getValue(OPEN) && state.getValue(VISIBLE)) {
            return super.getShape(state, level, pos, context);
        }
        return PlatformScreenDoorShapes.get(state.getValue(FACING), state.getValue(HINGE) == DoorHingeSide.RIGHT);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER || level.getBlockState(pos.below()).is(this);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state != null && state.getValue(OPEN)) {
            state = state.setValue(OPEN, false).setValue(POWERED, false);
        }
        if (state != null) {
            // 滑动方向由变体（左/右向标识）决定，忽略放置时的点击位置，
            // 这样渲染平移、碰撞箱（都按 HINGE 推导）与标识方向始终一致。
            state = state.setValue(HINGE, slideRight ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT);
        }
        return state;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!oldState.is(this)) {
            withBlockEntityDo(level, pos, door -> door.deferUpdate = true);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
                                  BlockPos currentPos, BlockPos facingPos) {
        BlockState result = super.updateShape(state, facing, facingState, level, currentPos, facingPos);
        if (result.isAir()) {
            return result;
        }
        DoubleBlockHalf half = result.getValue(HALF);
        if (facing.getAxis() == Direction.Axis.Y && half == DoubleBlockHalf.LOWER == (facing == Direction.UP)) {
            return facingState.is(this) && facingState.getValue(HALF) != half
                ? result.setValue(VISIBLE, facingState.getValue(VISIBLE))
                : Blocks.AIR.defaultBlockState();
        }
        return result;
    }

    /** 外部调用方最近一次的开/关请求，仅用于“被拒绝的开门”日志去重（Create 会每 tick 重试）。 */
    private static final java.util.Map<BlockPos, Boolean> LAST_EXTERNAL_REQUEST =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 每个门位最近一次“真实开门”的来源（by/slot/调用栈）。联控器的兜底校验用它回答
     * “这扇被禁的门到底是被谁打开的”；世界重载后仍为 OPEN 的门没有记录，会显示
     * {@link #UNKNOWN_OPENER}，即“存档里带来的开启状态”。
     */
    private static final java.util.Map<BlockPos, String> LAST_OPENER =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final String UNKNOWN_OPENER = "unknown/persisted(no setOpen record)";

    /** 联控器兜底校验用：该门位最近一次真实开门的来源描述；从未由本模组开过则返回 unknown。 */
    public static String lastOpener(BlockPos pos) {
        return LAST_OPENER.getOrDefault(pos, UNKNOWN_OPENER);
    }

    @Override
    public void setOpen(@Nullable Entity entity, Level level, BlockState state, BlockPos pos, boolean open) {
        // 屏蔽门只允许由红石（neighborChanged）或联控器（9 参重载带 by=...）驱动。
        //
        // 这个 5 参重载被外部代码调用时一律拒绝开门，实测来源两个：
        // 1) Create 的 SlidingDoorMovementBehaviour#tickOpen 会把“列车滑动门正前方的任意
        //    DoorBlock”直接 setOpen(entity=null) —— 屏蔽门继承 DoorBlock，于是维护/未勾选
        //    的门位会被列车门连带打开（日志 caller=...SlidingDoorMovementBehaviour.tickOpen:85）；
        // 2) 原版 DoorInteractGoal（村民/卫道士等 mob AI）的开门。
        // 两条路径都不认识本模组的维护/列车联动标记，必须在入口拒绝。
        if (open) {
            Boolean last = LAST_EXTERNAL_REQUEST.put(pos, Boolean.TRUE);
            if (last == null) {
                LOGGER.info("[DoorOpen] {} by=external allowed=false reason=screen-door-owned "
                        + "entity={} caller={}", pos.toShortString(),
                    entity == null ? "null" : entity.getClass().getName(), callerTrace());
            }
            return;
        }
        // 关门放行：外部关门不会造成“被禁门位被打开”，也能把历史遗留的开启状态收回去。
        LAST_EXTERNAL_REQUEST.remove(pos);
        LOGGER.info("[DoorOpen] {} by=other external from=false entity={} caller={}",
            pos.toShortString(), entity == null ? "null" : entity.getClass().getName(), callerTrace());
        setOpen(entity, level, state, pos, false, "other", false, false, -1);
    }

    /** 去掉本类与线程栈噪音后的调用链，便于定位外部开门来源。 */
    private static String callerTrace() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : stack) {
            String cls = element.getClassName();
            if (cls.startsWith("java.lang.Thread") || cls.startsWith("java.lang.reflect")
                || cls.equals(PlatformScreenDoorBlock.class.getName())) {
                continue;
            }
            sb.append(cls).append('.').append(element.getMethodName())
                .append(':').append(element.getLineNumber()).append(" <- ");
            if (sb.length() > 500) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 所有代码驱动开合的统一下发点：每次真实状态变化打印一行 {@code [DoorOpen]}，
     * 供排查“到底是谁开的门”。配对门的另一扇会递归调用并带同样的来源信息。
     *
     * @param by          来源：train / controller / redstone / manual / selftest / other
     * @param trainLinked 该门位是否勾选列车联动（未知传 false）
     * @param maintenance 该门位是否处于维护（未知传 false）
     * @param slotIndex   联控器列表行号，从 1 开始；未知传 -1
     */
    public void setOpen(@Nullable Entity entity, Level level, BlockState state, BlockPos pos, boolean open,
                        String by, boolean trainLinked, boolean maintenance, int slotIndex) {
        if (!state.is(this)) {
            return;
        }
        if (state.getValue(OPEN) == open) {
            return;
        }
        LOGGER.info("[DoorOpen] {} from={} by={} trainLinked={} maintenance={} slotIndex={}",
            pos.toShortString(), open, by, trainLinked, maintenance,
            slotIndex <= 0 ? "n/a" : slotIndex);
        if (open) {
            LAST_OPENER.put(pos.immutable(), "by=" + by + " slot=" + (slotIndex <= 0 ? "n/a" : slotIndex)
                + " caller=" + callerTrace());
        } else {
            LAST_OPENER.remove(pos);
        }
        BlockState changed = state.setValue(OPEN, open);
        if (open) {
            changed = changed.setValue(VISIBLE, false);
        }
        level.setBlock(pos, changed, UPDATE_CLIENTS | UPDATE_IMMEDIATE);

        BlockPos otherPos = partnerPos(pos, changed);
        BlockState otherDoor = level.getBlockState(otherPos);
        if (isDoubleDoor(changed, otherDoor)) {
            if (slotIndex >= 0) {
                // 联控器路径（by=train / by=controller）已经按门位逐扇下发（整对的两扇都会单独调一次）。
                // 这里禁用配对递归：递归带入的是“发起门位”的裁决，一旦配对门其实属于另一个
                // 被禁门位，就会越过 isLinkageAllowed 把维护/未勾选的门打开。
                LOGGER.info("[DoorOpen] {} partner={} pair-recursion skipped by={} slot={} "
                    + "reason=caller-manages-pair", pos.toShortString(), otherPos.toShortString(), by, slotIndex);
            } else {
                setOpen(entity, level, otherDoor, otherPos, open, by, trainLinked, maintenance, slotIndex);
            }
        }

        playDoorSound(entity, level, pos, open);
        level.gameEvent(entity, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                boolean isMoving) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        boolean powered = isDoorPowered(level, pos, state);
        if (defaultBlockState().is(block)) {
            return;
        }
        if (powered == state.getValue(POWERED)) {
            return;
        }

        PlatformScreenDoorBlockEntity door = getDoorEntity(level, lower ? pos : pos.below());
        if (door != null && door.deferUpdate) {
            return;
        }

        BlockState changed = state.setValue(POWERED, powered).setValue(OPEN, powered);
        if (powered) {
            changed = changed.setValue(VISIBLE, false);
        }

        if (powered != state.getValue(OPEN)) {
            LOGGER.info("[DoorOpen] {} from={} by=redstone trainLinked=n/a maintenance=n/a slotIndex=n/a",
                pos.toShortString(), powered);
            playDoorSound(null, level, pos, powered);
            level.gameEvent(null, powered ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
            if (powered) {
                LAST_OPENER.put(pos.immutable(), "by=redstone");
            } else {
                LAST_OPENER.remove(pos);
            }

            BlockPos otherPos = partnerPos(pos, changed);
            BlockState otherDoor = level.getBlockState(otherPos);
            if (isDoubleDoor(changed, otherDoor)) {
                otherDoor = otherDoor.setValue(POWERED, powered).setValue(OPEN, powered);
                if (powered) {
                    otherDoor = otherDoor.setValue(VISIBLE, false);
                }
                LOGGER.info("[DoorOpen] {} from={} by=redstone trainLinked=n/a maintenance=n/a "
                    + "slotIndex=n/a (partner)", otherPos.toShortString(), powered);
                level.setBlock(otherPos, otherDoor, Block.UPDATE_CLIENTS);
                if (powered) {
                    LAST_OPENER.put(otherPos.immutable(), "by=redstone (partner)");
                } else {
                    LAST_OPENER.remove(otherPos);
                }
            }
        }

        level.setBlock(pos, changed, Block.UPDATE_CLIENTS);
    }

    public static boolean isDoorPowered(Level level, BlockPos pos, BlockState state) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        BlockPos otherPos = partnerPos(pos, state);
        BlockState otherDoor = level.getBlockState(otherPos);

        if (isDoubleDoor(state.cycle(OPEN), otherDoor)
            && (level.hasNeighborSignal(otherPos)
                || level.hasNeighborSignal(otherPos.relative(lower ? Direction.UP : Direction.DOWN)))) {
            return true;
        }

        return level.hasNeighborSignal(pos)
            || level.hasNeighborSignal(pos.relative(lower ? Direction.UP : Direction.DOWN));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        // 屏蔽门只能由红石信号或联控器开合：空手/手持物品右键一律不切换开关，
        // 直接返回 PASS 让交互不做任何事（覆盖 DoorBlock 自带的手动开合）。
        return InteractionResult.PASS;
    }

    public static boolean isDoubleDoor(BlockState state, BlockState otherDoor) {
        return otherDoor.getBlock() instanceof PlatformScreenDoorBlock
            && otherDoor.getValue(HINGE) != state.getValue(HINGE)
            && otherDoor.getValue(FACING) == state.getValue(FACING)
            && otherDoor.getValue(OPEN) != state.getValue(OPEN)
            && otherDoor.getValue(HALF) == state.getValue(HALF);
    }

    /** 同一门对中另一扇门的位置：左铰链门朝 facing 的顺时针方向找，右铰链门朝逆时针方向找。 */
    public static BlockPos partnerPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return pos.relative(state.getValue(HINGE) == DoorHingeSide.LEFT
            ? facing.getClockWise()
            : facing.getCounterClockWise());
    }

    public static boolean isVisible(BlockState state) {
        return state.getOptionalValue(VISIBLE).orElse(true);
    }

    protected void deferUpdate(LevelAccessor level, BlockPos pos) {
        withBlockEntityDo(level, pos, door -> door.deferUpdate = true);
    }

    private static void withBlockEntityDo(LevelAccessor level, BlockPos pos,
                                          java.util.function.Consumer<PlatformScreenDoorBlockEntity> action) {
        if (level.getBlockEntity(pos) instanceof PlatformScreenDoorBlockEntity door) {
            action.accept(door);
        }
    }

    private static void playDoorSound(@Nullable Entity source, Level level, BlockPos pos, boolean opening) {
        SoundEvent sound = opening ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE;
        level.playSound(source, pos, sound, SoundSource.BLOCKS, 0.9f, level.getRandom().nextFloat() * 0.1f + 0.9f);
    }

    @Nullable
    private static PlatformScreenDoorBlockEntity getDoorEntity(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PlatformScreenDoorBlockEntity door ? door : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // 关闭且静止（VISIBLE=true）用区块静态模型；开合期间交给 BER。
        // 两条路径都必须是 translucent（模型 JSON 里声明了 render_type），否则
        // 玻璃的 alpha 会在 cutout/solid 下被丢弃或直接当不透明画，亮度就会不一致。
        return isVisible(state) ? RenderShape.MODEL : RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        return new PlatformScreenDoorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != CMBlockEntities.PLATFORM_SCREEN_DOOR.get()) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof PlatformScreenDoorBlockEntity door) {
                PlatformScreenDoorBlockEntity.tick(tickLevel, tickPos, tickState, door);
            }
        };
    }
}
