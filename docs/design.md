# Create Metro 设计说明

## 1. 目标环境（已核实）

| 项目 | 选择 | 核实方式 |
| --- | --- | --- |
| Minecraft | 1.21.1 | 目标版本 |
| 加载器 | NeoForge 21.1.219 | Create 6.0.11-320 的 `neoforge.mods.toml` 要求 |
| Create | 6.0.11-320 | `https://maven.createmod.net/com/simibubi/create/create-1.21.1/maven-metadata.xml` 的 `latest/release` |
| Ponder | 1.0.85+mc1.21.1 | Create POM 依赖 `[1.0.85+mc1.21.1,)` |
| Flywheel | 1.0.6（范围 `[1.0.6,2.0)`） | Create POM 依赖 |
| Java | 21 | NeoForge 21.1 要求 |

Create Maven 坐标：

```
com.simibubi.create:create-1.21.1:6.0.11-320
```

Create 官方 POM 的 `ponder-neoforge`、`flywheel-neoforge-*` 坐标也已写入 README，便于搭建整合测试环境。

## 2. 本阶段功能清单与优先级

| 优先级 | 功能 | 本阶段状态 |
| --- | --- | --- |
| P0 | 两格高玻璃屏蔽门，可沿站台连续拼接 | 已完成 |
| P0 | 左右滑动开合动画（复用 Create 滑动玻璃门参数） | 已完成 |
| P0 | 手动右键、红石信号开关；双开门联动 | 已完成 |
| P0 | 左箭头 / 右箭头两种导向标识外观变体 | 已完成（用户双向箭头二值化转纯白 + 1px 圆环包住，左向用原图、右向水平镜像，叠在 Create 原版门玻璃上） |
| P1 | 与 Create 车站/列车联动的自动模式 | 未实现（见第 5 节） |
| P2 | 站名/站台编号标牌、屏蔽门与车门对齐辅助 | 未实现 |
| P2 | 到站信息屏（下一班车 ETA）、线路图/换乘指示 | 未实现 |
| P2 | 站台广播/报站音效 | 未实现 |
| P3 | AFC 闸机与票务、自动售票机 | 未实现 |
| P3 | Ponder 教学场景 | 未实现 |

## 3. 本阶段内容清单

### 方块

| ID | 说明 |
| --- | --- |
| `createmetro:platform_screen_door_left` | 带左向箭头标识的屏蔽门 |
| `createmetro:platform_screen_door_right` | 带右向箭头标识的屏蔽门 |

两者共享 `PlatformScreenDoorBlock`，只有模型/贴图不同。

### 物品

| ID | 说明 |
| --- | --- |
| `createmetro:platform_screen_door_left` | 左箭头门的 BlockItem |
| `createmetro:platform_screen_door_right` | 右箭头门的 BlockItem |

### 方块实体

| 类型 | 说明 |
| --- | --- |
| `createmetro:platform_screen_door` | 只在下半格创建；保存 `animation` / `previousAnimation` / `deferUpdate` |

### GUI

本阶段没有 GUI。开关完全通过右键与红石完成。

### 客户端渲染

`PlatformScreenDoorRenderer` 是 `BlockEntityRenderer`：

- 静止关闭：方块使用静态模型（`RenderShape.MODEL`）。
- 开合过程中：方块切到 `RenderShape.ENTITYBLOCK_ANIMATED`，由 BER 平移渲染。
- 完全打开：`VISIBLE=false` 持续由 BER 渲染滑开后的门，碰撞箱使用滑开形状。

## 4. 屏蔽门状态机与动画

### 方块状态

| 属性 | 取值 | 用途 |
| --- | --- | --- |
| `facing` | 水平四向 | 门面朝向 |
| `half` | lower / upper | 两格高 |
| `hinge` | left / right | 决定滑动方向（Create 玻璃门原逻辑） |
| `open` | true / false | 目标状态 |
| `powered` | true / false | 红石状态 |
| `visible` | true / false | false 时交给 BER 渲染 |

### 动画

`animation` 在服务端和客户端都按 `1/20` 每 tick 推进（约 1 秒）：

- `0` = 完全关闭，`1` = 完全打开。
- 渲染位移（与 Create `SlidingDoorRenderer` 一致）：
  - 沿 `facing.getClockWise()`（`hinge=right` 时反向）平移 `value² × 13/16` 格；
  - 沿 `facing` 方向外推 `clamp(value*10, 0, 1) × 1/32` 格；
  - 上半格额外 `translate(0, 1 - 1/512, 0)`。
- 开门时立即把 `visible` 设为 false；关门动画结束（服务端 `animation=0`）后由方块实体把
  `visible` 设回 true，并播放一次关门音效。

### 触发方式

| 模式 | 触发 | 说明 |
| --- | --- | --- |
| 手动 | 右键 | `useWithoutItem` 翻转 `open`；双开门递归联动 |
| 红石 | 邻居信号变化 | `neighborChanged` 读取上下两格与相邻门的信号 |
| 自动 | Create 车站/列车 | 尚未实现 |

### 碰撞

- 关闭且可见：调用原版 `DoorBlock` 的薄板形状。
- 其他情况：使用从 Create `SlidingDoorShapes` 复制的 8 个滑开形状
  （按 `facing × hinge` 选择）。

## 5. 与 Create 列车系统的集成方案（P1，尚未实现）

目标：列车进站、停稳、对准站台后自动开门；发车/离站后自动关门。

建议实现路径（接入时以 Create 6.0.11 源码为准核对字段与事件）：

1. **绑定车站**
   - 在屏蔽门方块实体中保存 `GlobalStation` 的 UUID/名称，而不是直接保存对象引用，避免存档里持有运行时对象。
   - 玩家用 Create 的站台选中/绑定交互或本模组的绑定物品完成绑定。

2. **列车状态监听**
   - 轮询 `Train` 的当前站点与速度：速度低于阈值且停靠在绑定站点时判定为“停稳”。
   - 或监听 Create 的列车到站/发车事件（若有公开事件），减少轮询开销。

3. **开门/关门条件**
   - 进站：`train.currentStation == boundStation` 且速度 < 阈值，延迟若干 tick 后开门。
   - 发车：列车离开站点或速度 > 阈值，延迟关门。
   - 增加“停站时间上限”作为安全兜底，避免列车异常时门长期打开。

4. **与信号系统**
   - 只读取信号状态用于显示/联锁，不直接改写 Create 的信号网络，避免与列车调度冲突。
   - 站台门可选择“信号红灯时禁止开门”的联锁策略。

5. **模式切换**
   - 方块增加 `mode` 属性（AUTO / REDSTONE / MANUAL）。
   - AUTO 模式下右键只切换模式或维护，不直接开门；红石/手动模式保留当前行为。
   - 模式与绑定信息需要同步到客户端用于显示（见第 6 节）。

## 6. 数据同步与网络包方案

本阶段不使用自定义网络包：`open` / `visible` / `powered` 都在方块状态里，原版区块同步即可。

P1 接入车站联动后建议：

| 数据 | 同步方式 | 频率 |
| --- | --- | --- |
| 绑定车站 UUID、模式 | `BlockEntity` 的 `saveAdditional` / `loadAdditional` + `getUpdateTag` / `handleUpdateTag` | 区块加载、绑定变更时 |
| 当前列车/ETA | 自定义 S2C 包，按区块或按站台分组订阅 | 1 秒级，且仅在玩家附近 |
| 开门进度 | 不下发；客户端根据 `open` 自行播放动画 | 状态变化时 |

- 包体只携带 `BlockPos + 小整数/枚举`，避免传大对象。
- 服务端按玩家距离筛选订阅，离开范围取消订阅。
- 所有列车状态查询只在服务端做，客户端只接收结果。

## 7. 存档兼容与性能

### 存档

- 方块状态属性由 1.21.1 的 `StateDefinition` 管理，旧存档加载时会自动补默认值。
- 方块实体只保存基本类型（float / boolean / UUID 字符串），不保存 Create 运行时对象。
- 后续增加 `mode` 等属性时，旧门会得到默认值（建议默认 `MANUAL`），不需要迁移脚本。

### 性能

- 动画只在 `open != visible` 期间由 BER 渲染；静止时回到静态模型，避免常驻 BER 开销。
- 每扇门只有下半格一个方块实体，上半格不创建 BE。
- 列车状态轮询按“已绑定站台”分组，避免每扇门每 tick 查询列车。
- 大量屏蔽门场景下，优先把绑定/ETA 查询做成每 N tick 一次的批量任务。

## 8. 后续元素的设计要点（不在本阶段实现）

| 元素 | 设计要点 |
| --- | --- |
| AFC 闸机 | 与 Create 物品/背包交互，记录“进站/出站”状态；票务数据建议存玩家附件或独立 SavedData |
| 自动售票机 | 简单 GUI（目的地选择 + 票价），服务端校验余额与线路 |
| 线路图 / 换乘指示 | 数据驱动（线路定义 JSON 或 datapack），客户端渲染站名与连线 |
| 到站信息屏 | 显示下一班车 ETA；数据来自第 5 节的列车状态查询 |
| 广播/报站 | 到站事件触发 `SoundEvent`，支持每站自定义音效与字幕 |
| 站名/站台编号标牌 | 可编辑文本的方块实体 + 客户端文字渲染 |
| 车门对齐辅助 | 在放置/调整站台时显示列车车门位置投影；只做客户端预览，不改列车实体 |
| Ponder | 用一个场景演示“放门 → 绑定车站 → 列车进站开门 → 发车关门” |

## 9. 风险与约定

- 不修改 Create 本体源码，全部通过 NeoForge 注册与公开 API 接入。
- Create 6.x API 仍在演进；接入列车系统前需固定 6.0.11-320 并核对混淆名/公开方法。
- 屏蔽门不参与 Create 的 contraption/移动结构，避免把门当成动态装置的一部分。
- 玻璃贴图在 Create 原版 16×16 基础上最近邻放大到 32×32（观感不变），以承载 22px 的圆形导向徽标
  （外径约占门宽 0.69，与参考图一致；圆环 1px、圈内 1px 空隙，箭头不顶出圈外）；
  后续替换美术资源时保持同样的 UV/面朝向约定即可。
