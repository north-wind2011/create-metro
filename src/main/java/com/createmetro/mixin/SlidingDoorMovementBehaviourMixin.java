package com.createmetro.mixin;

import com.createmetro.content.platformdoor.TrainDoorBlocklist;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorMovementBehaviour;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让列车自己的 Create 玻璃滑动门跟着“对应屏蔽门门位”一起关门。
 *
 * <p>Create 6.0.11 的门判定链：{@code SlidingDoorMovementBehaviour#tick} → {@code tickOpen} →
 * {@code shouldOpen(context)}；后者只看列车当前车站的 {@code DoorControlBehaviour.mode} / 电梯模式，
 * 并不知道旁边那扇是我们模组的屏蔽门。这里在 {@code shouldOpen} 返回 true 后再按位置校验：
 * 若列车门世界坐标附近（±2 格水平、±1 格垂直）有被联控器标记为“禁止开”的屏蔽门门位，
 * 就把结果改成 false。屏蔽门本身的门位过滤（维护 / 未勾选）逻辑保持不变。</p>
 */
@Mixin(SlidingDoorMovementBehaviour.class)
public class SlidingDoorMovementBehaviourMixin {

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("CreateMetro/TrainDoor");
    /** 同一扇车门最多每秒打一条，避免每 tick 刷屏。 */
    private static final java.util.Map<BlockPos, Long> LOG_THROTTLE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_INTERVAL_MILLIS = 1000L;

    @Inject(method = "shouldOpen", at = @At("RETURN"), cancellable = true)
    private void createmetro$blockDoorsNextToDisallowedPlatformDoor(MovementContext context,
                                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || context == null || context.world == null
            || context.contraption == null
            || !(context.contraption.entity instanceof CarriageContraptionEntity entity)) {
            return;
        }

        Vec3 worldCenter = entity.toGlobalVector(Vec3.atCenterOf(context.localPos), 1.0f);
        BlockPos origin = BlockPos.containing(worldCenter);
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos platformPos = origin.offset(dx, dy, dz);
                    if (TrainDoorBlocklist.isBlocked(context.world, platformPos)) {
                        logDoor(origin, "blocked-open matchedPlatformDoor=" + platformPos.toShortString());
                        cir.setReturnValue(false);
                        return;
                    }
                }
            }
        }
        logDoor(origin, "open mode-source=station-doorcontrol");
    }

    private static void logDoor(BlockPos trainDoorPos, String action) {
        long now = System.currentTimeMillis();
        Long last = LOG_THROTTLE.get(trainDoorPos);
        if (last != null && now - last < LOG_INTERVAL_MILLIS) {
            return;
        }
        LOG_THROTTLE.put(trainDoorPos.immutable(), now);
        LOGGER.info("[TrainDoor] {} {}", trainDoorPos.toShortString(), action);
    }
}
