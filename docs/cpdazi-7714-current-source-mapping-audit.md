# CP搭子 7714 JSON 当前数据源与映射核对

> **8.1.10 左侧分岔发送隔离**：控制页过滤开关默认开启；真实同路线新鲜ROAD_TYPE
> 为0/6或类别未知时，撤销Carrot fork-left码102/105/112/115/7/44/17/75/76/118的
> current/next guidance，并清除lane_current动作副本；已知常规道路保留，关闭开关恢复旧映射。
> 类别过期不能放行旧动作；常规道路放行的信封取guide/类别最旧receipt。
> 保留独立导航状态、地图原始提示、速度/灯态/定位及手动命令，不用车道宽度证明第1/2车道。
> 真实左出口也被过滤，驾驶员需自行处理；仅为App边界隔离，不是现场成因或车辆效果证明。
> 47项定向单测与1项手机控制页仪器测试通过，最终8.1.10/26102602已安装并启动；
> detekt仍2133项全仓发现，按文件/规则未增加。未修改WSL或发送实车变道命令。

> **8.0.15融合覆盖说明**：生产`ModelLaneCountSource`不再直接采用AR单帧编号，
> 而要求AR/7000几何的独立递增观测连续一致，真实来源时间取AR/modelV2/lateralPlan最旧receipt，
> 缺源/过期/冲突/变道撤下。原始四分类第3类语义未核准，不扩为3+或高车道数。
> 高德路口图存在时`current_lane=-1`，count相同也不是当前主路车道身份的证据；
> 无图时保留同路线道路类别门，才发送融合count/current估计。`model_*`为估计诊断，不是地图定位。
> UI明确“融合估计 · 非定位确认”；无置信百分比、路肩识别、跨源采集同步或实车精度证明。
> 复用现有前台采样；没有修改WSL、7706、灯态或自动超车权限。
> 193项相关单测、4项手机仪器测试通过；最终8.0.16/26102402已安装并核验启动。
> lintDebug为0错误/868警告；detekt仍2140项既有全仓发现，不能记为静态检查通过。
> 手机测试使用合成输入/本机WebRTC轨，不是实际道路车道号准确率验证。

> **2026-10-23 后续App修复覆盖说明**：发现当前工作区7706并非仅最小包，
> 旧完整导航/GPS发送及转向块去重仍在运行；现修正所有周期/命令/目的地/预览入口，
> 由7714独占导航正文。7706只保留心跳/时间、显式命令和显式兼容目的地。
> 这是删除有害重复传输，不能说成“修好了Carrot的120→115旧协议特例”。
>
> 7714现使用独立路线/receipt绑定的route、SDI/secondary、section源快照；
> 复合SDI不再从共享`nSdi*`缓存拼装。缺字段不借旧限速或旧区间状态；
> 12110区间控制仅使用同包真实LIMITED_SPEED和正END_DISTANCE，不能拿总长度补剩余距离，
> 10001摄像头限速用CAMERA_SPEED，不用道路LIMITED_SPEED的策略修正值。
> 摄像头缺限速时撤旧控制事实（减速带可保留未知限速）；普通摄像头和区间终点清区间。
> route只有同包距离/时间都明确时才present，合法0保留；缺任一字段撤摘要，
> 不把“预计全程－剩余”发布成已行驶时间。道路名跟随实际定位样本，定位包未提供则为空，
> 不随新定位附共享旧道路名。重复/倒序receipt不能替换这些控制快照；
> 换路线/切源/停止/时钟回退清理，缩减复合流仍先撤后发并保留失败重试。
>
> 机动码按最新Carrot表校准：左后方CP16；普通左/右前方、高架靠左及环岛掉头无核准
> 等义码时不发错误匝道/fork/急右转码，本地高德标签/图形仍独立显示。
> 7714可选速度/方向在receiver默认0、无真实polyline/off_route/available、
> CAN无倒计时被serv清除及未知road_category撤流残留等限制仍未闭环。
> 下文为历史取证，不代表这些旧缺陷仍全部存在，也不代表手机/车辆已验证采用。

**本轮修复验证**：249项/27类相关单测通过，覆盖7706心跳/命令/目的地边界、
目标切换、源字段部分缺失、重复/倒序receipt、切源/路线/时钟回退及既有智能限速/
自动巡航安全门。Debug与仪器测试APK构建成功，lintDebug为0错误/868警告。
detekt未通过：2141项全仓发现（此前2147项）；逐文件/规则对比未新增发现计数，
不将全仓静态检查记为通过。只读提取真实接收端纯函数的合成探针确认摄像头实际限速、
区间真实限速/剩余距离、子源撤销及CP16解析；这些不是车端运行或控制效果验证。
未修改WSL、未发送车辆命令、未运行设备仪器测试。
后续交付升级为8.0.13/26102393，已ADB安装到CPH2671并核验版本、冷启动及运行进程。
CAN与高德仍未建立同灯关联，因此未混入高德倒计时；无倒计时CAN接收端限制仍未解决，
手机安装/启动不是实车灯态采用验证。

> **8.0.6覆盖说明（2026-10-23）**：当前7711 CAN灯态已进入7714优先选择链，
> 下文“7711不进入任何流”的结论是历史状态。CAN与高德不拼接方向/倒计时，
> 停止线不写灯距，已知CAN黄灯无协议槽位时撤销。使用真实本机接收时间而非发送时刻。
> 模型已增加目标/路线/前台视频会话绑定，按帧进入推理旁路的墙钟与单调接收年龄过滤，
> 不代表已验证相机采集时刻或模型/地图车道身份。
> 用户已纠正最新只读仓库为`\\wsl.localhost\Ubuntu24\home\jeff\carrot`，
> 实际接收器位于其`openpilot\selfdrive\carrot`。本轮纯函数探针确认红/绿CAN无倒计时
> 可经`carrot_navi_cereal._signal_light`与control解析，但
> `carrot_serv._apply_carrot_navi_traffic`在`remain_sec<=0`时清除TrafficLight，
> 因此App发送接线不代表该消费者已使用CAN相位。没有伪造正倒计时或修改WSL；
> 设备实际receiver版本/控制效果仍未验证。

> **7.9.33 UI后续变更**：驾驶卡已将7711 CAN灯色/停止线与高德导航灯分开展示，
> 不再把CAN优先灯色与高德方向/倒计时合成同一灯组。此变更不修改本文7714发送契约；
> 早前关于混合显示的现状描述保留为当时审计记录。详见[驾驶视图移植报告](dashpilot-ui-port-report.md)。

> 核对日期：2026-10-20（第二轮复核：随 7711 世界量通道、挡位视角与页面合并一并校正）  
> 性质：当前源码只读研究记录，不是修复完成报告，也不是实车验收结果。  
> 本文按“接收契约 → App 发送字段 → 手机／高德／模型真实来源”梳理，区分缺源、未接线、映射错误与跨端兼容问题。
>
> **本轮复核修正了 8 处偏差**（详见文末第 14 节）：撤销流数量、信封字段漏项、发送循环的真实间隔、4 处源码行号失效、
> 磁罗盘兜底的写入位置、`lane_count` 兼容键，以及**新增 7711 通道与本 13 个流的关系**。
> 后续更正见第 14 节；第 15 节记录新增车道接线，不能把前轮“未接入”结论当作当前状态。

## 1. 范围、依据与结论

本次核对使用：

- App：`C:\AIcode\CPdazi` 当前工作区源码，包括尚未提交的修改。
- Carrot：只读目录 `\\wsl.localhost\Ubuntu24\home\jeff\op\openpilot\openpilot\selfdrive\carrot`。
- 接收端验证：提取实际 Python 转换／解析函数，使用合成输入核对默认值；没有启动车辆服务或发送命令。

原先提到的 `artifacts\openpilot-Carrot-openpilot-selfdrive-carrot` 目录当前不存在，因此本文反映的是 **WSL 当前源码契约**，不代表设备正在运行的版本已经核实一致。第三方参考项目只能提供线索，不能代替真实广播取证。

**主要结论：**

1. 当前问题不只是缺字段，还包括转向编码不一致、旧字段被新广播续期、区间与普通测速状态残留。
2. 接收端会把部分未知值默认成 0／false；App 省略未知字段，不一定能在接收端保留“未知”语义。
3. 当前 **9 个 JSON 流存在 present 发送路径**，**4 个流明确撤销**（`lane_ahead`、`crossroad`、`camera_state`、`composition_state`）；并非 13 个流始终都有有效数据。
4. 本机模型的车道数／当前车道估计现已接入 7714（第 6.1、15 节）；但模型观察与地图车道图没有建立可靠关联，已接线不代表已验证为地图车道事实。
5. 应先修复已有来源的映射和状态契约，再对缺源字段取证；不能为凑齐字段而伪造数值。

相关历史文档：

- [7714 修复实施报告与阻塞项](cpdazi-7714-data-remediation-plan.md)
- [接收端字段消费研究](carrot-7714-field-consumption-map.md)
- [早期数据契约审计](carrot-7714-data-contract-audit.md)

这些文档包含不同阶段的历史结论。本文不照搬其中的错误数量、字段利用率、控制影响分级或“已修复”判断；具体结论以本次列出的源码证据和限制为准。

## 2. 7714 到底要求什么

7714 目录包含 **13 个 JSON 流、14 个图片流和 1 个渲染流**。目录完整不等于每个流必须有数据，也不等于所有业务字段必填。

| 层级 | 接收要求 | App 当前情况 |
|---|---|---|
| 会话／目录 | protocol v2、完整目录、manifest 协商、各流 handle/revision | 已实现 |
| JSON 信封 | `type`、`protocol_version`、`session_id`、`kind`、`name`、`manifest_revision`、`stream_handle`、`schema_version`、`sequence`、`source_timestamp_ms`、`sent_at_ms`、`present`、`value`；schema 按协商匹配 | 已实现 |
| 无数据撤销 | `present=false`、`value=null`，提供非空且不超过 64 字符的 `reason` | 已实现 |
| value 类型 | `lane_ahead` 为对象数组，其余 JSON 流为对象 | 有数据时按对应类型发送；无源流撤销 |
| 业务字段 | 接收入口主要验证对象／数组形状，随后 cereal 转换器读取已知键并补默认值 | 被接收不代表语义正确或数据完整 |

`roadCategoryValid`、`pointValid`、`sdiPresent`、各灯的 `redValid` 等很多字段是接收端派生的 **cereal 字段**，不是要求 App 直接发送同名 JSON。

接收端默认 JSON 参数为 `delivery_mode=on_change`、`interval_ms=1000`、`stale_timeout_ms=10000`。

**发送节拍（易误读，已核准）**：周期循环用的是 `NaviStreamManager.Companion.CHECK_INTERVAL_MS = 200L`，
所以每 200ms 触发一次 `update()`；但真正发包还受三层约束：

1. `publish()` 读流的 `interval_ms`（默认 1000），通常同一流连续 present 且距上次发送不足 interval 时跳过；字段收缩撤旧后的子集发送例外；
2. 内容未变化（`present`/`value`/`source_timestamp_ms`/`reason` 全同）且非 heartbeat 模式时不发；
3. `lane_current` 被显式排除在 `periodic`/`on_change_with_heartbeat` 定时重发之外，只能靠变化触发。

注意代码里存在**两个同名常量、取值不同**：`NaviV2Constants.CHECK_INTERVAL_MS = 1000L`（当前无人引用）与
`NaviStreamManager.Companion.CHECK_INTERVAL_MS = 200L`（被 `MainActivityLifecycle` 使用）。
改动周期时必须改后者，改前者不会生效——这是一处应当合并的隐患。

另有 `fieldsWithdrawn()` 收缩检测：当同一个流的新值比上一帧少了字段（或 true→false、非空数组→空数组）时，
会立即补发一次 `present=false`，不等 interval；撤销成功后，同次调用立即尝试发送仍有效子集，
也不受上一次 present 的 interval 限制。撤销或子集入队失败会在后续调用重试，不能理解为严格每流最多 1Hz。

关键证据：

- App：`navigation\NaviDataModels.kt:13–58, 93–133`。
- App：`navigation\NaviWebSocketV2Client.kt:506–543`。
- App：`navigation\NaviStreamManager.kt:232–272`（`publish` 的节流与 `fieldsWithdrawn`）、`:21`（200ms 常量）。
- Carrot：`carrot_navi.py:64–88, 287–307, 594–650, 875–912`。

> **7711 不属于这 13 个流。** 车端 `xiaoge_data.py` 另开 TCP 7711（20Hz，4 字节大端长度 + JSON）推送
> `data.carState.stopLineDist / trafficLightColor`（Tesla `DAS_road` CAN 直读），App 侧由
> `data\XiaogeBridgeClient.kt` 消费，只喂本机驾驶卡片，**不进入 7714 任何流**。详见第 10.3 节。

## 3. 13 个 JSON 流总览

| JSON 流 | App 当前来源 | 当前发送情况 | 主要缺口 |
|---|---|---|---|
| `vehicle` | 高德定位优先，过期后回退手机定位 | 有真实新鲜定位才发 | 可选速度／方向被接收端默认成 0；道路名未独立绑定定位 receipt |
| `guidance_current` | 高德 10001／10006 | 当前动作、距离、文字、道路名、可选航段时间 | 部分转向码语义不一致；无独立动作点坐标 |
| `guidance_next` | 高德 10001／10006 | 随后动作、距离、文字、道路名、可选时间 | 同样受转向码问题影响；无独立动作点坐标 |
| `lane_current` | 高德 13012／嵌入车道数据，加 10001 道路类别；**车道数／当前车道可回退本机 AR 模型** | 已知类别可发；车道图还要求显式启用、完整顺序、新鲜同路线；无车道图但有模型结果时发「count=模型数 + visible=true + 空 lanes」 | 无已验证 ego lane（当前车道来自模型估计）、available；turn_info 已按 CP 转向码翻译（详见 6.1） |
| `lane_ahead` | 无独立来源 | 恒 absent | 不能复制当前车道充数 |
| `speed` | 高德道路限速、主／次 SDI、区间广播，加本地智能限速策略 | 已发送大部分现有数据 | 主 SDI／区间字段有混用、残留和缺失值风险 |
| `traffic_signal` | 高德 60073 导航灯 | 只发布当前放行的红灯和左转绿灯阶段及倒计时 | 直行／右转／掉头等阶段未完整验证；无真实灯距 |
| `crossroad` | 无已接入的路口扩大图源 | 恒 absent | 导航动作距离不是独立路口图距离 |
| `route` | 高德 10001 剩余行程，10003／10001 总里程 | 有行程摘要；`polyline=[]` | 无当前选中路线实际点列；无真实已行驶时间 |
| `navigation_status` | App 导航状态和源新鲜度 | mode、guidance_active、route_present | 无真实地图偏航／恢复来源 |
| `app_status` | Activity 前后台生命周期 | `foreground` | 当前已接线 |
| `camera_state` | 无对应导航采集源 | 恒 absent | AR 有视频不等于有此流对应的导航相机状态 |
| `composition_state` | 无导航画面合成采集源 | 恒 absent | App 有投屏不等于已经有 7714 导航合成源 |

发送主入口为 `navigation\NaviStreamManager.kt:59–224`（`update()`）。
初始化和周期调用在 `MainActivityLifecycle.kt:897–957`（`initializeNaviV2Client` / `startNaviV2DataPush`）。

**流级计数（核准）**：present 路径 9 条 —— `vehicle`、`guidance_current`、`guidance_next`、`route`、
`navigation_status`、`speed`、`traffic_signal`、`lane_current`、`app_status`（其中 `navigation_status` 与
`app_status` 恒 present，其余为条件 present）；恒撤销 4 条 —— `lane_ahead`、`crossroad`、`camera_state`、`composition_state`。

## 4. 定位：vehicle

| JSON 字段 | 手机／高德实际来源 | 当前处理与判断 |
|---|---|---|
| `lat`、`lon` | 高德 10001 `CAR_LATITUDE/LONGITUDE`、10065 `LATITUDE/LONGITUDE`、备用广播坐标；回退 Android Location | 高德坐标转 WGS-84；手机直接使用 WGS-84 |
| `speed_kph` | 高德 `CUR_SPEED`／`SPEED`；手机 Location.speed × 3.6 | 手机单位转换明确；10065 与备用广播速度的单位／类型仍应以真实样本核对 |
| `heading_deg` | 高德 `CAR_DIRECTION`／`BEARING`；手机 Location bearing | 已接线；手机回退样本仅在 `location.hasBearing()` 时提供方向，否则省略；磁罗盘兜底写入共享 `carrotManFields.heading`，不进入该 7714 手机定位样本 |
| `road_name` | 共享字段 `szPosRoadName`，来自高德道路广播 | 有映射，但道路名未与定位样本一起保存、独立过期 |
| `virtual_gps` | 当前没有独立判断来源 | 固定 false，不能理解为已识别模拟定位和真实定位 |

高德定位新鲜时，即使手机定位更新，也优先用高德。手机传感器、速度与地图定位没有融合成新算法来源。回退手机定位时不附加地图道路名。

**磁罗盘写入边界**：`LocationSensorManager.updateLocation()` 把磁力计/加速度计融合角写进
`carrotManFields.heading`，但传给 `naviSources.updatePosition()` 的 `NaviPositionSample.headingDeg`
只在 `hasBearing()` 时非空。**没有 bearing 的手机回退样本不会把磁罗盘兜底送入 7714**；
高德定位仍可独立提供方向。本次未确认共享 heading 的其他消费去向，不能将它称为“7000 展示链”；
7000 compact 是另一条设备到 App 的输入链。

**接收风险：** App 省略未知 `heading_deg`／`speed_kph` 后，接收端仍转成 0°／0km/h，并交给 `external_navi` 定位源，未知语义丢失。

证据：

- `LocationSensorManager.kt:215–254`（`updateLocation`，含 216–249 的坐标/速度/heading 分流）。
- `AmapBroadcastHandlers.kt:918–923, 948–983`。
- `AmapBroadcastManager.kt:465–507`。
- `navigation\NaviSourceTracker.kt:354–357`（`isFresh`）。
- `navigation\NaviStreamManager.kt:67–79`。
- Carrot：`carrot_navi_cereal.py:207–216`、`carrot_navi_control.py:154–171`、`carrot_serv.py:993–1020`。

## 5. 引导：guidance_current／guidance_next

| JSON 字段 | 实际来源 | 当前状态 |
|---|---|---|
| `distance_m` | 当前 `SEG_REMAIN_DIS`／`TURN_DISTANCE`；随后 `NEXT_SEG_REMAIN_DIS`／`NEXT_TURN_DISTANCE` | 已接；随后是独立航段距离，不是累计距离 |
| `turn_type` | `NEW_ICON/ICON`、`NEXT_NEXT_TURN_ICON` 或 10006 TURN_TYPE，经 `AmapManeuver` 转换 | 部分编码与接收端语义不一致 |
| `main_text` | 10001 本地生成提示；10006 使用 `TURN_INSTRUCTION` | 本地生成文字不等于地图逐字原文 |
| `near_direction`、`far_direction` | 当前引导使用 `NEXT_ROAD_NAME`、`NEXT_NEXT_ROAD_NAME` | 是实际道路名，不能仅凭键名判定必须是左右方向词 |
| `road_name` | 当前使用下一道路名；随后使用再下一道路名 | 表示动作关联道路，不是 vehicle 当前道路 |
| `time_sec` | `SEG_REMAIN_TIME`／`NEXT_SEG_REMAIN_TIME` | 仅同路线、同 guide receipt 才发 |
| `mid_direction` | 无已验证独立来源 | 未发 |
| `point.lat/lon` | 无已接入的真实动作点坐标 | 未发，不能用车辆当前位置代替 |

### 5.1 已确认的两端转向语义不一致

| AMap 原码 | App 当前标签 | 发送 CP 码 | CP 当前解释（`carrot_serv.py:580–643` 实测码表） |
|---|---|---|---|
| 6 | 左后方 | 17 | `("fork", "left", 3)`，不是 sharp-left |
| 21 | 环岛左转 | 133 | `("rotary", "right", 5)` |
| 22 | 环岛右转 | 139 | `("rotary", "left", 5)` |
| 未识别的 44 | 未知原码透传 | 44 | `("fork", "left", 3)` |

前三项证明 **App 当前标签／映射与接收解释不一致**，但实际修正仍应核准 AMap 广播原码语义，不能只相信本地标签。

最后一项证明未知码透传存在码域碰撞：App 不认识的 AMap 编码可能恰好是 CP 已知动作，不能认为原样透传仍表示未知。

补充：CP 码表里 `6` 是 `("fork", "right", 4)`；`7`/`44`/`17`/`75`/`76`/`118` 才是 fork-left；
`133`/`134`/`135` 都在 rotary 家族但左右不同。因此「只错一位」的偏差在 CP 侧会变成另一个合法动作，
不会报 invalid——这比"未知码"更难在现场发现。

证据：

- `AmapManeuver.kt:26–60`（车机广播表）、`:62–81`（手机 SDK 表）、`:88–89`（未知原码透传）。
- `AmapBroadcastHandlers.kt:414–453, 577–593, 711–724, 1000–1020`。
- `navigation\NaviStreamManager.kt:80–100`。
- Carrot：`carrot_serv.py` 的 `_update_tbt()` 提供转向解释；`carrot_navi_cereal.py` 的 `_guidance()`
  负责字段转换，并非转向码表。不能把原先引用转换函数本身视为“码表发生迁移”的证据。

## 6. 车道：lane_current／lane_ahead

| JSON 字段 | 已有来源 | 当前状态 |
|---|---|---|
| `count` | 13012 条目数量；无车道图时回退本机 AR 模型 `lanesTotal` | 地图车道图有效时以地图为准（CP `cluster_renderer` 也不假设它是主路总车道数） |
| `visible`、`lane_play` | 显式启用、源新鲜、同路线、完整顺序等门槛 | `visible` = 地图车道图有效 **或** 模型有新鲜结果；`lane_play` 仍只跟地图 |
| `current_lane` | **本机 AR 模型 `lanesLeft`（1-based）**，因为它的定义就是「含当前车道的左侧计数」 | 仅当 `1 ≤ current ≤ count` 时发送，否则 -1；地图无 ego lane 时这是唯一来源 |
| `distance_m`、`turn_code` | 新鲜当前导航动作距离／转向码 | 不是独立 13012 路口距离或逐车道方向 |
| `road_category` | 10001 原始 `ROAD_TYPE` | 0/6→CP 1；1–5/7–9→CP 6；10／未知撤销 |
| `available[]` | 无已验证逐车道可用性 | 省略，不用推荐状态或默认 true 代替 |
| `turn_info[]` | 13012 每车道 `drive_way_lane_Back_icon` → CP 转向码 | **已实现翻译**（见 6.1）；任一条不可识别就整键省略 |
| `etc_info[]`、`guide_line_color` | 无已验证来源 | 未发 |
| `voice_code` | 无独立真实来源 | 固定 0，不算功能已实现 |
| 扩展 `lanes[].direction/is_recommended/...`、`recommended_lane` | 原始图示、推荐及条目元数据 | App 发送，但当前 cereal `_lane()` 不读取这些扩展键 |
| 诊断键 `lane_source`/`model_count`/`model_current_lane` | 新增 | CP 不读，用于现场区分「这条车道数是地图的还是模型的」 |

**兼容键（本轮补齐）**：`LaneData.toJson()` 除了协议键 `count`，还额外写 `lane_count`、`recommended_lane`、
`lanes[]`（含 `index/direction/is_recommended/is_available/drive_way_number/...`）。源码注释称用于兼容早期
Navipilot／CP 兜底解析器，但当前已核对的 cereal `_lane()` 只读取 `count` 等标准键，
不读取这三个扩展键；不能仅凭注释认定当前接收链会消费它们。
`recommended_lane` 计算为 `indexOfFirst { isRecommended }`，没有推荐时是 `-1`。
`available[]` 则**只有全部车道 `isAvailable != null`** 时才写入，任一未知就整组省略（不填 1 冒充可通行）。

当前只在 `drive_way_number` 完整唯一覆盖 `0..N-1` 时声明顺序已知。仅有 ROAD_TYPE 时，允许发送 count=0、visible=false 的类别数据，不必同时有车道图。

### 6.1 turn_info 翻译与模型车道数（本轮新增实现）

**turn_info**：`drive_way_lane_Back_icon` 是**字符串图标名**（如 `"straight"`），且与导航机动 ICON
是两套独立编码（历史已踩过 `toIntOrNull()` 的坑）。所以翻译**不复用** `AmapManeuver`，而是：

```text
Back_icon ──amapLaneDirection()（项目内已核的方向表，覆盖 0–12 与字符串别名）──► 方向语义
        ──LaneTurnCode.ofLabel()──► CP 转向码：掉头 14 > 左 12 > 右 13 > 直行 51
```

- 多方向车道（如「直行+左转」）CP 的 `turn_info[]` 是一车道一个 int16，无法表达，
  按「越具体越优先」取：**掉头 > 左 > 右 > 直行**
- **任一条不可识别就整组省略**，绝不发 0 或原码占位，避免 CP 逐车道错位
- CP 现在怎么用：`cluster_renderer._draw_navi_lane_strip()` 只把 `turn_info` 当
  **车道条数的下限**（`max(count, len(available), len(turn_info))`），不按码值画箭头。
  所以发对了暂时不改变仪表显示，但一旦 CP 开始消费，码值必须是 CP 自己的转向码

**模型车道数／当前车道**：`LaneInference` 的模型结果经 `LaneCountSource` 注入 `NaviStreamManager`：

| 量 | 模型口径 | 发送条件 |
|---|---|---|
| `count` | `lanesTotal`（= left + right − 1，5 帧中值滤波后） | 仅当 1..8 且距该帧 ≤ 1.2 s；地图车道图有效时以地图条目数为准 |
| `current_lane` | `lanesLeft`（含当前车道的左侧计数，天然是 1-based） | 仅当 `1 ≤ current ≤ count` |

约束与边界：

- 模型只在**实时页拿到 5001 视频帧**时出数（依赖页面生命周期），离开页面后 1.2 s 内失效，
  没有有效地图车道图时车道数回落成 0、`visible=false`；有地图图时仍保留地图条目数与可见状态
- 模型不提供逐车道可用性、路口距离、每车道方向 → `available`/`distance_m`/`turn_info` 仍然只在
  地图车道图有效时才出现，**不用模型数伪造这些字段**
- 只有模型、没有地图车道图时：`count=模型数`、`visible=true`、`lanes=[]`，
  按当前 CP 绘制路径预期显示车道格与当前车道标记；这只是接线／渲染行为，
  不证明模型估计与地图条目、实际通行车道或本车定位一致，设备显示仍待确认
- `road_category` 仍是发送的前置条件（control 只读它），类别未知时整条流撤销

**推荐链缺口：** App 能显示高德推荐车道，不代表 CP 已收到可消费的推荐高亮。当前 CP 车道条使用 `available[]` 着色、统一画直箭头；不能为补显示而擅自将推荐定义成可通行，也不能据此认定 turn_info 已有正确目标码表。

**类别兼容阻塞：** 接收 cereal 在缺少 `road_category` 时数值默认 0、Valid=false；control 仍可能在 lane present 时不检查 Valid 而读到 0。App 已在类别未知时撤整个流，但 `carrot_serv` 在 category=None 时不立即清旧 roadcate，跨端残留尚未闭环。

证据：

- `AmapBroadcastHandlers.kt:1905–2024`。
- `navigation\NaviStreamManager.kt:182–224`。
- `navigation\NaviDataModels.kt:326–379`（`LaneData.toJson`）。
- `navigation\AmapNaviSourceData.kt:11–22`（`carrotCategory`）。
- Carrot：`carrot_navi_cereal.py:119–136`、`carrot_navi_control.py:285–288`、`carrot_serv.py:1119–1120`。
- Carrot：`signal_road_policy.py:9–27`、`cluster\cluster_navi.py:136–153`、`cluster\cluster_renderer.py:5569–5600`。

## 7. 速度／测速：speed

| JSON 字段组 | 实际来源 | 当前状态 |
|---|---|---|
| `current_kph` | 与 vehicle 相同的有效定位速度 | 有值时写入，但仅有速度不足以使整个 speed 流 present |
| `road_limit_kph` | 10001 LIMITED_SPEED、非区间 12110；优先有效 route/receipt 绑定策略值 | 已接，包括既有智能策略；原始限速另存用于显示 |
| `sdi.type/distance_m/speed_limit_kph` | 10001 CAMERA_*；独立电子眼处理入口 | 已接，但部分更新保留旧字段 |
| `sdi.section_type/block_type/block_speed_kph/block_distance_m` | 共享 nSdi* 状态 | 已发，区间字段可能残留到后续普通电子眼 |
| `sdi_secondary.type/distance_m/speed_limit_kph` | 10007 SDI_PLUS_* | 已接；原始类型码与 CP 是否一致仍需真实来源证据 |
| 次 SDI `block_*` | 共享字段 | 序列化存在，但没有对应完整独立源更新链 |
| 次 SDI `section_type` | 无当前发送赋值 | 未发，CP 默认 -1 |
| `section.active` | section 源新鲜且 `nSdiBlockType==2` | 未要求剩余距离存在 |
| `section.speed_limit_kph` | `nSdiBlockSpeed` | 12110 来自 LIMITED_SPEED；10001 某路径来自修正道路限速，应与 CAMERA_SPEED 区分 |
| `section.average_kph` | AVERAGE_SPEED | 有真实字段才发 |
| `section.remaining_distance_m` | END_DISTANCE | 有真实数值才发，但缺失时 active 仍可能为 true |
| `section.progress` | START_DISTANCE / INTERVAL_DISTANCE | 有界计算值，不是广播原始比例 |
| `section.overall_average_kph`、`remaining_time_sec` | 无已验证独立来源 | 未发，不能用当前均速推算区间剩余时间 |
| `section.suspended`、`off_route` | 无已验证地图状态来源 | 未发，但 CP 默认 false |

### 7.1 现有兼容约束

- 道路限速只发送 CP 支持的 10–200km/h、10km/h 档位；原始 55 不会擅自四舍五入。
- 原始地图限速与智能策略值分开保存，7714 优先有效策略。不能回退使用不具备来源绑定的共享 UI 值。
- 复合 speed 信封已经取纳入字段的最旧 receipt，但这不能解决更上游的“共享子字段已被错误续期”。
- 7000 的车端采用限速／建议速度不是地图源，不能回灌作为 7714 道路限速。

### 7.2 主要问题路径

1. **区间缺距离仍 active：** App 可发送 active=true、limit=80、无 remaining_distance_m；接收端解析结果仍 active=true，remaining=0。
2. **普通 SDI 继承区间残留：** 普通电子眼更新不一定清 block_type/block_distance；CP 遇到 block_type=2/3 会改用 block_distance，并按区间处理。
3. **12110 只检查键存在：** START/END/INTERVAL 任一键存在即可进入区间中，即使内容无效；展示层的严格数值校验未同步约束发送状态。
4. **部分字段更新给旧值续期：** 10001 只有 CAMERA_DIST 等字段时，可能保留旧 type/limit，再刷新整个 sdi receipt。
5. **区间限速来源混用：** 10001 block speed 可取修正道路限速，而主 SDI 限速取 CAMERA_SPEED；后续缺值保留可能留下两套不同限速。

以上是源码可达路径，不代表已经取得对应实车故障样本。

证据：

- `AmapBroadcastHandlers.kt:747–854, 913–915, 1125–1307, 1340–1395, 2066–2080`。
- `navigation\NaviStreamManager.kt:118–173`。
- `navigation\NaviDataModels.kt:176–222`。
- Carrot：`carrot_navi_cereal.py:220–253`、`carrot_navi_control.py:238–283`、`carrot_serv.py:873–895, 948–991`。
- 现有测试 `navigation\NaviStreamManagerTest.kt:417–435` 也明确允许缺 remaining_distance_m 时 section.active 仍为 true；这只能证明当前行为，不能证明跨端语义正确。

## 8. 灯态：traffic_signal

| 字段 | 当前来源／处理 | 缺口与边界 |
|---|---|---|
| `visible` | 车端 7711 有效帧 **或** 高德有效相位 | 车端帧要求灯色 1/2 且 `0 < stopLineDist ≤ 125 m` |
| `source` | 仍固定 `"navigation"` | **值域刻意不改**：接收端可能按该字符串分支；真实相位来源见调试面板 `signalMerge.chosen` |
| `lights.red.{on,remain_sec}` | 相位以车端 `trafficLightColor=1` 为准；倒计时仅在高德同为红灯时借用 | 已接；无倒计时时只写 `valid/on`，不写 `remain_sec` |
| `lights.left.{on,remain_sec}` | 车端 `=2`（绿）且高德 `status=2&direction=1` → 用 left 槽承载倒计时 | 避免 green/left 双灯；高德仍只放行这一个绿灯相位 |
| `lights.green` | 车端 `=2` 且高德无左转绿 | `on=true`，无 `remain_sec` |
| `lights.right/uturn` | 无来源 | 车端只给整灯颜色不给方向；高德未放行这两相位，继续省略 |
| `ui_counter.remain_sec` | 从发布的有效灯阶段派生 | 非独立源；只取「`on=true` 且带 `remain_sec`」的第一个灯，车端无倒计时时不写 |
| `distance_m` | 7714 主源改为 7711 `stopLineDist`，只取 `0 < d ≤ 125 m`，四舍五入取整 | **有偏近似**：到停止线 ≠ 到灯杆，通常短数米；超距/哨兵 127/null 一律省略 |
| 黄灯 | 协议无 `yellow` 槽 | 车端 `=3` 时不伪装成红/绿，退回高德；高德也无有效相位则整条流撤销 |

灯态是**跨源合成**：`navigation\TrafficSignalComposer.kt` 先判车端帧可用性（灯色 1/2 + 距离
`(0,125]` + 7711 新鲜度 1.5 s），可用则以车端相位为主；高德只在「同相位」时补倒计时
（车端红 ↔ 高德红；车端绿 ↔ 高德左转绿）。相位冲突时不借用，避免把左转剩余秒安到直行红灯上。
车端不可用时完全退回原高德逻辑（`AmapNavigationSignalSource.toSignal`，status=1 或 status=2+direction=1，
1–999 s 真实倒计时）。

车端灯**不要求** `isNavigating`：它是 CAN 实测量，与导航状态无关；未导航时同样发送，
但 `source_timestamp_ms` 只能取发送时刻（车端 `timestamp` 是车机单调时钟，不能与墙钟混用）。

**接收陷阱：** `_signal_light()` 以灯对象非空判定有效，忽略对象内部 `valid=false`。因此未知灯必须整对象省略，不能发非空 invalid 对象。

**发送侧机制（已核准）**：`TrafficSignalData.putLight()` 只在「有 1..999 s 倒计时」或「该相位明确点亮」时才写灯对象。
后者是本轮为车端 CAN 加的：车端只给灯色、不给秒数，若沿用旧条件，车端红灯会被整条丢掉。
灭灯（`on=false`）且无倒计时仍然整键省略，保持「无有效相位 = `lights` 整对象省略」。
`ui_counter` 只在存在「`on=true` 且带 `remain_sec`」的灯时派生，**不是独立倒计时源**。

当前源码明确存在 `traffic_signal → cereal → control parser → carrot_serv → TrafficLight 参数` 链，cluster 也有灯态解析。仅有这些搬运代码不足以证明最终车辆控制影响，不能沿用旧报告中“必然直接控制”或“完全无人使用”的绝对结论。

证据：

- `AmapBroadcastHandlers.kt:1452–1523`。
- `navigation\AmapNaviSourceData.kt:24–45`（`AmapNavigationSignalSource.toSignal` 的放行条件与倒计时扣减）。
- `navigation\NaviDataModels.kt:225–300`（`TrafficSignalData` / `putLight` / `ui_counter`）。
- Carrot：`carrot_navi_cereal.py:139–143, 255–280`、`carrot_navi_control.py:197–222`、`carrot_serv.py:1030–1050`。

## 9. 路线与导航状态

| 字段 | 当前来源／处理 | 缺口与边界 |
|---|---|---|
| `route.remain_distance_m/remain_time_sec` | ROUTE_REMAIN_DIS/TIME | 已接，但共享新鲜度有部分字段续期问题 |
| `route.total_distance_m` | ROUTE_ALL_DIS／10003 ROUTE_DISTANCE | 已接 |
| `route.moved_distance_m` | 总距离减剩余距离 | 合法时计算，不是独立测量 |
| `route.moved_time_sec` | 全程预计时间减剩余时间（`ROUTE_ALL_TIME` − `ROUTE_REMAIN_TIME`，见 `AmapGuideExtras.totalTimeSec`），7.9.32 起发送 | 与 `moved_distance_m` 同口径的**派生值**；高德按路况重估会跳变，非单调秒表；全程 < 剩余时整键省略 |
| `route.polyline[]` | 无当前选中路线点列接入 | 恒空，模型线、终点连线均不能代替 |
| `navigation_status.mode/guidance_active` | 导航状态与引导／路线新鲜度 | 已接 |
| `navigation_status.route_present` | 路线摘要有效 | 不表示已有几何，不能因 polyline 为空就直接判此字段错误 |
| `navigation_status.off_route` | 无真实地图偏航／恢复写入链 | 未发；默认 isOffRoute 不能直接接线 |

**行程部分字段续期：** 10001 只收到剩余时间时会保留旧距离，或反过来，同时刷新整个 route receipt。因此组合报文新鲜不等于两个值均新鲜。

**几何能力缺失：** 当前 App 没有提供路线点列；接收端支持最多 256 个 `{lat,lon}` 点，并用于 navi_points 路线消费。不能另算一条 REST 路线冒充当前车机路线，也不能从模型或转向距离拼路线。未核实设备其他路线来源时，不能进一步断言整个设备所有路线能力都失效。

证据：

- `AmapBroadcastHandlers.kt:705–707, 861–877, 1079–1101`。
- `navigation\NaviStreamManager.kt:102–117`。
- `navigation\NaviDataModels.kt:282–320`。
- Carrot：`carrot_navi_cereal.py:181–191, 288–310`、`carrot_navi_control.py:174–194, 229–233`、`carrot_man.py:492–534`。

## 10. 本机模型、手机与 7000 的来源边界

### 10.1 本机模型实际输出

当前通路为：

```text
5001 WebRTC road 视频
  → 解码帧旁路（RoadFrameTap）
  → LaneInference（容量 1 队列、独立推理线程）
  → LaneResult（LaneInference.live 状态流）
  → UI：顶部标题栏 ArLaneChip 车道数／当前车道文字
  → ModelLaneCountSource → NaviStreamManager → 7714 lane_current（估计值，条件发送）
```

> **页面已改名**：原先的 `ui\components\ArCameraPage.kt` 已删除，内容并入
> `ui\components\RealTimePage.kt`（「实时」页 = 原看板 + 原 AR），`LaneInference` 的创建、帧旁路、
> 生命周期释放都在该文件的 93–128 行；标题栏 chip 仍是 `ui\components\ArLaneChip.kt`，
> 直接读取 `LaneInference.live` 单例，不经过 RealTimePage 传参。

| 模型结果 | 看起来可对应的 7714 字段 | 当前判断 |
|---|---|---|
| `lanesTotal` | `lane_current.count` | 已在无有效地图车道图时回退使用；模型观察与地图路口图的范围、条目数可能不同 |
| `lanesLeft` | `current_lane` | 已作为 1-based 估计发送，要求不超过 count；未证明与地图图示逐项对应 |
| `lanesRight` | 辅助检查车道数量 | 无可直接对应的同名标准键 |
| 路面输出张量 | `route.polyline`／逐车道方向 | 当前不回填掩码，无地理配准，不能当地图路线或 Back_icon |
| 耗时、帧数、尺寸、亮度、模型名 | 无相应导航业务字段 | 模型运行诊断，不是地图事实 |

模型结果已有 `atMs` 推理完成墙钟时间，发送侧按 1.2 秒窗口过滤；它不是视频采集时刻，
也没有提供路线绑定、来源会话等完整发布元数据。本机模型仍依赖实时页视频生命周期，不是后台常驻道路数据源。

“模型已经算出来／已经接线”与“已验证为 7714 地图事实”是两回事；当前实现及尚未完成的同场对照见第 15 节。

证据：`ui\components\RealTimePage.kt:93–128`（LaneInference 生命周期与帧旁路）、`ui\components\ArLaneChip.kt:44–46`（读取 `LaneInference.live`）、`vision\LaneInference.kt:171–212, 300–335, 409–423`、`vision\LaneResult.kt:6–27`。

### 10.2 其他已有数据不应误用

| 来源 | 当前可用于本次映射的内容 | 不能据此补齐的内容 |
|---|---|---|
| Android Location | 真实手机位置、可用速度与 bearing，作为定位回退 | 地图吸附证明、导航动作点、路线几何、地图偏航 |
| 手机方向传感器 | App 内已有方向处理 | 当前 7714 不以磁罗盘补未知 Location bearing |
| 高德只读展示缓存 | UI 路口图示、巡航灯／巡航道路等 | 超过原 TTL 的控制车道源、导航灯替代源 |
| 7000 compact | 车速／巡航／采用限速／模型／导航反馈等本地展示和既有业务输入 | 不回灌为 AMap 道路限速、地图车道、地图灯态、地图偏航或选中路线 |
| 7000 modelV2 实验几何 | 已有本地模型曲线和宽度带估计 | 地图主路总车道数、合法本车车道、可用车道或车机路线 |
| **7711 `xiaoge_data.py`（Tesla `DAS_road`）** | 车端 CAN 直读的 `stopLineDist`（米）与 `trafficLightColor`（**1 红 / 2 绿 / 3 黄**）：本机卡片画停止线＋灯色，**并作为 7714 `traffic_signal` 的主相位源**（见 8 节） | 只在灯色 1/2 且 `0 < stopLineDist ≤ 125 m` 时参与；编码仍按各自码表解释（车端 3 = 黄，高德 3 = 左转绿，从不互灌）；黄灯无槽位不伪装；`stopLineDist` 是停止线距离，作为 `distance_m` 属有偏近似 |

### 10.3 7711 通道的边界（本轮新增）

`selfdrive/carrot/xiaoge_data.py` 在本机另开 **TCP 7711**，20 Hz 主动推送：

```json
{"version":1,"sequence":N,"timestamp":<monotonic>,"ip":"...","data":{"carState":{"stopLineDist":<float>,"trafficLightColor":<int>}}}
```

- 推送帧格式：4 字节大端长度 + UTF-8 JSON，客户端无需请求即可接收。
  服务端 `handle_client()` 另接受 4 字节大端命令值 `2` 并回复 4 字节 `0`；本 App 不使用该分支，不能称协议完全没有请求／响应
- 车端从 `CarParams` 解出 `CP`，只在 `CP.brand == "tesla"` 且 `DAS_road`（地址 605，party bus 2）1 秒内有帧时才发，
  否则整包不发 → App 侧表现为「一段时间没有新帧」
- App 消费方：`data\XiaogeBridgeClient.kt`（长度前缀分帧、2s→30s 退避重连、socket 读取超时 3s），
  断连／读取结束后清空客户端 world；`DATA_TIMEOUT_MS=1500` 目前仅声明未使用。
  `DashboardStore.publishWorld()` 保存样本，`snapshot()` 另按本机单调接收年龄 0..1500ms 过滤，
  再合入 `DashboardSnapshot.road` 的 `trafficLightState` / `stopLineM`。展示过期不等于客户端 flow 在 1.5s 时清空
- 新鲜度限制：客户端未校验 `version`、`sequence` 或源 `timestamp`，每条可解析包都更新本机 receipt。
  车端可在 CAN 缓存的 1 秒窗口内重复推送，因此本机 1.5 秒 TTL 不等于源 CAN 年龄上限 1.5 秒，也不证明源帧持续前进
- **与 7714 的关系：本轮起接入 `traffic_signal` 的数据生产链**（此前只喂本机卡片）。路径：
  `XiaogeBridgeClient.world` → `DashboardStore.publishWorld/latestWorld` → `StoreSignalWorldSource`
  → `NaviStreamManager.update()` 的 `traffic_signal` 段 → `TrafficSignalComposer.compose()`。
  仍**不写** `carrotManFields`，也不回灌任何高德字段（`vehicle`、`speed`、`lane_current` 都不受它影响）
- 放行门槛：灯色 ∈ {1,2}、`0 < stopLineDist ≤ 125`、7711 帧龄 ≤ 1.5 s（与卡片同一 TTL）。
  超距/哨兵 127/null/≤0 一律退回高德，reason 记 `car_signal_distance_out_of_range`；
  车端黄灯因 7714 无槽位同样退回
- 灯色语义差异必须记住：7711 的 `3` = 黄灯，高德 `TrafficHint.state` 的 `3` = 左转绿。
  卡片用 `resolveLamp()` 优先采用有效 CAN 灯色，无有效 CAN 灯时回退高德；
  `TrafficPill()` 的倒计时／方向仍直接取高德。**这是跨源组合显示，尚无同一路口／方向／相位的关联验证**，
  不能把它称为已校准的统一灯态或认为倒计时必然属于当前 CAN 灯色。
  7714 合成侧同理：只在相位一致时借倒计时，不做「灯色取 A、秒数取 B」的无条件拼接

证据：App `XiaogeBridgeClient.applyFrame()`／`runLoop()`、`DashboardStore.snapshot()`、
`DriveViewCard.resolveLamp()`／`TrafficPill()`；车端 `xiaoge_data.py` 的 `handle_client()`、
`initialize_tesla_can()`、`collect_tesla_das_road()`；灯色枚举另见参考项目
`artifacts\dashpilot-main\dashpilot-android\app\src\main\assets\vehicles\tesla\bus_2_tesla_party.dbc:387`。

## 11. 问题与缺失汇总

这里的优先级是后续研究／修复顺序，不是已验证的实车事故严重度。

| 优先级 | 问题 | 源码支持的后果 | 分类 |
|---|---|---|---|
| 高 | 转向码与接收语义不一致 | 环岛左右、左后方等两端解释不同 | 映射不一致，需核准原码 |
| 高 | 未知 AMap ICON 原码透传 | 与 CP 已知动作码碰撞 | 确定可达的码域风险 |
| 高 | 区间缺剩余距离仍 active | CP 接收为 active=true、remaining=0 | 跨端缺失语义缺陷 |
| 高 | 普通 SDI 继承区间残留 | CP 使用残留 block distance 和区间模式 | 状态清理缺口 |
| 高 | 12110 按键存在而非数值有效判区间中 | 无效内容也可能激活区间发送状态 | 发送／展示门槛不一致 |
| 中高 | SDI 子字段互相续期 | 旧 type/limit 可获新 receipt | 源事实绑定缺口 |
| 中高 | route 子字段互相续期 | 旧距离／时间可获新 receipt | 源事实绑定缺口 |
| 中高 | 区间限速来源混用 | 道路限速与 CAMERA_SPEED 可能不同 | 来源不一致，场景待取证 |
| 中高 | 未知道路类别接收残留 | App 撤流后 CP 仍可能保留旧 roadcate | 接收端兼容阻塞 |
| 中 | 缺定位速度／方向补 0 | 未知变为 0km/h、0°进入定位源 | 接收端有效性缺口 |
| 中 | 推荐扩展字段未被标准链消费 | App 有推荐，CP 当前车道条不按此显示 | 协议接线／语义缺口 |
| 缺源 | 当前选中路线 polyline | 无法通过 App 此链提供真实路线几何 | 不得伪造或另算 |
| 缺源 | 地图偏航／恢复、动作点、灯距 | 无可靠源事实发布 | 取证后再接 |
| 待契约 | ego lane、available、turn_info、完整灯态 | 有估计／原始码或部分字段，不等于语义已验证 | 独立契约评审 |
| 待取证 | 定位单位、普通电子眼／次 SDI 类型 | 变量名和入口不足以证明类型及单位一致 | 真实广播样本确认 |
| 低（代码卫生） | 两个同名 `CHECK_INTERVAL_MS`（200 / 1000） | 改错常量不生效，节拍与预期不符 | 命名冲突，非数据缺陷 |

## 12. 接收端默认值验证记录

以下合成输入经过实际接收转换／control 解析函数，未启动服务、未发送车辆命令：

| 合成输入 | 实际解析结果 | 含义 |
|---|---|---|
| vehicle 仅提供合法 lat/lon | heading=0、speed=0 | 省略可选字段不能保留未知 |
| lane 有 count 与推荐扩展，缺 road_category／available | categoryValid=false、control category=0、available=[]、turn_info=[] | 类别默认风险与推荐扩展未接线 |
| section active=true、limit=80，缺剩余距离 | active=true、remaining=0 | 缺距离未阻止区间激活 |
| red 对象 valid=false、on=true、remain=10 | redValid=true、selected lamp=red | valid=false 不能撤销非空灯对象 |

另外对 App 转向表与 CP `_update_tbt` 字典进行了只读比较，得到第 5.1 节中的语义差异。这些验证不代替手机真实广播、设备 cereal 对照、停车联调或实际控制验收。

> 第 2 行（lane 缺 `road_category`）对应的其实是**接收端**行为：App 当前在 `roadCategory == null` 时会整条
> `lane_current` 撤流（`NaviStreamManager.kt:210–218`，reason=`unknown_road_category_receiver_compat`），
> 所以这条合成输入只能验证「假如有发送方这么发，CP 会怎么处理」，不代表 App 现在会发出这种包。

## 13. 建议的后续顺序（尚未实施）

### 第一阶段：已有来源的正确性

1. 核准 AMap→CP 转向码，禁止未知码跨码域原样透传。
2. 合并 `CHECK_INTERVAL_MS` 的两个取值（200 与 1000），避免「改了常量却不生效」；顺带确认 `SEND_INTERVAL_MS` 是否还有用途。
3. 梳理主／次 SDI 与区间状态，定义进入、部分缺失、退出、普通事件切换及新路线撤销。
4. 为复合源字段保留必要的独立 receipt／有效性，避免新包续旧值。
5. 明确接收端定位可选值、区间必需值和 road category 失效语义；只改 App 未必能闭环。
6. 保持已修正的道路类别边界、raw/policy 分离、源 TTL、失败入队重试和部分字段撤销。

### 第二阶段：真实来源取证

核对当前选中车机路线点列、地图偏航／恢复事件、动作点坐标、灯距、完整灯态方向、逐车道标志和独立码表。优先停车使用现有有界诊断，真实数据先脱敏，再形成合成回归 fixture。无源继续省略／撤销。

### 第三阶段：模型独立接线评审

模型估计已接入已有字段，但仍需评审观察与地图推荐如何关联、来源会话与采集新鲜度、数量不同时如何处理，
以及离页／冻结／换目标时如何撤销；不能把目前的数值范围检查视为已验证车道身份。

本次只把研究结果整理落盘，**没有实施以上修复，也没有修改 App、接收端、车辆参数或控制命令。**

## 14. 本轮复核修正清单（2026-10-20 第二轮）

第二轮改动记录如下，后续已确认的错误在本表和正文同步更正。**未修改的章节不代表所有结论与行号都已重新核准；
新增车道接线以第 6.1、10.1、15 节为准，历史行号可能随工作区变化而偏移。**

| # | 位置 | 原内容 | 修正后 | 依据 |
|---|---|---|---|---|
| 1 | 第 1 节结论 3 | 8 个流 present、5 个流撤销 | **9 个 present、4 个撤销**（撤销的是 `lane_ahead`/`crossroad`/`camera_state`/`composition_state`） | `NaviStreamManager.kt:77–224`：`app_status` 恒发，撤销只有 4 处 |
| 2 | 第 2 节信封字段 | 缺 `schema_version` | 补入字段列表 | `NaviDataModels.kt:109–132` |
| 3 | 第 2 节 200ms 说明 | 「200ms 检查循环…受约束」表述含糊 | 展开为「200ms 触发 + interval 1000 节流 + on_change 去重 + `lane_current` 不参与定时重发」，并点出两个同名 `CHECK_INTERVAL_MS` 取值冲突 | `NaviStreamManager.kt:21, 247–253`；`NaviDataModels.kt:25`；`MainActivityLifecycle.kt:949` |
| 4 | 第 3 节入口行号 | `NaviStreamManager.kt:59–229`、`MainActivityLifecycle.kt:879–935` | `:59–224`、`:897–957` | 两文件当前长度 306 / 1042 行，原区间内容已偏移 |
| 5 | 第 4 节 heading | 曾误称磁罗盘属于“7000 展示链” | 仅确认写入共享 heading；手机 7714 样本无 bearing 时省略方向，其他消费未确认 | `LocationSensorManager.updateLocation()` |
| 6 | 第 5 节证据 | 将 cereal 转换引用误说成失效的转向码表引用 | 区分 `_guidance()` 字段转换与 `_update_tbt()` 动作解释，不据此声称码表迁移 | `carrot_navi_cereal.py`／`carrot_serv.py` |
| 7 | 第 6/8 节 | 未说明兼容键与 `putLight` 的省略机制 | 补 `lane_count`/`recommended_lane`/`available[]` 的写入条件；补「invalid 或超范围时连键都不写」 | `NaviDataModels.kt:337–370, 246–256` |
| 8 | 第 10 节 | 引用已删除的 `ArCameraPage.kt` | 改为 `RealTimePage.kt:93–128`，并新增 **10.3 节** 记录 7711 通道边界 | 页面合并为「实时」页；`XiaogeBridgeClient.kt` 新增 |
| 9 | 第 5.1 节补充 | CP 码 6 被写为 fork-left | 改为 fork-right；7／44／17 等才是 fork-left | `carrot_serv._update_tbt()` |
| 10 | 第 2 节节流 | 不足 interval 一律跳过 | 明确收缩撤销后同次立即尝试重发子集及失败重试 | `NaviStreamManager.publish()` |
| 11 | 第 10.3 节 | CarProp、无请求响应、客户端 1.5s 清空 | 改为 CarParams 的 CP.brand、主动推送另有命令响应、展示 TTL 与 socket 超时分层 | 该节列出的客户端／车端函数 |
| 12 | 第 10.3 节 | CAN 灯色与高德倒计时被当作统一灯态 | 明确跨源未关联、接收 receipt 不证明 CAN 持续更新 | `TrafficPill()`／`applyFrame()`／车端 CAN 缓存 |
| 13 | 第 1／10／13 节 | 模型仍“仅展示／未接入”，与第 15 节冲突 | 同步已接线事实，区分推理完成时间与采集时间，保留未验证的地图关联限制 | `LaneCountSource`／`LaneResult`／`NaviStreamManager.update()` |

补充确认（复核后维持原文，列出以免被误当遗漏）：

- 第 5.1 节四个转向码的 CP 解释**完全正确**，已在 `carrot_serv.py:596–643` 逐条对到。
- 第 6 节 `road_category` 映射（0/6→1、1–5/7–9→6、其余撤销）与 `AmapNaviSourceData.kt:16–21` 一致。
- 第 7.2 节五条问题路径均可在当前源码定位，其中「普通 SDI 继承区间残留」的机制是 10001 分支里 `else ->` 保留旧值。
- 第 8 节的灯态放行条件（status=1，或 status=2 且 direction=1，倒计时 1–999s）与 `toSignal()` 一致。

## 15. 第三轮变更：lane_current／lane_ahead 落地（2026-10-20 下午）

第 6.1 节描述的实现已经进入源码，这里只记录**改了什么、验证到什么程度**：

| 项目 | 变更 |
|---|---|
| `LaneResult` | 新增 `atMs`（该帧推理完成时间），供消费方判断模型是否仍在出数 |
| `navigation\LaneCountSource.kt`（新） | 窄接口 `latest(): Sample(count, currentLane, atMs, model)`；生产实现读 `LaneInference.live`。抽接口是为了单测能注入、能验证过期行为 |
| `NaviStreamManager` | 构造增第 4 参数 `laneCounts`（默认 `ModelLaneCountSource`）；`lane_current` 的 `count` 在无地图车道图时取模型值，`current_lane` 取模型 `lanesLeft`（1-based，且必须 ≤ count）；新增 `lane_source`/`model_count`/`model_current_lane` 诊断键 |
| `navigation\LaneTurnCode.kt`（新） | Back_icon → 方向语义 → CP 转向码（掉头 14 > 左 12 > 右 13 > 直行 51），整组不可译时返回 null |
| `LaneData` | 新增 `turnInfo`（长度必须等于 count 才写）、`laneSource`、`modelCount`、`modelCurrentLane` |
| `lane_ahead` | 仍撤销，reason 细化为 `next_junction_lane_map_absent`；元素结构与 `lane_current` 同构，接入下一路口图时无需改协议 |
| 版本 | `versionCode 26102028` / `versionName 7.9.28` |

**lane_ahead 为什么还是空的**：高德 13012 只推**当前路口**的车道图（就是 `lane_current` 用的那组），
本机模型只给 ego 车道数，两者都无法表达「再下一个路口」的车道组。CP 目前也完全不消费 `laneAhead`
（cereal 转出后进 CarrotNavi，`cluster_renderer`/`navi_control` 都没有读取点）。所以这里保留撤销，
而不是把当前路口图复制一份充数。

**原实施记录报告的验证**：单测 335 项 0 失败，新增 6 项覆盖
「Back_icon→CP 码（含掉头优先）」「模型车道数/当前车道回退」「地图优先 + 模型补当前车道」
「模型越界不发」「模型 1.2s 过期回落」「lane_ahead 撤销 reason」。
**未做**：真实高德车道广播与模型输出的同场对照（需要实车路口），以及 CP 端收到后的仪表显示确认。
本轮文档更正仅复核源码与表述，没有重新执行该批单测，不能将上述历史计数视为本轮运行结果。

---

## 16. 后续核对（2026-10-21，7.9.45）

同一批源码重新逐字段核对后的可视化版本（含接收端 `carrot_navi_cereal.py` /
`carrot_navi_control.py` 的真实读取点、逐流字段判定与问题清单）见
[`cpdazi-7714-field-audit-2026-10-21.html`](cpdazi-7714-field-audit-2026-10-21.html)。

与本文第 5.1 节结论的差异：**转向码 21/22 的环岛左右已对调、未知 ICON 已改为 -1 不再原码透传**
（`AmapManeuver.kt`），本文相关段落属于历史记录；仍有偏差的是 4/5/6 三条与 mobileSdk 表的 1000/1001。
新增待处理项：车端只有灯色没有秒数时 CP `_apply_carrot_navi_traffic()` 会清空 TrafficLight（remain 必须 > 0）。
