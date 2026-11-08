package com.createmetro.client;

import com.createmetro.CreateMetro;
import com.createmetro.content.controller.PlatformScreenDoorControllerBlockEntity;
import com.createmetro.content.linker.PlatformScreenDoorLinkerItem;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlock;
import com.createmetro.registry.CMBlockEntities;
import com.createmetro.registry.CMBlocks;
import com.createmetro.registry.CMItems;
import com.createmetro.registry.CMMenus;
import com.createmetro.ponder.CMPonderPlugin;

import net.createmod.ponder.foundation.PonderIndex;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 客户端专用初始化。只在 Dist.CLIENT 下由 {@link CreateMetro} 调用，
 * 避免专用服务端加载任何客户端类。
 */
public final class CMClientEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateMetro/Ponder");

    private CMClientEvents() {
    }

    private static int highlightTick;
    private static boolean ponderSelfCheckDone;

    public static void register(IEventBus modBus) {
        modBus.addListener(CMClientEvents::registerRenderers);
        modBus.addListener(CMClientEvents::registerScreens);
        modBus.addListener(CMClientEvents::clientSetup);
        NeoForge.EVENT_BUS.addListener(CMClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(CMClientEvents::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(CMClientEvents::onPlayerLoggedInLinkerSelfTest);
    }

    /**
     * 仅用于开发自证（-Dcreatemetro.selftest=true）：进世界后在玩家前方临时摆一整对左右相邻的屏蔽门
     * （左向 + 右向、同朝向、铰链相反），用真实物品右键其中一扇的「上半格」，验证：
     * <ol>
     *   <li>一次右键加入的是 2 个下半格坐标，且这 2 个坐标互为配对；</li>
     *   <li>再右键一次，两个坐标同时移除；</li>
     *   <li>空手右键不会开门，红石通断电仍能开合。</li>
     * </ol>
     * 最后恢复方块与手持物品。正常游戏不会进入这段逻辑。
     */
    private static void onPlayerLoggedInLinkerSelfTest(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Boolean.getBoolean("createmetro.selftest")
            || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Level level = player.level();
        Direction facing = player.getDirection();
        Direction doorFacing = facing.getOpposite();
        // 左铰链门的配对门就在 facing 的顺时针方向一格，这里按同一规则预留两格。
        Direction partnerDirection = doorFacing.getClockWise();
        BlockPos base = null;
        BlockPos partnerBase = null;
        // 先试四个水平方向 2~5 格，再退化到玩家周围 9x9 的小范围找两格宽、两格高的空位。
        outer:
        for (Direction direction : new Direction[]{
            facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()}) {
            for (int distance = 2; distance <= 5; distance++) {
                BlockPos candidate = player.blockPosition().relative(direction, distance);
                BlockPos partnerCandidate = candidate.relative(partnerDirection);
                if (hasFreeTwoBlocks(level, candidate) && hasFreeTwoBlocks(level, partnerCandidate)) {
                    base = candidate.immutable();
                    partnerBase = partnerCandidate.immutable();
                    break outer;
                }
            }
        }
        if (base == null) {
            outerBox:
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) < 2) {
                        continue;
                    }
                    BlockPos candidate = player.blockPosition().offset(dx, 0, dz);
                    BlockPos partnerCandidate = candidate.relative(partnerDirection);
                    if (hasFreeTwoBlocks(level, candidate) && hasFreeTwoBlocks(level, partnerCandidate)) {
                        base = candidate.immutable();
                        partnerBase = partnerCandidate.immutable();
                        break outerBox;
                    }
                }
            }
        }
        if (base == null) {
            LOGGER.info("[Linker][SelfTest] skipped: no free 2x2-block space in front of player");
            return;
        }

        BlockState lowerLeft = CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get().defaultBlockState()
            .setValue(DoorBlock.FACING, doorFacing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(PlatformScreenDoorBlock.VISIBLE, true);
        BlockState lowerRight = CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get().defaultBlockState()
            .setValue(DoorBlock.FACING, doorFacing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(PlatformScreenDoorBlock.VISIBLE, true);
        level.setBlock(base, lowerLeft, 3);
        level.setBlock(base.above(), lowerLeft.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        level.setBlock(partnerBase, lowerRight, 3);
        level.setBlock(partnerBase.above(), lowerRight.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);

        BlockPos expectedPartner = PlatformScreenDoorLinkerItem.findPartnerLower(level, base, lowerLeft);
        LOGGER.info("[Linker][SelfTest] placed pair LEFT {} + RIGHT {} (facing={}) -> findPartnerLower({})={}",
            base.toShortString(), partnerBase.toShortString(), doorFacing, base.toShortString(),
            expectedPartner == null ? "none" : expectedPartner.toShortString());

        ItemStack linker = new ItemStack(CMItems.SCREEN_DOOR_LINKER.get());
        ItemStack previous = player.getMainHandItem().copy();
        BlockHitResult hitLower = new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false);
        BlockHitResult hitUpper = new BlockHitResult(Vec3.atCenterOf(base.above()),
            Direction.UP, base.above(), false);

        // 1) 空手右键：OPEN 必须保持 false（屏蔽门不允许手动开合）
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        boolean openBeforeEmpty = level.getBlockState(base).getValue(DoorBlock.OPEN);
        InteractionResult emptyHandResult = player.gameMode.useItemOn(player, level, ItemStack.EMPTY,
            InteractionHand.MAIN_HAND, hitLower);
        boolean openAfterEmpty = level.getBlockState(base).getValue(DoorBlock.OPEN);
        LOGGER.info("[Linker][SelfTest] empty-hand right-click {} -> result={} OPEN {} -> {}",
            base.toShortString(), emptyHandResult, openBeforeEmpty, openAfterEmpty);

        // 2) 屏蔽器右键其中一扇的上半格：应当一次加入一整对（2 个下半格坐标）
        player.setItemInHand(InteractionHand.MAIN_HAND, linker);
        boolean openBeforeLinker = level.getBlockState(base).getValue(DoorBlock.OPEN);
        linker.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hitUpper));
        List<BlockPos> selection = PlatformScreenDoorLinkerItem.getSelectedDoors(linker);
        BlockPos partnerOfBase = PlatformScreenDoorLinkerItem.findPartnerLower(level, base, lowerLeft);
        BlockPos partnerOfPartner = PlatformScreenDoorLinkerItem.findPartnerLower(level, partnerBase, lowerRight);
        boolean mutualPair = selection.size() == 2
            && selection.contains(base) && selection.contains(partnerBase)
            && partnerOfBase != null && partnerOfBase.equals(partnerBase)
            && partnerOfPartner != null && partnerOfPartner.equals(base);
        LOGGER.info("[Linker][SelfTest] pair add: UPPER {} -> count={} selection=[{}] mutualPair={} "
                + "partner({})={} partner({})={} dim={} OPEN {} -> {}",
            base.above().toShortString(), selection.size(),
            PlatformScreenDoorLinkerItem.describeDoors(selection), mutualPair,
            base.toShortString(), partnerOfBase == null ? "none" : partnerOfBase.toShortString(),
            partnerBase.toShortString(), partnerOfPartner == null ? "none" : partnerOfPartner.toShortString(),
            PlatformScreenDoorLinkerItem.getSelectedDimension(linker), openBeforeLinker,
            level.getBlockState(base).getValue(DoorBlock.OPEN));

        // 3) 再右键同一扇：这一对应当同时移除
        linker.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hitUpper));
        List<BlockPos> afterRemove = PlatformScreenDoorLinkerItem.getSelectedDoors(linker);
        LOGGER.info("[Linker][SelfTest] pair remove: right-click again -> count={} selection=[{}] bothRemoved={}",
            afterRemove.size(), PlatformScreenDoorLinkerItem.describeDoors(afterRemove),
            !afterRemove.contains(base) && !afterRemove.contains(partnerBase));
        player.setItemInHand(InteractionHand.MAIN_HAND, previous);

        // 4) 红石信号：等两 tick（让门实体的 deferUpdate 清零）后通电/断电，各记录一次 OPEN
        MinecraftServer server = player.getServer();
        if (server == null) {
            level.removeBlock(partnerBase.above(), false);
            level.removeBlock(partnerBase, false);
            level.removeBlock(base.above(), false);
            level.removeBlock(base, false);
            return;
        }
        BlockPos doorLower = base;
        BlockPos partnerLower = partnerBase;
        server.tell(new TickTask(server.getTickCount() + 2, () -> {
            if (player.isRemoved()) {
                return;
            }
            BlockPos redstonePos = null;
            for (int dy = 0; dy <= 1 && redstonePos == null; dy++) {
                for (Direction side : new Direction[]{
                    Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                    BlockPos candidate = doorLower.above(dy).relative(side);
                    if (level.getBlockState(candidate).canBeReplaced()) {
                        redstonePos = candidate.immutable();
                        break;
                    }
                }
            }
            if (redstonePos == null) {
                LOGGER.info("[Linker][SelfTest] redstone test skipped: no free side next to {}",
                    doorLower.toShortString());
            } else {
                boolean beforePower = level.getBlockState(doorLower).getValue(DoorBlock.OPEN);
                level.setBlock(redstonePos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                boolean afterPower = level.getBlockState(doorLower).getValue(DoorBlock.OPEN);
                LOGGER.info("[Linker][SelfTest] redstone ON at {} -> OPEN {} -> {} (partner OPEN={})",
                    redstonePos.toShortString(), beforePower, afterPower,
                    level.getBlockState(partnerLower).getValue(DoorBlock.OPEN));
                level.setBlock(redstonePos, Blocks.AIR.defaultBlockState(), 3);
                boolean afterCut = level.getBlockState(doorLower).getValue(DoorBlock.OPEN);
                LOGGER.info("[Linker][SelfTest] redstone OFF -> OPEN {} (partner OPEN={})",
                    afterCut, level.getBlockState(partnerLower).getValue(DoorBlock.OPEN));
            }
            // 6) 联控器门位（一行 = 一整对）+ 维护开关：维护门位不参与批量开合
            BlockPos controllerPos = doorLower.above(2);
            if (level.getBlockState(controllerPos).canBeReplaced()) {
                level.setBlock(controllerPos,
                    CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get().defaultBlockState(), 3);
                if (level.getBlockEntity(controllerPos)
                        instanceof PlatformScreenDoorControllerBlockEntity controller) {
                    controller.bindDoors(List.of(doorLower, partnerLower));
                    List<PlatformScreenDoorControllerBlockEntity.DoorSlot> bound =
                        controller.getDoorSlots();
                    LOGGER.info("[Linker][SelfTest] slots after bind: {} (slotCount={}, pairSlots={})",
                        describeDoorSlots(bound), bound.size(),
                        bound.stream().filter(PlatformScreenDoorControllerBlockEntity.DoorSlot::isPair).count());

                    // 开启维护后批量开门：这一对的扇仍应保持关闭
                    controller.toggleDoorMaintenance(0);
                    List<PlatformScreenDoorControllerBlockEntity.DoorSlot> maint =
                        controller.getDoorSlots();
                    controller.setGroupOpen(true);
                    List<PlatformScreenDoorControllerBlockEntity.DoorSlot> afterOpen =
                        controller.getDoorSlots();
                    PlatformScreenDoorControllerBlockEntity.DoorSlot firstAfterOpen = afterOpen.get(0);
                    boolean heldClosed = firstAfterOpen.maintenance()
                        && !firstAfterOpen.firstOpen() && !firstAfterOpen.secondOpen()
                        && !level.getBlockState(doorLower).getValue(DoorBlock.OPEN)
                        && !level.getBlockState(partnerLower).getValue(DoorBlock.OPEN);
                    LOGGER.info("[Linker][SelfTest] maintenance ON slot 1 ({}) + group OPEN -> {} heldClosed={} worldOpen={}/{}",
                        describeDoorSlot(maint.get(0)), describeDoorSlots(afterOpen), heldClosed,
                        level.getBlockState(doorLower).getValue(DoorBlock.OPEN),
                        level.getBlockState(partnerLower).getValue(DoorBlock.OPEN));

                    // 取消维护后再次联动：一行应当把整对同时打开
                    controller.toggleDoorMaintenance(0);
                    controller.setGroupOpen(true);
                    List<PlatformScreenDoorControllerBlockEntity.DoorSlot> normal =
                        controller.getDoorSlots();
                    PlatformScreenDoorControllerBlockEntity.DoorSlot firstNormal = normal.get(0);
                    boolean oneRowControlsPair = firstNormal.isPair()
                        && firstNormal.firstOpen() && firstNormal.secondOpen()
                        && level.getBlockState(doorLower).getValue(DoorBlock.OPEN)
                        && level.getBlockState(partnerLower).getValue(DoorBlock.OPEN);
                    LOGGER.info("[Linker][SelfTest] maintenance OFF + group OPEN -> {} oneRowControlsPair={}",
                        describeDoorSlots(normal), oneRowControlsPair);

                    controller.setGroupOpen(false);
                    List<PlatformScreenDoorControllerBlockEntity.DoorSlot> closed =
                        controller.getDoorSlots();
                    PlatformScreenDoorControllerBlockEntity.DoorSlot firstClosed = closed.get(0);
                    boolean pairClosed = !firstClosed.firstOpen() && !firstClosed.secondOpen()
                        && !level.getBlockState(doorLower).getValue(DoorBlock.OPEN)
                        && !level.getBlockState(partnerLower).getValue(DoorBlock.OPEN);
                    LOGGER.info("[Linker][SelfTest] group CLOSE -> {} pairClosed={}", describeDoorSlots(closed), pairClosed);
                }
                level.removeBlock(controllerPos, false);
            } else {
                LOGGER.info("[Linker][SelfTest] controller test skipped: {} not replaceable",
                    controllerPos.toShortString());
            }
            level.removeBlock(partnerLower.above(), false);
            level.removeBlock(partnerLower, false);
            level.removeBlock(doorLower.above(), false);
            level.removeBlock(doorLower, false);
        }));
    }

    /** 自检日志用：把门位快照格式化成 "a+b open=true/false maint=false" 列表。 */
    private static String describeDoorSlots(
        List<PlatformScreenDoorControllerBlockEntity.DoorSlot> slots) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(describeDoorSlot(slots.get(i)));
        }
        return builder.append(']').toString();
    }

    private static String describeDoorSlot(PlatformScreenDoorControllerBlockEntity.DoorSlot slot) {
        return slot.first().toShortString()
            + (slot.second() == null ? " (single)" : "+" + slot.second().toShortString())
            + " open=" + slot.firstOpen() + "/" + slot.secondOpen()
            + " maint=" + slot.maintenance();
    }

    /** 某格与其上方一格都能被替换（可以用来摆两格高的门）。 */
    private static boolean hasFreeTwoBlocks(Level level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced()
            && level.getBlockState(pos.above()).canBeReplaced();
    }

    /**
     * 自证用：进入世界后编译一次两个思索场景（此时客户端世界存在，Ponder 才能建 PonderLevel）。
     * 场景 storyboard 里的校验日志会打印模板非空气方块数，方块为 0 时直接抛错。
     */
    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (ponderSelfCheckDone) {
            return;
        }
        ponderSelfCheckDone = true;
        try {
            PonderIndex.getSceneAccess().compile(
                ResourceLocation.fromNamespaceAndPath(CreateMetro.MODID, "platform_screen_door_left"));
            PonderIndex.getSceneAccess().compile(
                ResourceLocation.fromNamespaceAndPath(CreateMetro.MODID, "platform_screen_door_controller"));
        } catch (Throwable t) {
            LOGGER.error("[Ponder] self-check compile failed", t);
        }
    }

    /**
     * 手持屏蔽器时用粒子高亮：已选择的门（白色 END_ROD）、准星指向的门（蓝色 ELECTRIC_SPARK）。
     */
    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof PlatformScreenDoorLinkerItem)) {
            stack = player.getOffhandItem();
            if (!(stack.getItem() instanceof PlatformScreenDoorLinkerItem)) {
                return;
            }
        }
        if (++highlightTick % 4 != 0) {
            return;
        }
        Level level = minecraft.level;
        for (BlockPos door : PlatformScreenDoorLinkerItem.getSelectedDoors(stack)) {
            emitHighlight(level, player, door, ParticleTypes.END_ROD);
        }
        BlockHitResult hit = PlatformScreenDoorLinkerItem.findTargetedDoor(level, player);
        if (hit != null) {
            BlockPos lower = PlatformScreenDoorLinkerItem.normalizeLower(level, hit.getBlockPos());
            emitHighlight(level, player, lower, ParticleTypes.ELECTRIC_SPARK);
        }
    }

    private static void emitHighlight(Level level, Player player, BlockPos pos, ParticleOptions particle) {
        if (!level.isLoaded(pos)) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0 * 64.0) {
            return;
        }
        level.addParticle(particle, pos.getX() + 0.5, pos.getY() + 2.02, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        level.addParticle(particle, pos.getX() + 0.5, pos.getY() + 1.55, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        level.addParticle(particle, pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(CMMenus.PLATFORM_SCREEN_DOOR_CONTROLLER.get(),
            PlatformScreenDoorControllerScreen::new);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(CMBlockEntities.PLATFORM_SCREEN_DOOR.get(),
            PlatformScreenDoorRenderer::new);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get(), RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get(), RenderType.translucent());
            logDoorRenderTypes();
            // 注册思索场景；Create 的 PonderIndex 会在模组加载完成时统一编译场景。
            PonderIndex.addPlugin(new CMPonderPlugin());
        });
    }

    /** 一次性渲染类型一致性自检：静态区块层与 BER 取到的 RenderType 必须相同。 */
    private static void logDoorRenderTypes() {
        BlockState closedVisible = CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get().defaultBlockState()
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(PlatformScreenDoorBlock.VISIBLE, true);
        // 注意：不要用已过时的 ItemBlockRenderTypes.getChunkRenderType/getRenderType(state,false)，
        // 它们只查原版 TYPE_BY_BLOCK，模组方块会退化成 solid / entity_cutout（就是之前亮度不一致的根因）。
        LOGGER.info(
            "[DoorRender] registration: registeredChunkLayers={} berRenderType={} renderShape={} ambientOcclusion={}",
            ItemBlockRenderTypes.getRenderLayers(closedVisible), RenderType.translucent(),
            closedVisible.getRenderShape(), Minecraft.useAmbientOcclusion());
    }
}
