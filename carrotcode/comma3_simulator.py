#!/usr/bin/env python3
"""
Comma3 Device Simulator for Windows
模拟comma3设备,接收手机App(CarrotAmap)发送的导航数据并显示。

协议逆向自 carrot_man.py / carrot_serv.py:
  - 7705 UDP广播: comma3 → 手机 (设备发现 + 状态推送, 20Hz)
  - 7706 UDP接收: 手机 → comma3 (导航/GPS/SDI/TBT数据)
  - 7709 TCP接收: 手机 → comma3 (路线点串)
  - 7711 TCP推送: comma3 → 手机 (设备状态: carState/modelV2/controlsState)
  - 7000 HTTP服务: comma3 ↔ 手机 (参数读写 API: /api/params_bulk, /api/param_set)
"""

import json
import socket
import struct
import threading
import time
import tkinter as tk
from tkinter import ttk, scrolledtext, filedialog
from datetime import datetime
from collections import OrderedDict
import traceback
import math
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs


# ─────────────────────────────────────────────
#  协议常量 (来自 carrot_man.py)
# ─────────────────────────────────────────────
BROADCAST_PORT = 7705
DATA_PORT      = 7706
ROUTE_PORT     = 7709
STATUS_PORT    = 7711  # TCP: 向手机推送设备状态
PARAM_PORT     = 7000  # HTTP: 参数读写 API
BROADCAST_HZ   = 20        # 广播频率
VERSION        = "0.9.4"   # 模拟的 openpilot 版本


# ─────────────────────────────────────────────
#  nav_type_mapping (来自 carrot_serv.py)
# ─────────────────────────────────────────────
NAV_TYPE_MAPPING = {
    12: ("turn", "left", 1), 16: ("turn", "sharp left", 1),
    1000: ("turn", "slight left", 1), 1001: ("turn", "slight right", 2),
    1002: ("fork", "slight left", 3), 1003: ("fork", "slight right", 4),
    1006: ("off ramp", "left", 3), 1007: ("off ramp", "right", 4),
    13: ("turn", "right", 2), 19: ("turn", "sharp right", 2),
    102: ("off ramp", "slight left", 3), 105: ("off ramp", "slight left", 3),
    112: ("off ramp", "slight left", 3), 115: ("off ramp", "slight left", 3),
    101: ("off ramp", "slight right", 4), 104: ("off ramp", "slight right", 4),
    111: ("off ramp", "slight right", 4), 114: ("off ramp", "slight right", 4),
    7: ("fork", "left", 3), 44: ("fork", "left", 3), 17: ("fork", "left", 3),
    75: ("fork", "left", 3), 76: ("fork", "left", 3), 118: ("fork", "left", 3),
    6: ("fork", "right", 4), 43: ("fork", "right", 4), 73: ("fork", "right", 4),
    74: ("fork", "right", 4), 123: ("fork", "right", 4), 124: ("fork", "right", 4),
    117: ("fork", "right", 4),
    131: ("rotary", "slight right", 5), 132: ("rotary", "slight right", 5),
    140: ("rotary", "slight left", 5), 141: ("rotary", "slight left", 5),
    133: ("rotary", "right", 5), 134: ("rotary", "sharp right", 5),
    135: ("rotary", "sharp right", 5), 136: ("rotary", "sharp left", 5),
    137: ("rotary", "sharp left", 5), 138: ("rotary", "sharp left", 5),
    139: ("rotary", "left", 5), 142: ("rotary", "straight", 5),
    14: ("turn", "uturn", 5), 201: ("arrive", "straight", 5),
    51: ("notification", "straight", -1), 52: ("notification", "straight", -1),
    53: ("notification", "straight", -1), 54: ("notification", "straight", -1),
    55: ("notification", "straight", -1),
    153: ("", "", 6), 154: ("", "", 6), 249: ("", "", 6),
}

SDI_DESCRIPTIONS = {
    -1: "无", 0: "信号测速", 1: "固定测速", 2: "区间开始", 3: "区间结束",
    4: "区间中", 5: "压线拍照", 6: "闯红灯", 7: "流动测速", 8: "测速拍照",
    22: "减速带", 24: "隧道", 26: "收费站",
}



def get_local_ip() -> str:
    """获取本机局域网IP"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def get_subnet_broadcast(ip: str) -> str:
    """
    根据本机IP计算子网广播地址 (假设 /24 子网掩码)。
    例: 192.168.1.100 → 192.168.1.255
    对照 carrot_man.py get_broadcast_address() — 原始代码用 fcntl (Linux),
    Windows 上我们直接用 /24 掩码计算。
    """
    parts = ip.split(".")
    if len(parts) == 4:
        parts[3] = "255"
        return ".".join(parts)
    return "255.255.255.255"


# ═══════════════════════════════════════════════
#  CarrotServ 精简模拟 (来自 carrot_serv.py)
# ═══════════════════════════════════════════════
class CarrotServSim:
    """
    模拟 carrot_serv.py 的 update() 方法，
    解析手机App发来的JSON并存储所有字段。
    """

    def __init__(self):
        # 所有从手机接收的原始字段
        self.raw_fields: dict = {}
        # 解析后的关键状态
        self.carrotIndex = 0
        self.nRoadLimitSpeed = 30
        self.nRoadLimitSpeed_counter = 0
        self.nRoadLimitSpeed_last = 30

        self.active_count = 0
        self.active_sdi_count = 0
        self.active_sdi_count_max = 200
        self.active_kisa_count = 0
        self.active_carrot = 0

        # SDI
        self.nSdiType = -1
        self.nSdiSpeedLimit = 0
        self.nSdiSection = 0
        self.nSdiDist = 0
        self.nSdiBlockType = -1
        self.nSdiBlockSpeed = 0
        self.nSdiBlockDist = 0
        self.nSdiPlusType = -1
        self.nSdiPlusSpeedLimit = 0
        self.nSdiPlusDist = 0
        self.nSdiPlusBlockType = -1
        self.nSdiPlusBlockSpeed = 0
        self.nSdiPlusBlockDist = 0
        self.roadcate = 8

        # TBT
        self.nTBTDist = 0
        self.nTBTTurnType = -1
        self.szTBTMainText = ""
        self.szNearDirName = ""
        self.szFarDirName = ""
        self.nTBTNextRoadWidth = 0
        self.nTBTDistNext = 0
        self.nTBTTurnTypeNext = -1

        # 目的地
        self.nGoPosDist = 0
        self.nGoPosTime = 0
        self.szPosRoadName = ""

        # GPS
        self.vpPosPointLat = 0.0
        self.vpPosPointLon = 0.0
        self.nPosAngle = 0.0
        self.nPosSpeed = 0.0

        # 目标
        self.goalPosX = 0.0
        self.goalPosY = 0.0
        self.szGoalName = ""

        # 命令
        self.carrotCmd = ""
        self.carrotArg = ""
        self.carrotCmdIndex = 0

        # 计算字段
        self.xSpdLimit = 0
        self.xSpdDist = 0
        self.xSpdType = -1
        self.xTurnInfo = -1
        self.xDistToTurn = 0
        self.xTurnInfoNext = -1
        self.xDistToTurnNext = 0

        # 交通灯
        self.traffic_state = 0

        # 手机GPS (回退)
        self.phone_lat = 0.0
        self.phone_lon = 0.0
        self.phone_heading = 0.0
        self.phone_accuracy = 0.0
        self.phone_gps_speed = 0.0
        self.nPosAnglePhone = 0.0       # 原版: heading→nPosAnglePhone
        self.phone_gps_frame = 0        # 原版: accuracy<15时自增

        # 手机GPS (完整字段: carrot_serv.py 中的 phoneGPS)
        self.phone_lat2 = 0.0      # phoneLatitude
        self.phone_lon2 = 0.0      # phoneLongitude
        self.phone_acc2 = 0.0      # phoneAccuracy
        self.phone_frame = 0       # phoneGPSFrame
        self.phone_speed2 = 0.0    # phoneSpeed

        # sdiData (区间测速控制)
        self.sdiData_type = -1     # nSdiType in sdiData
        self.sdiData_dist = 0      # nSdiDist in sdiData
        self.sdiData_speed = 0     # nSdiSpeedLimit in sdiData
        self.sectionStartType = -1 # sectionStartType
        self.sectionStartDist = 0  # sectionStartDist

        # carrotDetect (探测数据)
        self.carrotDetect_type = 0  # carrotDetectType
        self.carrotDetect_dist = 0  # carrotDetectDist
        self.carrotDetect_speed = 0 # carrotDetectSpeed
        self.carrotDetect_cnf = 0   # carrotDetectCnf

        # autoNaviSpeed 参数
        self.autoNaviSpeedCtrlMode = 0
        self.autoNaviSpeedDecelRate = 0
        self.autoNaviSpeedSafetyFactor = 0
        self.autoNaviSpeedBumpSpeed = 30
        self.autoNaviSpeedBumpTime = 2
        self.autoNaviSpeedCtrlEnd = 0

        # 时间同步
        self.epochTime = 0
        self.timezone = 0
        self.timestamp = 0

        # GPS 内部状态
        self.vpPosPointLatNavi = 0.0
        self.vpPosPointLonNavi = 0.0
        self.gps_valid = False
        self.bearing_offset = 0.0
        self.bearing_measured = 0.0
        self.last_calculate_gps_time = 0
        self.last_update_gps_time = 0
        self.last_update_gps_time_navi = 0
        self.last_update_gps_time_phone = 0
        self.bearing = 0

        # 交通灯
        self.traffic_light_x = 0
        self.traffic_light_y = 0
        self.traffic_light_color = 0
        self.traffic_light_cntf = 0

        # KISA 数据
        self.kisa_raw_bytes = b""
        self.kisa_parsed = {}
        self.kisa_data_str = ""

        # 下一转弯信息 (szTBTMainTextNext 等)
        self.szTBTMainTextNext = ""
        self.szNearDirNameNext = ""
        self.szFarDirNameNext = ""

        # 总距离 (用于 delta 计算)
        self.totalDistance = 0.0

        # 路线点
        self.navi_points = []
        self.navi_points_active = False
        self.route_recv_time = 0.0       # 路线接收时间
        self.route_total_dist = 0.0      # 路线总距离 (m)
        self.route_curvatures = []       # 曲率列表
        self.route_curve_speeds = []     # 弯道限速列表
        self.route_min_curve_speed = 300  # 最小弯道限速

        # 统计
        self.total_packets = 0
        self.last_update_time = 0.0

    def update(self, data: dict):
        """
        完全对照 carrot_serv.py CarrotServ.update() 实现。
        """
        if data is None:
            return
        self.raw_fields.update(data)
        self.total_packets += 1
        self.last_update_time = time.time()

        # ── 基本字段 ──
        if "carrotIndex" in data:
            self.carrotIndex = int(data["carrotIndex"])

        if "carrotCmd" in data:
            self.carrotCmdIndex = self.carrotIndex
            self.carrotCmd = data.get("carrotCmd", "")
            self.carrotArg = data.get("carrotArg", "")

        self.active_count = 80

        # ── 目标 ──
        if "goalPosX" in data:
            self.goalPosX = float(data.get("goalPosX", 0))
            self.goalPosY = float(data.get("goalPosY", 0))
            self.szGoalName = data.get("szGoalName", "")

        # ── 导航数据 (当 nRoadLimitSpeed 存在时) ──
        if "nRoadLimitSpeed" in data:
            self.active_sdi_count = self.active_sdi_count_max
            # 限速解码 (与原始代码一致)
            nRoadLimitSpeed = int(data.get("nRoadLimitSpeed", 20))
            if nRoadLimitSpeed > 0:
                if nRoadLimitSpeed > 200:
                    nRoadLimitSpeed = (nRoadLimitSpeed - 20) / 10
                elif nRoadLimitSpeed == 120:
                    nRoadLimitSpeed = 115
            else:
                nRoadLimitSpeed = 30
            if self.nRoadLimitSpeed != nRoadLimitSpeed:
                self.nRoadLimitSpeed_counter += 1
                if self.nRoadLimitSpeed_counter > 5:
                    self.nRoadLimitSpeed = nRoadLimitSpeed
            else:
                self.nRoadLimitSpeed_counter = 0

            # SDI 完整12字段
            self.nSdiType = int(data.get("nSdiType", -1))
            self.nSdiSpeedLimit = int(data.get("nSdiSpeedLimit", 0))
            self.nSdiSection = int(data.get("nSdiSection", -1))
            self.nSdiDist = int(data.get("nSdiDist", -1))
            self.nSdiBlockType = int(data.get("nSdiBlockType", -1))
            self.nSdiBlockSpeed = int(data.get("nSdiBlockSpeed", 0))
            self.nSdiBlockDist = int(data.get("nSdiBlockDist", 0))
            self.nSdiPlusType = int(data.get("nSdiPlusType", -1))
            self.nSdiPlusSpeedLimit = int(data.get("nSdiPlusSpeedLimit", 0))
            self.nSdiPlusDist = int(data.get("nSdiPlusDist", 0))
            self.nSdiPlusBlockType = int(data.get("nSdiPlusBlockType", -1))
            self.nSdiPlusBlockSpeed = int(data.get("nSdiPlusBlockSpeed", 0))
            self.nSdiPlusBlockDist = int(data.get("nSdiPlusBlockDist", 0))
            self.roadcate = int(data.get("roadcate", 0))

            # TBT 转弯 (原版: vpPosPointLat→vpPosPointLatNavi)
            self.nTBTDist = int(data.get("nTBTDist", 0))
            self.nTBTTurnType = int(data.get("nTBTTurnType", -1))
            self.szTBTMainText = data.get("szTBTMainText", "")
            self.szNearDirName = data.get("szNearDirName", "")
            self.szFarDirName = data.get("szFarDirName", "")
            self.nTBTNextRoadWidth = int(data.get("nTBTNextRoadWidth", 0))
            self.nTBTDistNext = int(data.get("nTBTDistNext", 0))
            self.nTBTTurnTypeNext = int(data.get("nTBTTurnTypeNext", -1))

            # 下一转弯信息
            self.szTBTMainTextNext = data.get("szTBTMainTextNext", "")
            self.szNearDirNameNext = data.get("szNearDirNameNext", "")
            self.szFarDirNameNext = data.get("szFarDirNameNext", "")

            # 目的地
            self.nGoPosDist = int(data.get("nGoPosDist", 0))
            self.nGoPosTime = int(data.get("nGoPosTime", 0))
            self.szPosRoadName = data.get("szPosRoadName", "")
            if self.szPosRoadName == "null":
                self.szPosRoadName = ""

            # 导航GPS → 原版存入 vpPosPointLatNavi (与显示用 vpPosPointLat 分离)
            lat = float(data.get("vpPosPointLat", 0.0))
            lon = float(data.get("vpPosPointLon", 0.0))
            if lat != 0.0:
                self.vpPosPointLatNavi = lat
                self.vpPosPointLonNavi = lon
                self.vpPosPointLat = lat   # 显示用 (原版在 _update_gps 中计算)
                self.vpPosPointLon = lon
                self.nPosAngle = float(data.get("nPosAngle", self.nPosAngle))
                self.last_update_gps_time_navi = self.last_calculate_gps_time = time.time()
            self.nPosSpeed = float(data.get("nPosSpeed", self.nPosSpeed))

            if "epochTime" in data:
                self.epochTime = int(data.get("epochTime", 0))
                self.timestamp = data.get("timestamp", 0)
            self._update_tbt()
            self._update_sdi()

        # ── 手机GPS回退 (原版: 3秒无导航GPS则用phoneGPS覆盖) ──
        if "latitude" in data:
            self.nPosAnglePhone = float(data.get("heading", self.nPosAngle))
            self.phone_lat = float(data.get("latitude", self.vpPosPointLatNavi))
            self.phone_lon = float(data.get("longitude", self.vpPosPointLonNavi))
            self.phone_accuracy = float(data.get("accuracy", 0))
            self.phone_gps_speed = float(data.get("gps_speed", 0))
            if self.phone_accuracy < 15.0:
                self.phone_gps_frame += 1
            if (time.time() - self.last_update_gps_time_navi) > 3.0:
                self.vpPosPointLatNavi = self.phone_lat
                self.vpPosPointLonNavi = self.phone_lon
                self.vpPosPointLat = self.phone_lat
                self.vpPosPointLon = self.phone_lon
                self.nPosAngle = self.nPosAnglePhone
                self.last_update_gps_time_phone = self.last_calculate_gps_time = time.time()
                self.nPosSpeed = float(data.get("gps_speed", 0))

        # ── sdiData (区间测速控制) ──
        if "sdiData" in data:
            sdi_data = data.get("sdiData", {})
            if isinstance(sdi_data, dict):
                self.sdiData_type = int(sdi_data.get("nSdiType", -1))
                self.sdiData_dist = int(sdi_data.get("nSdiDist", 0))
                self.sdiData_speed = int(sdi_data.get("nSdiSpeedLimit", 0))
                self.sectionStartType = int(sdi_data.get("sectionStartType", -1))
                self.sectionStartDist = int(sdi_data.get("sectionStartDist", 0))

        # ── carrotDetect (探测数据) ──
        if "carrotDetect" in data:
            det = data.get("carrotDetect", {})
            if isinstance(det, dict):
                self.carrotDetect_type = int(det.get("carrotDetectType", 0))
                self.carrotDetect_dist = int(det.get("carrotDetectDist", 0))
                self.carrotDetect_speed = int(det.get("carrotDetectSpeed", 0))
                self.carrotDetect_cnf = int(det.get("carrotDetectCnf", 0))

        # ── phoneGPS (完整手机GPS) ──
        if "phoneGPS" in data:
            pg = data.get("phoneGPS", {})
            if isinstance(pg, dict):
                self.phone_lat2 = float(pg.get("phoneLatitude", 0))
                self.phone_lon2 = float(pg.get("phoneLongitude", 0))
                self.phone_acc2 = float(pg.get("phoneAccuracy", 0))
                self.phone_frame = int(pg.get("phoneGPSFrame", 0))
                self.phone_speed2 = float(pg.get("phoneSpeed", 0))
        elif "latitude" in data:
            # 旧格式手机GPS (当导航GPS不可用时回退)
            self.phone_heading = float(data.get("heading", 0))
            self.phone_lat = float(data.get("latitude", 0))
            self.phone_lon = float(data.get("longitude", 0))
            self.phone_accuracy = float(data.get("accuracy", 0))
            self.phone_gps_speed = float(data.get("gps_speed", 0))

        # ── 时间同步 (每60帧) ──
        if "epochTime" in data and self.total_packets % 60 == 0:
            self.epochTime = int(data.get("epochTime", 0))
            tz_val = data.get("timezone", 0)
            if isinstance(tz_val, str):
                tz_val = 0
            self.timezone = int(tz_val)

        # ── autoNaviSpeed 参数 ──
        if "autoNaviSpeed" in data:
            an = data.get("autoNaviSpeed", {})
            if isinstance(an, dict):
                self.autoNaviSpeedCtrlMode = int(an.get("autoNaviSpeedCtrlMode", 0))
                self.autoNaviSpeedDecelRate = float(an.get("autoNaviSpeedDecelRate", 0))
                self.autoNaviSpeedSafetyFactor = float(an.get("autoNaviSpeedSafetyFactor", 0))
                self.autoNaviSpeedBumpSpeed = float(an.get("autoNaviSpeedBumpSpeed", 30))
                self.autoNaviSpeedBumpTime = int(an.get("autoNaviSpeedBumpTime", 2))
                self.autoNaviSpeedCtrlEnd = int(an.get("autoNaviSpeedCtrlEnd", 0))

        # ── 交通灯 ──
        if "trafficLight" in data:
            tl = data.get("trafficLight", {})
            if isinstance(tl, dict):
                self.traffic_light_x = int(tl.get("trafficLightX", 0))
                self.traffic_light_y = int(tl.get("trafficLightY", 0))
                self.traffic_light_color = int(tl.get("trafficLightColor", 0))
                self.traffic_light_cntf = int(tl.get("trafficLightCntf", 0))

        # ── KISA 数据 (Korean safety camera) ──
        if "kisaData" in data:
            kisa_raw = data.get("kisaData", "")
            if kisa_raw:
                self.kisa_data_str = str(kisa_raw)
                self.active_kisa_count = 200
                self._parse_kisa_data(kisa_raw)

        # ── 总距离 (delta 计算) ──
        if "totalDistance" in data:
            self.totalDistance = float(data.get("totalDistance", 0))

    def _update_sdi(self):
        """对照 carrot_serv.py _update_sdi()"""
        if self.nSdiType in [0, 1, 2, 3, 4, 7, 8, 75, 76] and self.nSdiSpeedLimit > 0 and self.autoNaviSpeedCtrlMode > 0:
            self.xSpdLimit = int(self.nSdiSpeedLimit * self.autoNaviSpeedSafetyFactor)
            self.xSpdDist = self.nSdiDist
            self.xSpdType = self.nSdiType
            if self.nSdiBlockType in [2, 3]:
                self.xSpdDist = self.nSdiBlockDist
                self.xSpdType = 4
            elif self.nSdiType == 7 and self.autoNaviSpeedCtrlMode < 3:  # 이동식카메라
                self.xSpdLimit = self.xSpdDist = 0
        elif (self.nSdiPlusType == 22 or self.nSdiType == 22) and self.roadcate > 1 and self.autoNaviSpeedCtrlMode >= 2:
            self.xSpdLimit = int(self.autoNaviSpeedBumpSpeed)
            self.xSpdDist = self.nSdiPlusDist if self.nSdiPlusType == 22 else self.nSdiDist
            self.xSpdType = 22
        else:
            self.xSpdLimit = 0
            self.xSpdType = -1
            self.xSpdDist = 0

    def _update_tbt(self):
        """对照 carrot_serv.py _update_tbt()"""
        if self.nTBTTurnType in NAV_TYPE_MAPPING:
            nav_type, modifier, self.xTurnInfo = NAV_TYPE_MAPPING[self.nTBTTurnType]
            self.navType = nav_type
            self.navModifier = modifier
        else:
            self.xTurnInfo = -1
            self.navType, self.navModifier = "invalid", ""

        if self.nTBTTurnTypeNext in NAV_TYPE_MAPPING:
            nav_type, modifier, self.xTurnInfoNext = NAV_TYPE_MAPPING[self.nTBTTurnTypeNext]
            self.navTypeNext = nav_type
            self.navModifierNext = modifier
        else:
            self.xTurnInfoNext = -1
            self.navTypeNext, self.navModifierNext = "invalid", ""

        if self.nTBTDist > 0 and self.xTurnInfo > 0:
            self.xDistToTurn = self.nTBTDist
        if self.nTBTDistNext > 0 and self.xTurnInfoNext > 0:
            self.xDistToTurnNext = self.nTBTDistNext + self.nTBTDist

    def tick(self):
        """每帧递减计数器 (模拟 update_navi 中的递减逻辑)"""
        self.active_count = max(self.active_count - 1, 0)
        self.active_sdi_count = max(self.active_sdi_count - 1, 0)
        self.active_kisa_count = max(self.active_kisa_count - 1, 0)
        if self.active_kisa_count > 0:
            self.active_carrot = 2
        elif self.active_count > 0:
            self.active_carrot = 2 if self.active_sdi_count > 0 else 1
        else:
            self.active_carrot = 0

    def _parse_kisa_data(self, data: str):
        """解析 KISA 数据 (Korean safety camera)"""
        self.kisa_raw_bytes = b""
        self.kisa_parsed = {}
        try:
            raw_bytes = data.encode("latin-1")
            self.kisa_raw_bytes = raw_bytes
            # 替换转义字符为实际字符
            parsed = data.replace("\\x", "\\x").replace("\\n", "\n")
            try:
                json_str = parsed.encode("latin-1").decode("unicode_escape")
                self.kisa_parsed = json.loads(json_str)
            except (UnicodeDecodeError, json.JSONDecodeError):
                # 尝试直接解析
                try:
                    self.kisa_parsed = json.loads(data)
                except json.JSONDecodeError:
                    self.kisa_parsed = {"raw": data}
        except Exception:
            self.kisa_parsed = {"error": "parse_failed"}


    def set_route_points(self, points):
        """设置路线点并计算分析数据"""
        self.navi_points = points
        self.navi_points_active = len(points) > 0
        self.route_recv_time = time.time()
        self.route_total_dist = self._calc_route_distance(points)
        self._calc_curvatures(points)

    def _calc_route_distance(self, points):
        """计算路线总距离 (haversine)"""
        total = 0.0
        for i in range(len(points) - 1):
            total += _haversine(points[i][0], points[i][1], points[i+1][0], points[i+1][1])
        return total

    def _calc_curvatures(self, points):
        """
        对照 carrot_man.py carrot_navi_route() 的曲率计算逻辑:
        1. GPS→相对坐标 (gps_to_relative_xy)
        2. 10m间隔重采样
        3. 三点法计算曲率
        4. 查表得弯道限速
        """
        self.route_curvatures = []
        self.route_curve_speeds = []
        self.route_min_curve_speed = 300

        if len(points) < 10:
            return

        # 简化: 用第一个点和0度航向做相对坐标转换
        ref_lon, ref_lat = points[0]
        heading_rad = 0.0
        rel_coords = []
        for lon, lat in points:
            x = (lon - ref_lon) * 40008000 * math.cos(math.radians(ref_lat)) / 360
            y = (lat - ref_lat) * 40008000 / 360
            x_rot = x * math.cos(heading_rad) - y * math.sin(heading_rad)
            y_rot = x * math.sin(heading_rad) + y * math.cos(heading_rad)
            rel_coords.append((y_rot, x_rot))

        # 10m间隔重采样 (简化版: 线性插值)
        resampled = _resample_path(rel_coords, 10.0)
        if len(resampled) < 9:  # sample*2+1
            return

        sample = 4
        for i in range(len(resampled) - sample * 2):
            p1 = resampled[i]
            p2 = resampled[i + sample]
            p3 = resampled[i + sample * 2]
            curv = _calculate_curvature(p1, p2, p3)
            self.route_curvatures.append(curv)
            # 查表: V_CURVE_LOOKUP
            speed = _interp_curve_speed(abs(curv))
            self.route_curve_speeds.append(speed)

        if self.route_curve_speeds:
            self.route_min_curve_speed = min(self.route_curve_speeds)



# ─────────────────────────────────────────────
#  路线分析辅助函数 (来自 carrot_man.py)
# ─────────────────────────────────────────────
# 弯道限速查找表 (来自 carrot_man.py)
V_CURVE_LOOKUP_BP   = [0., 1./800., 1./670., 1./560., 1./440., 1./360., 1./265., 1./190., 1./135., 1./85., 1./55., 1./30., 1./25.]
V_CURVE_LOOKUP_VALS = [300, 150, 120, 110, 100, 90, 80, 70, 60, 50, 40, 15, 5]

def _haversine(lon1, lat1, lon2, lat2):
    """两点间距离 (m)"""
    R = 6371000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlam/2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1-a))

def _calculate_curvature(p1, p2, p3):
    """三点法计算曲率 (来自 carrot_man.py calculate_curvature)"""
    v1 = (p2[0]-p1[0], p2[1]-p1[1])
    v2 = (p3[0]-p2[0], p3[1]-p2[1])
    cross = v1[0]*v2[1] - v1[1]*v2[0]
    len1 = math.sqrt(v1[0]**2 + v1[1]**2)
    len2 = math.sqrt(v2[0]**2 + v2[1]**2)
    if len1 * len2 == 0:
        return 0
    return cross / (len1 * len2 * len1)

def _resample_path(coords, interval):
    """将路径按固定间隔重采样"""
    if len(coords) < 2:
        return coords
    resampled = [coords[0]]
    accum = 0.0
    for i in range(1, len(coords)):
        dx = coords[i][0] - coords[i-1][0]
        dy = coords[i][1] - coords[i-1][1]
        seg_len = math.sqrt(dx*dx + dy*dy)
        if seg_len == 0:
            continue
        accum += seg_len
        while accum >= interval:
            accum -= interval
            ratio = 1.0 - accum / seg_len if seg_len > 0 else 1.0
            x = coords[i-1][0] + dx * ratio
            y = coords[i-1][1] + dy * ratio
            resampled.append((x, y))
    return resampled

def _interp_curve_speed(curvature):
    """线性插值查表得弯道限速 (对照 np.interp)"""
    bp = V_CURVE_LOOKUP_BP
    vals = V_CURVE_LOOKUP_VALS
    if curvature <= bp[0]:
        return vals[0]
    if curvature >= bp[-1]:
        return vals[-1]
    for i in range(len(bp) - 1):
        if bp[i] <= curvature <= bp[i+1]:
            t = (curvature - bp[i]) / (bp[i+1] - bp[i])
            return vals[i] + t * (vals[i+1] - vals[i])
    return vals[-1]


# ═══════════════════════════════════════════════
#  HTTP 参数服务器 (7000 端口)
# ═══════════════════════════════════════════════
class ParamHTTPHandler(BaseHTTPRequestHandler):
    """
    模拟 comma3 的 HTTP 参数 API (端口 7000)

    支持的端点:
    - GET  /api/params_bulk?keys=key1,key2   批量读取参数
    - POST /api/param_set?key=xxx&value=yyy  设置参数

    参数存储在 server.params 字典中
    """

    def log_message(self, format, *args):
        """抑制默认的HTTP日志输出，使用自定义日志"""
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/params_bulk":
            self._handle_params_bulk(parsed)
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/param_set":
            self._handle_param_set(parsed)
        else:
            self.send_error(404, "Not Found")

    def _handle_params_bulk(self, parsed):
        """批量读取参数"""
        query = parse_qs(parsed.query)
        keys_str = query.get("keys", [""])[0]
        keys = [k.strip() for k in keys_str.split(",") if k.strip()]

        result = {}
        for key in keys:
            result[key] = self.server.params.get(key, "")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(result).encode("utf-8"))

        if self.server.log_callback:
            self.server.log_callback(f"HTTP GET /api/params_bulk keys={keys_str}")

    def _handle_param_set(self, parsed):
        """设置参数"""
        content_len = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_len).decode("utf-8")

        # 解析参数 (支持 query string 或 POST body)
        params = parse_qs(parsed.query)
        if body:
            params.update(parse_qs(body))

        key = params.get("key", [""])[0]
        value = params.get("value", [""])[0]

        if key:
            self.server.params[key] = value
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")

            if self.server.log_callback:
                self.server.log_callback(f"HTTP POST /api/param_set key={key} value={value[:50]}")
        else:
            self.send_error(400, "Missing key parameter")


class ParamHTTPServer(HTTPServer):
    """扩展HTTPServer以支持参数存储和日志回调"""
    def __init__(self, server_address, handler_class, log_callback=None):
        super().__init__(server_address, handler_class)
        self.params = {
            # 默认参数 (模拟 comma3 常见参数)
            "DongleId": "sim_" + get_local_ip().replace(".", "_"),
            "Version": VERSION,
            "IsOnroad": "1",
            "CarrotManActive": "1",
        }
        self.log_callback = log_callback


# ═══════════════════════════════════════════════
#  TCP 状态推送服务器 (7711 端口)
# ═══════════════════════════════════════════════
class StatusTCPServer:
    """
    模拟 comma3 向手机推送设备状态 (端口 7711)

    协议: 每秒发送一次 JSON 数据，包含:
      - carState: 车辆状态 (速度、转向角等)
      - modelV2: 模型输出
      - controlsState: 控制状态
      - systemState: 系统状态
    """

    def __init__(self, port, serv, log_callback=None):
        self.port = port
        self.serv = serv
        self.log_callback = log_callback
        self.is_running = False
        self.clients = []  # 已连接的客户端列表
        self.lock = threading.Lock()

    def start(self):
        """启动TCP服务器"""
        self.is_running = True
        threading.Thread(target=self._accept_loop, daemon=True).start()
        threading.Thread(target=self._broadcast_loop, daemon=True).start()

    def stop(self):
        """停止服务器"""
        self.is_running = False
        with self.lock:
            for client in self.clients:
                try:
                    client.close()
                except:
                    pass
            self.clients.clear()

    def _accept_loop(self):
        """接受客户端连接"""
        srv_socket = None
        try:
            srv_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv_socket.settimeout(5)
            srv_socket.bind(("0.0.0.0", self.port))
            srv_socket.listen(5)

            if self.log_callback:
                self.log_callback(f"✅ TCP状态服务器已启动: 0.0.0.0:{self.port}")

            while self.is_running:
                try:
                    client, addr = srv_socket.accept()
                    with self.lock:
                        self.clients.append(client)
                    if self.log_callback:
                        self.log_callback(f"📱 状态客户端连接: {addr[0]}:{addr[1]}")
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.is_running:
                        if self.log_callback:
                            self.log_callback(f"⚠️ 状态服务器接受连接错误: {e}")
        except Exception as e:
            if self.log_callback:
                self.log_callback(f"❌ TCP状态服务器异常: {e}")
                traceback.print_exc()
        finally:
            if srv_socket:
                try:
                    srv_socket.close()
                except:
                    pass

    def _broadcast_loop(self):
        """每秒向所有客户端广播状态"""
        if self.log_callback:
            self.log_callback(f"TCP状态广播循环已启动")

        while self.is_running:
            time.sleep(1.0)

            # 生成模拟状态数据
            try:
                status_data = self._make_status_data()
                json_str = json.dumps(status_data)
                data = json_str.encode("utf-8")

                # 发送给所有客户端 (长度前缀协议: 4字节大端长度 + JSON数据)
                with self.lock:
                    dead_clients = []
                    for client in self.clients:
                        try:
                            # 先读取客户端发来的心跳数据(非阻塞), 防止缓冲区满
                            client.setblocking(False)
                            try:
                                while True:
                                    chunk = client.recv(4096)
                                    if not chunk:
                                        break
                            except (BlockingIOError, socket.timeout):
                                pass
                            finally:
                                client.setblocking(True)
                        except:
                            pass

                        try:
                            # 长度前缀协议: [4字节大端长度][JSON数据]
                            payload = struct.pack('>I', len(data)) + data
                            client.sendall(payload)
                        except Exception as e:
                            if self.is_running and self.log_callback:
                                self.log_callback(f"⚠️ 状态广播错误: {e}")
                            dead_clients.append(client)

                    # 清理断开的客户端
                    for dead in dead_clients:
                        try:
                            dead.close()
                        except:
                            pass
                        self.clients.remove(dead)
            except Exception as e:
                if self.log_callback:
                    self.log_callback(f"⚠️ 状态广播循环错误: {e}")

    def _make_status_data(self):
        """生成模拟的设备状态数据"""
        s = self.serv

        # 模拟车辆状态
        car_state = {
            "vEgo": s.nPosSpeed / 3.6,  # km/h → m/s
            "aEgo": 0.0,
            "steeringAngleDeg": 0.0,
            "steeringTorque": 0.0,
            "gas": 0.0,
            "brake": 0.0,
            "gearShifter": "drive",
            "cruiseState": {
                "enabled": True,
                "speed": s.nRoadLimitSpeed / 3.6,
                "available": True,
            }
        }

        # 模拟模型输出
        model_v2 = {
            "position": {"x": [0.0] * 33, "y": [0.0] * 33, "z": [0.0] * 33},
            "velocity": {"x": [0.0] * 33, "y": [0.0] * 33, "z": [0.0] * 33},
            "orientation": {"x": [0.0] * 33, "y": [0.0] * 33, "z": [0.0] * 33},
            "laneLines": [],
            "roadEdges": [],
            "leads": [],
        }

        # 模拟控制状态
        controls_state = {
            "enabled": True,
            "active": True,
            "vPid": s.nPosSpeed / 3.6,
            "vTargetLead": 0.0,
            "vCruise": s.nRoadLimitSpeed,
            "alertText1": "",
            "alertText2": "",
            "alertStatus": "normal",
        }

        # 模拟系统状态
        system_state = {
            "deviceType": "comma",
            "started": True,
            "ignitionLine": True,
        }

        return {
            "carState": car_state,
            "modelV2": model_v2,
            "controlsState": controls_state,
            "systemState": system_state,
        }


# ═══════════════════════════════════════════════
#  主模拟器
# ═══════════════════════════════════════════════
class Comma3Simulator:
    def __init__(self):
        self.local_ip = get_local_ip()
        self.is_running = False
        self.remote_addr = None       # 手机App地址 (ip, port)
        self.serv = CarrotServSim()   # 协议解析器

        # HTTP 参数服务器 (7000)
        self.http_server = None

        # TCP 状态推送服务器 (7711)
        self.status_server = None

        # GUI
        self.root = tk.Tk()
        self.root.title(f"Comma3 模拟器  [{self.local_ip}]")
        self.root.geometry("1100x750")
        self._build_gui()

        # 数据表行映射
        self._tree_items: OrderedDict = OrderedDict()
        self._paused = False

    # ─── GUI 构建 ───────────────────────────────
    def _build_gui(self):
        top = ttk.Frame(self.root)
        top.pack(fill=tk.X, padx=8, pady=4)

        self.btn_start = ttk.Button(top, text="▶ 启动", command=self._start)
        self.btn_start.pack(side=tk.LEFT, padx=4)
        self.btn_stop = ttk.Button(top, text="■ 停止", command=self._stop, state=tk.DISABLED)
        self.btn_stop.pack(side=tk.LEFT, padx=4)
        self.btn_pause = ttk.Button(top, text="⏸ 暂停刷新", command=self._toggle_pause)
        self.btn_pause.pack(side=tk.LEFT, padx=4)
        self.btn_export = ttk.Button(top, text="导出JSON", command=self._export)
        self.btn_export.pack(side=tk.LEFT, padx=4)

        self.lbl_status = ttk.Label(top, text="状态: 未启动")
        self.lbl_status.pack(side=tk.RIGHT, padx=8)

        # Notebook
        nb = ttk.Notebook(self.root)
        nb.pack(fill=tk.BOTH, expand=True, padx=8, pady=4)

        # Tab 1: 实时数据表
        frm1 = ttk.Frame(nb); nb.add(frm1, text="实时数据")
        self._build_data_table(frm1)

        # Tab 2: 原始JSON
        frm2 = ttk.Frame(nb); nb.add(frm2, text="原始JSON")
        self.txt_raw = scrolledtext.ScrolledText(frm2, wrap=tk.WORD, font=("Consolas", 9))
        self.txt_raw.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        # Tab 3: 路线数据 (7709)
        frm_route = ttk.Frame(nb); nb.add(frm_route, text="路线数据 (7709)")
        self._build_route_tab(frm_route)

        # Tab 4: 日志
        frm3 = ttk.Frame(nb); nb.add(frm3, text="通信日志")
        self.txt_log = scrolledtext.ScrolledText(frm3, wrap=tk.WORD, font=("Consolas", 9))
        self.txt_log.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        # 底部状态栏
        bar = ttk.Frame(self.root)
        bar.pack(fill=tk.X, padx=8, pady=2)
        self.lbl_stats = ttk.Label(bar, text="包数: 0 | 手机: 未连接")
        self.lbl_stats.pack(side=tk.LEFT)
        self.lbl_time = ttk.Label(bar, text="")
        self.lbl_time.pack(side=tk.RIGHT)

    def _build_data_table(self, parent):
        """构建实时数据 Treeview"""
        cols = ("分类", "字段名", "当前值", "类型", "说明")
        self.tree = ttk.Treeview(parent, columns=cols, show="headings", height=28)
        widths = {"分类": 90, "字段名": 160, "当前值": 200, "类型": 60, "说明": 280}
        for c in cols:
            self.tree.heading(c, text=c, anchor=tk.W)
            self.tree.column(c, width=widths.get(c, 100), anchor=tk.W)

        vsb = ttk.Scrollbar(parent, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=vsb.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        vsb.pack(side=tk.RIGHT, fill=tk.Y)

        self.tree.tag_configure("updated", background="#fff2cc")
        self.tree.tag_configure("normal", background="white")
        self.tree.tag_configure("warn", background="#ffebee")

    def _build_route_tab(self, parent):
        """构建路线数据Tab"""
        # 上部: 路线统计信息
        info_frame = ttk.LabelFrame(parent, text="路线统计")
        info_frame.pack(fill=tk.X, padx=4, pady=4)

        self.lbl_route_info = ttk.Label(info_frame, text="等待路线数据...", font=("Consolas", 10))
        self.lbl_route_info.pack(fill=tk.X, padx=8, pady=4)

        # 中部: 弯道限速分析
        curve_frame = ttk.LabelFrame(parent, text="弯道限速分析 (vturn_speed)")
        curve_frame.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        cols_c = ("序号", "距离(m)", "曲率", "曲率半径(m)", "限速(km/h)", "等级")
        self.route_curve_tree = ttk.Treeview(curve_frame, columns=cols_c, show="headings", height=12)
        widths_c = {"序号": 50, "距离(m)": 80, "曲率": 100, "曲率半径(m)": 100, "限速(km/h)": 90, "等级": 80}
        for c in cols_c:
            self.route_curve_tree.heading(c, text=c, anchor=tk.W)
            self.route_curve_tree.column(c, width=widths_c.get(c, 80), anchor=tk.W)
        vsb_c = ttk.Scrollbar(curve_frame, orient=tk.VERTICAL, command=self.route_curve_tree.yview)
        self.route_curve_tree.configure(yscrollcommand=vsb_c.set)
        self.route_curve_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        vsb_c.pack(side=tk.RIGHT, fill=tk.Y)

        self.route_curve_tree.tag_configure("sharp", background="#ffcdd2")   # 急弯 <40
        self.route_curve_tree.tag_configure("medium", background="#fff9c4")  # 中弯 40-80
        self.route_curve_tree.tag_configure("gentle", background="#c8e6c9")  # 缓弯 80-120
        self.route_curve_tree.tag_configure("straight", background="white")  # 直道 >120

        # 下部: 路线点列表
        pts_frame = ttk.LabelFrame(parent, text="路线点 (lon, lat)")
        pts_frame.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        cols_p = ("序号", "经度", "纬度", "段距(m)", "累计距(m)")
        self.route_pts_tree = ttk.Treeview(pts_frame, columns=cols_p, show="headings", height=8)
        widths_p = {"序号": 50, "经度": 130, "纬度": 130, "段距(m)": 90, "累计距(m)": 90}
        for c in cols_p:
            self.route_pts_tree.heading(c, text=c, anchor=tk.W)
            self.route_pts_tree.column(c, width=widths_p.get(c, 80), anchor=tk.W)
        vsb_p = ttk.Scrollbar(pts_frame, orient=tk.VERTICAL, command=self.route_pts_tree.yview)
        self.route_pts_tree.configure(yscrollcommand=vsb_p.set)
        self.route_pts_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        vsb_p.pack(side=tk.RIGHT, fill=tk.Y)

        self._route_data_version = 0  # 用于检测路线数据是否更新

    # ─── 启动 / 停止 ──────────────────────────
    def _start(self):
        if self.is_running:
            return
        # 刷新本机IP (可能网络切换了)
        self.local_ip = get_local_ip()
        self.is_running = True
        self.btn_start.config(state=tk.DISABLED)
        self.btn_stop.config(state=tk.NORMAL)
        self.lbl_status.config(text=f"状态: 运行中  IP={self.local_ip}")
        self.root.title(f"Comma3 模拟器  [{self.local_ip}]")

        # 启动 HTTP 参数服务器 (7000)
        try:
            self.http_server = ParamHTTPServer(
                ("0.0.0.0", PARAM_PORT),
                ParamHTTPHandler,
                log_callback=self._log
            )
            http_thread = threading.Thread(target=self._http_server_loop, daemon=True)
            http_thread.start()
            # 等待一小段时间确保服务器启动
            time.sleep(0.1)
            self._log(f"✅ HTTP参数服务器已启动: 0.0.0.0:{PARAM_PORT} (线程ID: {http_thread.ident})")
        except Exception as e:
            self._log(f"❌ HTTP参数服务器启动失败: {e}")
            traceback.print_exc()

        # 启动 TCP 状态推送服务器 (7711)
        try:
            self.status_server = StatusTCPServer(STATUS_PORT, self.serv, log_callback=self._log)
            self.status_server.start()
            # 等待一小段时间确保服务器启动
            time.sleep(0.1)
        except Exception as e:
            self._log(f"❌ TCP状态推送服务器启动失败: {e}")
            traceback.print_exc()

        # 启动网络线程
        threading.Thread(target=self._broadcast_loop, daemon=True).start()
        threading.Thread(target=self._data_recv_loop, daemon=True).start()
        threading.Thread(target=self._route_recv_loop, daemon=True).start()

        # 启动GUI刷新
        self._schedule_gui_update()
        self._log("=" * 60)
        self._log("模拟器已启动")
        self._log(f"  广播端口: {BROADCAST_PORT} (UDP) - 设备发现")
        self._log(f"  数据端口: {DATA_PORT} (UDP) - 接收导航数据")
        self._log(f"  路线端口: {ROUTE_PORT} (TCP) - 接收路线点")
        self._log(f"  状态端口: {STATUS_PORT} (TCP) - 推送设备状态")
        self._log(f"  参数端口: {PARAM_PORT} (HTTP) - 参数读写API")
        self._log(f"  本机IP: {self.local_ip}")
        self._log(f"  子网广播: {get_subnet_broadcast(self.local_ip)}")
        self._log(f"  请确保手机和电脑在同一WiFi网络")
        self._log("=" * 60)

    def _stop(self):
        self.is_running = False
        self.remote_addr = None
        self.btn_start.config(state=tk.NORMAL)
        self.btn_stop.config(state=tk.DISABLED)
        self.lbl_status.config(text="状态: 已停止")

        # 停止 HTTP 服务器
        if self.http_server:
            try:
                self.http_server.shutdown()
                self.http_server.server_close()
                self._log("HTTP参数服务器已停止")
            except:
                pass
            self.http_server = None

        # 停止 TCP 状态服务器
        if self.status_server:
            try:
                self.status_server.stop()
                self._log("TCP状态推送服务器已停止")
            except:
                pass
            self.status_server = None

        self._log("模拟器已停止")

    def _http_server_loop(self):
        """HTTP服务器运行循环"""
        try:
            self._log(f"HTTP服务器线程开始运行...")
            self.http_server.serve_forever()
        except Exception as e:
            if self.is_running:
                self._log(f"❌ HTTP服务器异常: {e}")
                traceback.print_exc()
        finally:
            self._log(f"HTTP服务器线程已退出")

    def _toggle_pause(self):
        self._paused = not self._paused
        self.btn_pause.config(text="▶ 继续刷新" if self._paused else "⏸ 暂停刷新")

    def _export(self):
        fn = filedialog.asksaveasfilename(
            defaultextension=".json",
            filetypes=[("JSON", "*.json"), ("All", "*.*")],
            title="导出接收数据"
        )
        if fn:
            export = {
                "export_time": datetime.now().isoformat(),
                "total_packets": self.serv.total_packets,
                "remote_addr": str(self.remote_addr),
                "raw_fields": self.serv.raw_fields,
            }
            with open(fn, "w", encoding="utf-8") as f:
                json.dump(export, f, indent=2, ensure_ascii=False)
            self._log(f"数据已导出: {fn}")

    # ─── 网络: 广播线程 (模拟 broadcast_version_info) ──
    def _broadcast_loop(self):
        """
        对照 carrot_man.py broadcast_version_info():
        每 1/20 秒向手机广播一次设备状态JSON。

        原始逻辑:
          - 无连接时: 用子网广播地址 (get_broadcast_address), 每秒1次 (frame%20==0)
          - 有连接时: 直接发给手机IP, 每帧发送 (20Hz)
        """
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(5)  # 避免 sendto 在极端情况下永久阻塞
        # 绑定到 "" (0.0.0.0)，在 Windows 上更可靠
        try:
            sock.bind(("", 0))
        except Exception:
            pass  # 绑定失败则使用未绑定socket

        interval = 1.0 / BROADCAST_HZ
        subnet_broadcast = get_subnet_broadcast(self.local_ip)
        frame = 0

        self._log(f"广播线程启动: 子网广播={subnet_broadcast} | 自身IP={self.local_ip}")

        while self.is_running:
            try:
                self.serv.tick()
                msg = self._make_broadcast_msg()
                data = json.dumps(msg).encode("utf-8")

                if self.remote_addr:
                    # 有连接时: 每帧直接发给手机 (20Hz)
                    try:
                        sock.sendto(data, (self.remote_addr[0], BROADCAST_PORT))
                    except Exception as e:
                        self._log(f"发送单播给 {self.remote_addr[0]} 失败: {e}")
                else:
                    # 无连接时: 每秒广播一次 (frame%20==0)
                    if frame % 20 == 0:
                        sent_ok = False
                        # 子网广播
                        try:
                            sock.sendto(data, (subnet_broadcast, BROADCAST_PORT))
                            sent_ok = True
                        except Exception as e:
                            self._log(f"子网广播发送失败 {subnet_broadcast}:{BROADCAST_PORT} — {e}")
                        # 全局广播
                        try:
                            sock.sendto(data, ("255.255.255.255", BROADCAST_PORT))
                            sent_ok = True
                        except Exception:
                            pass  # 全局广播可能被防火墙拦截，忽略
                        if sent_ok and frame % (20 * 5) == 0:  # 每5秒log一次
                            self._log(f"📡 广播已发送: len={len(data)}B | 等待手机从 {BROADCAST_PORT} 发现...")

                time.sleep(interval)
                frame += 1
            except Exception as e:
                if self.is_running:
                    self._log(f"广播循环异常: {e}")
                time.sleep(1)
        sock.close()

    def _make_broadcast_msg(self) -> dict:
        """
        对照 carrot_man.py make_send_message()
        """
        return {
            "Carrot2": VERSION,
            "IsOnroad": True,
            "CarrotRouteActive": self.serv.navi_points_active,
            "ip": self.local_ip,
            "port": DATA_PORT,
            "log_carrot": "",
            "v_cruise_kph": 0.0,
            "carcruiseSpeed": 0.0,
            "v_ego_kph": 0,
            "tbt_dist": int(self.serv.xDistToTurn),
            "sdi_dist": int(self.serv.xSpdDist),
            "active": False,
            "xState": 0,
            "trafficState": self.serv.traffic_state,
            "active_carrot": self.serv.active_carrot,
            "carrotIndex": self.serv.carrotIndex,
            "nRoadLimitSpeed": self.serv.nRoadLimitSpeed,
            "roadcate": self.serv.roadcate,
            "nSdiType": self.serv.nSdiType,
            "nSdiSpeedLimit": self.serv.nSdiSpeedLimit,
            "nSdiDist": self.serv.nSdiDist,
            "xSpdType": self.serv.xSpdType,
            "xSpdLimit": self.serv.xSpdLimit,
            "nTBTTurnType": self.serv.nTBTTurnType,
            "nTBTDist": self.serv.nTBTDist,
            "szTBTMainText": self.serv.szTBTMainText,
            "nGoPosDist": self.serv.nGoPosDist,
            "nGoPosTime": self.serv.nGoPosTime,
            "szGoalName": self.serv.szGoalName,
            "vpPosPointLat": self.serv.vpPosPointLat,
            "vpPosPointLon": self.serv.vpPosPointLon,
            "nPosSpeed": self.serv.nPosSpeed,
            "latitude": self.serv.phone_lat,
            "longitude": self.serv.phone_lon,
            "nPosAngle": self.serv.nPosAngle,
            "heading": self.serv.phone_heading,
            "accuracy": self.serv.phone_accuracy,
            "gps_speed": self.serv.phone_gps_speed,
        }

    # ─── 网络: 数据接收线程 (模拟 carrot_man_thread) ──
    def _data_recv_loop(self):
        """
        对照 carrot_man.py carrot_man_thread():
        在 7706 端口监听手机App发来的UDP JSON数据。
        """
        while self.is_running:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                    sock.settimeout(10)  # 与原始代码一致: 10秒超时
                    sock.bind(("0.0.0.0", DATA_PORT))
                    self._log(f"数据接收线程启动，监听 0.0.0.0:{DATA_PORT}")

                    while self.is_running:
                        try:
                            data, addr = sock.recvfrom(4096)
                            if not data:
                                continue

                            if self.remote_addr is None:
                                self._log(f"📱 手机已连接: {addr[0]}:{addr[1]} (首次收到UDP数据)")
                                # 打印接收到的原始数据头
                                raw_preview = data.decode("utf-8", errors="replace")[:200]
                                self._log(f"📦 首包数据预览: {raw_preview}")
                            else:
                                # 地址变化时也提示
                                if self.remote_addr != addr:
                                    self._log(f"📱 手机地址变更: {self.remote_addr[0]} → {addr[0]}")
                            self.remote_addr = addr

                            try:
                                json_obj = json.loads(data.decode("utf-8"))
                                self.serv.update(json_obj)
                            except json.JSONDecodeError as e:
                                self._log(f"JSON解析错误: {e}")

                        except socket.timeout:
                            # 与原始代码一致: 超时后重置连接，继续等待
                            if self.remote_addr:
                                self._log("手机数据超时(10s)，等待重连...")
                            self.remote_addr = None
                            time.sleep(1)
                        except Exception as e:
                            if self.is_running:
                                self._log(f"接收错误: {e}")
                            self.remote_addr = None
                            break
            except OSError as e:
                self._log(f"端口 {DATA_PORT} 绑定失败: {e}，2秒后重试...")
                time.sleep(2)
            except Exception as e:
                if self.is_running:
                    self._log(f"数据线程异常: {e}")
                time.sleep(2)

    # ─── 网络: 路线接收线程 (模拟 carrot_route) ──
    def _route_recv_loop(self):
        """
        对照 carrot_man.py carrot_route():
        在 7709 端口监听TCP路线点串。
        格式: 4字节总长度 + N*(4字节float经度 + 4字节float纬度)
        """
        while self.is_running:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
                    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    srv.settimeout(5)
                    srv.bind(("0.0.0.0", ROUTE_PORT))
                    srv.listen(1)
                    self._log(f"路线接收线程启动，监听 0.0.0.0:{ROUTE_PORT}")

                    while self.is_running:
                        try:
                            conn, addr = srv.accept()
                            with conn:
                                self._log(f"🗺️ 路线连接: {addr}")
                                # 读取总长度
                                size_bytes = self._recvall(conn, 4)
                                if not size_bytes:
                                    continue
                                total_size = struct.unpack("!I", size_bytes)[0]
                                all_data = self._recvall(conn, total_size)
                                if not all_data:
                                    continue
                                points = []
                                for i in range(0, len(all_data), 8):
                                    x, y = struct.unpack("!ff", all_data[i:i+8])
                                    points.append((x, y))
                                self.serv.set_route_points(points)
                                self._log(f"🗺️ 收到路线点: {len(points)} 个, 总距离: {self.serv.route_total_dist:.0f}m, 最小弯道限速: {self.serv.route_min_curve_speed:.0f}km/h")
                        except socket.timeout:
                            continue
                        except Exception as e:
                            if self.is_running:
                                self._log(f"路线接收错误: {e}")
            except OSError as e:
                self._log(f"端口 {ROUTE_PORT} 绑定失败: {e}，2秒后重试...")
                time.sleep(2)
            except Exception as e:
                if self.is_running:
                    self._log(f"路线线程异常: {e}")
                time.sleep(2)

    @staticmethod
    def _recvall(sock, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                return None
            buf.extend(chunk)
        return buf

    # ─── 日志 ──────────────────────────────────
    def _log(self, msg):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        line = f"[{ts}] {msg}\n"
        try:
            print(line.strip())
        except UnicodeEncodeError:
            print(line.strip().encode("utf-8", errors="replace").decode("utf-8", errors="replace"))
        if hasattr(self, "txt_log"):
            try:
                self.txt_log.insert(tk.END, line)
                self.txt_log.see(tk.END)
            except tk.TclError:
                pass


    # ─── GUI 定时刷新 ─────────────────────────
    def _schedule_gui_update(self):
        if not self.is_running:
            return
        try:
            self._refresh_gui()
        except Exception:
            pass
        self.root.after(200, self._schedule_gui_update)  # 5Hz 刷新

    def _refresh_gui(self):
        """刷新所有GUI元素"""
        s = self.serv
        # 底部状态栏
        phone = f"{self.remote_addr[0]}:{self.remote_addr[1]}" if self.remote_addr else "未连接"
        self.lbl_stats.config(text=f"包数: {s.total_packets} | 手机: {phone} | active_carrot: {s.active_carrot}")
        self.lbl_time.config(text=datetime.now().strftime("%H:%M:%S"))

        if self._paused:
            return

        # Tab 1: 数据表
        self._refresh_data_table()

        # Tab 2: 原始JSON
        if s.raw_fields:
            self.txt_raw.delete("1.0", tk.END)
            self.txt_raw.insert("1.0", json.dumps(s.raw_fields, indent=2, ensure_ascii=False))

        # Tab 3: 路线数据
        self._refresh_route_tab()

    def _refresh_route_tab(self):
        """刷新路线数据Tab"""
        s = self.serv
        pts = s.navi_points
        ver = len(pts)  # 用点数作为简单版本号

        if ver == self._route_data_version:
            return  # 数据未变化，跳过
        self._route_data_version = ver

        # ── 统计信息 ──
        if not pts:
            self.lbl_route_info.config(text="等待路线数据... (手机App导航规划后通过TCP 7709发送)")
            return

        recv_str = datetime.fromtimestamp(s.route_recv_time).strftime("%H:%M:%S") if s.route_recv_time else "N/A"
        start_lon, start_lat = pts[0]
        end_lon, end_lat = pts[-1]
        lons = [p[0] for p in pts]
        lats = [p[1] for p in pts]
        sharp_count = sum(1 for sp in s.route_curve_speeds if sp < 60)
        medium_count = sum(1 for sp in s.route_curve_speeds if 60 <= sp < 100)

        info = (
            f"接收时间: {recv_str}  |  点数: {len(pts)}  |  总距离: {s.route_total_dist:.0f}m ({s.route_total_dist/1000:.1f}km)\n"
            f"起点: ({start_lon:.6f}, {start_lat:.6f})  →  终点: ({end_lon:.6f}, {end_lat:.6f})\n"
            f"经度范围: [{min(lons):.6f}, {max(lons):.6f}]  纬度范围: [{min(lats):.6f}, {max(lats):.6f}]\n"
            f"弯道分析: 急弯(<60km/h)={sharp_count}处, 中弯(60-100km/h)={medium_count}处, 最小限速={s.route_min_curve_speed:.0f}km/h"
        )
        self.lbl_route_info.config(text=info)

        # ── 弯道限速表 ──
        self.route_curve_tree.delete(*self.route_curve_tree.get_children())
        for i, (curv, spd) in enumerate(zip(s.route_curvatures, s.route_curve_speeds)):
            dist = (i + 1) * 10  # 10m间隔
            radius = f"{1/abs(curv):.0f}" if abs(curv) > 1e-6 else "∞"
            if spd < 40:
                grade, tag = "🔴 急弯", "sharp"
            elif spd < 80:
                grade, tag = "🟡 中弯", "medium"
            elif spd < 120:
                grade, tag = "🟢 缓弯", "gentle"
            else:
                grade, tag = "⚪ 直道", "straight"
            self.route_curve_tree.insert("", tk.END,
                values=(i+1, dist, f"{curv:.6f}", radius, f"{spd:.0f}", grade),
                tags=(tag,))

        # ── 路线点表 ──
        self.route_pts_tree.delete(*self.route_pts_tree.get_children())
        accum = 0.0
        for i, (lon, lat) in enumerate(pts):
            if i > 0:
                seg = _haversine(pts[i-1][0], pts[i-1][1], lon, lat)
                accum += seg
            else:
                seg = 0.0
            # 只显示前500个点 + 最后10个点，避免GUI卡顿
            if i < 500 or i >= len(pts) - 10:
                self.route_pts_tree.insert("", tk.END,
                    values=(i+1, f"{lon:.6f}", f"{lat:.6f}", f"{seg:.1f}", f"{accum:.0f}"))
            elif i == 500:
                self.route_pts_tree.insert("", tk.END,
                    values=("...", "...", "...", "...", f"(省略{len(pts)-510}个点)"))

    def _refresh_data_table(self):
        """刷新实时数据表 - 增量更新防闪烁"""
        rows = self._build_display_rows()
        now = datetime.now().strftime("%H:%M:%S")

        for key, (cat, field, value, vtype, desc) in rows.items():
            formatted = self._format_value(field, value)
            row_data = (cat, field, formatted, vtype, desc)

            if key in self._tree_items:
                old_val = self._tree_items[key].get("value")
                tag = "updated" if old_val != value else "normal"
                self.tree.item(self._tree_items[key]["iid"], values=row_data, tags=(tag,))
                self._tree_items[key]["value"] = value
            else:
                iid = self.tree.insert("", tk.END, values=row_data, tags=("updated",))
                self._tree_items[key] = {"iid": iid, "value": value}

    def _build_display_rows(self) -> OrderedDict:
        """构建显示行数据，按分类排列"""
        s = self.serv
        rows = OrderedDict()

        # ── 通信状态 ──
        rows["conn_remote"] = ("通信", "remote_addr", str(self.remote_addr) if self.remote_addr else "未连接", "str", "手机App地址")
        rows["conn_packets"] = ("通信", "total_packets", s.total_packets, "int", "累计接收包数")
        rows["conn_index"] = ("通信", "carrotIndex", s.carrotIndex, "int", "数据包序号")
        rows["conn_active"] = ("通信", "active_carrot", s.active_carrot, "int", "0=未激活 1=CarrotMan 2=SDI")

        # ── 道路信息 ──
        rows["road_name"] = ("道路", "szPosRoadName", s.szPosRoadName, "str", "当前道路名称")
        rows["road_limit"] = ("道路", "nRoadLimitSpeed", s.nRoadLimitSpeed, "int", "道路限速 (km/h)")
        rows["road_cate"] = ("道路", "roadcate", s.roadcate, "int", "道路类别 (0=高速 8=地方)")

        # ── SDI 测速 ──
        rows["sdi_type"] = ("SDI", "nSdiType", s.nSdiType, "int", SDI_DESCRIPTIONS.get(s.nSdiType, f"类型{s.nSdiType}"))
        rows["sdi_limit"] = ("SDI", "nSdiSpeedLimit", s.nSdiSpeedLimit, "int", "测速限速 (km/h)")
        rows["sdi_dist"] = ("SDI", "nSdiDist", s.nSdiDist, "int", "到测速点距离 (m)")
        rows["sdi_block"] = ("SDI", "nSdiBlockType", s.nSdiBlockType, "int", "区间状态 (-1=无 1=开始 2=中 3=结束)")
        rows["sdi_block_spd"] = ("SDI", "nSdiBlockSpeed", s.nSdiBlockSpeed, "int", "区间限速 (km/h)")
        rows["sdi_block_dist"] = ("SDI", "nSdiBlockDist", s.nSdiBlockDist, "int", "区间距离 (m)")
        rows["sdi_plus_type"] = ("SDI+", "nSdiPlusType", s.nSdiPlusType, "int", "Plus类型 (22=减速带)")
        rows["sdi_plus_limit"] = ("SDI+", "nSdiPlusSpeedLimit", s.nSdiPlusSpeedLimit, "int", "Plus限速")
        rows["sdi_plus_dist"] = ("SDI+", "nSdiPlusDist", s.nSdiPlusDist, "int", "Plus距离 (m)")

        # ── 计算后的速度控制 ──
        rows["x_spd_type"] = ("速度控制", "xSpdType", s.xSpdType, "int", "生效的速度类型")
        rows["x_spd_limit"] = ("速度控制", "xSpdLimit", s.xSpdLimit, "int", "生效的速度限制 (km/h)")
        rows["x_spd_dist"] = ("速度控制", "xSpdDist", s.xSpdDist, "int", "到限速点距离 (m)")

        # ── TBT 转弯 ──
        rows["tbt_type"] = ("TBT", "nTBTTurnType", s.nTBTTurnType, "int", self._turn_desc(s.nTBTTurnType))
        rows["tbt_dist"] = ("TBT", "nTBTDist", s.nTBTDist, "int", "到转弯点距离 (m)")
        rows["tbt_text"] = ("TBT", "szTBTMainText", s.szTBTMainText, "str", "转弯指令文本")
        rows["tbt_near"] = ("TBT", "szNearDirName", s.szNearDirName, "str", "近处方向名")
        rows["tbt_far"] = ("TBT", "szFarDirName", s.szFarDirName, "str", "远处方向名")
        rows["tbt_width"] = ("TBT", "nTBTNextRoadWidth", s.nTBTNextRoadWidth, "int", "下一道路宽度(车道数)")
        rows["tbt_next_type"] = ("TBT下一", "nTBTTurnTypeNext", s.nTBTTurnTypeNext, "int", self._turn_desc(s.nTBTTurnTypeNext))
        rows["tbt_next_dist"] = ("TBT下一", "nTBTDistNext", s.nTBTDistNext, "int", "下一转弯距离 (m)")

        # ── 计算后的转弯控制 ──
        rows["x_turn"] = ("转弯控制", "xTurnInfo", s.xTurnInfo, "int", "1=左转 2=右转 3=左岔 4=右岔 5=环岛 6=TG 7=掉头 8=到达")
        rows["x_turn_dist"] = ("转弯控制", "xDistToTurn", s.xDistToTurn, "int", "到转弯距离 (m)")

        # ── 目的地 ──
        rows["goal_dist"] = ("目的地", "nGoPosDist", s.nGoPosDist, "int", "剩余距离 (m)")
        rows["goal_time"] = ("目的地", "nGoPosTime", s.nGoPosTime, "int", "剩余时间 (s)")
        rows["goal_name"] = ("目的地", "szGoalName", s.szGoalName, "str", "目标名称")
        rows["goal_x"] = ("目的地", "goalPosX", s.goalPosX, "float", "目标经度")
        rows["goal_y"] = ("目的地", "goalPosY", s.goalPosY, "float", "目标纬度")

        # ── 导航GPS ──
        rows["gps_lat"] = ("导航GPS", "vpPosPointLat", s.vpPosPointLat, "float", "导航纬度")
        rows["gps_lon"] = ("导航GPS", "vpPosPointLon", s.vpPosPointLon, "float", "导航经度")
        rows["gps_angle"] = ("导航GPS", "nPosAngle", s.nPosAngle, "float", "导航方向角")
        rows["gps_speed"] = ("导航GPS", "nPosSpeed", s.nPosSpeed, "float", "导航速度")

        # ── 手机GPS ──
        rows["phone_lat"] = ("手机GPS", "latitude", s.phone_lat, "float", "手机纬度")
        rows["phone_lon"] = ("手机GPS", "longitude", s.phone_lon, "float", "手机经度")
        rows["phone_head"] = ("手机GPS", "heading", s.phone_heading, "float", "手机方向角")
        rows["phone_acc"] = ("手机GPS", "accuracy", s.phone_accuracy, "float", "GPS精度 (m)")
        rows["phone_spd"] = ("手机GPS", "gps_speed", s.phone_gps_speed, "float", "GPS速度 (m/s)")
        # 完整手机GPS字段
        rows["phone_lat2"] = ("手机GPS", "phoneLatitude", s.phone_lat2, "float", "手机纬度 (完整)")
        rows["phone_lon2"] = ("手机GPS", "phoneLongitude", s.phone_lon2, "float", "手机经度 (完整)")
        rows["phone_acc2"] = ("手机GPS", "phoneAccuracy", s.phone_acc2, "float", "GPS精度 (完整)")
        rows["phone_frame"] = ("手机GPS", "phoneGPSFrame", s.phone_frame, "int", "GPS帧号")
        rows["phone_speed2"] = ("手机GPS", "phoneSpeed", s.phone_speed2, "float", "手机速度 (完整)")

        # ── 下一转弯信息 ──
        rows["tbt_next_text"] = ("TBT下一", "szTBTMainTextNext", s.szTBTMainTextNext, "str", "下一转弯主文本")
        rows["tbt_next_near"] = ("TBT下一", "szNearDirNameNext", s.szNearDirNameNext, "str", "下一转弯近处方向")
        rows["tbt_next_far"] = ("TBT下一", "szFarDirNameNext", s.szFarDirNameNext, "str", "下一转弯远处方向")

        # ── sdiData (区间测速控制) ──
        rows["sdi_data_type"] = ("sdiData", "nSdiType", s.sdiData_type, "int", "区间测速类型")
        rows["sdi_data_dist"] = ("sdiData", "nSdiDist", s.sdiData_dist, "int", "区间测速距离 (m)")
        rows["sdi_data_spd"] = ("sdiData", "nSdiSpeedLimit", s.sdiData_speed, "int", "区间限速 (km/h)")
        rows["sec_start_type"] = ("sdiData", "sectionStartType", s.sectionStartType, "int", "区间开始类型")
        rows["sec_start_dist"] = ("sdiData", "sectionStartDist", s.sectionStartDist, "int", "区间开始距离 (m)")

        # ── carrotDetect (探测数据) ──
        rows["detect_type"] = ("carrotDetect", "carrotDetectType", s.carrotDetect_type, "int", "探测类型")
        rows["detect_dist"] = ("carrotDetect", "carrotDetectDist", s.carrotDetect_dist, "int", "探测距离 (m)")
        rows["detect_spd"] = ("carrotDetect", "carrotDetectSpeed", s.carrotDetect_speed, "int", "探测速度 (km/h)")
        rows["detect_cnf"] = ("carrotDetect", "carrotDetectCnf", s.carrotDetect_cnf, "float", "探测置信度")

        # ── autoNaviSpeed 参数 ──
        rows["auto_ctrl_mode"] = ("autoNaviSpeed", "autoNaviSpeedCtrlMode", s.autoNaviSpeedCtrlMode, "int", "控制模式 (0=关 1=限速 2=减速带)")
        rows["auto_decel_rate"] = ("autoNaviSpeed", "autoNaviSpeedDecelRate", s.autoNaviSpeedDecelRate, "float", "减速率 (km/h/s)")
        rows["auto_safe_factor"] = ("autoNaviSpeed", "autoNaviSpeedSafetyFactor", s.autoNaviSpeedSafetyFactor, "float", "安全系数")
        rows["auto_bump_spd"] = ("autoNaviSpeed", "autoNaviSpeedBumpSpeed", s.autoNaviSpeedBumpSpeed, "float", "减速带限速 (km/h)")
        rows["auto_bump_time"] = ("autoNaviSpeed", "autoNaviSpeedBumpTime", s.autoNaviSpeedBumpTime, "int", "减速带持续时间 (s)")
        rows["auto_ctrl_end"] = ("autoNaviSpeed", "autoNaviSpeedCtrlEnd", s.autoNaviSpeedCtrlEnd, "int", "控制结束标志")

        # ── 交通灯 ──
        rows["tl_x"] = ("交通灯", "trafficLightX", s.traffic_light_x, "int", "交通灯坐标X")
        rows["tl_y"] = ("交通灯", "trafficLightY", s.traffic_light_y, "int", "交通灯坐标Y")
        rows["tl_color"] = ("交通灯", "trafficLightColor", s.traffic_light_color, "int", "交通灯颜色 (0=红 1=黄 2=绿)")
        rows["tl_cntf"] = ("交通灯", "trafficLightCntf", s.traffic_light_cntf, "float", "交通灯置信度")

        # ── KISA 数据 ──
        rows["kisa_data"] = ("KISA", "kisaData", s.kisa_data_str[:50] if s.kisa_data_str else "无", "str", "KISA安全摄像头数据")

        # ── 时间同步 ──
        rows["epoch_time"] = ("时间", "epochTime", s.epochTime, "int", "Unix时间戳")
        rows["timezone"] = ("时间", "timezone", s.timezone, "int", "时区偏移")
        rows["timestamp"] = ("时间", "timestamp", s.timestamp, "float", "系统时间戳")

        # ── 命令 ──
        rows["cmd_cmd"] = ("命令", "carrotCmd", s.carrotCmd, "str", "命令类型 (DETECT等)")
        rows["cmd_arg"] = ("命令", "carrotArg", s.carrotArg, "str", "命令参数")
        rows["cmd_idx"] = ("命令", "carrotCmdIndex", s.carrotCmdIndex, "int", "命令索引")

        # ── 路线 (7709 TCP) ──
        rows["route_active"] = ("路线7709", "navi_points_active", s.navi_points_active, "bool", "路线是否激活")
        rows["route_count"] = ("路线7709", "navi_points_count", len(s.navi_points), "int", "路线点数量")
        rows["route_dist"] = ("路线7709", "route_total_dist", f"{s.route_total_dist:.0f}", "float", "路线总距离 (m)")
        rows["route_min_spd"] = ("路线7709", "route_min_curve_speed", f"{s.route_min_curve_speed:.0f}", "float", "最小弯道限速 (km/h)")
        rows["route_curves"] = ("路线7709", "curve_count", len(s.route_curvatures), "int", "曲率采样点数")
        if s.navi_points:
            rows["route_start"] = ("路线7709", "起点", f"({s.navi_points[0][0]:.6f}, {s.navi_points[0][1]:.6f})", "str", "路线起点 (lon, lat)")
            rows["route_end"] = ("路线7709", "终点", f"({s.navi_points[-1][0]:.6f}, {s.navi_points[-1][1]:.6f})", "str", "路线终点 (lon, lat)")
        recv_ago = f"{time.time() - s.route_recv_time:.0f}秒前" if s.route_recv_time > 0 else "未收到"
        rows["route_time"] = ("路线7709", "route_recv_time", recv_ago, "str", "路线接收时间")

        # ── 额外原始字段 (App发送但上面未列出的) ──
        known_keys = {
            "carrotIndex", "epochTime", "timestamp", "timezone", "heading",
            "goalPosX", "goalPosY", "szGoalName",
            "nRoadLimitSpeed", "roadcate",
            "nSdiType", "nSdiSpeedLimit", "nSdiSection", "nSdiDist",
            "nSdiBlockType", "nSdiBlockSpeed", "nSdiBlockDist",
            "nSdiPlusType", "nSdiPlusSpeedLimit", "nSdiPlusDist",
            "nSdiPlusBlockType", "nSdiPlusBlockSpeed", "nSdiPlusBlockDist",
            "nTBTDist", "nTBTTurnType", "szTBTMainText",
            "szNearDirName", "szFarDirName", "nTBTNextRoadWidth",
            "nTBTDistNext", "nTBTTurnTypeNext", "szTBTMainTextNext",
            "nGoPosDist", "nGoPosTime", "szPosRoadName",
            "vpPosPointLat", "vpPosPointLon", "nPosAngle", "nPosSpeed",
            "latitude", "longitude", "accuracy", "gps_speed",
            "carrotCmd", "carrotArg", "carrotCmdIndex",
            "phoneGPS", "sdiData", "carrotDetect", "autoNaviSpeed",
            "trafficLight", "kisaData", "totalDistance",
            "phoneLatitude", "phoneLongitude", "phoneAccuracy",
            "phoneGPSFrame", "phoneSpeed",
        }
        for k, v in s.raw_fields.items():
            if k not in known_keys:
                rows[f"extra_{k}"] = ("其他", k, v, type(v).__name__, "App额外字段")

        return rows

    @staticmethod
    def _format_value(field, value):
        """格式化显示值"""
        if isinstance(value, float):
            if "Lat" in field or "Lon" in field or "lat" in field or "lon" in field:
                return f"{value:.6f}"
            return f"{value:.2f}"
        if isinstance(value, bool):
            return "✅ 是" if value else "❌ 否"
        return str(value)

    @staticmethod
    def _turn_desc(turn_type):
        """转弯类型描述"""
        if turn_type in NAV_TYPE_MAPPING:
            nav_type, modifier, info = NAV_TYPE_MAPPING[turn_type]
            return f"{nav_type} {modifier} (xTurnInfo={info})"
        if turn_type == -1:
            return "无转弯"
        return f"未知类型({turn_type})"

    # ─── 主循环 ────────────────────────────────
    def run(self):
        self.root.mainloop()


# ═══════════════════════════════════════════════
#  入口
# ═══════════════════════════════════════════════
if __name__ == "__main__":
    sim = Comma3Simulator()
    sim.run()
