# Create Metro

Create（机械动力）的地铁拓展模组。当前阶段只实现一个功能：**两格高的地铁站台屏蔽门**（Platform Screen Door），
以及带左向 / 右向箭头导向标识的两个外观变体。

- Minecraft：1.21.1
- 模组加载器：NeoForge 21.1.219
- Create：6.0.11-320（`create-1.21.1` 在 2026-11 的 Maven `latest/release`，已用 `maven-metadata.xml` 核实）
- Java：21
- 构建：Gradle 8.11 + ModDevGradle 2.0.78

> Create 本阶段只作为“可选依赖”：屏蔽门不调用 Create 的列车 API，单独安装也能放置和开合；
> 与 Create 6.x 同时安装时按 `AFTER` 顺序加载。后续接入 Train/Station 联动时会改为 required。

## 当前已完成

- 可编译的 NeoForge 工程骨架（`DeferredRegister` 注册体系、模组元数据、创造模式标签页）。
- 两个方块：`createmetro:platform_screen_door_left`、`createmetro:platform_screen_door_right`。
- **门体就是 Create 原版 `create:framed_glass_door`（两格高框玻璃门）的整套复制**，不是自造模型：
  - blockstate：直接由 Create 的 `framed_glass_door.json`（64 个 variant）转换而来，只把 model 路径换成本模组；
  - block model：逐字段复制 Create 的 `block_bottom.json` / `block_top.json`（3/16 厚的板、相同的 UV、rotation、面集合）；
  - 贴图：直接使用 Create 的 `glass_door_side` / `glass_door_top` / `glass_door_bottom` / 物品图标，
    仅在玻璃面上叠加箭头图案（见下）；
  - 方块类、方块实体、碰撞形状、BER 位移公式与开合时序均照抄 Create 的 `SlidingDoor*` 实现。
- 箭头标识：叠加在玻璃面上，做成左箭头 / 右箭头两个变体（左变体是右变体的严格水平镜像）。
  图标位于两格交界处（门中部），跨上下两格贴图连续拼接。
  - 图形来自用户提供的 `双向左.png`（本身即左向双箭头）：按 alpha 二值化后所有不透明像素统一改成
    纯白 `#FFFFFF`；左向版直接用它，右向版 = 左向版水平镜像，两版严格互为镜像。
  - 按参考图量：徽标外径占门宽约 0.69，32×32 玻璃贴图里取 **22px**；圆环厚 1px、圈与箭头之间留 1px
    空隙，箭头按“到中心最大半径”等比缩放，保证整枚箭头被圆环包住、不顶出圈外。
  - 徽标先在 4× 超采样下合成，再按块内占比二值化，全部硬边、无抗锯齿、无半透明过渡。
  - 玻璃贴图为 **32×32**：Create 原版 16×16 用最近邻放大 2 倍（观感完全一致），
    左侧门框仍是原版 16×16 的 `glass_door_side`。
  - 独立图标资源：`arrow_left.png` / `arrow_right.png`（32×32，纯白，整枚圆形徽标），
    以后换图直接替换这两个文件或重跑生成器即可。
- 音效：沿用原版铁门 `IRON_DOOR_OPEN` / `IRON_DOOR_CLOSE`；碰撞箱与 Create 门一致。
- 创造模式物品栏：自带「机械动力：地铁」分类；**安装 Create 时还会追加到 Create 原版 `create:base` 栏**。
- `build` 已在本机验证通过；开发客户端已带 Create 6.0.11 成功启动
  （日志显示 `Create Metro 0.1.0 (createmetro)` 与 `Create 6.0.11`，无模组相关报错）。

## 目录结构

```
D:\CreateMetro
├─ build.gradle                  # ModDevGradle、镜像仓库、NFRT 启动器替换
├─ settings.gradle
├─ gradle.properties
├─ gradlew / gradlew.bat
├─ gradle/
│  ├─ neoforged-mirror.init.gradle   # 把 maven.neoforged.net 重写到可访问镜像
│  └─ wrapper/
├─ local-maven/                  # 上游镜像缺同步的极小构件（minecraft-dependencies BOM）
│  └─ net/neoforged/minecraft-dependencies/1.21.1/
├─ tools/
│  ├─ gen-minecraft-bom.ps1      # 由 Minecraft 版本清单生成本地 BOM（含 LWJGL natives）
│  ├─ IconFromPng.java           # 用用户提供的双向箭头生成圆形徽标并叠到玻璃贴图
│  ├─ create-ref/                # 从 Create jar 中提取的原版门资源（仅供比对/再生成）
│  └─ nfrt-launcher/             # 给 NeoFormRuntime 注入可用 Maven 镜像的包装启动器
│     ├─ NfrtLauncher.java
│     ├─ build-launcher.ps1
│     └─ neoform-runtime-launcher.jar
├─ src/main/java/com/createmetro/
│  ├─ CreateMetro.java
│  ├─ registry/                  # 方块 / 物品 / 方块实体 / 创造标签页
│  ├─ content/platformdoor/      # 方块、方块实体、碰撞形状
│  └─ client/                    # BER 与客户端事件
├─ src/main/resources/
│  ├─ META-INF/neoforge.mods.toml
│  ├─ pack.mcmeta
│  ├─ assets/createmetro/        # blockstates / models / textures / lang
│  └─ data/createmetro/loot_table/blocks/
└─ docs/design.md                # 设计说明与后续路线
```

## 环境准备

1. 安装 JDK 21（`java -version` 应显示 21）。
2. 工程自带 Gradle Wrapper，不需要单独安装 Gradle。
3. 首次构建会下载 NeoForge、NeoForm、Minecraft 库和 NFRT，耗时较长。

### 当前网络环境的构建参数

`maven.neoforged.net` 在本机不可达，工程用三处兜底：

- `build.gradle` 中依次配置了 Stellardrift、BMCLAPI（南阳理工镜像）、Minecraft 官方库、Create Maven。
- `gradle/neoforged-mirror.init.gradle` 把 ModDevGradle 自动注入的 `maven.neoforged.net`
  重写到南阳理工的 BMCLAPI 镜像。
- `tools/nfrt-launcher` 包装了 NeoFormRuntime，给它注入可访问的 Maven 仓库；
  `build.gradle` 会把 `createMinecraftArtifacts` 使用的 NFRT 替换成这个包装 jar。

因此**每次构建都要带 init script**：

```powershell
# 如果默认 Gradle 用户目录存在权限问题，可先指定一个可写目录
$env:GRADLE_USER_HOME = 'D:\文档\Trae-CN\temp_20261107_01\.gradle-home'

cd D:\CreateMetro
.\gradlew.bat build --init-script gradle/neoforged-mirror.init.gradle --no-daemon
```

产物：`build\libs\create-metro-0.1.0.jar`。

如果 NFRT 依赖被清理，重新生成包装启动器：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\nfrt-launcher\build-launcher.ps1
```

## 运行与验证

### 开发服务端（已验证可启动）

```powershell
cd D:\CreateMetro
"eula=true" | Set-Content run\eula.txt
.\gradlew.bat runServer --init-script gradle/neoforged-mirror.init.gradle --no-daemon
```

看到 `Done (...)! For help, type "help"` 即启动成功。

### 开发客户端（已在本机验证可启动）

```powershell
cd D:\CreateMetro
.\gradlew.bat runClient --init-script gradle/neoforged-mirror.init.gradle --no-daemon
```

启动后日志应出现 `Backend library: LWJGL version 3.3.3`、`Create Metro 0.1.0 (createmetro)`，
且 `Reloading ResourceManager` 中包含 `mod/createmetro`。

> runClient 需要 LWJGL 的 natives（`lwjgl-3.3.3-natives-windows.jar` 等）。它们来自
> `local-maven` 里的 `minecraft-dependencies` 本地 BOM；该 BOM 由
> `tools/gen-minecraft-bom.ps1` 依据 Minecraft 版本清单生成，已包含所有 classifier 依赖。
> 如果清空了 Gradle 缓存，重新生成即可：
>
> ```powershell
> powershell -ExecutionPolicy Bypass -File tools\gen-minecraft-bom.ps1 `
>   -VersionManifest "<GRADLE_USER_HOME>\caches\neoformruntime\artifacts\minecraft_1.21.1_version_manifest.json"
> ```
>
> 版本清单也可以从 `https://piston-meta.mojang.com/v1/packages/.../1.21.1.json` 获得。

若要与 Create 一起测试，把 Create 的 jar 放进 `run\mods`（Create 为可选依赖，不放也能测试屏蔽门）：

```
https://maven.createmod.net/com/simibubi/create/create-1.21.1/6.0.11-320/create-1.21.1-6.0.11-320.jar
```

Create 6.0.11 已通过 jarJar 内嵌 Flywheel 与 Ponder，只需这**一个** jar 即可。
本机已执行过这一步（`libs\create-1.21.1-6.0.11-320.jar` 复制到 `run\mods`），
日志中同时出现 `Create 6.0.11 (create)` 与 `Create Metro 0.1.0 (createmetro)`。

### 游戏内验证清单

1. 创造模式物品栏出现“机械动力：地铁”页签，内含左箭头 / 右箭头两种屏蔽门。
2. 搭建两格高站台边缘，放置门（会自动生成上下两格）。
3. 右键：门应横向滑开约 1 秒，再右键滑回关闭，伴随铁门音效。
4. 红石：门旁放拉杆/红石火把，通电开门、断电关门。
5. 碰撞：关闭时是薄板碰撞；打开后碰撞箱让开通道。
6. 连续放置多扇门，检查朝向与贴图拼接；左/右箭头变体的图标方向不同。
7. 两个门并排且铰链相对时，右键一扇应联动另一扇。

## 当前卡点与限制

- 编译、服务端启动、客户端启动（含 Create 6.0.11）均已验证；**门在游戏内的实际摆放与开合观感仍需人工确认**
  （自动化无法代替玩家进游戏放置方块），请按上面的“游戏内验证清单”逐条检查。
- 门体贴图来自 Create 原版资源（Create 采用 MIT 协议）。箭头图标由 `tools/IconFromPng.java` 从用户图标生成：
  - 生成：`javac -encoding UTF-8 -d tools/out2 tools/IconFromPng.java` 后执行
    `java -cp tools/out2 IconFromPng "C:\Users\north\Downloads\双向左.png"`。
  - 预览：`tools/arrow-work/door_left.png` / `door_right.png`（整扇门 8 倍放大图，深色底），
    `tools/arrow-work/arrow_left_8x.png` / `arrow_right_8x.png`（徽标本体 8 倍放大）。
  - 图标为纯白 `#FFFFFF`；因为 Create 原版玻璃是全透明的，白色图标在实际游戏中会透出背景，
    在明亮背景（雪地、天空）下对比度可能偏低——这是按需求使用纯白的结果，如需增强可在
    `IconFromPng.java` 里改 `WHITE` 常量后重跑。
- 本阶段未实现：与 Create 车站/列车联动的自动开关门、AFC 闸机、售票机、线路图、到站屏、广播、站牌、Ponder。
  设计路线见 `docs/design.md`。
- 构建依赖镜像可能随网络环境变化；若镜像失效，需要替换 `build.gradle` 与 init script 中的仓库地址。
