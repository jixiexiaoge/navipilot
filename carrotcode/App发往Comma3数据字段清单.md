# 手机 App → Comma3（Carrot）数据字段清单

> **目的**：以车机 **[carrot_man.py](carrot/carrot_man.py)** 的**入口**与 **[carrot_serv.py](carrot/carrot_serv.py) `CarrotServ.update(self, json)`**（约 1182–1314 行）为准，逆向说明 **哪些 JSON/二进制字段会生效**；并与 CPlink **[CarrotManNetworkClient.kt](../app/src/main/java/com/example/carrotamap/CarrotManNetworkClient.kt)** 实际发送内容对照。  
> **坐标**：导航/路径点约定 **WGS-84**（高德系需在 App 侧完成 GCJ→WGS）。

> **⚠️ 2025-07 逆向验证结果**：代码实际行为与本清单的 **§1.5** 和 **§7** 一致，车机端 `szTBTMainTextNext` 键读取存在 **Bug**（见 §1.5 说明）。

---

## 〇、车机侧数据入口总览（`carrot_man.py`）

| 入口 | 端口/协议 | 代码位置 | 进入 `CarrotServ.update`？ |
|------|-----------|----------|----------------------------|
| `carrot_man_thread` | **UDP 7706**，`json.loads` → `update(json_obj)` | 约 501–525 行 | **是**，整包即 `update` 的 `json` |
| `handle_carrot_state` | **7712/7713** 中 **`rgdata`** 子对象 `d` → `update(d)` | 约 1138–1140、1195 行 | **是**，`d` 与 7706 包**同一套键语义**（子集即可） |
| `handle_route` / `vrtx` | **7712/7713** 行 JSON 或 HTTP body 中的 **`vrtx` 数组** | `handle_route` 约 1052–1101；`_dispatch_obj` 1186–1187 行 | **否**，直接维护 `navi_points` + `send_routes`，**不经 `update`** |
| `carrot_route` | **TCP 7709** 二进制 | 约 938–1001 行 | **否** |
| `handle_traffic_light` / `sinf` | **7712/7713** 中 **`sinf` 对象** | 1103–1135、1197–1198 行 | **否**，写入 `params_memory` 键 `TrafficLight` |
| `kisa_app_thread` | **UDP 12345** 文本 → `parse_kisa_data` → **`update_kisa`** | 577–603、819 行起 | **否**，走 **`update_kisa`**，与 `update` 不同 |

**结论**：App 经 **7706** 或经 **7712/7713 的 `rgdata`** 发给车机的「导航 JSON」，最终都进 **`CarrotServ.update`**，字段约定一致；**路线几何**可走 **7709** 或 **`vrtx`**，二者与 `update` 内 `vp*` 独立但共同影响 `broadcast_version_info` 里的路径融合。

---

## 一、`CarrotServ.update(json)` 分支（`carrot_serv.py`）

### 1.1 执行顺序（同一包内可叠加）

1. **`"carrotIndex" in json`** → `self.carrotIndex = int(json.get("carrotIndex") or self.carrotIndex + 1)`  
2. **`carrotIndex % 60 == 0` 且 `"epochTime" in json`** → 非 PC：`set_time(int(epoch), json.get("timezone","Asia/Seoul"))`  
3. **`"carrotCmd" in json`** → 保存 `carrotCmd`、`carrotArg`；**`self.carrotCmdIndex = self.carrotIndex`**（注意：**不读取** JSON 里的 `carrotCmdIndex` 键）  
4. **任意包** → `self.active_count = 80`  
5. **`"goalPosX" in json`** 且 `goalPosX/goalPosY` 非 null → 更新 `goalPosX/Y`（float）、`szGoalName`  
6. **`"nRoadLimitSpeed" in json`** → **主导航块**（限速纠偏、SDI/TBT、`vp*`、条件更新 `nPosAngle`、`nPosSpeed`、`_update_tbt()`、`_update_sdi()`）  
7. **`"latitude" in json`** → **手机 GPS 块**（与 6 独立，**其后**仍可能执行）

### 1.2 `nRoadLimitSpeed` 主导航块内限速纠偏（易忽略）

- `nRoadLimitSpeed = int(json.get("nRoadLimitSpeed", 20))`  
- 若 `> 0`：若 `> 200` → `(nRoadLimitSpeed - 20) / 10`；若 `== 120` → **115**（硬编码修正）  
- 若 `<= 0` → 置 **30** 再参与计数器比较  
- **写回 `self.nRoadLimitSpeed`**：仅当与上次不同连续累计 **`nRoadLimitSpeed_counter > 5`** 才采纳新值，否则计数器清零（防抖）

### 1.3 `vpPosPointLat` 与 `nPosAngle`（主导航块）

- 始终读 `vpPosPointLat/Lon` 为 float。  
- **仅当 `vpPosPointLatNavi != 0.0`** 时：刷新 `last_update_gps_time_navi` / `last_calculate_gps_time`，并 **`nPosAngle = float(json.get("nPosAngle", self.nPosAngle))`**。  
- **`nPosSpeed`**：在主导航块末尾**无条件** `float(json.get("nPosSpeed", self.nPosSpeed))`。

### 1.4 手机 GPS 块（`"latitude" in json`）

- `nPosAnglePhone = _f(json.get("heading"), self.nPosAngle)` — **`heading` 在此块消费**；缺省用当前 `nPosAngle`。  
- `phone_latitude/longitude/accuracy` 来自 `latitude/longitude/accuracy`。  
- 若 **`(now - last_update_gps_time_navi) > 3.0`**：用 phone 坐标覆盖 `vpPosPointLatNavi/LonNavi`，`nPosAngle = nPosAnglePhone`，**`nPosSpeed = float(json.get("gps_speed", 0))`**。

### 1.5 `szTBTMainTextNext` ⚠️ Bug 确认

- **代码行 1271**：`self.szTBTMainTextNext = json.get("szTBTMainText", "")`  
- **实际行为**：**误读 `szTBTMainText`**（主文案键），而非 `szTBTMainTextNext`。即使 App 正确发送 `szTBTMainTextNext`，车机仍然读的是主文案的值。  
- **正确应写为**：`self.szTBTMainTextNext = _s(json.get("szTBTMainTextNext"))`  
- 此 Bug 已在文档 §7 标注，但**当前代码尚未修复**。App 端若已发 `szTBTMainTextNext`，但车机侧需手动修复此行才生效。

---

## 二、UDP **7706** — App JSON 与 `update` 消费对照

**车机**：`carrot_man_thread` → `json.loads` → **`carrot_serv.update`**。  
**App**：`convertCarrotFieldsToJson` / `sendHeartbeat`。

### 2.1 主包字段表（`convertCarrotFieldsToJson`）

| JSON 键 | 主导航块 | GPS 块 | 索引/命令/其它 | 备注 |
|---------|:-------:|:------:|:--------------:|------|
| `carrotIndex` | | | 是 | |
| `epochTime` / `timezone` | | | 条件校时 | |
| `timestamp` | | | **否** | 未在 `update` 读取 |
| `heading` | | 是（`heading`） | | 主导航块不读；App 内 `put` 两次取后者 |
| `goalPosX` / `goalPosY` / `szGoalName` | | | 条件 goal 分支 | 需 `"goalPosX" in json` 且坐标非 null |
| `nRoadLimitSpeed` | **触发块** | | | 键存在即进入；见 §1.2 纠偏 |
| `nSdiType` … `nSdiPlusBlockDist` | 是 | | | `_i` |
| `roadcate` | 是 | | | |
| `nLaneCount` | **否** | | | |
| `nTBTDist` … `nTBTTurnTypeNext` | 是 | | | int |
| `szTBTMainText` / `szNearDirName` / `szFarDirName` | 是 | | | `_s` |
| `szTBTMainTextNext` | 是 | | | **⚠️ Bug：代码读的是 `szTBTMainText`** |
| `nGoPosDist` / `nGoPosTime` / `szPosRoadName` | 是 | | | |
| `latitude` / `longitude` / `accuracy` | | 是 | | |
| `gps_speed` | | 条件赋 `nPosSpeed` | | 见 §1.4；单位常与 `nPosSpeed` 不一致风险 |
| `vpPosPointLat` / `vpPosPointLon` | 是 | 可覆盖 | | |
| `nPosAngle` / `nPosSpeed` | 条件/是 | | | 见 §1.3 |
| `isNavigating` | **否** | | | |
| `tCamera*` / `remainingTrafficLights` / `passed*` / `isOnMainRoad` | **否** | | | |
| `carrotCmd` / `carrotArg` | | | 是 | |
| `carrotCmdIndex` | **否** | | | 不在此 JSON 读取 |

### 2.2 心跳包（`sendHeartbeat`）

含 `carrotCmd=heartbeat`、`source` 等；**无 `nRoadLimitSpeed`** 时不进主导航块；`source` **不消费**。

---

## 三、TCP **7709** — 二进制路径（`carrot_route`）

| 段 | 格式 |
|----|------|
| 4 字节 | 大端 `uint32` = 后续 payload 长度 |
| Payload | 每 8 字节 `!ff`：**经度 x、纬度 y**（float32） |

→ `navi_points`、`send_routes`、`NavDestination`；**不经 `update`**。

---

## 四、**7712 / 7713** — 与 7706 的关系（`carrot_man.py` `_dispatch_obj`）

**同一 `_dispatch_obj`** 处理 **TCP 每行 JSON** 与 **HTTP POST body**。

### 4.1 `rgdata` → `update(d)`

- **`d` 为 dict**，键集合与 **§二** 相同即可（可只发子集）。  
- **顶层**（与 `rgdata` 同级）可有 **`timestamp_ms`**（int）：`<=0` 不去重；否则单调递增，旧包丢弃（`[STALE DROP]`）。

### 4.2 `vrtx` → `handle_route`

| 元素字段 | 类型 | 说明 |
|----------|------|------|
| `x` | number → float | **经度** |
| `y` | number → float | **纬度** |
| `valid` | 可选 boolean | 默认 `true`，`false` 过滤掉 |

空数组 → 清空路线、`navi_points_active=False`。

### 4.3 `sinf` → `handle_traffic_light`

| 字段 | 用途 |
|------|------|
| `redLightOn` / `leftLightOn` / `greenLightOn` / `rightLightOn` / `uturnLightOn` | 优先级：红 > 左 > 绿 > 右 > uturn |
| `*RemainTime` | 各灯倒计时 |
| `distance` | 米 |

输出：`params_memory["TrafficLight"]` = JSON 串 `{distance,lamp,remain}`。**不经 `update`**。

---

## 五、UDP **12345** — KISA / Waze 文本（非 7706 JSON）

**格式**：`key:value/key:value`；**`update_kisa`** 消费的键见 `carrot_serv.py` 约 819–857 行（如 `kisawazeroadspdlimit`、`kisawazeroadname`、`kisawazereportid`+`kisawazealertdist`）。  
**典型 CPlink**：不经过此端口；独立 App 才可能发往车机。

---

## 六、TCP **7711**（小哥）

车机 → App 为主；App 仅发 **4 字节大端 `uint32` = 2** 心跳。**非** `update` JSON。

---

## 七、与 App 实现的对照小结

| App 行为 | 车机 |
|----------|------|
| UDP 7706 JSON | `carrot_man_thread` → `update` |
| TCP 7709 折线 | `carrot_route` |
| 未发 7712/7713 | 若将来发送，`rgdata` 内字段与 7706 一致；可加顶层 `timestamp_ms` |
| `szTBTMainTextNext` | **⚠️ 车机代码行 1271 误读 `szTBTMainText` 键，需修复为 `_s(json.get("szTBTMainTextNext"))`** |

---

## 八、后续建议（跨端）

| 项 | 说明 |
|----|------|
| `gps_speed` vs `nPosSpeed` | 统一 **m/s** 或 **km/h**，避免 GPS 回退与主导航速度混用 |
| App `heading` 双写 | 合并为一次 `put` |
| 🐛 **修复 `szTBTMainTextNext` Bug** | `carrot_serv.py` 第 1271 行：`json.get("szTBTMainText", "")` → `_s(json.get("szTBTMainTextNext"))` |
| 上游 openpilot | 若合并分支，请同步 **`szTBTMainTextNext`** 一行修正 |

---

*逆向依据：`carrot_man.py`（`carrot_man_thread`、`carrot_route`、`_dispatch_obj`、`handle_route`、`handle_carrot_state`、`handle_traffic_light`）、`carrot_serv.py`（`update`、`update_kisa`）。*
