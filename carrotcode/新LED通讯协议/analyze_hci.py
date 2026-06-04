#!/usr/bin/env python3
"""
a800 / Popusign LED 屏幕 BLE HCI 抓包逆向分析工具 v2
======================================================
解析 btsnoop 格式的 HCI 日志, 提取 BLE ATT 写入/通知数据,
按 Popusign APP 的实际协议格式分析帧结构.

关键发现:
  Popusign APP 使用 0x5E 协议的内核 (payload 格式相同),
  但 BLE 传输层不用 0x5E 帧头/校验, 而是直接用:
    [seq(1B)] [remaining_len(1B)] [payload(type+cuid+content)]

  写入和通知使用完全相同的格式, 没有前导 0x00 字节.

  0x5E 帧头(A0,A1,A2,Length,A4,CheckSum) 由 BLE IC 内部处理,
  APP 只需发送裸载荷.

用法:
    python analyze_hci.py bt_hci_20260604_110135_d.cfa
"""

import struct
import sys
import os
from typing import Optional


# ═══════════════════════════════════════════════════════════════
# btsnoop 格式解析器
# ═══════════════════════════════════════════════════════════════

BTSNOOP_MAGIC = b'btsnoop'


class BtsnoopReader:
    def __init__(self, path: str):
        self.path = path
        self.data = open(path, 'rb').read()
        self.pos = 8  # skip identification field (8 bytes)
        self.packets = []
        self._parse()

    def _read(self, fmt: str):
        size = struct.calcsize(fmt)
        val = struct.unpack(fmt, self.data[self.pos:self.pos + size])
        self.pos += size
        return val[0] if len(val) == 1 else val

    def _parse(self):
        self.version = self._read('>I')
        self.data_link_type = self._read('>I')
        while self.pos + 16 <= len(self.data):
            orig_len = self._read('>I')
            incl_len = self._read('>I')
            flags = self._read('>I')
            drops = self._read('>I')
            ts = self._read('>Q')
            if self.pos + incl_len > len(self.data):
                break
            pkt_data = self.data[self.pos:self.pos + incl_len]
            self.pos += incl_len
            self.packets.append({
                'ts': ts / 1_000_000,
                'incl_len': incl_len,
                'flags': flags,
                'data': pkt_data,
            })

    def __len__(self):
        return len(self.packets)


# ═══════════════════════════════════════════════════════════════
# HCI / ACL / ATT 解析
# ═══════════════════════════════════════════════════════════════

HCI_ACL = 0x02
HCI_EVENT = 0x04

ATT_OPCODES = {
    0x02: "MTU_REQ", 0x03: "MTU_RSP", 0x08: "EX_MTU_REQ",
    0x0A: "READ_REQ", 0x0B: "READ_RSP",
    0x0C: "READ_BLOB_REQ", 0x0D: "READ_BLOB_RSP",
    0x12: "WRITE_REQ", 0x13: "WRITE_RSP",
    0x16: "PREP_WRITE_REQ", 0x17: "PREP_WRITE_RSP",
    0x18: "EXEC_WRITE_REQ", 0x19: "EXEC_WRITE_RSP",
    0x1B: "NTF", 0x1D: "IND",
    0x52: "WRITE_CMD",
}


def extract_att(data: bytes):
    """从 HCI ACL 分组中提取 ATT 操作."""
    if not data:
        return []
    pkt_type = data[0]
    body = data[1:]
    if pkt_type == HCI_ACL:
        return extract_acl_att(body)
    return []


def extract_acl_att(pkt_data: bytes):
    """提取 ACL 分组中的 ATT 命令/通知."""
    results = []
    if len(pkt_data) < 4:
        return results
    handle_flags = struct.unpack('<H', pkt_data[:2])[0]
    dlen = struct.unpack('<H', pkt_data[2:4])[0]
    l2cap_data = pkt_data[4:4 + dlen] if dlen > 0 else b''
    if len(l2cap_data) < 4:
        return results
    l2_cid = struct.unpack('<H', l2cap_data[2:4])[0]
    if l2_cid != 0x0004:  # ATT channel
        return results
    att_data = l2cap_data[4:]
    if not att_data:
        return results
    att_opcode = att_data[0]
    att_params = att_data[1:] if len(att_data) > 1 else b''
    results.append((att_opcode, att_params))
    return results


# ═══════════════════════════════════════════════════════════════
# Popusign BLE 协议解析
# ═══════════════════════════════════════════════════════════════
#
# 发现:
#   写入和通知使用完全相同的格式 (无前导 0x00):
#     [seq(1B)] [remaining_len(1B)] [payload...]
#     payload = [type(1B)] [cuid_lo(1B)] [cuid_hi(1B)] [content...]
#
#   payload 格式与 0x5E 协议完全一致, 只是去掉了 0x5E 帧头/校验.
# ═══════════════════════════════════════════════════════════════

PAYLOAD_CMD = 0xCD
PAYLOAD_DATA = 0xDA
PAYLOAD_ERROR = 0xE0

PAYLOAD_TYPES = {
    0xCD: "CMD(命令)",
    0xDA: "DATA(数据)",
    0xE0: "ERROR(报错)",
}

KNOWN_COMMANDS = {
    0x0E01: "显示开关", 0x0E02: "亮度调节(旧式)",
    0x0A01: "播放控制", 0xED01: "节目编辑",
    0xD001: "资产下载", 0xD002: "资产请求/上传",
    0xD501: "实时显示", 0xD502: "实时显示请求/上传",
    0xD507: "快速像素(全局颜色)", 0xD508: "快速像素(像素着色)",
    0xD517: "实色填充", 0xD518: "实时律动同步",
    0xD582: "显示上传(数据)", 0xD602: "节目列表请求/上传",
    0xD702: "设备信息请求/上传",
    0xC001: "设备状态控制", 0xC002: "设备参数控制",
    0xC003: "设备鉴权", 0xC055: "设备系统控制",
    0xC081: "设备状态通知", 0x0F01: "产线测试",
}


def parse_popusign_value(value: bytes) -> Optional[dict]:
    """
    解析 Popusign BLE 协议值.
    写入和通知使用完全相同的格式:
      [seq(1B)] [remaining_len(1B)] [type(1B)] [cuid_lo(1B)] [cuid_hi(1B)] [content...]
    返回 dict 或 None.
    """
    if len(value) < 2:
        return None
    seq = value[0]
    plen = value[1]
    payload = value[2:2 + plen] if plen > 0 else b''

    if len(payload) < 3:
        return {'seq': seq, 'len': plen, 'raw_payload': payload, 'error': 'payload_too_short'}

    ptype = payload[0]
    cuid = payload[1] | (payload[2] << 8)
    content = payload[3:]

    result = {
        'seq': seq,
        'len': plen,
        'type': ptype,
        'type_str': PAYLOAD_TYPES.get(ptype, f"0x{ptype:02X}"),
        'cuid': cuid,
        'content': content,
        'content_hex': content.hex().upper(),
    }

    # 解析 CMD 类型的内容
    if ptype == PAYLOAD_CMD and len(content) >= 2:
        cmd_code = content[0] | (content[1] << 8)
        cmd_name = KNOWN_COMMANDS.get(cmd_code, f"0x{cmd_code:04X}")
        args = content[2:]
        result['cmd_code'] = cmd_code
        result['cmd_name'] = cmd_name
        result['args'] = args
        result['args_hex'] = args.hex().upper()
        result['description'] = describe_cmd(cmd_code, args)

    elif ptype == PAYLOAD_ERROR and len(content) >= 2:
        err_code = content[0] | (content[1] << 8)
        result['error_code'] = err_code

    return result


def describe_cmd(cmd_code: int, args: bytes) -> str:
    """描述命令参数."""
    if cmd_code == 0x0E01:
        return f"显示{'开' if args and args[0] == 0x01 else '关'}"
    elif cmd_code == 0x0E02:
        return f"亮度={args[0] if args else '?'}"
    elif cmd_code == 0x0A01:
        if len(args) >= 1:
            cmd = args[0]
            if cmd == 0x00: return "暂停"
            elif cmd == 0x01:
                if len(args) >= 2:
                    if args[1] == 0x41:
                        return f"全部循环播放 组={list(args[2:])}"
                    elif args[1] in (0x00, 0x01):
                        loop = args[2] if len(args) > 2 else 0
                        g = args[3] if len(args) > 3 else 0
                        p = args[4] if len(args) > 4 else 0
                        mode = "单循环" if loop == 0x53 else f"LOOP=0x{loop:02X}"
                        return f"播放 {mode} 组{g} 节目{p}" + (" (改变模式)" if args[1] == 0x01 else "")
                    return f"播放 LOOP=0x{args[1]:02X}"
            elif cmd == 0xEA: return "读取播放参数"
        return args.hex().upper()
    elif cmd_code == 0xED01:
        if len(args) >= 3:
            g, p, count = args[0], args[1], args[2]
            rest = args[3:]
            desc = f"节目编辑 组{g} 节目{p} {count}个子元素"
            # 解析子元素详情
            pos = 0
            for ei in range(count):
                if pos >= len(rest):
                    desc += f" [{ei}]数据不足"
                    break
                elem_start = pos
                elem_data = rest[pos:]
                desc += f" [{ei}]"
                # 尝试解析子元素格式
                # 格式: [type(1B)] [data...]
                if len(elem_data) >= 1:
                    elem_type = elem_data[0]
                    desc += f" type=0x{elem_type:02X}"
                    if elem_type == 0x00 and len(elem_data) >= 14:
                        # Asset reference sub-element
                        # [type(1B)] [padding(7B)] [uuid(6B)] = 14B
                        uuid = elem_data[8:14]
                        desc += f" UUID={uuid.hex().upper()}"
                        pos += 14
                    elif elem_type == 0x01 and len(elem_data) >= 15:
                        uuid = elem_data[9:15]
                        desc += f" subtype={elem_data[1]} UUID={uuid.hex().upper()}"
                        pos += 15
                    elif len(elem_data) >= 14:
                        uuid = elem_data[8:14]
                        desc += f" UUID={uuid.hex().upper()} (推测为资产引用)"
                        pos += 14
                    else:
                        pos += len(elem_data)
                else:
                    pos += 1
            return desc
        return args.hex().upper()
    elif cmd_code == 0x0F01:
        type_names = {0x00: "红全屏", 0x01: "绿全屏", 0x02: "蓝全屏",
                      0x03: "黄全屏", 0x04: "青全屏", 0x05: "紫全屏",
                      0x06: "白全屏", 0x07: "黑全屏",
                      0x10: "行条纹", 0x11: "列条纹", 0x12: "格子"}
        return f"产线测试: {type_names.get(args[0], f'0x{args[0]:02X}')}" if args else "产线测试"
    elif cmd_code == 0xD001:
        if len(args) >= 8:
            length = args[0] | (args[1] << 8) | (args[2] << 16) | (args[3] << 24)
            start_cuid = args[4] | (args[5] << 8)
            uuid = args[6:12] if len(args) >= 12 else args[6:]
            return f"资产下载 length={length}B start_CUID={start_cuid} UUID={uuid.hex().upper()}"
        return args.hex().upper()
    elif cmd_code == 0xD702:
        return "请求设备信息" if args and args[0] == 0x00 else args.hex().upper()
    elif cmd_code == 0xD602:
        return "节目列表请求/上传" if args and args[0] == 0x00 else "节目列表上传(含数据)"
    elif cmd_code == 0xC002:
        if len(args) >= 2:
            if args[0] == 0x00: return "获取参数"
            elif args[0] == 0x01:
                param_names = {0x01: "旋转", 0x02: "定时关机", 0x03: "密码", 0x04: "屏幕开关", 0x05: "亮度"}
                pname = param_names.get(args[1], f"0x{args[1]:02X}")
                pval = f"0x{args[2]:02X}" if len(args) >= 3 else ""
                return f"设置 {pname}={pval}"
        return args.hex().upper()
    elif cmd_code == 0xC055:
        sys_cmds = {0xF1: "重启", 0xF2: "清空节目", 0xFE: "格式化Flash"}
        if len(args) >= 2 and args[0] == 0x01:
            return f"系统控制: {sys_cmds.get(args[1], f'0x{args[1]:02X}')}"
        return args.hex().upper()
    elif cmd_code == 0xD517:
        if len(args) >= 2:
            color = args[0] | (args[1] << 8)
            rest = ' '.join(f'{b:02X}' for b in args[2:])
            return f"实色填充 COLOR=0x{color:04X} {rest}"
        return args.hex().upper()
    return args.hex().upper()


def analyze_asset_data(content: bytes):
    """尝试解析资产内容 (D001 传输的数据帧)."""
    if len(content) < 12:
        return ""

    # 资产数据格式:
    # [uuid(6B)] [type(1B)] [data...]
    uuid = content[0:6]
    rest = content[6:]

    result = f"UUID={uuid.hex().upper()}"
    if len(rest) >= 1:
        asset_type = rest[0]
        type_names = {0x00: "文本", 0x01: "位图", 0x02: "字体", 0xFF: "彩色图片"}
        result += f" type={type_names.get(asset_type, f'0x{asset_type:02X}')}"
        result += f" data_len={len(rest)-1}B"
    return result


def format_hex(data: bytes, max_len: int = 80) -> str:
    s = data.hex().upper()
    if len(s) > max_len:
        return s[:max_len] + f"...({len(data)}B total)"
    return s


# ═══════════════════════════════════════════════════════════════
# 主分析函数
# ═══════════════════════════════════════════════════════════════

def analyze(capture_path: str):
    print(f"[*] 分析抓包文件: {os.path.basename(capture_path)}")
    print(f"[*] 文件大小: {os.path.getsize(capture_path)} bytes")
    print()

    reader = BtsnoopReader(capture_path)
    print(f"[*] btsnoop 版本: {reader.version}, data_link_type: 0x{reader.data_link_type:04X}")
    print(f"[*] 总分组数: {len(reader.packets)}")
    print()

    # ── 提取所有 ATT 操作 ──
    att_ops = []  # (时间戳, 方向, opcode, att_params)
    for pkt in reader.packets:
        ts = pkt['ts']
        for opcode, params in extract_att(pkt['data']):
            if opcode in (0x52, 0x12, 0x1B, 0x1D, 0x02, 0x03, 0x08, 0x0A, 0x0B):
                direc = "->DEV" if opcode in (0x52, 0x12, 0x02, 0x08, 0x0A) else "<-APP"
                att_ops.append((ts, direc, opcode, params))

    att_ops.sort(key=lambda x: x[0])

    base_ts = att_ops[0][0] if att_ops else 0

    # ── GATT 服务发现 ──
    print("=" * 70)
    print("  GATT 服务发现 & MTU 协商")
    print("=" * 70)
    for ts, direc, opcode, params in att_ops:
        rel = ts - base_ts
        if opcode in (0x02, 0x08):
            mtu = struct.unpack('<H', params[:2])[0] if len(params) >= 2 else 0
            print(f"  [{rel:+.3f}] {direc} MTU_REQ client_rx_mtu={mtu}")
        elif opcode == 0x03:
            mtu = struct.unpack('<H', params[:2])[0] if len(params) >= 2 else 0
            print(f"  [{rel:+.3f}] {direc} MTU_RSP server_rx_mtu={mtu}")
        elif opcode == ATT_OPCODES.get(0x0A) or opcode == 0x0A:
            h = struct.unpack('<H', params[:2])[0]
            print(f"  [{rel:+.3f}] {direc} READ_REQ handle=0x{h:04X}")
        elif opcode == 0x0B:
            # 读取响应 — 可能是 UUID
            if len(params) >= 2:
                # GATT Primary Service Declaration 返回: [handle(2B)] [uuid...]
                print(f"  [{rel:+.3f}] {direc} READ_RSP ({len(params)}B) {params.hex().upper()}")
            else:
                print(f"  [{rel:+.3f}] {direc} READ_RSP ({len(params)}B) {params.hex().upper()}")

    print()

    # ── 解析所有写入 (WRITE_CMD / WRITE_REQ) ──
    writes = [(ts, params) for ts, d, op, params in att_ops if op in (0x52, 0x12)]
    # 过滤掉非数据写入 (ATT 协议层操作)
    data_writes = []
    for ts, params in writes:
        # WRITE_CMD/WRITE_REQ 的 att_params = handle(2B) + value
        if len(params) >= 4:
            handle = params[0] | (params[1] << 8)
            value = params[2:]
            p = parse_popusign_value(value)
            if p:
                data_writes.append((ts, handle, p))
            else:
                # 尝试直接解析为 0x5E 帧 (某些 APP 可能直接发送 0x5E)
                if value and len(value) >= 7 and value[0] == 0x5E:
                    data_writes.append((ts, handle, {'raw_5e': value.hex().upper(), 'note': '可能为0x5E帧'}))

    print("=" * 70)
    print(f"  数据写入 (APP -> 屏幕) 共 {len(data_writes)} 条")
    print("=" * 70)
    base_ts2 = data_writes[0][0] if data_writes else base_ts

    for i, (ts, handle, p) in enumerate(data_writes):
        rel = ts - base_ts2

        if 'raw_5e' in p:
            print(f"\n  [{rel:+.3f}] #{i} handle=0x{handle:04X} (可能为0x5E帧)")
            print(f"        RAW: {p['raw_5e']}")
            continue

        seq = p['seq']

        if 'error' in p:
            print(f"\n  [{rel:+.3f}] #{i} handle=0x{handle:04X} seq={seq} [payload_too_short] raw={p['raw_payload'].hex().upper()}")
            continue

        pt = p['type_str']
        cuid = p['cuid']
        content = p['content']
        desc = p.get('description', '')

        print(f"\n  [{rel:+.3f}] #{i} handle=0x{handle:04X} seq={seq} {pt} CUID={cuid} | content={len(content)}B")
        print(f"        RAW: {p['content_hex'][:120]}")

        if 'cmd_code' in p:
            print(f"        CMD: {p['cmd_name']} (0x{p['cmd_code']:04X})")
            if desc:
                print(f"        {desc}")
        elif p.get('type') == PAYLOAD_DATA and len(content) > 0:
            if len(content) >= 6:
                uuid = content[0:6]
                print(f"        DATA: 可能包含资产数据 UUID={uuid.hex().upper()}")
                print(f"        资产分析: {analyze_asset_data(content)}")
            else:
                print(f"        DATA: {content.hex().upper()}")

    print()

    # ── 解析所有通知 ──
    notifs = [(ts, params) for ts, d, op, params in att_ops if op == 0x1B]
    data_notifs = []
    for ts, params in notifs:
        # 通知的 att_params = handle(2B) + value
        if len(params) >= 4:
            handle = params[0] | (params[1] << 8)
            value = params[2:]
            p = parse_popusign_value(value)
            if p:
                data_notifs.append((ts, handle, p))

    print("=" * 70)
    print(f"  通知/数据 (屏幕 -> APP) 共 {len(data_notifs)} 条")
    print("=" * 70)
    if data_notifs:
        base_ts3 = data_notifs[0][0]

        for ts, handle, p in data_notifs:
            rel = ts - base_ts3
            seq = p['seq']

            if 'error' in p:
                print(f"\n  [{rel:+.3f}] handle=0x{handle:04X} seq={seq} [payload_too_short] raw={p['raw_payload'].hex().upper()}")
                continue

            pt = p['type_str']
            cuid = p['cuid']
            content = p['content']
            desc = p.get('description', '')

            print(f"\n  [{rel:+.3f}] handle=0x{handle:04X} seq={seq} {pt} CUID={cuid} | content={len(content)}B")
            print(f"        RAW: {p['content_hex'][:120]}")

            if 'cmd_code' in p:
                print(f"        CMD: {p['cmd_name']} (0x{p['cmd_code']:04X})")
                if desc:
                    print(f"        {desc}")

                # 设备信息上传 (D702) 特殊解析
                if p['cmd_code'] == 0xD702 and len(content) >= 34:
                    model_end = 8
                    model = content[2:2+8].decode('utf-8', errors='replace').rstrip('\x00 ')
                    w = content[19] | (content[20] << 8)
                    h = content[21] | (content[22] << 8)
                    ver_major = content[16]
                    ver_minor = content[17]
                    ver_patch = content[18]
                    print(f"        设备信息: model={model} {w}x{h} v{ver_major}.{ver_minor}.{ver_patch}")

            elif p.get('type') == PAYLOAD_ERROR:
                err = p.get('error_code', -1)
                print(f"        ERROR code={err}")

            elif p.get('type') == PAYLOAD_DATA and len(content) > 0:
                if len(content) >= 6:
                    uuid = content[0:6]
                    print(f"        DATA: 资产数据 UUID={uuid.hex().upper()}")
                    print(f"        资产分析: {analyze_asset_data(content)}")

    print()
    print("=" * 70)
    print("  协议工作流总结")
    print("=" * 70)
    if data_writes:
        print()
        for i, (ts, handle, p) in enumerate(data_writes):
            if 'raw_5e' in p:
                print(f"  {i}. [0x5E帧]")
            elif 'error' in p:
                print(f"  {i}. [WRITE seq={p['seq']}] payload_too_short: {p['raw_payload'].hex().upper()}")
            elif 'cmd_code' in p:
                print(f"  {i}. [{p['cmd_name']}] {p.get('description', '')}")
            elif p.get('type') == PAYLOAD_DATA:
                if len(p['content']) > 10:
                    print(f"  {i}. [DATA] ({len(p['content'])}B) cuid={p['cuid']}")
                else:
                    print(f"  {i}. [DATA] (ACK/? cuid={p['cuid']})")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法: python analyze_hci.py <btsnoop_file.cfa>")
        sys.exit(1)
    analyze(sys.argv[1])
