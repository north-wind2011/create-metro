package com.createmetro.ponder;

import com.createmetro.content.controller.PlatformScreenDoorControllerBlock;
import com.createmetro.content.platformdoor.PlatformScreenDoorBlock;
import com.createmetro.registry.CMBlocks;
import com.createmetro.registry.CMItems;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 两个思索场景：
 * <ul>
 *   <li>{@code platform_door_basics}：两格高、朝向、左右标识、成对关系、只能红石 / 联控器开合；</li>
 *   <li>{@code platform_door_linker}：屏蔽器右键一扇即成一对待加入/移出、累加、右键地面放联控器、
 *       GUI 开关、写入校验、潜行清空。</li>
 * </ul>
 * 场景底图来自 {@code assets/createmetro/ponder/*.nbt}，文本全部走语言文件
 * （键为 {@code createmetro.ponder.<场景名>.header / .text_N}，序号与 {@code showText} 调用顺序一一对应）。
 * 只演示真实可用的行为，不出现已经被移除的「空手右键开门」或旧的自动配对逻辑。
 */
public final class CMPonderScenes {

    /** 场景 A 的结构模板名，同时也是语言键前缀。 */
    public static final String SCENE_A = "platform_door_basics";
    /** 场景 B 的结构模板名。 */
    public static final String SCENE_B = "platform_door_linker";

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateMetro/Ponder");

    private CMPonderScenes() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation leftDoor = id("platform_screen_door_left");
        ResourceLocation rightDoor = id("platform_screen_door_right");
        ResourceLocation controller = id("platform_screen_door_controller");
        ResourceLocation linker = id("screen_door_linker");

        helper.forComponents(leftDoor, rightDoor)
            .addStoryBoard(SCENE_A, CMPonderScenes::platformDoorBasics, CMPonderPlugin.TAG);

        helper.forComponents(controller, linker)
            .addStoryBoard(SCENE_B, CMPonderScenes::linkerAndController, CMPonderPlugin.TAG);
    }

    // ---------------------------------------------------------------- 场景 A

    /**
     * 站台屏蔽门基础：两格高、朝向、左右向标识与滑动方向、成对关系、
     * 手动右键无效、红石通电/断电完整开合、联控器统一开关。
     * 结构模板里 x=3 是左向门、x=4 是右向门（同一朝向、并排相邻），两侧 x=2/x=5 是玻璃隔断。
     */
    public static void platformDoorBasics(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title(SCENE_A, "站台屏蔽门");
        verifyScene(scene, SCENE_A, new BlockPos[]{
            new BlockPos(0, 0, 0), new BlockPos(3, 1, 4), new BlockPos(4, 1, 4)
        });
        scene.configureBasePlate(0, 0, 9);
        scene.showBasePlate();
        scene.idle(10);

        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(10);

        Selection dividers = util.select().position(2, 1, 4).add(util.select().position(2, 2, 4))
            .add(util.select().position(5, 1, 4)).add(util.select().position(5, 2, 4));
        scene.world().showSection(dividers, Direction.SOUTH);
        scene.idle(5);

        Selection doors = util.select().fromTo(3, 1, 4, 4, 2, 4);
        scene.world().showSection(doors, Direction.SOUTH);
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 4));
        scene.idle(20);

        // 1/10：两格高
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(), doors, 90);
        scene.overlay().showText(90)
            .text("屏蔽门有两格高：放下下半格会自动补出上半格，破坏时两格也要一起处理。")
            .pointAt(util.vector().centerOf(4, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        // 2/10：朝向由放置时面对的方向决定（镜头转成俯视角度）
        scene.rotateCameraY(-18);
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), doors, 80);
        scene.overlay().showText(80)
            .text("门的朝向由放置时你面对的方向决定：门面朝你，滑动方向始终与门面平行。")
            .pointAt(util.vector().centerOf(4, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        // 3/10：一对 = 左向 + 右向
        scene.rotateCameraY(18);
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), doors, 85);
        scene.overlay().showText(85)
            .text("一对门由左向标识和右向标识各一扇组成：朝向一致、左右相邻，铰链方向相反。")
            .pointAt(util.vector().centerOf(4, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(95);

        // 4/10：左向标识 → 向左滑（完整开→关）
        Selection leftDoor = util.select().fromTo(3, 1, 4, 3, 2, 4);
        scene.special().movePointOfInterest(util.vector().centerOf(3, 1, 4));
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), leftDoor, 85);
        scene.overlay().showText(85)
            .text("左向标识：箭头朝左；开门时整扇向左滑动，收进左侧的玻璃隔断里。")
            .pointAt(util.vector().centerOf(3, 1, 4))
            .placeNearTarget();
        scene.idle(10);
        animateDoor(scene, 3, true);
        scene.idle(10);

        // 5/10：右向标识 → 向右滑（完整开→关）
        Selection rightDoor = util.select().fromTo(4, 1, 4, 4, 2, 4);
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 4));
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), rightDoor, 85);
        scene.overlay().showText(85)
            .text("右向标识：箭头朝右；开门时向右滑动。两扇各朝外侧滑开，中间就是上下客通道。")
            .pointAt(util.vector().centerOf(4, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(10);
        animateDoor(scene, 4, false);
        scene.idle(10);

        // 6/10：手动右键无效
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 4));
        scene.overlay().showOutline(PonderPalette.RED, new Object(), doors, 90);
        scene.overlay().showControls(util.vector().centerOf(3, 1, 4), Pointing.DOWN, 50).rightClick();
        scene.overlay().showText(95)
            .text("手动无效：空手或手持任何物品右键都不会开关，屏蔽门只认红石信号和联控器。")
            .pointAt(util.vector().centerOf(4, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        // 7/10：红石通电开门
        scene.world().setBlock(new BlockPos(3, 1, 5), Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        scene.world().setBlock(new BlockPos(4, 1, 5), Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        scene.effects().indicateRedstone(new BlockPos(3, 1, 5));
        scene.effects().indicateRedstone(new BlockPos(4, 1, 5));
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), doors, 85);
        scene.overlay().showText(85)
            .text("红石通电开门：红石方块、拉杆或红石线接到门旁，通电后一对门同步滑开。")
            .pointAt(util.vector().centerOf(4, 1, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(10);
        setPair(scene, 3, 4, true, false);
        scene.idle(35);

        // 8/10：红石断电关门
        scene.world().setBlock(new BlockPos(3, 1, 5), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(new BlockPos(4, 1, 5), Blocks.AIR.defaultBlockState(), false);
        scene.overlay().showText(85)
            .text("红石断电关门：信号一断门就滑回原位，所以要保持打开必须持续供电。")
            .pointAt(util.vector().centerOf(4, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(5);
        setPair(scene, 3, 4, false, false);
        scene.idle(30);
        setPair(scene, 3, 4, false, true);
        scene.idle(15);

        // 9/10：联控器统一开合（完整开→关）
        BlockState controllerState = CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get().defaultBlockState()
            .setValue(PlatformScreenDoorControllerBlock.FACING, Direction.NORTH);
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 6));
        scene.world().setBlock(new BlockPos(4, 1, 6), controllerState, false);
        scene.overlay().showOutline(PonderPalette.INPUT, new Object(), util.select().position(4, 1, 6), 55);
        scene.overlay().showControls(util.vector().topOf(4, 1, 6), Pointing.DOWN, 40).rightClick();
        scene.overlay().showText(115)
            .text("联控器放在站台地面：写入连接后右键打开 GUI，一次开/关所有连着的门。")
            .pointAt(util.vector().centerOf(4, 1, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(15);
        setPair(scene, 3, 4, true, false);
        scene.idle(35);
        setPair(scene, 3, 4, false, false);
        scene.idle(30);
        setPair(scene, 3, 4, false, true);
        scene.idle(25);

        // 10/10：连接方法见下一个思索
        scene.overlay().showText(80)
            .text("连接方法见下一个思索：屏蔽器成对选门 → 右键地面放联控器 → GUI 统一开关。")
            .pointAt(util.vector().centerOf(4, 1, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.markAsFinished();
    }

    // ---------------------------------------------------------------- 场景 B

    /**
     * 屏蔽器与联控器：右键一扇自动成对加入、单扇兜底、累加、成对移出、
     * 右键地面放联控器、写入校验、GUI 开关、潜行清空。
     * 结构模板里有两组左右门：x=2/3 与 x=6/7（z=4），隔断在 x=1/4/5/8。
     */
    public static void linkerAndController(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title(SCENE_B, "屏蔽器与屏蔽门联控器");
        verifyScene(scene, SCENE_B, new BlockPos[]{
            new BlockPos(0, 0, 0), new BlockPos(2, 1, 4), new BlockPos(7, 1, 4)
        });
        scene.configureBasePlate(0, 0, 9);
        scene.showBasePlate();
        scene.idle(10);

        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(10);

        Selection dividers = util.select().position(1, 1, 4).add(util.select().position(1, 2, 4))
            .add(util.select().position(4, 1, 4)).add(util.select().position(4, 2, 4))
            .add(util.select().position(5, 1, 4)).add(util.select().position(5, 2, 4))
            .add(util.select().position(8, 1, 4)).add(util.select().position(8, 2, 4));
        scene.world().showSection(dividers, Direction.SOUTH);
        scene.idle(5);

        Selection pair1 = util.select().fromTo(2, 1, 4, 3, 2, 4);
        Selection pair2 = util.select().fromTo(6, 1, 4, 7, 2, 4);
        scene.world().showSection(pair1, Direction.SOUTH);
        scene.idle(5);
        scene.world().showSection(pair2, Direction.SOUTH);
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 4));
        scene.idle(20);

        ItemStack linker = new ItemStack(CMItems.SCREEN_DOOR_LINKER.get());

        // 1/11：屏蔽器是连接工具（平视近景）
        scene.overlay().showControls(util.vector().of(2.5, 2.4, 5.6), Pointing.DOWN, 80)
            .withItem(linker)
            .rightClick();
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(), pair1, 80);
        scene.overlay().showText(80)
            .text("屏蔽器是连接工具：拿在手里右键屏蔽门，就会把这一扇和它的配对门一起加入选择。")
            .pointAt(util.vector().centerOf(2, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        // 2/11：右键一扇 → 整对自动加入
        scene.special().movePointOfInterest(util.vector().centerOf(2, 1, 4));
        scene.overlay().showControls(util.vector().centerOf(2, 1, 4), Pointing.DOWN, 50)
            .withItem(linker)
            .rightClick();
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), pair1, 90);
        scene.overlay().showText(90)
            .text("右键任意一扇，配对门自动一起加入：动作条提示「已加入一对，共 N 扇」。")
            .pointAt(util.vector().centerOf(2, 1, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(10);
        scene.effects().indicateSuccess(new BlockPos(2, 1, 4));
        scene.effects().indicateSuccess(new BlockPos(3, 1, 4));
        scene.idle(90);

        // 3/11：一对的判定条件（镜头侧移近景）
        scene.rotateCameraY(15);
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(), pair1, 85);
        scene.overlay().showText(85)
            .text("一对的判定：左右相邻、朝向相同、铰链相反。上下半格都算，点上半格也归到下半格。")
            .pointAt(util.vector().centerOf(3, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(95);
        scene.rotateCameraY(-15);

        // 4/11：没有配对门 → 只加一扇（临时摆一扇孤立门演示，随后收走）
        Selection loneDoor = util.select().fromTo(0, 1, 4, 0, 2, 4);
        scene.world().setBlock(new BlockPos(0, 1, 4),
            doorState(CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get(), DoubleBlockHalf.LOWER, false, true), false);
        scene.world().setBlock(new BlockPos(0, 2, 4),
            doorState(CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get(), DoubleBlockHalf.UPPER, false, true), false);
        scene.special().movePointOfInterest(util.vector().centerOf(0, 1, 4));
        scene.overlay().showControls(util.vector().centerOf(0, 1, 4), Pointing.DOWN, 45)
            .withItem(linker)
            .rightClick();
        scene.overlay().showOutline(PonderPalette.RED, new Object(), loneDoor, 80);
        scene.overlay().showText(80)
            .text("没有配对门时只加这一扇，提示「已加入 1 扇（附近没有配对门）」，不会点了没反应。")
            .pointAt(util.vector().centerOf(0, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(10);
        scene.effects().indicateSuccess(new BlockPos(0, 1, 4));
        scene.idle(80);
        scene.world().setBlock(new BlockPos(0, 2, 4), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(new BlockPos(0, 1, 4), Blocks.AIR.defaultBlockState(), false);

        // 5/11：继续右键第二对 → 累加
        scene.special().movePointOfInterest(util.vector().centerOf(6, 1, 4));
        scene.overlay().showControls(util.vector().centerOf(6, 1, 4), Pointing.DOWN, 50)
            .withItem(linker)
            .rightClick();
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), pair2, 85);
        scene.overlay().showText(85)
            .text("继续右键其它门对会累加，一次最多 128 扇；同一对不会被重复加入。")
            .pointAt(util.vector().centerOf(6, 1, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(10);
        scene.effects().indicateSuccess(new BlockPos(6, 1, 4));
        scene.effects().indicateSuccess(new BlockPos(7, 1, 4));
        scene.idle(85);

        // 6/11：右键已在列表里的门 → 整对移出
        scene.special().movePointOfInterest(util.vector().centerOf(3, 1, 4));
        scene.overlay().showControls(util.vector().centerOf(3, 1, 4), Pointing.DOWN, 50)
            .withItem(linker)
            .rightClick();
        scene.overlay().showOutline(PonderPalette.RED, new Object(), pair1, 90);
        scene.overlay().showText(90)
            .text("右键已在列表里的任意一扇，这一对会一起移出：提示「已移除一对，剩 N 扇」。")
            .pointAt(util.vector().centerOf(3, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(95);

        // 7/11：右键地面放下联控器
        BlockState controllerState = CMBlocks.PLATFORM_SCREEN_DOOR_CONTROLLER.get().defaultBlockState()
            .setValue(PlatformScreenDoorControllerBlock.FACING, Direction.NORTH);
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 6));
        scene.overlay().showControls(util.vector().topOf(4, 1, 6), Pointing.DOWN, 55)
            .withItem(linker)
            .rightClick();
        scene.overlay().showText(90)
            .text("选好后右键站台地面放下联控器：列表写入联控器，GUI 门数按实际写入的下半格统计。")
            .pointAt(util.vector().centerOf(4, 1, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(10);
        scene.world().setBlock(new BlockPos(4, 1, 6), controllerState, false);
        scene.effects().indicateSuccess(new BlockPos(4, 1, 6));
        scene.idle(90);

        // 8/11：写入校验条件
        scene.overlay().showOutline(PonderPalette.INPUT, new Object(), util.select().position(4, 1, 6), 85);
        scene.overlay().showText(85)
            .text("写入前会校验：列表不能为空、门必须同维度、距联控器不超过 256 格，否则提示失败。")
            .pointAt(util.vector().centerOf(4, 1, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(95);

        // 9/11：GUI 统一开关（完整开→关）
        scene.special().movePointOfInterest(util.vector().centerOf(6, 1, 4));
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(), pair2, 120);
        scene.overlay().showText(120)
            .text("右键联控器打开 GUI：显示已连接门数与开关状态，可「打开全部 / 关闭全部」。")
            .pointAt(util.vector().centerOf(4, 1, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(15);
        setPair(scene, 6, 7, true, false);
        scene.idle(35);
        setPair(scene, 6, 7, false, false);
        scene.idle(30);
        setPair(scene, 6, 7, false, true);
        scene.idle(30);

        // 10/11：一对门由自身配对关系保持同步
        scene.overlay().showOutline(PonderPalette.GREEN, new Object(), pair2, 85);
        scene.overlay().showText(85)
            .text("联控器开合时，一对门由门自身的配对关系保持同步，不需要额外设置。")
            .pointAt(util.vector().centerOf(6, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(95);

        // 11/11：潜行右键清空
        scene.overlay().showControls(util.vector().centerOf(6, 1, 4), Pointing.DOWN, 70)
            .withItem(linker)
            .whileSneaking()
            .rightClick();
        scene.overlay().showText(80)
            .text("选择途中潜行 + 右键会清空本轮选择；已写进联控器的门不受影响。")
            .pointAt(util.vector().centerOf(6, 1, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        // 12/12：列车联动开关（勾选后列车停靠自动开门、离站自动收回）
        scene.special().movePointOfInterest(util.vector().centerOf(4, 1, 6));
        scene.overlay().showOutline(PonderPalette.BLUE, new Object(),
            util.select().position(4, 1, 6), 110);
        scene.overlay().showText(110)
            .text("在 GUI 的「列车联动」列勾选门位：列车停靠开门时只打开已勾选且非维护的门位，"
                + "列车离站后自动收回；未勾选的门位不受影响。")
            .pointAt(util.vector().centerOf(4, 1, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(15);
        setPair(scene, 6, 7, true, false);
        scene.idle(35);
        setPair(scene, 6, 7, false, false);
        scene.idle(30);
        setPair(scene, 6, 7, false, true);
        scene.idle(20);

        scene.markAsFinished();
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 自证：确认结构模板真的被 Ponder 加载并铺进了 PonderLevel。
     * 编译场景时打印模板路径、方块总数和几个探针位置的实际方块；方块数为 0 直接报错，
     * 避免再出现「只有文字、没有方块」的空场景。
     */
    private static void verifyScene(SceneBuilder scene, String sceneName, BlockPos[] probes) {
        PonderScene ponderScene = scene.getScene();
        PonderLevel level = ponderScene.getWorld();
        int nonAir = 0;
        StringBuilder samples = new StringBuilder();
        for (Map.Entry<BlockPos, BlockState> entry : level.getBlockMap().entrySet()) {
            BlockState state = entry.getValue();
            if (state.isAir()) {
                continue;
            }
            nonAir++;
            if (nonAir <= 8) {
                BlockPos pos = entry.getKey();
                samples.append('(').append(pos.getX()).append(',').append(pos.getY()).append(',')
                    .append(pos.getZ()).append(")=")
                    .append(BuiltInRegistries.BLOCK.getKey(state.getBlock())).append(' ');
            }
        }
        StringBuilder probeLog = new StringBuilder();
        for (BlockPos pos : probes) {
            BlockState state = level.getBlockState(pos);
            probeLog.append(pos.toShortString())
                .append('=')
                .append(state.isAir() ? "air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                .append(' ');
        }
        LOGGER.info("[Ponder] scene '{}' template=createmetro:ponder/{}.nbt nonAirBlocks={} size={} bounds={} "
                + "samples=[{}] probes=[{}]",
            sceneName, sceneName, nonAir, level.getBounds().getXSpan() + "x"
                + level.getBounds().getYSpan() + "x" + level.getBounds().getZSpan(),
            level.getBounds(), samples.toString().trim(), probeLog.toString().trim());
        if (nonAir == 0) {
            String message = "[Ponder] scene '" + sceneName + "' has NO blocks in PonderLevel - template "
                + "createmetro:ponder/" + sceneName + ".nbt was placed as empty";
            LOGGER.error(message);
            throw new IllegalStateException(message);
        }
    }

    /** 单扇门完整开合：滑开 → 停留 → 滑回（visible=false 走方块实体滑动动画）。 */
    private static void animateDoor(SceneBuilder scene, int x, boolean leftVariant) {
        setDoor(scene, x, leftVariant, true, false);
        scene.idle(30);
        setDoor(scene, x, leftVariant, false, false);
        scene.idle(25);
        setDoor(scene, x, leftVariant, false, true);
        scene.idle(10);
    }

    /** 同时切换一对门（左门 x=leftX，右门 x=rightX，z=4）。 */
    private static void setPair(SceneBuilder scene, int leftX, int rightX, boolean open, boolean visible) {
        setDoor(scene, leftX, true, open, visible);
        setDoor(scene, rightX, false, open, visible);
    }

    /**
     * 设置一整扇门（上下两半）的开关与渲染方式。
     * visible=false 时由方块实体渲染滑动动画；visible=true 时回到静态模型（动画已经归位）。
     */
    private static void setDoor(SceneBuilder scene, int x, boolean leftVariant, boolean open, boolean visible) {
        Block block = leftVariant
            ? CMBlocks.PLATFORM_SCREEN_DOOR_LEFT.get()
            : CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get();
        BlockPos lower = new BlockPos(x, 1, 4);
        scene.world().setBlock(lower, doorState(block, DoubleBlockHalf.LOWER, open, visible), false);
        scene.world().setBlock(lower.above(), doorState(block, DoubleBlockHalf.UPPER, open, visible), false);
    }

    private static BlockState doorState(Block block, DoubleBlockHalf half, boolean open, boolean visible) {
        return block.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.NORTH)
            .setValue(DoorBlock.HALF, half)
            .setValue(DoorBlock.HINGE, block == CMBlocks.PLATFORM_SCREEN_DOOR_RIGHT.get()
                ? DoorHingeSide.RIGHT
                : DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, open)
            .setValue(DoorBlock.POWERED, false)
            .setValue(PlatformScreenDoorBlock.VISIBLE, visible);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("createmetro", path);
    }
}
