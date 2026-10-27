# Dashpilot 驾驶界面移植完善记录（7.9.33）

## 范围与结论

参考本地 `artifacts\dashpilot-main\dashpilot-android` 的 `WebDashView`、
`CarStateBridge`，及其实际加载的 `dash-apps\web-vanilla\index.html`。
本轮完善 CP搭子原生 `DriveViewCard`，不是复制 Android 外壳或新增 CAN 接口。
使用已有7000 compact解码、独立服务TTL与本机快照，不修改车端、7714、7706或自动控制。

最终交付版本为 **7.9.33 / 26102033**，四个语言资源中的桌面名称同步更新。
本轮最初从7.9.30增加至7.9.31；构建期间工作区并行升至7.9.33，保留较新版本，没有回退其他变更。
实时页固定驾驶卡与5001摄像头画中画保留，下方详情独立滚动；
首页保持当前实时0、导航1、控制2、指南3，不恢复已合并页面。

## 已完善的映射

| 原版元素 | 本 App 实际来源 | 本轮处理与边界 |
|---|---|---|
| 大车速、挡位 | 7000 `carState.vEgo`、`gearShifter` | m/s转km/h，数字截整；合法0保留，过期撤值，不解析本地化卡片文本 |
| 方向盘角度/激活颜色 | `carState.steeringAngleDeg`、独立`carControl.latActive` | 旋转实际转向角；不再要求纵向同时激活才变绿，不把颜色当授权 |
| 巡航设定/激活颜色 | `carState.vCruiseCluster`、独立`carControl.longActive` | 已是km/h，不重复换算；P挡隐藏，过期/缺失不显示定速 |
| 红圈限速牌 | `carrotMan.nRoadLimitSpeed`，经既有展示模型验证 | 仅车端采用限速；删除建议速度兜底，缺失不画牌；不是高德原始限速或下一测速限速 |
| 建议速度 | `carrotMan.desiredSpeed` | 仍放左下并标明建议；250哨兵不显示，不冒充限速 |
| 转向灯 | `carState.leftBlinker/rightBlinker` | 点亮时绿色闪烁，关闭/缺失不画；无激活灯时不运行无限动画 |
| 制动灯/车尾灯 | `carState.brakeLights` | 保留明确制动提示及车形刹车灯；不是驾驶员踏板或停车原因 |
| 盲区 | `carState.leftBlindspot/rightBlindspot` | 左右渐变和明确方向提示；不生成有虚构坐标的相邻车辆，不把false当安全 |
| 驾驶提示 | `selfdriveState`告警、`lateralPlan`变道阶段、既有导航测速事件 | 车端告警优先，其次准备/开始/收尾，最后最近的已知前方测速/区间起点；收尾不称完成 |
| 道路/车道线 | `modelV2.position/laneLines/roadEdges`、`laneLineProbs` | 只绘制真实样本0–50m内范围；边界插值只用于裁切，不外推，不补默认4m直路 |
| 车道颜色/虚实 | 独立新鲜`carState.leftLaneLine/rightLaneLine` | 仅内边界使用已核实0/1、10/11、20/21、30/31；外侧不借用内侧码，未知不解释为物理通行权限 |
| 绿色道路带 | 两条模型内边界 | 概率均至少0.7、有共同范围且全段不交叉才填充，不证明本车真实车道编号或合法性 |
| 挡位场景 | `carState.gearShifter` | D后视，P侧视，R俯视，N/其他/未知后视；侧面与俯视改用独立车形，不再仅移动同一车尾剪影 |
| CAN灯色/停止线 | 原有7711只读入口，经`DashboardStore`1500ms本机接收TTL | 保留现有功能，灯色1红/2绿/3黄；停止线仅0–127m开区间；不新增CAN订阅或迁到7000假字段 |
| 高德导航灯 | `AmapNavigationDisplay.trafficSignal` | 须Fresh态势及原有灯源TTL；使用对应红/绿秒数，方向含左右掉头与未知，不把未知画成直行 |
| 红绿灯场景对象 | 原有7711真实灯色/停止线距离 | 独立矢量灯杆示意，不使用高德倒计时或动作距离；横向位置是示意布局，不是测量定位 |

数据入口为新 `data\DrivingDisplay.kt` 和 `DashboardSnapshot.driving`。
不再通过“实际车速”“采用限速”等中文指标标题寻找数值；网页仍使用原有显式JSON面板，
除下面列出的无源指标移除外，不改变其传输协议或投屏尺寸。

## 无可靠输入，未移植的清单

“没有”指**当前App解码的7000 compact子集没有已验证字段**，不等于车辆或上游完整cereal永远没有。
不为补界面新增raw/CAN订阅，不用同名但不同语义数据代替。

| 原版功能 | 当前缺口 | 处理 |
|---|---|---|
| 动力电池SOC、电量、续航/充电状态 | 无已核实SOC/续航/充电字段；`fuelGauge`不是通用电池SOC契约 | 不显示、不估算 |
| 总里程/小计里程 | 无odometer/trip字段 | 不用速度积分或导航已行驶距离代替 |
| 电机功率、能量回收/耗电功率条 | 无真实功率及其正负/标定契约 | 不用加速度、请求加速度或制动灯代替 |
| 安全带、车门警告图标 | 无`buckleStatus`、`anyDoorOpen` | 删除无源卡片入口及相关指标，不假装未触发 |
| LDW车道偏离警告 | 无`laneDepartureWarning` | 删除死绘图/指标输入，不用转向灯、盲区或模型概率推断 |
| MADS/原车ADAS统一状态 | 无参考CAN车型对应字段 | 不冒充完整状态；分别展示7000明确的横向/纵向激活 |
| Experimental图标 | 无同语义、完整状态输入 | 不从当前规划风格猜测 |
| 原版固定车道宽度 | 当前wire无`modelV2.laneWidth` | 移除默认4m字段和无效读取，使用实际模型曲线 |
| CAN灯距改走7000 | 当前compact没有对应真实灯距/停止线字段 | 只保留此前已经恢复的7711只读链，不造7000字段 |
| 原版手机电池/温度附属面板 | 非现有驾驶数据快照；此前已要求移除设备诊断/附属展示 | 本轮不重新引入设备采样与附属面板 |

## 与原版仍有的视觉/平台差异

- 原版采用WebView内Bevy/WASM真3D，本轮为**独立原生Canvas矢量近似**。
  后视、侧视、俯视车形不同，但没有移植原3D网格、光照、材质或真实相机运动，
  不能称逐像素一比一复刻。前轮转动也只是车形示意，不是车轮角传感器输出。
- 原版盲区用固定位置合成侧车图形；本App保留方向提示与渐变，避免把它画成有真实位置的雷达目标。
- 原版固定直道/速度驱动世界动画不能代替7000真实模型，故缺模型时明确等待、停止填路。
- 保留本App摄像头画中画、固定白色驾驶卡与其他页面深色主题；不复制参考App的CAN车型配置、
  固件操作、Web主题设置或能源界面。以上不是无源字段已被补齐。
- 当前未进行手机截图或像素对照，不声称大字体、窄屏及SurfaceView合成已在实机验收。

## 验证记录

本轮按要求**不使用ADB，不连接、不安装、不运行手机测试**。

- 定向JUnit覆盖`DashboardRoadTest`、`DashboardSnapshotTest`、
  `XiaogeBridgeWorldTest`、`CarrotDisplaySnapshotTest`、`AmapNavigationDisplayTest`，
  最终隔离构建**70项全部通过，0失败/0错误**。
- 新增覆盖：无建议限速混用、0/非法值、各服务独立过期、未来源字段不发布、
  最近已知测速事件、曲线裁切不外推、低概率/交叉边界不填路、线型独立TTL。
- 仪器测试源码补充P挡隐藏、独立侧/俯视场景、缺限速/转向灯、CAN/高德灯分离。
  测试APK构建不等于运行通过。
- 网页回归：`node --test app\src\test\js\dashboard.test.cjs`，**21项通过**。
- Release编译、R8、资源压缩、lintVital、测试APK编译均通过；最终单独打包命令返回`BUILD SUCCESSFUL`。
  使用SDK `apksigner`核验v2签名通过，`aapt dump badging`确认包名、
  versionName/versionCode和桌面名称均为预期值。
- 全仓`lintDebug`仍失败：**15错误、858警告**，包括DeviceManager权限、
  旧API等级调用、原有produceState检查及清单权限等；不把Release的lintVital通过当作全Lint通过。
  本次驾驶卡/新数据模型无Lint错误。
- 全仓`detekt`仍失败：**2540项**；最终报告中
  `DriveViewCard.kt`、`DrivingDisplay.kt`、`DashboardRoad.kt`、`DashboardSnapshot.kt`无条目。
  Compose PascalCase和数值协议/绘图常量沿用组件既有抑制约定，
  纯数据边界函数的提前返回仅局部抑制ReturnCount，未新建全仓基线或禁用规则。
- 共享标准输出目录曾出现Kotlin缓存锁/测试发现冲突，因此最终用会话私有Gradle init脚本、
  buildDirectory与project-cache目录隔离构建，关闭Kotlin增量并限制单worker；
  不清理或终止其他构建，也不提交临时构建配置。
- **最终APK**：`artifacts\CPdazi-7.9.33-driving-ui-release.apk`，
  41,936,389字节（约39.99MiB），arm64-v8a。
  这是隔离构建的签名Release复制件，复制前后SHA-256一致；
  不用可能被其他构建覆盖的常规`app\build`输出作为本次交付。
- SHA-256：`9B10E3AC8C5B5246A52648CEC016CC64B95694EF4352FED43B201C5127AFB121`。
