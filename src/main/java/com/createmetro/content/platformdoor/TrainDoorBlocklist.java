package com.createmetro.content.platformdoor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 列车门（Create 原版玻璃滑动门）的“禁止开”名单：由联控器按当前被禁的屏蔽门门位发布，
 * 由 {@code SlidingDoorMovementBehaviourMixin} 在 Create 决定开门时按位置查询。
 *
 * <p>为什么不写车站 {@code DoorControlBehaviour.mode = NONE}：那会把用户在车站界面里设置的
 * 开门方向一起顶掉（用户表现为“保存不了”）。这里只按位置拦截对应车门，车站 mode 保持用户值。</p>
 */
public final class TrainDoorBlocklist {

    /**
     * 超过这么多游戏刻没有刷新就自动失效（防止联控器区块卸载后把车门永久锁死）。
     *
     * <p>刻意用 {@link Level#getGameTime()} 而不是墙钟时间：单人游戏暂停（窗口失焦）时服务端
     * 不推进游戏刻，但墙钟一直在走。实测暂停 93 秒后恢复，名单按墙钟已过期，恢复瞬间列车实体
     * 先于联控器 tick，于是被禁门位对应的车厢门开了一次（{@code [TrainDoor] ... open}）。</p>
     */
    private static final long TTL_TICKS = 40L;

    private static final Map<String, Long> BLOCKED = new ConcurrentHashMap<>();

    private TrainDoorBlocklist() {
    }

    public static void mark(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return;
        }
        BLOCKED.put(key(level, pos), level.getGameTime() + TTL_TICKS);
    }

    public static void unmark(Level level, BlockPos pos) {
        if (level == null) {
            return;
        }
        BLOCKED.remove(key(level, pos));
    }

    public static boolean isBlocked(Level level, BlockPos pos) {
        if (level == null) {
            return false;
        }
        Long expiry = BLOCKED.get(key(level, pos));
        return expiry != null && expiry > level.getGameTime();
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "@" + pos.asLong();
    }
}
