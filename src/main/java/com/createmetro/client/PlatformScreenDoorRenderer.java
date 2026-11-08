package com.createmetro.client;

import com.createmetro.content.platformdoor.PlatformScreenDoorBlock;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 屏蔽门滑动渲染器。与 Create 的 {@code SlidingDoorRenderer} 同样的思路：
 * 方块模型此时被设为 ENTITYBLOCK_ANIMATED（不可见），这里把“关闭姿态”的门模型
 * 沿门所在平面平移 13/16 格，并对上半部分额外抬高 1 格，绘制成一扇滑开的门。
 *
 * <p>光照必须走和静态方块模型完全相同的一条路径，否则开合瞬间会“闪一下”：
 * 旧实现用 {@code renderSingleBlock}（内部是 {@code renderModel} 的平面光照），
 * 所有顶点共用 BER 传入的同一个 {@code packedLight}（而且上下半格共用下半格的光），
 * 丢掉了原版区块渲染的平滑光照 / AO / 逐面亮度；静态模型（VISIBLE=true）用的是
 * {@code tesselateBlock} 的 AO 路径，两条路径一换就产生整体亮度跳变。
 * 现在改成为每一半单独用 {@code tesselateBlock}：位置传各自的方块坐标，渲染类型取
 * {@code ItemBlockRenderTypes}（与静态模型一致），光照完全由游戏按该坐标采样，
 * 于是 VISIBLE 切换前后亮度连续。</p>
 */
public class PlatformScreenDoorRenderer implements BlockEntityRenderer<PlatformScreenDoorBlockEntity> {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("CreateMetro/DoorRender");
    /** 一次性日志：对比静态区块层与 BER 各自的 RenderType。 */
    private static boolean loggedRenderTypes;

    public PlatformScreenDoorRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * 门始终由 BER 绘制，超出 64 格不能让整排门消失（站台通常很长）。
     */
    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public void render(PlatformScreenDoorBlockEntity door, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState state = door.getBlockState();
        if (!door.shouldRenderSpecial(state)) {
            return;
        }

        Direction facing = state.getValue(DoorBlock.FACING);
        // 与 Create SlidingDoorRenderer 完全一致：HINGE LEFT 时才反向。
        // HINGE RIGHT → 朝 facing.getClockWise() 滑，这正是放置时玩家所站一侧的“右方”。
        Direction movementDirection = facing.getClockWise();
        if (state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT) {
            movementDirection = movementDirection.getOpposite();
        }

        float value = door.getAnimationValue(partialTick);
        float value2 = Mth.clamp(value * 10.0F, 0.0F, 1.0F);
        Vec3 offset = Vec3.atLowerCornerOf(movementDirection.getNormal())
            .scale(value * value * 13.0F / 16.0F)
            .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(value2 * 1.0F / 32.0F));

        Level level = door.getLevel();
        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();

        for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
            poseStack.pushPose();
            if (half == DoubleBlockHalf.UPPER) {
                poseStack.translate(0.0F, 1.0F - 1.0F / 512.0F, 0.0F);
            }
            poseStack.translate(offset.x, offset.y, offset.z);

            BlockState renderState = state
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.HALF, half)
                .setValue(PlatformScreenDoorBlock.VISIBLE, true);
            // 上下半格各自用自己的坐标采样光照：旧实现两半共用 lower 的 packedLight，上半格会偏亮/偏暗。
            BlockPos samplePos = half == DoubleBlockHalf.UPPER ? door.getBlockPos().above() : door.getBlockPos();

            if (level == null) {
                // 极端情况（方块实体已脱离世界）退化为旧路径，保证不崩。
                blockRenderer.renderSingleBlock(renderState, poseStack, bufferSource, packedLight, packedOverlay,
                    ModelData.EMPTY, RenderType.translucent());
            } else {
                BakedModel model = blockRenderer.getBlockModel(renderState);
                // 必须和区块静态模型用同一个 RenderType（translucent）。
                // 旧实现用 ItemBlockRenderTypes.getRenderType(state, false)：那是走原版
                // TYPE_BY_BLOCK 的过时 API，模组方块查不到就退化成 solid→entity_cutout，
                // 玻璃的半透明像素被 alpha 测试丢掉；而静态路径按模型 render_type 走 translucent，
                // 于是“关死后更浅/更白”。这里显式用 translucent，两条路径混合方式一致。
                RenderType renderType = RenderType.translucent();
                if (!loggedRenderTypes && door.getAnimationValue(partialTick) <= 1.0e-4f) {
                    loggedRenderTypes = true;
                    LOGGER.info(
                        "[DoorRender] consistency: currentStateShape={} staticStateShape={} staticModelChunkLayers={} "
                            + "berRenderType={} ambientOcclusion={}",
                        state.getRenderShape(), renderState.getRenderShape(),
                        model.getRenderTypes(renderState, RandomSource.create(42L), ModelData.EMPTY),
                        renderType, Minecraft.useAmbientOcclusion());
                }
                blockRenderer.getModelRenderer().tesselateBlock(
                    level, model, renderState, samplePos, poseStack,
                    bufferSource.getBuffer(renderType), false,
                    RandomSource.create(), renderState.getSeed(samplePos), OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, renderType);
            }

            poseStack.popPose();
        }
    }
}
