# CLAUDE.md

本文件是 AI 编码代理在本仓库工作的项目指令文件。

**8.1.10 左侧导航隔离**：`LeftGuidancePolicy`在7714边界过滤Carrot fork-left码，
控制页`CarrotAmap.suppress_highway_left_guidance`默认true，由既有200ms发送循环读取，
不新增轮询。当前/下一guidance及lane_current动作副本共用有效性；
新鲜同路线ROAD_TYPE 0/6及未知/失效拦截，已知常规道路不拦截，开关关闭恢复旧映射。
撤销立即发送、失败重试；常规道路放行时信封取道路类别/guide最旧receipt。
真实左出口也被过滤，UI必须说明；本机高德显示、手动变道、其他导航流不改。
禁止用宽度带当作地图车道身份，禁止写WSL或声称实车误动作已复现/排除。

超车模式按钮区分“关闭/点击切换”和拨杆、自动的“仅保存·未启用”；
监听overtake_mode偏好更新，延迟回调不得回写旧模式。此为本机配置，
不解除compact缺完整授权的自动请求保护，手动左右变道请求保持独立。

> **维护规则**：当项目结构、构建/测试命令、架构边界、开发约定或本文件记录的其他事实发生变化时，必须在同一变更中更新本文件。

**匝道汇入恢复（覆盖旧按距离升速）**：`SmartSpeedLimiter`以同路线入口文本、当前匝道名配
低限速或既有40→60保护建立90秒候选；仅真实当前限速70–120稳定3秒/源receipt递增才恢复，
按单调时间每3秒最多10km/h，不依赖旧动作距离，不猜高架80/高速120或使用测速限速。
曲率输入从既有compact回调读取controlsState.curvature与carState.vEgo，不再读未赋值orientationRate。
双源<=500ms/接收差<=150ms且分别递增；乘积为观测角速度近似，<=0.01rad/s持续2秒且>=3组，
>=0.02rad/s或缺失/过期/非有限/车速<5m/s撤确认；这是工程附加门，不证明汇入或安全速度。
保留原显式弯道保护开关。路线/源断档/目标切换清旧，超时阻止本轮恢复。
7714策略与7706仍独立；自动巡航额外受恢复cap及当前设定+10约束，原始限速上限、
既有激活门/15秒重试保留；等待7000设定回报，非预期设定变化取消本轮自动巡航提速，
退出巡航或换路线后解除。无新增订阅/定时器/控制授权/车辆参数或诊断控制命令。
部分限速缺失撤目标并保留有界候选；断档撤旧后禁止独立raw自动提速绕过，等待新入口证据/
换路线或手动设置。仅策略到目标且7000巡航设定回报到目标后结束该状态，不当请求ACK或实际车速确认。
智能调速页显示等待/曲率/超时原因，策略完成不当车辆执行。未作本轮手机安装或车辆联调。

**8.1.5 直板横屏**：按页面实际约束width>height且height<480dp判断短横屏，
默认隐藏地图/视频并取消5001会话和帧旁路，Dashpilot按可用高度限制宽度后居中，
详情仍可滚动。挡位进入同WebView全屏，Back回驾驶卡后媒体仍隐藏；
隐藏地图不占尺寸/输入/语义，稳定组合父节点不变。较高展开屏保持原布局，
不改系统方向、投屏分辨率、车辆控制或任何来源TTL。

**8.1.3 窄屏实时页**：有效可用宽度（除以至少1的fontScale）<600dp时，
视频全宽固定顶部，高度按真实比例且不超过页高28%；下方单滚动区为地图→驾驶卡→原详情，
地图按投屏比例、最高320dp完整显示。>=600dp保留原地图/视频并排、驾驶卡固定与详情滚动。
SurfaceView始终不放入滚动容器。地图保持稳定组合父节点与单WebView，滚动时用未裁切锚点尺寸，
外层裁到下方视口，禁止遮住视频；全屏仍铺满页并暂停视频，Back恢复原滚动位置。
窄屏/详情各自持有滚动状态，折叠/展开不丢状态，不改投屏分辨率、TTL或控制行为。
`RealTimeCompactLayoutTest`覆盖窄屏顺序、滚动、全屏返回、宽窄切换和同WebView；
既有实时页/全屏测试显式用宽屏density，避免依赖测试机当前折叠状态。

**8.1.0 P0核查修复**：桌面`app_name`固定CP搭子，关于页单独显示BuildConfig版本/版本码，
以后只维护Gradle版本源。`DashboardSnapshotPublisher`绑定Core scope，容量1合并请求，
Main捕获不可变`DashboardInputs`、Default计算、Main发布；有更新时丢弃旧结果，
不续源TTL，取消后不回灌；compact客户端内部到期/映射不继承Main。
7714保留串行锁/会话/发送顺序，耗时用`Navipilot.7714.update` trace；
看板trace为`Navipilot.dashboard.snapshot`。不盲目把整个发送循环移到后台或解锁JSON。
会员专用客户端HTTPS-only、拒绝重定向、无公网HTTP套餐回退，LAN HTTP/WS保持。
签名材料不跟踪，保留本机同钥；不自动轮换或重写历史。7711的1500ms门与自动超车暂停保持。

**8.0.24 自动能力状态**：控制页禁止/拨杆/自动都是配置档，常驻“请求暂停”及只读状态卡。
`data/AutomationObservation`只按既有compact连接/超时和必要服务原TTL显示原因，
即使全部新鲜且active=true也不授予控制权限；不调用旧超车update、不新增计时器。
条件实验页明确仅配置、自动切换未启用，保存不写车辆参数；手动实验/Chill独立标明。
保持compact展示门、自动超车挂起、手动左右请求链及入队非执行边界，路缘/AR不补授权。
不采用可变默认允许发送的evaluateOnly或延迟变道队列。现状以
`docs\fix-auto-overtake-wiring.md`顶部实施记录为准。

**8.0.21 路缘参考（补充下方融合契约）**：7000 `modelV2`左右路缘分别验证，
5m/15m内线宽与路缘余宽一致才给等宽带候选；至少3个递增帧跨600ms、间隔<=750ms。
UI优先左起宽度带，左不可用则给右起宽度带，不倒推出左起编号或总车道数。
AR逐侧标注一致/冲突/未核准，不把上限分类补成精确计数。单侧几何仅供显示，
不能进入7714 `current_lane`，原双源融合发送条件不放宽。
几何参考无需AR成功，但必须有当前前台视频session与同host/route的新7000样本；
保持各服务原TTL、lateralPlan=off及<=300ms接收差，过期/变道/rollback/身份或模型变化重确认。
仅新鲜高德ROAD_TYPE=0/6启用几何参考与AR帧旁路；匝道不能凭动作/常见车道数推定，
类别未知或常规道路停用。视频叠层两行摘要，点击可滚动看距离/宽度/计算方法/限制，避免长文字遮视频。
路肩/导流区/分合流身份未验证，明确显示“宽度带/非定位确认/非变道依据”。
不新增订阅/计时器、控制授权或WSL写入。版本以Gradle为准。

**8.0.15 车道融合（覆盖下方旧AR直接编号规则）**：`data/LaneFusionTracker`联合同帧AR
左右计数和7000 `modelV2`的5m/15m宽度带；需要新鲜明确lateralPlan=off、本机接收差<=300ms、
至少3组双源递增观测跨600ms。配对间断>750ms重启确认；无采集时间同步或路肩/合法车道认证。
AR第3类的精确/饱和语义未核准，撤编号，不扩展成3+；原始8个分数保留，左右/总数同帧，
不再历史总数中值配单帧编号。仅显示“融合估计 · 非定位确认”，不提供伪造置信百分比。
缺失/冲突/变道/过期/rollback/host/route/session/model变化清旧；接收TTL取各源较短者。
共享`ModelLaneCountSource`只在融合成立时给7714；高德路口图存在时current_lane=-1，
即使count相同也不能证明和当前横截面对应，model_*诊断保留估计；无图时使用融合估计且
仍需新鲜道路类别。融合仅在高德新鲜`reportedRoadType`为0（高速）或6（快速路/高架）时启用，
其余道路状态撤为未启用。复用Core现有500ms采样，禁新订阅/定时器/控制授权/WSL写入。

**8.0.14 显示契约（覆盖下方旧门槛）**：Jetlink遵循当前Carrot `jetlink_status.badge`：
仅jetSON要求专用健康遥测；MAC/iOS/Android的有效ready/active报告不因fresh=false或
severity=unknown误标警告。active主标签只显示设备名（如绿色MAC），就绪仍显示READY，
明确warning/error、断开及HTTP失败仍黄/红；本机接收3秒TTL和生命周期门不变。
高德导航灯展示只需选中AMAP及有效、同路线、未过期的独立灯快照，不再等待isNavigating
或routeTasksReady；缺失/非法、倒计时结束、停止/换路线/切源仍撤旧。不改7714/CAN或巡航码表。

**2026-10-23 协议修复（覆盖旧7706完整包说明）**：7714独占导航正文；
7706周期、命令、目的地和调试预览均不携带GPS、限速/道路类别、SDI、TBT或行程，
目的地只在显式兼容操作中发送，不由缓存导航状态自动夹带。禁止为carrotCmd恢复完整包
或转向块去重。7714行程摘要要求同一真实guide包同时有距离/时间，缺失/异常撤摘要，
不以新字段续旧兄弟字段；不把预计全程减剩余当`moved_time_sec`。
道路名绑定定位样本，不读共享旧路名。主/次SDI和区间控制使用不可变同路线/receipt快照，
部分包不借旧限速/区间状态；区间必须有真实正`END_DISTANCE`及同包`LIMITED_SPEED`，
不用智能道路策略或总长度替代。普通摄像头/区间终点清旧区间控制。
普通靠左/右和环岛掉头没有核准等义CP码时撤wire guidance，本地高德标签/图示独立保留；
左后方用CP16，不能用fork-left17。CAN缺倒计时及未知道路类别撤流残留仍为接收端阻塞，
不补假值、不改WSL。版本仍以Gradle为准；本轮不隐含ADB安装或车端采用验证。

**当前实时页/投屏布局（覆盖下方历史说明）**：固定区先地图/5001视频行、后Dashpilot驾驶卡，
8.0.11两格等高，宽度按投屏服务真实width/height与视频帧比例分配，不再强制等宽；
高度上限生效则整对居中，不裁切/拉伸，不改共享虚拟屏。复用原500ms前台投屏状态观察，
未运行时地图4:3仅作占位，端口探测身份门不变。
详情独立滚动，SurfaceView不放入滚动容器。播放器内嵌/外部均contain完整显示，不裁掉顶部；
按visualViewport尺寸/偏移适配，回退innerWidth/innerHeight，触摸按实际图片比例换算。
仅外部浏览器尝试全屏，权限拒绝则首次触摸重试；不强制重新进入已退出的全屏。
内嵌不全屏、不上报viewport、不改变共享虚拟屏。相关浏览器回归：
`node --test app\src\test\js\dashboard.test.cjs app\src\test\js\player.test.cjs`。
`ProjectionPlayerFitTest`在手机真实WebView加载本地fixture的实际播放器/JPEG，
验证宽/窄容器完整显示与触摸坐标，不代表真实高德图源、外部浏览器全屏或车辆联调。

## 高德红绿灯显示边界

**8.0.6当前契约（覆盖下方历史说明）**：最低Android12/API31。7714灯态以新鲜7711 CAN优先，
CAN不可用才回退独立高德灯态；不拼接高德方向/倒计时，不把停止线充当灯距。
CAN黄灯因协议无槽位撤销而非退回另一来源。7711 sequence与源timestamp须递增；
重复不续期，非法/回退包断线清旧，读取超时及展示TTL统一1500ms。
源timestamp只判顺序，不证明CAN采集时刻；7714使用真实本机墙钟接收时刻。
模型样本绑定host/routeGeneration/前台视频session，换目标/路线/Renderer、后台或离页清旧；
`atMs`为帧进入推理旁路的墙钟接收时刻，同时用单调年龄限制1200ms，不是相机采集时间。
内嵌投屏仅消费`ProjectionState.serverPort`（服务支持8080–8099），
`/health`须有`service=navipilot-projection`、`schema_version=1`、`running=true`；
不以TCP开放或固定8080/8081判定投屏。Jetlink点击查看只读原因，异常不替换主状态。
版本以Gradle为准，桌面名称固定CP搭子、关于页显示BuildConfig版本；既有设备ID不迁移，车辆参数不变。
7706心跳只含carrotIndex/epochTime/timezone，禁止写carrotCmd（即便heartbeat）
覆盖待消费请求。命令副本固定命令/方向/序号并绑定入队目标，换目标中止，
异步失败明确通知；最近非空命令诊断不被心跳/清除包覆盖，也不是车端ACK。
最新modeld直接把carrotMan送入DesireHelper，不以旧get_carrot_man导航限速门控
解释LANECHANGE或伪造导航绕过车辆条件。compact仍缺完整控制授权，自动超车暂停，
模式开关仅保存配置；拒绝入队不可记为变道动作，实车排查不自动发送控制命令。
Jetlink胶囊采用Carrot风格短词READY/ON/LOAD/LINK/RETRY/OFF及ERROR/WARN/DATA?；
健康就绪/使用中均绿但文案区分，准备/警告黄、异常/断开红、未知/等待灰。
警告保留主阶段，中文详情与原因点击查看，不把一般warning推断为过热。
驾驶卡右下角IP/状态整块通过`onConnectionClick`打开既有7706调试弹窗，
不得只保留视频空态中的点击入口；读诊断不发送命令。
最新Carrot只读根为`\\wsl.localhost\Ubuntu24\home\jeff\carrot`，接收源码在
`openpilot\selfdrive\carrot`。纯函数探针确认CAN无倒计时相位能进cereal/control，
但`carrot_serv._apply_carrot_navi_traffic`会因`remain_sec<=0`清除TrafficLight。
此跨端消费限制仍阻塞，不伪造正倒计时，不修改WSL或宣称车辆采用。

**8.0旧UI清理**：`MainActivityUI`不再保留未接入生产首页的`AmapHeaderCard`、
`CommaConnectionBadge`、`DrivingHudCard`及其私有辅助/旧状态车道面板；
对应旧`DrivingHudVisibilityTest`已移除，不再引用这些组件。高级功能直接在控制页
`AdvancedFunctionsContent`展示，不恢复失效的`showAdvancedDialog`回调链。
保留当前`RealTimePage`/`DriveViewCard`、Core连接状态及CAN灯枚举，不改变7714/7706。

导航胶囊从 `NaviSourceTracker.displayNavigationSignal` 读取独立路线/接收时间快照，
不从共享 `trafficLightState` 或通用时钟重建。选中AMAP且收到有效独立灯快照即可显示，
不等待导航状态或路线任务就绪；
使用原始status 1红/2绿及该相位真实正倒计时，显示扣除接收年龄，倒计时结束或10秒TTL到期即撤下，
不猜下一灯态。未知方向用中性问号，不默认直行；缺字段、异常、停止、换路线、切源或时钟回退撤旧。
未核准的status 3/4不映射黄灯。巡航 `lightsData` 独立使用status 0红/1绿、
dir 1左/2直/3右，保留广播倒计时，不套导航dir码表。
此显示快照不放宽7714的 `navigationSignal.toSignal`，不更改CAN灯、7706或车辆控制。

## 项目概述

**8.0.20 全屏地图彩蛋**：点击实时页挡位，将原内嵌地图铺满页面安全区域；
系统返回键/手势只退出全屏，恢复原布局与详情滚动位置。固定地图组合父节点，
按原行内锚点或全屏尺寸布局，保留同一个WebView，避免跨子组合搬移崩溃；
不新建投屏会话或调整共享虚拟屏；全屏期间暂停5001视频/帧旁路并隐藏SurfaceView，
返回后恢复前台视频生命周期。没开启投屏仍显示原提示，不因挡位点击自动开启投屏。
隐藏的驾驶卡/详情同时隔离触摸与无障碍语义，加载空白不能穿透触发控制入口。
`RealTimeMapFullscreenTest`覆盖同一WebView连续切换、返回分发、滚动/虚拟屏尺寸保留、
未投屏空态及加载期间触摸隔离；`RealTimePageTest`还验证视频全屏暂停后重新出真实帧。

**Jetlink只读组件**：实时页前台通过选中目标的7000 `GET /api/egpu/model`每2秒查询，
仅消费嵌套`jetlink`，不以外层eGPU `active/available`代表Jetlink连接。左上角组件以灰/黄/
绿/红分别表示未知或等待、准备或警告、健康就绪或使用中、异常或失联；
`fresh`只表示健康遥测新鲜，`ready`与`active`分开，断开可保留设备标签但不能显示就绪。
HTTP限时1秒、响应64KiB、禁重定向，成功接收后本机单调年龄3秒到期撤旧；
离开实时页/后台取消请求，换目标清旧状态，错误明确显示查询不可用并记诊断。
不新增服务端接口、订阅、车辆命令或控制权限，不把iOS细分为iPhone/iPad。

Navipilot（CP搭子 / 特斯拉搭子）是 Android 车机导航辅助应用，两大能力：
1. **投屏**：通过 Shizuku/Stellar 特权服务创建虚拟 Display，运行本机安装的高德车机版，ImageReader 捕获 → MJPEG → 局域网 HTTP 服务（默认 8080，被占用自动 8081-8099），浏览器触摸经 WebSocket 反控。
2. **comma3/openpilot 联动**：高德车机版导航广播 → WebSocket 7714 Navi v2；UDP 7706 仅保留心跳/时间、命令及目的地兼容信息。车辆/HUD遥测使用 WebSocket 7000 compact；实时页按需读取5001 WebRTC road视频。当前另恢复7711灯色/停止线只读展示输入，不授予控制权限，不以相似字段补齐缺失数据。

**版本**：以 `app/build.gradle.kts` 的 `versionName` / `versionCode` 为准。
每次通过ADB安装到手机前，必须递增versionName和versionCode，先构建再安装并核验设备版本。
Jetlink角标使用与右上角ModePill一致的浅色胶囊、细描边、字号和顶部内缩，不使用深色底块。

**当前首页与投屏入口（覆盖下方历史布局）**：以当前源码7.9.64为准，`HomeDashboardTabs`只有实时0/控制1，默认实时；导航内容已并入实时页，旧右侧分类栏不再使用。驾驶卡方向盘进入控制，控制页左上角返回/切页；保留saveable索引收敛和`dashboard-v2-*`状态键。唯一前台500ms高德采样器仍在分类外。实时页固定驾驶卡及地图/视频行，下方详情独立滚动；左格`LocalMapBrowserPanel`内嵌投屏，右格恒显示5001 road摄像头，按真实帧尺寸适配。7.9.64已移除路口示意/30426 JSON IPC显示链，不按路口数据替换摄像头；下方7.9.57/58说明仅为历史取证和实现记录。

**本地HTTP端口边界**：`ProjectionService`将实际绑定端口写入`ProjectionState.serverPort`，
服务从8080尝试至8099。`LocalMapBrowser`只探实际端口、最多三次，要求有界
`/health`包含`service=navipilot-projection`、`schema_version=1`、`running=true`；
重启/换端口须重新核验，不能只测TCP。WebView使用`/?embedded=1`及`/frame` JPEG，
外部浏览器使用`/stream` MJPEG，内嵌不争抢viewport或改变虚拟屏。
`RealTimePage`在高德广播新鲜或投屏服务运行任一成立时尝试连接；
只开投屏、未导航也应显示地图。

**7.9.57路口显示边界**：实时页右侧`JunctionCrossView`是广播驱动的示意图，不是官方SDK模型渲染。通过`maneuverGraphic`解释导航ICON，不从中文label或Back_icon猜动作；无动作编码不能默认直行，环岛不猜出口。仅`Fresh`且`routeReady`时消费`amap.junction`，不得用保留的`displayJunction`绘制实时道路；已确认左右排序的完整进口车道保留全部推荐，不能定位本车或推断出口车道。不得恢复固定匝道/桥墩/路牌。`JunctionPhotoHolder.photos`为StateFlow，完整引导图绑定路线代次、精确动作及源receipt，10秒内有效，Fit原样显示，不裁切/自造引导叠层；目前没有生产图片发布者，不得用5001前视帧冒充高德实景。已有图片优先接口不等于官方实景来源已接通。保留当前左右引导卡/示意图位置与摄像头前台生命周期，不改变7714/7706。

**高德路口数据取证与7.9.58接线**：车机9.5.0.600013的45秒模拟导航广播实测中，10019的`EXTRA_CROSS_MAP=0/1`只是状态，不含图片；外接屏CROSS_CONTROL/TYPE是控制输入，KEY_PICTURE_PATH是拍照结果输入，GuideInfoModel的Bitmap来自转向图标。30426发送动作无标准广播Intent接口，但JSON IPC回调实测可接收`iconId/aroundNum/iconRoadInfoList`。`AmapJunctionIpcClient`仅对已验证版本、实时页前台且Fresh/routeReady时注册只读回调，离页/后台/换路线释放；不发请求/设置。30426在1500ms内与后续30407动作码匹配，再核对广播真实`AmapGuideExtras.currentSegmentIndex`和当前动作，原始单调receipt的10秒TTL不被guide续期。航段/动作变化、断开/非法包撤下，缺失航段不补0。仅画有界0–255整数点序，未知`roadUsage`不解释，不虚构宽度/模型；JSON≤64Ki字符、道路≤32、每路坐标值≤512。图片优先，其次“高德动作矢量 · 非模型路口”，否则原示意/视频。事件没有已核准路线ID/源时刻，此关联不证明真实模型身份；非周期快照，仍非`AMapModelCross.picBuf1`或实景图片。临时探针已移除，官方完整图源仍未接通。详见README；禁止以广播状态或转向图标冒充官方路口图。

**当前工作区覆盖旧版说明**：首页为实时0／控制1，默认实时；原看板与AR合并到`RealTimePage`，固定`DriveViewCard`与下方详情独立滚动，不能外套整页滚动造成SurfaceView越界。左侧嵌入地图、右侧5001摄像头，保留v2滚动键与唯一高德采样器。`XiaogeBridgeClient`接收7711长度前缀JSON灯色/停止线，校验递增sequence/源timestamp，读取超时和`DashboardStore`单调接收TTL统一1500ms。灯色3为黄灯，导航hint 3为左转绿；CAN场景/灯色与高德导航灯独立显示，不借用彼此距离、方向或倒计时。CAN相位按顶部规则参与7714，不写共享导航、不补控制授权。

**7.9.33驾驶视图完善**：`DashboardSnapshot.driving`使用`DrivingDisplay`类型化消费原7000独立TTL快照，不能反解析中文指标文本。方向盘/巡航分别反映`carControl.latActive/longActive`，不作为控制授权。限速牌只读车端采用限速，不能用建议速度补缺；P挡隐藏定速/限速。D/未知、P、R分别采用独立后视、侧视、俯视矢量车形；并非原Bevy真3D。只绘制模型真实范围内0–50m曲线，不外推/补默认道路，线透明度使用真实概率；绿色带要求两内边界概率>=0.7、范围重叠且不交叉。无源安全带/车门/LDW与虚构车道宽度代码已移除。高德hint只取Fresh态势下独立新鲜灯组和对应红/绿倒计时。移植映射、无源清单及验证记录见`docs/dashpilot-ui-port-report.md`；不新增订阅、车端参数、7714或7706行为。

**当前模型与7714边界**：实时页5001帧旁路→容量1推理队列→`LaneInference.live`，
经`LaneFusionTracker`双源确认后才由`ModelLaneCountSource`进入`NaviStreamManager`。
融合count须1..8、current须1..count，各源原TTL/1200ms上限及host/route/session门保持。
地图图示有效时count优先地图且`current_lane=-1`，同数量不能证明当前横截面关联；
无地图图示时才可使用完整融合编号，仍需新鲜同路线道路类别。单侧路缘宽度带只供UI。
`atMs`是接收墙钟不是采集时刻，不等于定位确认或控制授权。
`LaneTurnCode`独立翻译Back_icon，任一未知省略整组turn_info，不套导航ICON或补available。
完整现状见`docs/cpdazi-7714-current-source-mapping-audit.md`。

**7714源数据维护**：`AmapNaviSourceData`与`NaviSourceTracker`保存独立路线/receipt绑定的原始道路类别、导航scalar灯和实时车道，实时发送不得借用长期只读保留的`laneDisplay`。仅7714边界把ROAD_TYPE 0/6→CP1、1–5/7–9→CP6；不改共享legacy roadcate。未知/10撤lane_current而非默认0，接收端control忽略Valid且serv撤流残留旧roadcate仍阻塞，详见`docs/cpdazi-7714-data-remediation-plan.md`。车道需显式enable及完整唯一0..N-1排序；available无源省略、isAvailable为nullable，turn_info缺码表省略，禁止套导航ICON表，current_lane仍-1。灯只发布已核准范围及真实正倒计时，right/uturn只补JSON能力，其他绿灯/组合方向仍待证据；不以转向距充灯距。源重置/过期撤旧，复合子字段收缩立即先撤旧再发子集且保留失败重试；lane_current无新事实不得心跳续门控。不增加数据源、算路、轮询、命令或WSL修改。

**7.9.27自动巡航提速**：App生命周期后台任务只在7000 `controlsState.enabled=true`、`carControl.longActive=true`且两项均在原500ms TTL内时工作。使用同路线、10秒内的高德原始当前道路限速，不使用前方测速、区间限速、车端采用限速或SmartSpeedLimiter策略值；限速须在60–120km/h内并连续稳定3秒，且高于当前`vCruiseCluster`，才通过既有7706 `SPEED <limit>`单次命令向上修正。绝不自动降速；路线/限速变化重启3秒确认，命令占用不算成功，同一目标等待车端回报并最多15秒后受控重试。成功入队显示Toast；不修改7714、手动SPEED、车辆参数或变道命令。

**7.9.26 AR WebRTC**：AR从旧7000摄像头WS/本地NAL解析与MediaCodec管线迁移到`http://<selectedIP>:5001/stream`。Android使用WebRTC SDK创建无STUN/TURN的recvonly video transceiver及兼容teleoprtc的`data`通道，提交`sdp`、`cameras:["road"]`、空bridge列表和稳定安装client_id，`takeover=false`、`carrot_state=false`；仅接收road H.264轨，默认/实际比例964×604。离页、后台、换目标、重连或Renderer销毁释放track/data channel/PeerConnection；短暂冻结保留连接，20秒无帧才重建。HTTP409 `carrot_vision_busy`明确显示，不自动抢占。移除旧`RoadCameraPlayer`/`CameraWire`及H.265测试fixture依赖；不新增车辆状态、控制、推理、车端参数或后台摄像头会话。

**7.9.26看板顶部重排**：Board固定标题区移除“看板/车辆与导航数据/只读”文字，改为单行横向滚动仪表；仅显示当前有新鲜值的挡位、巡航设定、实际车速、车端建议速度、采用限速、左右黄色转向箭头（点亮时闪烁）和制动灯，缺失/过期项不显示“未知”且不占位，数值/图标在上、辅助标签在下。下方滚动摘要保留告警、横纵向激活、变道阶段、当前引导、实际纵向加速度和制动保持，详情只保留规划与控制。完整高德、导航对照及7000导航/限速面板迁到Navigation(2)；车辆状态不再重复成卡片；车辆附属、设备诊断、定位质量及其展示快照模型删除。模型/横向/目标观察仍不在手机Board渲染，但其未删除快照与网页数据保持原TTL。

**7.9.25智能调速**：修复只更新本地nRoadLimitSpeed、未进入7714链路的问题，明确覆盖7.9.2“7714始终原值”策略。AMapRoadSpeedLimit保留原始limitKph及独立policyLimitKph/policyReceivedAtMs；7714 speed.road_limit_kph优先有效、同路线且新鲜的策略，无有效策略回原值。复合信封取包含字段最旧receipt。10001/独立12110均通过SmartSpeedLimiter；40→60受智能调速/匝道保护开关及明确高速/快速路类型约束，不改区间测速限速。原始地图展示保持40，策略输出可为60；关闭后新广播回原值，缺失/停止/换路线/过期不能复用策略。智能调速页显示最近计算记录而非发送/执行确认；7706及手动SPEED不变，无新增控制授权或车端参数修改。

**7.9.25右侧分类栏/清单**：首页分类栏移至内容右侧，宽59dp（原88dp减少约1/3），按钮最小高59dp，字体放大可增高；连接图标/状态/IP不截断，侧栏独立滚动，折叠零占位、标题按钮重新展开。默认看板及索引、v2滚动键、内容组合和唯一采样器保持。`artifacts/dashboard_inventory.xlsx`是看板源码盘点，含原生高德完整嵌套信息、模型图、网页差异及字段/TTL/布局/源码位置，不含实车数据。

**7.9.24接收背压**：队列仍8帧，移除DROP_OLDEST；满时只在OkHttp接收线程最多等待500ms让单消费者腾出空间，不阻塞主线程、不新增异步任务或扩大队列。超时才丢当前帧，由本地序号识别缺口，flush/关键帧门保留。RoadCameraPlayerTest覆盖突发第9帧等待消费而不丢前8帧。

**7.9.24 AR本地解码恢复**：真实日志捕获releaseOutputBuffer抛MediaCodec.CodecException导致WS重连。`CameraSurfaceDecoder.recoverCodec`只处理codec故障，记录diagnostic/recoverable/transient并释放损坏codec，保留同源参数集、原WS等待真实关键帧。正常序号/积压缺口改用同步codec.flush，waitingForKeyframe期间不重复flush且不喂P帧；恢复首帧补VPS/SPS/PPS，不能仅依赖configure时旧CSD在flush后仍存在。渲染回调使用volatile代次隔离flush/reset前回调，不把旧回调续成新鲜。真配置/编码/尺寸变更仍重建。原时效、队列、生命周期及不使用WebRTC保持，日志记录实际decoder名称。ArCameraPageTest新增真实MediaCodec复用/无重复参数头恢复、P帧门及注入CodecException后缓存参数集恢复和真实绿色像素。

**7.9.23广播IP/侧栏**：7705广播优先解析JSON有效IPv4 `ip`作为连接目标，缺失/非法记录警告回退UDP来源；覆盖7.9.22来源优先策略。沿既有选中目标回调自动连接compact/7714及前台AR，不新增采样或硬编码IP。侧栏功能按钮上方`SidebarCommaConnection`展示状态图标、原7000连接徽标及选中IP（沿selectedCameraHost目标流更新）；仅发现不是在线，保留握手/独立新鲜度门。88dp侧栏可滚动、折叠零占位，现有页面索引/默认看板/滚动状态不变。NetworkTargetSelectionTest覆盖JSON与来源不一致及非法地址回退；AdaptiveLayoutsInstrumentedTest覆盖大字体侧栏IP变化、切页与折叠恢复。

**7.9.22自动发现**：`CarrotManNetworkClient`不再固定探测192.168.88.247或优先192.168.88网段。7705以UDP真实发送方作为连接端点，不信报文内可能过期的其他接口ip；新发现可接替已过期目标，保持在线选中目标不被其他设备抢占。主动兜底只枚举当前wlan/eth/ap私网IPv4的局部/24候选（排除本机），16并发分批，7000只读`/api/heartbeat_status`必须返回HTTP200及最多1024字节的`ok=true`/`hb` JSON才认设备；开放TCP端口不再是已验证依据。不扫描VPN/蜂窝，不回退旧phoneIP，不新增摄像头订阅或改系统网络。既有目标通知同步AR/compact/7714；手机必须与设备网络可互通。`NetworkTargetSelectionTest`新增10.0.0.x真实来源覆盖旧报文IP、过期目标自动替换、候选网段约束、无关/过大/错误HTTP响应拒绝。

**7.9.21 AR积压/握手恢复**：`RoadCameraPlayer`在WS/hello最多等待10秒，hello由listener独立校验、通过`CompletableDeferred`交付，不进入帧队列；4秒输入/8秒解码时钟从握手完成开始。8帧`DROP_OLDEST`二进制队列通过listener本地递增序号保证即使无encodeId也能发现丢帧；消费者发现本地缺口、源缺口或>500ms过期帧时在原WS内`resynchronize`，过期帧不解码，真实关键帧前不喂损坏P帧。队列满不关闭socket，仍保留包大小上限、独立源/尺寸配置清除、1秒冻结撤下与真实超时重连。断开日志包含消费/渲染/重同步计数、握手完成状态、HTTP/底层错误与有界远端关闭原因，不记录帧内容。`ArCameraPageTest`新增4.5秒hello、无encodeId的128帧突发、1928×1208/30fps真实HEVC GOP一分钟丢帧/突发和真实静默超时；fixture为原创绿色含P帧`camera-road-gop-green.h265`，不能把本地测试等同真实车端网络验证。不使用WebRTC，不修改车端或系统网络配置。

**7.9.20看板紧凑布局**：`DashboardOverview`保留仪表首屏，详情顺序明确为导航与限速→车辆与跟车→规划与控制→模型与边界→车辆附属→设备与定位诊断。原生高德完整内容放在导航组开头，模型图后移到模型组，不丢参数或新建采样。每组共用一个8dp内边距外框，内部服务分区用细分隔线，标题/真实来源/独立时效FlowRow可换行；指标仍64dp最小高/18sp值，不截断。仪表间距5dp，车速表200dp且随fontScale>=1增高，仪表内容有效宽>=500dp横向排列两侧观察与车速表，否则纵向；控制状态栏复用有效宽度1–3列规则。默认看板、既有索引/滚动键、源TTL、AR、投屏与控制行为保持；`DashboardOverviewTest`覆盖优先顺序、全部指标保留、正常首屏和1.5倍字体。

**7.9.19 AR丢帧恢复**：`CameraSurfaceDecoder.resynchronize`只释放参考帧/codec并保留同源参数集，避免encodeId缺口后没有重复参数头的关键帧无法恢复；真正换编码或尺寸才撤旧配置。输入缓冲暂不可用时最多500ms循环排空输出并可取消等待，不因一次10ms超时关闭WS。单次连接持续至少10秒后重试退避归零；8帧队列、1秒画面撤下、4秒输入/8秒解码超时保持。`ArCameraPageTest`以约30fps HEVC无重复参数头、反复序号缺口持续36秒检查仅一次WS连接及真实绿色像素，原AVC/HEVC切换/断流/生命周期场景继续覆盖。不使用WebRTC、不改车端。

**7.9.18 AR编码兼容**：AR继续直连`ws://<selectedIP>:7000/ws/camera/road`，不使用WebRTC、不访问`/stream`且不增加WebRTC依赖，避免其单客户端占用与浏览器播放冲突。`CameraWire`优先信任cereal `frameType`：`livestreamH264`/`qcameraH264`为AVC，`fullHEVC`为HEVC；仅在旧服务缺少类型时按codec及参数集回退识别，不能同时用AVC/HEVC位布局检测同一NAL并据此判混合编码。`RoadCameraPlayer`使用原生MediaCodec分别处理SPS/PPS/IDR与VPS/SPS/PPS/IRAP，编码或配置变化、encodeId断档时重建并等待新关键帧。保持8帧有界单消费者、自动目标连接、1928×1208占位/实际crop比例、离页/后台释放和原超时重连；不启动车端编码参数、JPEG轮询、转码、推理或控制。

**7.9.17使用指南**：首页追加指南(5)，看板0/车辆1/导航2/控制3/AR4与默认看板不变。`UsageGuidePage`是离线只读文字页，覆盖首次连接、各页用途/入口、投屏准备、会员/参数备份与排障；只用已有AdaptivePageColumn滚动，不新增网络、采样、权限或自动操作。标题固定、v2分类滚动独立保存，折叠零占位。新增`AdaptiveLayoutsInstrumentedTest.guideRemainsReadableScrollableAndRestoresPositionWithoutOpeningOtherFeatures`覆盖1.5倍字体、全部说明可达、折叠和切页位置恢复。

**7.9.16 AR比例/自动连接**：视频默认占位1928×1208，收到真实解码尺寸后按源比例居中Fit，包含MediaCodec crop边界；同设备等待/过期/重连保留最近比例，不跳回16:9，换设备恢复默认占位。AR目标回调在NetworkManager创建时注册，与compact初始化独立；setOnDeviceIPUpdated重放已选IP，发现后无需按连接按钮，清空选中目标发空字符串并撤下旧视频，不自动跳离看板。前台AR独占流的生命周期不变。仪器用原创1928×1208和320×240 H.264检查真实像素/比例、分辨率切换与断流保留；NetworkTargetSelectionTest验证早/晚监听者、其他设备不抢占和清除事件。

**7.9.15 AR实时画面**：首页追加AR(4)，原看板0/车辆1/导航2/控制3和默认看板不变。AR用固定标题下的有界剩余空间显示源比例视频，不包在纵向滚动容器；折叠仍不重建画面。`camera/CameraWire.kt`核对最新WSL `openpilot/selfdrive/carrot/realtime/transports/camera_ws.py`与`server/features/ws.py`：`ws://<selectedIP>:7000/ws/camera/road`，文本hello后每条binary是4字节大端JSON长度、JSON元数据、header+data Annex-B码流，不是直接裸帧。2MiB整包/16KiB元数据上限、尺寸/size/camera/codec/frameType校验；只收H.264，拒绝HEVC回退。`RoadCameraPlayer`有界8帧队列+单Default消费者，溢出不随意丢P帧而是重连；encodeId断档/源类型或分辨率/SPS/PPS变化重新等关键帧，segmentId是每帧序号，不能当分段代次。原生MediaCodec+SurfaceView直接显示，无JPEG/HTTP轮询、转码、第三方解码器或编码器。真实渲染回调计FPS，1秒无渲染覆盖撤下画面，4秒无输入或8秒无解码重连，2–30秒退避；不把接收时间或源时间当端到端延迟。Compose STARTED内运行，离AR/后台/换设备/Surface销毁取消WS和解码器，不改7000遥测/唯一AMap sampler/投屏/7714/7706，不启动车端参数或授予控制权限。不做车道叠加、模型推理或校准；AR仅视频预览。车内可信LAN使用。`CameraWireTest`覆盖协议校验，`ArCameraPageTest`以原创绿色H.264 fixture经本机WS→MediaCodec→Surface PixelCopy核验真实像素、过期、换目标、前后台及离页释放。

**7.9.14标题折叠入口**：`DashboardSectionHeader`固定在内容滚动区上方，其左侧48dp按钮展开/收起侧栏。展开88dp，收起完全移除侧栏占位（0dp，无48dp轨道）；内容获得全部可用宽度，保留1040dp上限/安全区。内容始终在同一位置组合，滚动仍用v2分类键，折叠不销毁状态或启动额外采样。

**7.9.13首页合并**：左侧折叠栏按看板0/车辆1/导航2/控制3排序，初始选中看板。车辆页完整合并旧车辆/规划/结果：实验几何、连接诊断、超车配置及请求暂停、7714入队/撤销（非ACK）。`HomeDashboardTabs`四分类滚动使用`dashboard-v2-$selectedSection`，避免复用旧六分类滚动位置。侧栏88/48dp、rememberSaveable选中/折叠、内容折叠不重建及独立滚动保留；不改变业务采样或操作。

**7.9.12单一车端来源**：`XiaogeTcpClient`、`ReceivedRoadEdges`、`TeslaTrafficSignalData`和专属卡片已删除。`VehicleObservation`替换旧路口聚合，仅取7000各服务独立时效的实际运动、规划、雷达及保持。旧`XiaogeVehicleData`/`XiaogeControlsStateData`名称仅为内部兼容展示模型，由compact适配器填充，不是TCP输入；不改自动请求暂停门。首屏右下改为7000/carState实际加速度与制动保持，原生与网页不再有Tesla灯色/停止线卡；车端导航灯组仍明确是carrotNavi、不能冒充CAN。所有数据卡按车辆与控制、规划与目标、导航接收、附属与诊断连续分组，高德完整卡在导航分组。车辆分类保留7000实验几何（不是独立meta路缘）与连接诊断；连接只认7000握手/新鲜数据。以下旧版本提到7711的实现均为历史，不适用于当前版本。
**包名**：`com.jixiexiaoge.drivingassist`
**minSdk**：31（Android 12），**targetSdk/compileSdk**：35，JDK 17
**工具链**：Gradle 9.2.1 + AGP 8.6.0 + Kotlin 2.1.0

**首页分类改版**：实机2248×2480px、520dpi约691×763dp；7.9.11使用左侧可折叠“车辆/规划/结果/导航/控制/看板”分类栏，展开88dp，收起48dp仅保留展开按钮。分类栏独立滚动适配小高度/大字体，默认车辆，selectedSection/expanded经rememberSaveable保留，类别滚动键不变；折叠不分支重建内容。7.9.10起重复数据集中看板：车辆页只留独立实验几何/7711路缘与连接诊断入口，规划页只留超车配置/请求暂停状态，结果页显示7714入队/撤销（非ACK），导航页保留原生地图、标题灯组、模式操作和路线任务，控制页保留九按钮与参数。7000数据、7711信号、高德完整态势/巡航道路及导航对照仅在看板；手机看板不重复JSON map-*摘要，改用同一采样快照的完整高德卡以保留车道图/设施/TMC。看板padding8dp/gap5dp，取消重复辅助说明，但保留来源/过期/未知、64dp指标与18sp值和必要只读边界。唯一前台500ms AMap sampler仍在分类容器外，TTL、命令、权限不变。

**7.9.8共享看板**：`data/DashboardSnapshot.kt`以现有compact和7711回调生成不可变只读分区；原始车速/巡航、实际/规划/请求、radar选中目标、盲区三态、四边界概率、告警、7000导航/灯组/区间、胎压/能源与Tesla信号独立来源和TTL。`DashboardOverview`为第六分类，原五页和动作不移除。`DashboardStore`不引用Activity，HTTP线程不读取Compose；7000时钟必须与CarrotWsClient一样为System.nanoTime/1e6，不混elapsedRealtime。高德通过既有500ms sampler发布，来源10秒TTL与750ms采样租约取较短者，任务门撤下当前/随后航段，不影响原始限速。`LocalProjectionServer`增加只读GET `/dashboard`及CSS/JS资源，显式JSON键抗R8，不含经纬度/设备身份/凭据。网页为纯地图全屏（已移除返回键、看板切换、FPS、推广条与看板DOM/脚本）；embedded不启用。浏览器扣全请求耗时、独立到期撤下、失败/隐藏清理，单请求有界，保留原viewport尺寸和坐标映射，不能因侧栏改变虚拟屏。无新增车端订阅、摄像头/标定、编码器或App定时器，无控制命令；网络仅供可信局域网。新增`DashboardSnapshotTest`、`DashboardOverviewTest`及`node --test app\\src\\test\\js\\dashboard.test.cjs`。
**看板仪表布局**：整体座舱首屏，中央实际车速圆弧、左右巡航/挡位与雷达前车值，上方独立控制状态，下方导航和7711观察；按实机约691×763dp正常字体适配。所有来源分区连续向下滑动可见，无明细开关；补充车辆转向/制动、独立controlsState横向量、规划首点/jerk/来源、第二选中目标、模型质量、设备及不含经纬度的定位质量。`DashboardRoad`仅使用新鲜modelV2已有position/laneLines/roadEdges绘制0–80m、横向±12m俯视坐标，严格递增有限坐标、最多64点，原始点不外推，过期撤下；无伪造车道或本车定位，无雷达/7711/摄像头融合。窄屏/大字体允许滚动，不截断。不增加订阅、定时器或控制权限。
**7.9.7首页易用性**：五个首页分类共用标题/用途说明与“只读”或“手动”提示；底部固定栏以图标、文字和选中强调提高可识别性。投屏和会员页共用固定SecondaryPageHeader，提供用途说明和返回首页入口，不随内容滚动。继续等宽、车辆默认、独立滚动与单一AMap sampler；不隐藏内容、不变更TTL、动作或权限。
**7.9.6路口只读观测**：控制页原7711卡复用已有250ms STARTED刷新，将7711灯色/停止线与独立TTL的7000 carState、longitudinalPlan、radarState leadOne、carControl longActive合并展示；保留速度/加速度单位及符号、hold三态、radar未选择与未知差异，不解释为安全/通行/停车/执行结果。7711重复或乱序packet sequence/timestamp不更新信号接收时刻，missing/invalid signal撤下；连接重置序列状态。7711时间是本机数据包接收时间，不等于源CAN帧新鲜度。没有新增定时器、订阅、推理或控制动作。
**右侧紧凑布局**：`VehicleDetailsGroup`用一个外框包含行为、车辆规划、感知三个完整分区；通过局部CompositionLocal取消分区重复外框/padding，所有标题/独立状态徽标/内容/tag保留。TelemetryCard padding8dp、间距5dp，右结果卡距8dp，MetricGrid仍64dp最低高/18sp值，不截断。超车左右观察扣8dp列距、每侧16dp内边距，每侧指标有效宽>=280dp才等权并排，否则纵向，字体缩放至少1。没有折叠隐藏、数据删减、TTL或业务控制改动。

**7.9.4卡片时效检查**：TelemetryCard标题注明具体7000服务；`CarrotDisplaySnapshot.lateralAvailability`取controlsState独立TTL，横向指标不得借modelV2徽标续期。车道线采用注明lateralPlan状态，导航灯组注明carrotNavi状态，控制状态注明carControl状态；实际值仍用原服务TTL。变道收尾非完成确认，删除已移除候选统计的遗留说明。`overtakeSidesUseTwoColumns`扣真实列距和分区padding后复用telemetryMetricColumns，字体1/1.5时内容边界600/880dp，保留全部指标。自适应仪器对齐测量Button外层实际布局，不用48dp最小语义触摸区误判位置；不修改生产地图布局/解码/控制。新增独立过期单测及卡片仪器断言。

**7.9.3超车只读状态**：`data/OvertakeDisplaySnapshot.kt`直接取新鲜compact各服务，不借兼容HUD的默认false；侧前方仅radarState leadLeft/Right status及真实dRel/vRel，status=false隐藏数值、缺失未知，选择目标非已确认相邻车道。车辆车道线用carState.left/rightLaneLine原码（-1未报告、基础末位0/1虚/实，白黄蓝10/20/30，其他未确认）；modelV2概率索引1/2内边界、0/3外侧，不解释为线型或通行权限。`OvertakeStatusCard`放右侧结果区，compact无完整权限始终显示自动请求暂停；配置变更经SharedPreferences监听、DisposableEffect关闭撤监听，不新增采样/网络。未检出/虚线/概率不能显示安全或可超车，不宣称绝对车道或完成；不是恢复独立雷达卡。AutoOvertakeManager空数据/未明确激活/缺模型meta在推断及待执行分支前suspend，suspend清空pending、方向、防抖、原车道、结果与记忆。保留compact只读与现有手动控制入口，不新增自动控制。覆盖`AutoOvertakeManagerTest`、`OvertakeDisplaySnapshotTest`、`OvertakeStatusCardTest`。

**7.9.2最新Carrot对齐**：只读参考`\\wsl.localhost\Ubuntu24\home\jeff\op\openpilot`，不得修改。高德有界队列保存入口接收时间，单主线程消费者将该时间提供给handler的`nowMs`，处理结束清除，不以排队/处理时间续源TTL。7714各流传入真实源时间，heartbeat保留该时间，复合speed/车道用包含字段最旧接收时间；新真实receipt可在原节流下重新发布相同值。GuidanceData可选`time_sec`仅同路线、同次guide的当前/随后航段时间，随后非累计，未知省略、明确0保留。7714道路限速使用`roadSpeedLimit`原始快照+路线代次/TTL，不再用SmartSpeedLimiter输出；本地策略和7706不变，不支持的限速不四舍五入。CompactTelemetryStore按服务校验16位序号（前进1..32767、回绕合法、重连清空），模型还要求32位frameId前进；冻结不续期，模型倒序撤下并在原重连机制建新代次。拒绝有诊断日志，不能把传输活跃当源模型新鲜保证。结果卡显示车辆自带导航测速状态，非高德接收/执行ACK；推理耗时秒×1000显示ms，合法0保留、缺失/过期未知，不是端到端延迟。不增加构造业务字段、摄像头/模型/标定订阅、循环或控制权限。覆盖`CompactTelemetryStoreTest`、`NaviStreamManagerTest`和`CarrotTelemetryCardsTest`新增仪器用例。

**7.9.1路口图示保留**：`NaviSourceTracker.laneDisplay`只接受正在导航时13012显式启用的有效车道数组，绑定原始接收时间和`noaRouteGeneration`；不使用旧SharedPreferences恢复数据。`AmapNavigationDisplay.displayJunction`/`displayJunctionRetained`仅供路口UI，在同路线真实`guideExtras`和`guidance_current`都新鲜时保留超过10秒的图示，标明“最近路口图示 · 非实时推荐”及“原推荐标记”，不带当前动作距离。`junction`/`lanes`仍受10秒TTL，模式候选、导航对照、控制与7714不得消费displayJunction；不得通过guide或UI采样更新`lane_current`。关闭/空/非法包、路线边界（同毫秒车道不算新路线证据）、停止、切源、guide断档、时钟回退永久清理缓存；重复/乱序接收不能复活。复用唯一500ms采样与`routeTasksReady`门，不增加构造状态、定时器或网络。单测`AmapPersistentLaneDisplayTest`，广播与仪器覆盖保留标签、过期撤下和任务门；无设备只构建测试APK。

**7.9.1航段配对与地图放大（旧AdaptiveHomeOverview组件）**：`AmapSegmentPair`复用`telemetryMetricColumns`有效宽度规则并限制最多两列，>=280dp等宽左右，否则上下；当前距离/当前航段剩余时间和随后航段距离/用时必须独立，随后不是累计值。未知仍显示，`AmapNavigationCard`的Fresh与`routeTasksReady`门同时撤下两侧。`AdaptiveHomeOverview.map`接收本地UI `Modifier`，由`MainActivityUI`传入原生`ProjectionMapPreview`；组件在有效内容宽>=600dp时可点击地图在全宽顶层与原左列间切换，有恢复指示和可访问状态；此双列组件不用于当前生产首页。用同一个`movableContentOf`重挂预览，解码/帧源不重建；不新增定时器、HTTP、编码器或反控。宽窄/字体回退单列时`remember(twoPanes)`清除放大，重新展开默认正常大小；UI状态不写业务配置。保留safeDrawing、1040dp上限、4:3容器、Fit完整源比例及960×720虚拟屏，不改浏览器协议。仪器覆盖全宽边界、折叠/字体回退、状态与预览组合只启动一次、真实JPEG/空态点击及航段未知/过期/任务门；没有设备只能构建测试APK，不声称实机验证。

**7.9卡片增强**：`AmapRoadDisplay.kt`独立巡航道路/区间展示模型，`NaviSourceTracker`运行时缓存，不增加CarrotManFields构造字段。显式ICON=0巡航guide只捕获道路、CUR_SPEED、CAR_DIRECTION及原始CAMERA_TYPE/DIST/SPEED；自身10秒TTL，只在AMAP且`isNavigating=false`显示，过期导航仍为true时不复活巡航。新包缺失/无效字段撤下，25巡航结束及导航停止清理，结束3秒拒绝迟包；现有500ms采样`sampleReadOnly`在切源/时钟回退永久清理只读缓存，不改导航时钟或7714。导航速度/八方位航向仅取独立新鲜mapPosition，不借phonePosition/guide时钟；航向有限0<=角度<360，速度0-300km/h。区间阶段仅10001 CAMERA_TYPE8/9=接近/结束，12110实际有效数值距离字段=区间内，不以均速或文本推断；routeGeneration/10秒TTL/切路线/停止/切源门独立。START_DISTANCE_TEXT/END_DISTANCE_TEXT最多80字符，不转数值、不入协议；设施当前/随后各保留原文距离与真实米值、原始类型码，去掉重复当前设施行。`AmapManeuverIcon`独立Canvas，仅导航AmapManeuver已核实ICON，未知中性，路线任务hold撤下动作图，原55码车道图不变。单测`AmapRoadDisplayTest`/`AmapManeuverIconTest`/`AmapCruiseBroadcastTest`，仪器`AmapNavigationOverviewTest`覆盖卡片、动作图、未知和撤下；无设备只构建测试APK，不声称执行。没有第三方PNG、悬浮窗、语音、雷达卡、Jetlink或额外控制/网络。

**7.7入口恢复**：巡航按钮打开选中设备的`http://<IP>:7000`网页；未选择设备时提示连接。首页高德红绿灯卡仅保留原导航信号和连接徽标，不含手机模型推理状态；App不运行本机模型推理。

**7.8导航完善（不恢复Jetlink）**：`ui/components/AmapLaneIcon.kt`以独立Canvas图形覆盖参考lane_pdf的0-48/56/62/71/85/88/89共55个数字码和已知文字别名，不复制第三方PNG。原始明暗分支不等于推荐，复杂码图示不扩大`AmapLaneDirection`验证范围或路线候选权限；`AmapJunctionLane.rawIcon`用于展示，未知码明确中性标记，原方向文字、顺序、推荐标记、路线TTL均保留。诊断弹窗增加“高德输入”：仅打开时记录100包，最多48字段/字段2048字符，包含原始类型、KEY_TYPE及分发后导航/代次状态；分发结束不是字段全有效或车端采用，关闭清空，复用原有有界Channel单消费者。`SettingsBackup`经系统文档选择器导出/恢复白名单参数（路线模式、最小超车速度、速度差），严格schema/类型/范围及64KiB上限，先预览后确认，保存后手动重启；不导出位置、授权、设备信息或控制开关，不清全部prefs，不发送命令。相关测试`AmapLaneIconTest`、`SettingsBackupTest`及仪器`AmapDiagnosticTest`；仪器需设备，不以APK构建冒充执行。

**7.8巡航与只读提醒**：`AmapCruiseState`解析有界`60073.lightsData`，巡航状态0红/1绿、方向1左/2直/3右，其他码未知，禁止混用导航编码。巡航快照独立10秒TTL，不更新导航源时钟/Carrot控制字段；首页仅无新鲜导航任务时在原高德灯卡展示广播灯组。高德原始定位速度须独立新鲜，才与当前原始道路限速对照；区间均速只与区间限速对照，缺失未知，不与前方测速点限速混算，不改速度或发送控制。图形和灯组沿用现有500ms采样，不增加循环/订阅。

巡航JSON限定16KiB/16灯，整数字段及倒计时0-3600，错误清空并记录日志；倒计时显示广播值，不本机合成。10019状态9/39撤导航，25仅撤巡航、不影响另一新鲜导航；结束后3秒拒绝迟到包，明确启动/status1可解除导航等待，10003不解除。GUIDE中的ICON=0以及嵌入车道不更新导航，缺失/非法ICON不能猜巡航；handler复用manager当前AMAP源门。诊断总值字符8192/包，处理时刻不是源接收时刻。特殊图示/高德多实例来源仍需真实广播验证；不改变摄像头类型映射、不新增前后台/昼夜自动主题。

**7.5实验功能调整**：实验性语音导航已移除，首页原有“回家/公司”快捷入口已恢复。

**7.7审查与雷达展示移除（覆盖旧卡片布局说明）**：首页不再调用`CarrotFollowingCard`，其实现及侧向目标区已删除；`CarrotBehaviorCard`不显示radar候选或cutInCount。盲区三态由`BlindspotMetrics`放在`CarrotVehiclePlanCard`，不再增加独立雷达卡；后台compact雷达解析、订阅和安全逻辑不变。仪器测试验证带真实radarState输入仍不显示雷达卡/侧目标/第二目标/切入指标，同时盲区保持时效。`RouteTaskTracker`缺失guide后建立恢复接收时间下界，拒绝之前已接受的旧包；恢复期间乱序包不能重置确认时间后当成新证据。时钟回退清理任务、要求两条新guide，但保留已有偏航锁存，不能用未知/断线7000解锁。`AutomaticDrivingMode`检测本机now回退并重新确认三包/两秒。审查范围主要为近期导航任务、自动模式、发送反馈和数据卡片生命周期，不是全仓库安全认证。

**7.6路线任务闭环**：`RouteTaskTracker`在Core现有500ms高德采样内管理只读任务，绑定`AmapNavigationDisplay.routeGeneration`及真实guide接收时间，10秒TTL；切路线/过期/告警恢复要求两条不同新guide，不能把重复UI采样当证据。7000偏航锁存撤下任务，恢复还需Fresh支持schema的Guiding；断线/未知不能解锁。无路线代次变化只称引导恢复，不称重规划成功，更不是车端采用确认。状态在`RouteTaskCard`、模式候选、导航对照及`AmapNavigationCard`动作/车道/出口区域一致撤下；现有道路限速等只读原始数据保留。随后航段明确原始距离，只有实际用时<=30秒才提示连续动作，不猜冲突方向/累计距离。`NaviSourceTracker.resetGuideDisplay`同步invalidate旧guidance/route/lane/roadCategory/speed/section时钟和区间派生值，不动定位，也不把compact回灌7714；publisher用原有流/撤销/manifest模式。`NaviStreamManager.deliverySnapshot`是四个导航流的成功OkHttp入队缓存，不是接收ACK；通道未就绪隐藏缓存。导航对照只在双方新鲜/引导且地图序号可靠时比较，显示7000实际变道阶段，off非完成。没有请求关联的采用/拒绝原因不伪造。测试`RouteTaskTrackerTest`、`NaviDeliverySnapshotTest`、`NavigationFeedbackTest`；仪器`RouteTaskCardTest`需设备。不得改下载的openpilot树、不得恢复语音或俯视卡。

**7.6模式与卡片**：`DrivingModeAdvice.hold`区分告警、偏航、自动场景未稳定、地图失效、道路未知/失配，撤下候选及priority；`DrivingLanePriority`区分默认组内偏好与导航推荐约束，不宣称是车端执行原因。`AutomaticDrivingMode.evidence`展示不同guide接收时间计数及首末跨度，不用UI轮询时间代替证据，同时间戳不同道路清空检测。Core在唯一现有采样器同步证据，自动/手动切换清理；不增加定时器、网络或控制命令。模式卡蓝/紫/橙对应EAP/NOA/NOO，待识别用中性色；路线任务只用Fresh显示当前动作/距离、随后航段距离和出口，不把随后距离当累计距离，不保留过期任务。MetricGrid细边框及末行Row等权Spacer，不将Spacer放进单个指标Column。新增单测覆盖证据/冲突/撤下原因/导航推荐优先，仪器测试覆盖任务过期及待确认反馈；无设备时只能构建仪器APK，不能宣称实机执行通过。

**7.4态势与紧凑卡片**：俯视卡及其仪器测试已删除，地图插槽仅保留原生预览。`CarrotFollowingCard`保留左右盲区Boolean?三态、第二目标距离/相对速度；未触发不是安全，缺失/非法/未报告前车分别标识。`DrivingModeVehicleCard`几何区保留独立7711双路缘，`ReceivedRoadEdges`拒绝零/负/非有限/>50m，要求包序号/时间递增及1秒本机接收TTL，缺失/重复/乱序/断开/换设备撤下，复用高德500ms采样清TTL；包新鲜不证明源模型新鲜，不是左右车道宽度，不与7000边界至路缘混算。`MetricGrid`有效内容宽度除以至少1的fontScale后>=420dp最多三列，>=280dp两列，否则单列；实际列数不超过指标数量，末行用等权Spacer保持列宽。`NavigationComparisonCard`限制最多两列保护高德/车端配对。通用卡padding10dp/间距7dp、分区padding8dp/间距5dp、指标最小高64dp/值18sp，不设maxLines，不截断值或告警；长解释缩短，保留未知、过期、非授权等关键边界。新增三列/字体回退及迁移字段时效仪器用例在`CarrotTelemetryCardsTest`。导航对照仅地图新鲜、左右顺序可靠及双方引导/推荐完整时比较，非ACK或执行确认。7000新增规划阶段/风格/cruiseTarget(km/h)/jerk、雷达候选、质量及横向量沿用各服务TTL；灯组要求schemaVersion1、linked、guiding、visible及逐灯valid，不自行倒计时。高德extras按最新包替换灯数/原始ETA/设施，不猜单位；自动道路识别使用筛选后的`map.guideDetails`。布局及只读边界不变，不新增网络/推理/自动控制。

**7.2模式与几何更新（覆盖下方7.1模式行为描述）**：默认启用`CarrotAmap.automatic_driving_assistance_mode`，`AutomaticDrivingMode`只累计不同高德guide接收时间：至少3包且首末间隔>=2000ms才采用场景，0=高速、6=快速路/高架待确认、1-5/7-9=常规。重复500ms采样不计包；未知/缺失/过期/换路线/时钟倒退/10秒包间断清理，变化期间`detectedDrivingMode=null`，不继续旧候选。自动结果不持久化为实时状态；点手动模式保存选择并关闭自动，点自动恢复。继续复用唯一前台高德采样回调，不新增循环，切换取消旧待执行自动变道，不写车端参数/命令。高速/快速路默认偏好左起第二条合法通行车道：地图推荐组内取最接近2的编号，距离相同取较小编号；单条推荐或出口/转向推荐优先。常规取推荐组最右条，不把路口数量当主路数量。

**7.2车道几何**：`data/LaneGeometry.kt`只读7000已订阅的`modelV2`，左右当前边界必须位于本车两侧，路缘按右正坐标保持正确顺序，x/y数组长度一致、有限、x严格递增。在同一5m/15m横截面线性内插，不按1秒预测时间冒充当前位置，不外推。需明确新鲜`lateralPlan.laneChangeState=off`、frameAge0-2、内侧线概率>=0.7及线/路缘std<=0.3m、本车道宽2.5-4.5m。路缘距离/本车道宽仅作等宽“宽度带”估计，整数残差<=0.2，两截面编号/右带数一致且车道宽差<=0.4m；推断额外带时对应外侧线需同概率/std/宽度一致性。`LaneGeometryTracker`在现有compact收集器处理：不同递增frameId、包间隔<=250ms，>=3帧跨>=300ms才显示临时宽度带编号。重复包不积累，冻结/乱序/断开/缺失/过期/变道清除编号。量测不足显式状态，不用宽度带假定实际通行车道、本车定位确认、应急/专用道分类或变道许可；右侧展示本车道宽与两侧边界到路缘距离和实验状态，不新增推理/网络订阅。测试`AutomaticDrivingModeTest`、`LaneGeometryTest`和`DrivingModeSelectionTest`；APK保留Release混淆/资源压缩。

**三种路线辅助模式**：`navigation/DrivingMode.kt` 提供高速EAP、高架NOA、常规NOO和纯只读地图候选策略。`MainActivityCore.drivingMode` 使用独立的`CarrotAmap.driving_assistance_mode`稳定键持久化，不改变`activeNavMode`、超车开关、车端参数或网络协议；切换取消旧待执行自动变道，不发送控制命令。模式卡继续沿用高德唯一500ms前台采样和7000现有TTL，不新增订阅或循环。高德10001的实际`ROAD_TYPE`存入`AmapGuideExtras.reportedRoadType`，缺失/非法立即未知，遵守10秒TTL、路线代次、切源/停止；禁止从默认`roadType`、简化`roadcate`或7000推断。0=高速；6=快速路，不证明高架；1-5/7-9可作常规道路地图提示，10不适用。高速/高架偏左、常规偏右，但只能在左右顺序完整、方向已识别的高德路口推荐组内排序；退出/转向推荐优先。路口数量不是主路车道数，2/3/4只是常见布局；应急车道、专用道、本车位置和变道合法性未知，不默认最右条为应急车道。道路失配/未知、推荐缺失/缓存/过期、车端偏航或接管/提示告警撤下候选。7000横纵向状态不授予权限，保持compact只读安全门；不实现自动路口通行或FSD。`DrivingModeTest`覆盖策略与生命周期，`DrivingModeSelectionTest`验证持久化及无车端命令/参数副作用，仪器测试`DrivingModeCardsTest`覆盖大字体窄屏切换及过期清除。研究目标与本地openpilot边界见`docs/highway_noa_eap_implementation_plan.md`。

**品牌与调试**：应用名统一由 `app_name` 资源提供；启动图标使用`artifacts/Navi-Link-local_color/app/src/main/ic_launcher-playstore.png`的Navi-Link蓝色图稿，通过adaptive icon安全区适配桌面遮罩。导航调试面板仅保留7706精简正文与7714 JSON发送快照，打开时定时刷新、关闭后停止；7714正文不截断，发送状态不代表车端接收确认。

**7.1信息补充**：左侧高德行程展示有效全程距离、全程减剩余得到的已行驶距离、`ROUTE_ALL_TIME`全程预估时间，以及广播剩余秒数加本机接收时刻估算的到达时间（标明本机估算）。不得用每次重绘时间重新计算ETA；缺失剩余距离、不合理总距离、过期/切源/换路线时撤下相关值。10001的`SEG_REMAIN_TIME`与`NEXT_SEG_REMAIN_TIME`保存在`AmapGuideExtras`运行时属性，按当前/随后航段时间显示；随后航段用时不冒充从当前位置到下一动作点的累计时间。右侧“车辆与规划补充”用已有7000订阅展示挡位、实际aEgo、制动灯/制动保持、纵向规划首点速度/加速度、carControl请求加速度/转向角及lateralPlan车道线采用标记；速度m/s转km/h，加速度保留符号及m/s²，合法0值保留，缺失/非有限值未知。按carState/longitudinalPlan/carControl/lateralPlan各自TTL清理，不用其他服务刷新续期。前车卡增加雷达leadLeft/leadRight选中目标及相对速度，不保证目标在相邻车道，不授予变道许可；只展示status=true且字段完整有限的目标。制动灯/保持不等于驾驶员踏板；规划/指令值不代表实际执行。没有新增摄像头/网络订阅或设备健康卡，7711职责和安全门保持不变。

**首页地图预览**：`ui/components/ProjectionMapPreview.kt` 在导航分类提供4:3原生只读实时图片，18dp圆角及细边框；默认初始虚拟屏960×720，避免提高编码像素负担。不再使用WebView或发送触摸/按键反控。`ProjectionService.onCreate` 将自身 `LatestFrameStore` 注册到 `ProjectionState.previewFrameStore`；UI只读取运行时的 `activePreviewFrameStore`，不走HTTP、不创建新编码器。未投屏时提示开启，画面ContentScale.Fit保持比例、不裁切；外部浏览器按原viewport协议适配，改变源比例时首页允许留边。右上角FPS徽标区分预览图片更新次数与源编码FPS，每秒采样，初始未统计用“—”，不能把轮询或屏幕刷新率计入。源帧超过1秒未更新时metrics返回0fps，避免冻结后保留历史高帧率；暂停/重启预览重置计数。外部浏览器的viewport与触摸协议不变。

**原生预览生命周期**：Compose produceState结合repeatOnLifecycle(STARTED)维护单解码循环，每33ms检查最新快照并按序号去重，不积压帧；BitmapFactory在Default线程解码，Compose状态在主线程更新。后台/离开页面取消循环并清除图片引用；服务停止时先将running置false、断开previewFrameStore，reset也清除帧源；外部浏览器重建虚拟屏时沿用同一帧仓库，预览自动跟随新帧。不可主动recycle仍由Compose显示的Bitmap，以免与渲染线程竞争。解码失败写ProjectionMapPreview日志并显示错误，新帧到来后自动重试。移除原WebView诊断弹窗和传输切换，外部HTML播放器与 `/frame`、`/stream`、`/control`、`/health` 保持兼容。

**投屏性能与布局**：虚拟屏和编码目标为30fps，`FramePacer` 使用纳秒固定节拍及2ms以内抖动容差，避免49ms回调被50ms硬限频误丢帧；采集Image在JPEG压缩前关闭，Bitmap等资源由编码线程在在途任务结束后释放。真实帧率仍受高德渲染与设备编码/解码性能约束。

**首页分类导航**：主首页使用右侧59dp可折叠六分类栏“看板/车辆/导航/控制/AR/指南”，默认看板；常规字体按钮接近59dp正方形，大字体/长文字可增高，连接状态/IP换行且侧栏独立滚动。原车辆/规划/结果合并为车辆页，前四类及指南单列且独立保存滚动位置，AR使用剩余高度显示源比例视频。九按钮及参数保留控制页，高德完整态势及7000遥测集中看板，导航页保留地图与模式操作。指南离线说明各页用法与安全边界。唯一AMap sampler在父容器STARTED下每500ms运行，切页不停止；标题左按钮收起侧栏后无轨道占位，不重建内容。根容器处理安全区，内容宽上限1040dp，大字体与未知态不截断。

**首页控制页**：原九宫格9按钮顺序、等宽和最小76dp高度保持不变，超车参数在同页常驻；页面入口按钮与现有动作不变。车辆HUD/状态、规划结果分置于车辆和规划分类；行程/告警/7000结果位于结果分类；地图、高德态势、模式候选/导航比较位于导航分类。缺失/过期仍明确未知，导航/车辆/结果全数据保留，不因分类隐藏业务字段。关闭超车模式只保存设置，不开启自动控制。关于与诊断既有入口保持。Activity继续支持竖屏/折叠/字体配置变化，不锁方向；分类导航不改变导航、速度或安全门逻辑。

**7000信息展示**：`data/CarrotDisplaySnapshot.kt` 将现有compact快照转换为只读展示模型；`ui/components/CarrotTelemetryCards.kt` 将车端剩余行程归入右侧7000结果区，右侧显示前车距离、相对速度和目标跟车时距/间距，7000卡显示中文速度约束来源、测速事件、有效区间的均速/剩余距离/进度、归一化导航动作与双行告警；车端变道状态位于右侧HUD标题下。`CompactTelemetry.toDisplayVehicleData` 是兼容HUD唯一车辆适配入口，保留转向灯和四条车道线概率，将兼容层m/s巡航换回km/h；缺失meta/orientation/自定义NOA不伪造对象。设备CPU/内存/存储卡不再显示，但保留deviceState解码和内部健康管理。信息使用各服务原有过期规则，断开/过期后移除旧数值并明确提示未知；导航路线/动作须符合版本、连接、引导、present及偏航门槛。已连接的归一化导航不回退到冲突的旧测速事件。250速度候选哨兵不是建议速度；compact丢失有效性标记的可选区间零值按未知显示。前车相对速度由m/s换为km/h，负值表示接近；目标跟车距离不等于实际距离。横纵向激活仅为车端状态，不授予自动控制权限；compact展示模式保持开启，收到数据时取消待执行自动变道，不回退7711补齐控制授权。不修改7714发送、7706命令或7711车端包格式，不增加网络轮询/订阅服务或编码器。

**数据源边界**：当前7711只恢复Tesla CAN灯色/停止线展示，详见顶部覆盖说明；旧`XiaogeTcpClient`及model meta独立路缘仍未恢复。7000已有模型曲线、几何量测及导航灯组不是这些独立路缘字段的等价替代。

**comma3连接模式**：`data/CommaConnectionDisplay.kt` 区分搜索、连接中、等待数据、7000在线和超时，不能以UDP监听已启动/设备已发现冒充数据在线。7000须握手完成、未超时且有新鲜compact服务；无其他遥测链路兜底。首页在STARTED每500ms本机采样，品牌行徽标常规字体下22dp最小可见高度。设备发现列表不抢占仍在线的选中目标，只在无目标或离线后选择设备；UDP状态仅由选中IP进入共享车辆状态。NetworkManager通知选中设备，7000/7714订阅选中目标变化，启动前已发现IP重放给订阅者。相同IP的断线由客户端自身重连。换设备清旧车端状态，阻止旧实例的排队回调覆盖新设备。

**高德原始导航展示**：`navigation/AmapNavigationDisplay.kt` 生成只读态势；`ui/components/AmapNavigationOverview.kt` 位于左列地图下方，统一维护高德标题/信号和导航态势，显示广播实际提供的目的地、当前道路、道路类别、剩余距离/时间、总距离已知时的进度、当前/随后动作、航段提示、出口名称/方向、非缓存推荐车道、沿途服务设施及测速/区间信息。10001的附加值保存到`NaviSourceTracker`维护的`AmapGuideExtras`运行时快照，不增加CarrotManFields构造参数；可选字段按最新包替换，缺失不复用旧值。非AMAP源、停止导航、源时钟超过10秒、路线代次改变均撤下旧提示；10003或目的地变化重置UI路线边界，旧车道不能跟随新路线。未知ICON明确显示未知；较新10006使用自身指令，不套旧图标。Compose在STARTED维护一个500ms本机状态采样循环，离开页面/后台取消，不新增网络请求、逐广播协程或投屏解码器。高德导航提示与7000车端采用结果分区；地图推荐不是自动驾驶能力或安全变道许可，不改变安全门/自动超车/调速逻辑，不宣称EAP/FSD能力等级。

**路口车道与路况**：参考本地`artifacts/Navi-Link-local_color`的广播字段与车道资源含义，独立实现Compose展示，不复制第三方PNG、悬浮窗或动画代码。`AmapLaneDirection.kt` 的Back_icon编码独立于导航ICON；仅解释已核实的0-12与15，缺失及复杂/未知编码显示“方向未知”。13012支持布尔及0/1推荐标记；显式关闭/未知有效标记、数量与数组不符时撤销旧车道。只有真实序号完整覆盖0..N-1才按从左到右排序；序号缺失保留-1，界面按原始条目展示并说明顺序未确认。推荐高亮不闪烁，方向与文字可换行；距离仅称“当前导航动作”，不伪造13004独立路口距离或本车车道。10070/13003仅呈现实际描述及正距离，不猜级别含义。13011的EXTRA_TMC_SEGMENT由`AmapTrafficSegments.kt`解析，限制64KiB/512段，正段长合计必须等于正总距离；已行驶距离可缺失但有效时须在0..total内。展示路线顺序色条与真实进度标记，1-4对应畅通/缓行/拥堵/严重拥堵，10为已驶过，其他代码明确未知。解析失败记录错误并撤下旧路况，不照搬参考项目静默保留旧值。路况快照绑定路线代次与10秒TTL，切路线/停止清理，沿用同一本机UI采样循环。

**首页态势显示门槛与空态**：高德导航态势仅在`AmapDisplayState.Fresh`时显示；未收到有效导航广播、等待、未导航、未选中或过期时不显示整卡，但CP标题与高德红绿灯区域保留。不以车辆数据或lastUpdateTime冒充收到高德广播。有效卡内按当前道路/类别→当前动作（方向18sp突出）→路口车道→随后动作→出口/航段→目的地/行程→测速/区间→分段路况/道路提示→服务设施/更新时间排列，相关字段分组，长文字换行。卡内缺失字段保留“当前无数据”或“—”，不绘制假箭头/进度；新有效广播自动恢复。独立道路分类、测速、区间或下一动作广播也可更新态势，不强制等待10001。右侧7000动作/推荐车道/测速/区间保留空态；告警未提供与未收到/过期明确区分，不用无告警推断安全。行程进度/接收年龄未知时保留标签，实际图形只在有效时显示。未投屏、等待画面或解码失败时FPS徽标也保留，未知为“—”且指示点中性。保持源选择、路线代次、TTL、安全门及单解码循环。

**信息卡视觉与补充数据**：7000信息卡采用18dp圆角、细色边框、分区图标、更新状态徽标及等高指标块，保持固定深色主题；指标块按卡片内部宽度/字体缩放达到280dp时双列，否则单列，字号放大不截断或隐藏值。双行告警置于处理结果卡顶部，严重程度以色块区分。行程进度仅在真实总距离不小于有效剩余距离时计算，未知总距离不显示伪进度；导航新鲜度为本机接收年龄，不是端到端延迟。车端返回的laneCurrent仅在导航有效、present及visible成立且车道数1-16时显示；完整0/1推荐掩码按从左到右编号，缺失/非法掩码明确提示，不补全为安全车道，不推断本车所在车道或变道许可。数据过期移除进度与车道推荐。

**高德道路与前方限速**：道路信息后、当前动作前显示限速指标。10001的原始LIMITED_SPEED及不含区间距离字段的12110限速保存在`NaviSourceTracker.roadSpeedLimit`，绑定路线代次、接收时间与10秒TTL；缺失/无效值撤下原值，停止导航/切路线清理，不从已修正或预调的nRoadLimitSpeed及车端数据回填。有效范围1-249km/h。前方限速提醒从新鲜主/次SDI中取距离最近且限速有效的测速/区间起点事件（类型0/1/2/7/8/75/76），同时显示该事件距离与类型；排除区间终点、进行中及未知事件。当前已核实广播不提供独立下一路段道路限速变更点，必须标明前方值为测速/区间起点提醒，不能冒充下一道路限速。原有调速、7714及7706协议不变。

**7706目标端口**：命令、心跳及兼容目的地固定使用选中IP的UDP 7706；不能把设备发现信息中的HTTP 7000等port字段直接当作UDP发送端口。NetworkTargetSelectionTest实际捕获DatagramPacket检查目标IP和7706端口，其他设备广播不改变选中IP或进入其UDP状态。

---

## 构建命令


Windows 用 `gradlew.bat`，macOS/Linux 用 `./gradlew`（仓库根目录执行）：

Wrapper可由JDK17启动，但`gradle\gradle-daemon-jvm.properties`要求Java21 daemon，
不能仅凭JAVA_HOME判断实际构建JVM；产物Java/Kotlin目标仍为JVM11。
Android SDK及`platform-tools\adb.exe`按本机`local.properties`的`sdk.dir`解析，
不硬编码IDE安装目录作为SDK路径。Gradle测试与APK构建串行运行，避免共享生成目录竞态。

```bash
.\gradlew.bat assembleDebug                 # Debug 构建（applicationIdSuffix .debug）
.\gradlew.bat assembleRelease               # 需 local.properties 中 RELEASE_* 签名配置
.\gradlew.bat detekt                        # 静态检查（config/detekt/detekt-config.yml）
.\gradlew.bat test                          # 运行全部单元测试
.\gradlew.bat :app:testDebugUnitTest --tests "com.jixiexiaoge.drivingassist.data.DashboardSnapshotTest"
```

- **ABI**：默认 `arm64-v8a`（`gradle.properties` 中 `navipilot.abis` 定义），`-Pnavipilot.abis="arm64-v8a,armeabi-v7a"` 可同时构建两种。
- **local.properties**（不提交）：`sdk.dir`、`MAPS_API_KEY`、`GITHUB_CLIENT_ID`、`RELEASE_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`。

---

## 测试说明（重要）

- **框架**：JUnit 4（`org.junit.Test`）+ Google Truth + MockK；反引号方法名，方法内 `// Given // When // Then` 注释。
- **新测试位置**：`app/src/test/java/com/jixiexiaoge/drivingassist/`。
- 当前内嵌地图仪器测试为 `com.jixiexiaoge.drivingassist.ui.components.LocalMapBrowserTest`，
  覆盖启动提示/投屏入口、只探发布端口（包括8081以上）、三次失败及探测空态；
  URL与有界health身份校验单测为`LocalMapBrowserProbeTest`。
  单类执行：`.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jixiexiaoge.drivingassist.ui.components.LocalMapBrowserTest`。
- 自适应布局单测为 `ui.components.AdaptiveLayoutsTest`；仪器测试为 `ui.components.AdaptiveLayoutsInstrumentedTest`，覆盖高德左/车端及控制右的分区、宽窄切换的元素保留、状态/预览组合保留、大字体单列回退、9个控制按钮的顺序/尺寸及页面入口；`CarrotTelemetryCardsTest`也检查真实高德卡与7000/跟车/行程卡的左右关系及设备健康卡已移除。
- 7000展示单测为 `data.CarrotDisplaySnapshotTest` 和 `ui.components.CarrotTelemetryLabelsTest`，覆盖过期清除、速度哨兵、导航有效性、区间状态、雷达单位、告警、中文来源、真实总距离进度、推荐掩码和指标列数；仪器测试 `ui.components.CarrotTelemetryCardsTest` 覆盖告警置顶与过期移除、宽窄布局中的未知状态、大字体指标重排，以及车道推荐/行程进度过期清除。
- 高德态势单测为 `navigation.AmapNavigationDisplayTest`，覆盖导航源选择、过期、路线切换、缓存车道、缺失字段、未知动作、服务设施、区间数据、车道排序及道路提示生命周期；`AmapLaneDirectionTest`和`AmapTrafficSegmentsTest`覆盖方向/标记与TMC数值/段长校验。仪器测试 `AmapLaneBroadcastTest`覆盖真实Intent解析，`ui.components.AmapNavigationOverviewTest`覆盖高德路线/动作/服务设施、车道及路况过期撤下，须设备执行。
- 仪器测试`AmapNavigationOverviewTest`检查无广播/等待/未选中时隐藏态势、有效广播后出现、字段顺序及缺失车道空态；`RealTimePageTest`和`DriveViewSignalCapsuleTest`覆盖当前实时页及高德灯胶囊；旧HUD专用测试已随8.0清理移除。`AdaptiveLayoutsInstrumentedTest`检查控制页九按钮可达，以及切页期间单一AMap采样持续。
- AndroidTest依赖中的JUnit/MockK重复 `META-INF/LICENSE*.md` 和 `META-INF/NOTICE*.md` 通过packaging.resources.merges合并，保留许可证内容并避免测试APK打包冲突。
- 投屏状态及控制服务测试按生产包放在 `app/src/test/java/com/navipilot/amap2tesla/`。
- `app/src/test/java/com/example/navipilot/` 中的 7 个旧包路径测试已删除；当前测试集中在 `app/src/test/java/com/jixiexiaoge/drivingassist/`，按生产代码包名维护。

---

## 目录结构（两个包根）

```
app/src/main/java/
├── com/jixiexiaoge/drivingassist/        # 主包：首页 UI + comma3 联动
│   ├── MainActivity*.kt                  # 入口 + 协调器（Core/UI/UIComponents/UISearch/Lifecycle 6 文件拆分）
│   ├── CarrotManDataModels.kt            # CarrotManFields（中央状态 SSOT）+ 协议模型
│   ├── CarrotManNetworkClient.kt         # UDP 7706 最小用途包；旧7709/7712/7713发送已禁用
│   ├── AmapBroadcastManager.kt           # 高德广播接收 + 有界 Channel 单消费者
│   ├── AmapBroadcastHandlers.kt          # 广播解析 + updateXxx(copy) 状态更新助手
│   ├── NetworkManager.kt / CarrotParamClient.kt / DeviceManager.kt
│   ├── AutoOvertakeManager.kt            # 自动超车决策（3 帧防抖）
│   ├── data/CarrotWsClient.kt            # WebSocket 7000 /ws/compact_state（明确的CVS1/CVB1字段子集）
│   ├── camera/                          # CameraWire帧协议、RoadCameraPlayer原生H.264解码（仅前台AR页）
│   ├── navigation/                       # CoordinateConverter、NaviSourceTracker、NaviStreamManager（v2 13 JSON流）、
│   │                                     # NaviWebSocketV2Client、NaviDataModels
│   ├── core/                             # Result.kt（sealed + runSafely + ErrorCode）、SecurePrefs、
│   │                                     # ErrorReporter
│   └── ui/components|theme|utils/        # 少量组件 + 固定深色车载主题（禁用 dynamicColor）
│
└── com/navipilot/amap2tesla/             # 投屏子系统
    ├── privileged/PrivilegedDisplayService.kt   # Shizuku/Stellar 虚拟 Display
    ├── projection/ProjectionService.kt          # 投屏生命周期、每日 45 分钟试用
    ├── server/LocalProjectionServer.kt          # /stream、/control、/health（8080-8099）
    ├── video/MjpegEncoder.kt、membership/、network/TeslaVirtualIpService.kt
```

其他：`app/src/main/assets/amap2tesla_player.html`（浏览器播放器）；`web/commerce/`（会员/支付/发布 Flask 服务）；`docs/`。
**已删除勿引用**：Koin DI、SSH(SSHJ)、JeroMQ/ZMQ、Media3、腾讯/Google 导航 SDK、scoring/ 评分模块、`*NavDataBridge`、`XiaogeDataReceiver`(TCP 7711)、`patchRClass` 任务、`carrotcode/` 目录。

---

## 通信端口

| 端口/协议 | 方向 | 用途 |
|----------|------|------|
| HTTP/WS 8080-8099 | 浏览器 ↔ App | 投屏 /stream、/control、/health |
| UDP 7705 | ← comma3 | 设备发现（固定监听） |
| UDP 7706 | → comma3 | 心跳/时间、SPEED/LANECHANGE等命令、目的地；不携带旧导航正文 |
| WS 7714 | → comma3 | Navi v2：严格28项目录，13 JSON流；无真实媒体源时不宣称图片/渲染可用 |
| TCP 7709/7712、HTTP 7713 | — | carrot仍实现接收，App旧发送已禁用 |
| HTTP/WS 7000 | ↔ comma3 | `/api/param_set`、`/api/params_bulk`、`/ws/compact_state`、AR只读`/ws/camera/road` |
| TCP 7711 | ← comma3 | `XiaogeBridgeClient`只读灯色/停止线展示，不进入7714或控制授权 |

---

## 核心架构模式（改代码前必读）

1. **SSOT**：`MainActivityCore.carrotManFields = mutableStateOf(CarrotManFields())`，所有导航数据经 `AmapBroadcastHandlers.updateXxx` 用 `copy()` 更新（旧 `postFieldsMutate` 已不存在）。
2. **有界 Channel 背压**：`AmapBroadcastManager.broadcastChannel = Channel(BUFFERED)` + `trySend`（满则丢弃）+ 单协程顺序消费。高频高德广播禁止改无界队列或"一条广播一个协程"。
3. **7706 字段契约**：`build7706Payload` 的基础包3字段、命令包5字段、有效目的地包6字段。carrot旧导航块按 `nRoadLimitSpeed` 键是否存在触发；最小包不得带此键。心跳不携带命令字段，命令清理使用显式命令包。
4. **坐标系**：内部统一 WGS-84；高德广播为 GCJ-02，在 `CoordinateConverter` 边界转换。
5. **CarrotManFields 不要加构造参数**：过长 data class 会导致真机 `VerifyError`；`build.gradle.kts` 的 `-Xno-call/param/receiver-assertions` 是缓解手段，勿删。新字段用默认值 var。
6. **未选中的地图源不得覆盖共享导航状态**；目前有效导航模式仅 `"AMAP"`（`VALID_USER_NAV_MODES`）。
7. **三帧防抖**：`AutoOvertakeManager` 需连续 3 帧满足条件才触发（`DEBOUNCE_FRAMES`）。
8. **导航源有效性**：`NaviSourceTracker` 在真实源入口维护独立时间与位置快照，禁止7705/7000状态刷新旧高德数据。按manifest的发送模式/间隔处理；撤销成功后，相同值恢复仍必须重新present。未知灯方向/SDI/媒体源不构造虚假有效对象。
9. **SmartSpeedLimiter**：高速/快速路匝道40纠正为60；距动作点800m开始降速，10km/h档位按剩余距离分配，每次更新最多调整10。首次发现不足800m时使用实际距离；距离跳变逐次追赶，不能保证迟到数据已在匝道前达到目标。无已知主路限速不虚构升速目标；弯道暂停升速、不暂停出口降速。
10. **7000子集边界**：compact不含完整接管/故障/模型meta等字段；缺失的控制授权数据必须阻止自动控制，不得用默认false当“安全”。导航接收结果独立于高德发送模型，禁止将车端建议速度回灌为道路限速。

---

## 关键依赖

Kotlin 2.1 + Compose Material 3 + Coroutines；OkHttp 4.12 + Gson（HTTP）；Shizuku 13.1.5（特权虚拟屏）；nanohttpd 2.3.1（投屏服务）；security-crypto（EncryptedSharedPreferences）；Timber 5.0.1；`coreLibraryDesugaring` 2.1.4 保持启用。

**构建配置注意**：Release启用R8代码混淆/优化和资源压缩（`isMinifyEnabled = true` / `isShrinkResources = true`），加载默认`proguard-android-optimize.txt`及`app/proguard-rules.pro`、`app/security-config.pro`；Debug保持关闭并使用`.debug`包名。第三方使用consumer规则，不整体keep应用或Compose/Kotlin包，不使用全局dontwarn/ignorewarnings。Shizuku反射创建的`PrivilegedDisplayService`类名和无参构造须保留；超大`CarrotManFields`保留生成成员、防止VerifyError，但允许类/成员名混淆，协议使用显式JSON键。Release移除Android Log.v/d/i，保留w/e；不禁用错误报告。mapping保存在`app/build/outputs/mapping/release/`，须私密归档，不随APK分发。R8不能保证防反编译，授权/支付秘密和关键校验留在服务端；APK assets和协议字符串仍可提取。`gradle.properties`设`-Xmx12288m` + R8 16g，勿降。
