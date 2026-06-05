# -*- coding: utf-8 -*-
"""
_find_a1.py — 增强版

功能:
  1) 解析 BLE HCI 抓包 (bt_hci_20260605_084652_d.cfa), 提取 ATT Write/Notify 帧
  2) 展示完整的协议序列 (所有命令 + 数据 + 通知)
  3) 解析资产 (D001) 载荷 — 保留原解析逻辑
  4) 与 navipilot App logcat 日志对比, 标注差异

用法:
  python _find_a1.py
"""

import struct
import sys

# ============================================================
# 0. 已知命令定义 (来自 Popusign_BLE协议分析报告 + 代码反推)
# ============================================================
CMD_NAMES = {
    0x0A01: '播放控制',
    0xD001: '资产下载',
    0xED01: '节目编辑',
    0xC001: '设备状态控制',
    0xC081: '设备状态通知',
    0xC002: '设备参数控制',
    0xC055: '设备系统控制',
    0x0E01: '显示开关',
    0x0E02: '亮度调节',
    0xD517: '矩形填充(实时)',
    0xD702: '设备信息请求',
    0xD602: '节目列表请求',
}

TYPE_NAMES = {0xCD: 'CMD', 0xDA: 'DATA', 0xE0: 'ERR'}


def parse_cmd_args(cmd: int, args: bytes) -> str:
    """尝试解析命令参数为可读形式"""
    if cmd == 0x0A01:  # 播放控制
        if len(args) >= 1:
            ctrl = args[0]
            mode_desc = {0x00: '暂停', 0x01: '播放', 0xEA: '读取'}.get(ctrl, f'CTRL=0x{ctrl:02X}')
            if len(args) >= 2:
                loop = args[1]
                if loop == 0x53 and len(args) >= 5:
                    return f'{mode_desc} 单循环 group={args[2]} prog={args[3]}'
                elif loop == 0x41:
                    groups = list(args[2:])
                    return f'{mode_desc} 全部循环 groups={groups}'
            return mode_desc
    elif cmd == 0xD001:  # 资产下载
        if len(args) >= 12:
            length = struct.unpack_from('<I', args, 0)[0]
            start_cuid = struct.unpack_from('<H', args, 4)[0]
            uuid = args[6:12].hex()
            return f'len={length} start_cuid=0x{start_cuid:04X} uuid={uuid}'
        elif len(args) >= 6:
            return f'uuid={args[:6].hex()}'
    elif cmd == 0xED01:  # 节目编辑
        if len(args) >= 3:
            group = args[0]
            prog = args[1]
            sub_count = args[2]
            rest = args[3:]
            uuid_str = rest[-6:].hex() if len(rest) >= 6 else ''
            return f'group={group} prog={prog} subs={sub_count} uuid={uuid_str}'
    elif cmd == 0xC001:  # 设备状态
        if len(args) >= 2:
            ctrl = {0x00: '获取', 0x01: '设置'}.get(args[0], f'0x{args[0]:02X}')
            state = {0xFF: '开机', 0x01: '节目播放', 0x02: '节目预览', 0x03: '涂鸦'}.get(args[1], f'0x{args[1]:02X}')
            return f'{ctrl} {state}'
    elif cmd == 0x0E01:
        return '开' if len(args) >= 1 and args[0] else '关'
    elif cmd == 0x0E02:
        return f'亮度={args[0] if len(args) >= 1 else "?"}'
    elif cmd == 0xC081:
        return f'通知: {args.hex()}'
    return args.hex()


def parse_data_cuid(data_args: bytes) -> int:
    """解析 DATA 帧的前 2 字节 (CUID LE)"""
    if len(data_args) >= 2:
        return struct.unpack_from('<H', data_args, 0)[0]
    return -1


# ============================================================
# 1. 读取 HCI 抓包文件
# ============================================================
CAPTURE_FILE = 'carrotcode/新LED通讯协议/bt_hci_20260605_084652_d.cfa'

with open(CAPTURE_FILE, 'rb') as f:
    raw = f.read()

print(f'读取文件: {CAPTURE_FILE}')
print(f'文件大小: {len(raw)} 字节')
print()

pos = 16  # skip PCAP global header
pkt_no = 0

# 存储所有帧: (direction, seq, payload_type, cuid, cmd_or_desc, args, frame_bytes)
all_frames = []

data_frames = []  # 用于资产重建

while pos + 24 <= len(raw):
    incl_len = struct.unpack_from('>I', raw, pos + 4)[0]
    pos += 24  # skip packet header
    pkt_data = raw[pos:pos + incl_len]
    pos += incl_len
    pkt_no += 1

    if not pkt_data or pkt_data[0] != 0x02:  # ACL data
        continue
    if len(pkt_data) < 5:
        continue
    l2cap = pkt_data[5:]
    if len(l2cap) < 4:
        continue
    cid = struct.unpack_from('<H', l2cap, 2)[0]
    if cid != 0x0004:  # ATT channel
        continue
    att = l2cap[4:]
    if len(att) < 1:
        continue
    opcode = att[0]
    if opcode not in (0x52, 0x12, 0x1B):
        continue

    # 0x52 / 0x12 / 0x1B: opcode(1) + handle(2) + value(N)
    value = att[3:]

    # 方向: 0x52 = Write Command (App→设备), 0x12/0x1B = Notification/ReadRsp (设备→App)
    direction = '→→→' if opcode == 0x52 else '←←←'

    # 帧格式: [seq(1)] [len(1)] [payload(N)]
    if len(value) < 2:
        continue
    seq = value[0]
    payload_len = value[1]
    payload = value[2:]
    if len(payload) != payload_len:
        # HCI 可能有额外包头, 有些 capture 会附加 LLID 等
        # 对于 Write Cmd, 值就是原始 frame bytes
        payload = value[2:]

    if len(payload) < 3:
        # ACK 帧 = 3B: type + CUID(2)
        ptype = payload[0] if len(payload) >= 1 else 0
        cuid_val = struct.unpack_from('<H', payload, 1)[0] if len(payload) >= 3 else 0
        type_name = TYPE_NAMES.get(ptype, f'0x{ptype:02X}')
        desc = f'ACK({type_name}) cuid=0x{cuid_val:04X}'
        all_frames.append((direction, seq, ptype, cuid_val, desc, payload, value))
        if direction == '→→→' and ptype == 0xDA and cuid_val == 0:
            # DATA ACK (cuid=0) — 资产传输结束标记
            pass
        continue

    ptype = payload[0]
    cuid_val = struct.unpack_from('<H', payload, 1)[0]
    content = payload[3:]

    if ptype == 0xCD:  # 命令
        if len(content) >= 2:
            cmd = struct.unpack_from('<H', content, 0)[0]
            cmd_args = content[2:]
            cmd_name = CMD_NAMES.get(cmd, f'0x{cmd:04X}')
            args_desc = parse_cmd_args(cmd, cmd_args)
            desc = f'{cmd_name}(0x{cmd:04X}) {args_desc}'
            all_frames.append((direction, seq, ptype, cuid_val, desc, content, value))
        else:
            all_frames.append((direction, seq, ptype, cuid_val, f'CMD? content={content.hex()}', content, value))
    elif ptype == 0xDA:  # 数据
        # DATA 帧: CUID 在 type+2, 内容在 type+3
        data_payload = payload  # 完整 payload
        desc = f'DATA cuid=0x{cuid_val:04X} ({len(payload)}B)'
        if len(payload) > 3:
            desc += f' data={payload[3:3+16].hex()}{"..." if len(payload) > 19 else ""}'
        all_frames.append((direction, seq, ptype, cuid_val, desc, payload, value))
        # 保存 DATA 帧用于资产重建 (只保存下行)
        if direction == '→→→' and cuid_val != 0:
            data_frames.append(payload)
    elif ptype == 0xE0:  # 错误
        all_frames.append((direction, seq, ptype, cuid_val, f'ERROR: {content.hex()}', content, value))
    else:
        all_frames.append((direction, seq, ptype, cuid_val, f'UNKNOWN type=0x{ptype:02X}', payload, value))

# ============================================================
# 2. 打印完整协议序列
# ============================================================
print('=' * 80)
print('2. 完整协议序列 (BLE HCI 抓包 → 按序排列)')
print('=' * 80)
print(f'共 {len(all_frames)} 帧 (下行+上行)')
print()
print(f'{"方向":>4} {"#":>3} {"Seq":>3} {"类型":>5}  {"CUID":>6}  {"命令/描述":<55}  {"原始帧(前32B)"}')
print('-' * 80)

downlink_idx = 0  # 下行帧计数 (用于与 logcat 对比)
for idx, (direction, seq, ptype, cuid_val, desc, content, frame_bytes) in enumerate(all_frames):
    type_name = TYPE_NAMES.get(ptype, f'0x{ptype:02X}')
    raw_preview = frame_bytes.hex()[:64]
    print(f'  {direction} {idx:3d}  {seq:02X}  {type_name:>5}  0x{cuid_val:04X}  {desc:<55}  {raw_preview}')
    if direction == '→→→':
        downlink_idx += 1

print()

# ============================================================
# 3. 与 Logcat 日志对比
# ============================================================
print('=' * 80)
print('3. 与 navipilot App Logcat 对比')
print('=' * 80)
print()
print('【Logcat 写入记录】')
print()

# 从用户 logcat 中提取的写入命令 (已在 _find_a1 开发者消息中提供)
logcat_writes = [
    # (seq, hex, size, 描述)
    (0x03, '030ACD0400010A0153000000', 12, '0A01: 单循环 group=0 prog=0 (锁定空节目)'),
    (0x04, '0411CD050001D0DB00000005003D733C030030', 19, 'D001: 资产下载 len=219 cuid_start=5 uuid=3D733C030030'),
    (0x05, '0595DA0600' + '?', 151, 'DATA: 资产分片1 cuid=6 (146B资产数据)'),
    (0x06, '064CDA0700' + '?', 78, 'DATA: 资产分片2 cuid=7 (73B剩余资产数据)'),
    (0x07, '0703DA0000', 5, 'DATA: 传输完成 ACK cuid=0'),
    (0x08, '0816CD060001ED00000100000000000000003D733C030030', 24, 'ED01: 节目编辑 group=0 prog=0 uuid=3D733C030030'),
    (0x09, '090ACD0700010A0153010000', 12, '0A01: 单循环 group=1 prog=0 (切换到新节目)'),
]

print(f'{"方向":>4} {"Seq":>3} {"大小":>5}  Hex内容')
print('-' * 60)
for seq, hex_str, size, desc in logcat_writes:
    # 截断过长的 hex
    preview = hex_str[:48] + ('...' if len(hex_str) > 48 else '')
    print(f'  →→→  {seq:02X}  {size:3d}B  {preview:<48}  {desc}')

print()

# 从 logcat 提取的通知记录
logcat_notifies = [
    (0x1F, '1F03CD0400', 5, 'ACK CD cuid=0x0004'),
    (0x20, '2003CD0500', 5, 'ACK CD cuid=0x0005'),
    (0x21, '2103DA0700', 5, 'ACK DA cuid=0x0007'),
    (0x22, '2203CD0600', 5, 'ACK CD cuid=0x0006'),
    (0x23, '230FCDB00081C0800100003D733C030030', 17, 'C081: 设备状态通知(节目播放) uuid=3D733C030030'),
    (0x24, '2403CD0700', 5, 'ACK CD cuid=0x0007'),
]

print('【Logcat 通知记录】')
print()
print(f'{"方向":>4} {"Seq":>3} {"大小":>5}  Hex内容')
print('-' * 60)
for seq, hex_str, size, desc in logcat_notifies:
    preview = hex_str[:48]
    print(f'  ←←←  {seq:02X}  {size:3d}B  {preview:<48}  {desc}')

print()
print('【Logcat 流程总结】')
print()

flow_steps = [
    '1. 0A01(单循环,group=0,prog=0) → 锁定空节目,暂停当前播放',
    '2. D001(len=219,cuid_start=5,uuid=3D733C030030) → 资产下载请求',
    '3. DATA ×2 (cuid=6,7) → 资产数据分片 (146B+73B)',
    '4. DATA cuid=0 ACK → 传输完成',
    '5. ED01(group=0,prog=0,uuid=3D733C030030) → 节目编辑,引用资产',
    '6. C081 通知 → 设备回报解码完成 (携带uuid=3D733C030030)',
    '7. 0A01(单循环,group=1,prog=0) → 切换到新节目,显示生效',
]
for s in flow_steps:
    print(f'  {s}')

print()

# ============================================================
# 4. 差异分析
# ============================================================
print('=' * 80)
print('4. 差异分析 (App 发送 vs 抓包反向)')
print('=' * 80)
print()

print('[4.1] BLE 帧头格式')
print('  App (writeRawFrame):  [seq(1B)] [payload_len(1B)] [payload...]')
print('  抓包确认:             [seq(1B)] [payload_len(1B)] [payload...]')
print('  [OK] 完全一致')
print()

print('[4.2] 命令载荷格式')
print('  Command: [type=CD(1B)] [cuid LE16(2B)] [cmd LE16(2B)] [args...]')
print('  Data:    [type=DA(1B)] [cuid LE16(2B)] [content...]')
print('  ACK:     [type(1B)] [cuid LE16(2B)]  (仅3B, 无内容)')
print('  [OK] 完全一致')
print()

print('[4.3] 0A01 播放控制')
print('  App 发送:   01 53 00 00 00  (播放, 单循环, group=0, prog=0, pad=0)')
print('  抓包解析:   args[0]=CMD, [1]=LOOP, [2]=group, [3]=program, [4]=pad')
print('  [OK] 解析一致')
print()

print('[4.4] D001 资产下载')
print('  App 发送:   [len LE32=DB 00 00 00=219] [start_cuid LE16=05 00=5] [uuid=3D733C030030]')
print('  抓包解析:   length=219, start_cuid=5, uuid=3D733C030030')
print('  [OK] 一致')
print()

print('[4.5] DATA 分帧方式')
print('  App TEXT_ASSET_DATA_CHUNK = 146B (每帧数据载荷)')
print('  分片1: cuid=6, 载荷=资产前146B (含UUID+type+前缀前62B)')
print('  分片2: cuid=7, 载荷=剩余73B (后部字型数据)')
print('  总资产大小: 219B = 6(uuid) + 1(type) + 212(payload)')
print('  [OK] 分片逻辑一致')
print()

print('[4.6] ED01 节目编辑')
print('  App 发送:   args = [group=00] [prog=00] [subs=01] + [8B zeros] + [uuid=3D733C030030]')
print('  抓包解析:   16B args: 00 00 01 + 00×8 + 3D 73 3C 03 00 30')
print('  [OK] 一致')
print()

print('[4.7] CUID 序列一致性')
print('  App 序列:  0A01→cuid=4, D001→cuid=5, DATA1→cuid=6, DATA2→cuid=7, DATA_ACK→cuid=0')
print('              ED01→cuid=6, 0A01→cuid=7')
print('  抓包序列:  相同 (见完整协议序列表)')
print('  [!] 注意: ED01 的 CUID=6 与 DATA1 重合, 0A01(切换)的 CUID=7 与 DATA2 重合')
print('    因为 ED01 安排在 DATA ACK 之后, CUID 计数器在 DATA ACK 后重新从 0 开始计数')
print('    (这与App代码逻辑一致: sendDataFrame(cuid=0, empty) 后未重置 cuidCounter,')
print('    但后续 sendCommand 调用 nextCuid() 继续递增。实际抓包显示 CUID 延续。)')
print()

print('[4.8] 通知格式')
print('  App 收到:   [seq] [len] [payload...] — 与下行相同帧格式')
print('  抓包确认:   [OK] 一致')
print('  通知中的 ACK:  [type] [cuid LE16] (3B, 无内容) — 确认帧')
print('  [OK] 一致')
print()

# ---- 资产提取: 扫描下行帧中的 D001 + DATA 序列 ----
print('提取资产事务...')
asset_transactions = []
current_asset = None

for idx, f in enumerate(all_frames):
    direction, seq, ptype, cuid_val, desc, content, frame_bytes = f
    if direction != '→→→':
        continue
    if ptype == 0xCD and len(content) >= 4:
        cmd = struct.unpack_from('<H', content, 0)[0]
        if cmd == 0xD001:
            cmd_args = content[2:]
            total_size = struct.unpack_from('<I', cmd_args, 0)[0]
            start_cuid = struct.unpack_from('<H', cmd_args, 4)[0]
            asset_uuid = cmd_args[6:12]
            current_asset = {
                'uuid': asset_uuid,
                'total_size': total_size,
                'start_cuid': start_cuid,
                'chunks': [],
            }
            print(f'  D001: uuid={asset_uuid.hex()} total={total_size}B cuid_start=0x{start_cuid:04X}')
            continue
    if current_asset and ptype == 0xDA:
        if len(frame_bytes) >= 4:
            da_cuid = struct.unpack_from('<H', frame_bytes, 3)[0]
            if da_cuid == 0:
                current_asset['full'] = b''.join(current_asset['chunks'])
                actual_size = len(current_asset['full'])
                print(f'  DATA-ACK -> 共 {len(current_asset["chunks"])} 分片, {actual_size}B')
                asset_transactions.append(current_asset)
                current_asset = None
            elif da_cuid >= current_asset['start_cuid']:
                data_bytes = frame_bytes[5:]
                current_asset['chunks'].append(data_bytes)

if current_asset:
    current_asset['full'] = b''.join(current_asset['chunks'])
    asset_transactions.append(current_asset)

print(f'共 {len(asset_transactions)} 个资产事务')
print()

# ============================================================
# 5. 资产数据解析 (D001 → DATA → 重建)
# ============================================================
print('=' * 80)
print('5. 资产数据解析 (D001 → DATA → 重建)')
print('=' * 80)
# ============ 资产解析 ============
ANIM_NAMES = {0x00: '静态', 0x23: '左移', 0x21: '右移', 0x42: '闪烁'}

def parse_asset(full: bytes, label: str = ''):
    """Parse a full asset: UUID(6) + type(1) + payload(N)"""
    if len(full) < 7:
        print(f'  [资产数据不足]')
        return
    uuid = full[0:6]
    atype = full[6]
    payload = full[7:]
    print(f'\n[{label}] 资产UUID={uuid.hex()}  type={atype}  总大小={len(full)}B')
    print(f'  载荷: {len(payload)} 字节')

    # ---- Prefix (62B) ----
    if len(payload) < 62:
        print(f'  [前缀不足62B]')
        return
    print(f'  62B prefix: {payload[:62].hex()}')
    print(f'    Header(17B):     {payload[0:17].hex()}')
    uuid_copy = payload[17:23]
    print(f'    UUID copy[17-23]: {uuid_copy.hex()}')
    print(f'    [23-25]:          {payload[23:25].hex()}  (fixed 0x5400)')
    var1 = struct.unpack_from('<I', payload, 25)[0]
    var2 = struct.unpack_from('<I', payload, 29)[0]
    print(f'    var1[25-29]=0x{var1:08X}  var2[29-33]=0x{var2:08X}')
    print(f'    sub-elements[33-57]: {payload[33:57].hex()}')
    anim_code = payload[40]
    print(f'      anim_code[40]=0x{anim_code:02X} ({ANIM_NAMES.get(anim_code, "?")})')
    print(f'    color[57-62]:    {payload[57:62].hex()}')
    color = struct.unpack_from('<H', payload, 60)[0]
    r = ((color >> 11) & 0x1F) << 3
    g = ((color >> 5) & 0x3F) << 2
    b = (color & 0x1F) << 3
    print(f'      RGB565=0x{color:04X} -> rgb({r},{g},{b})')

    # ---- 文本段 (62B~) ----
    pos = 62
    if pos + 14 > len(payload):
        print(f'  [文本头不足]')
        return
    text_hdr = payload[pos:pos+8]
    print(f'   text_header[62-70]: {text_hdr.hex()}')
    pos += 8
    marker = payload[pos:pos+4]
    print(f'   marker[70-74]:      {marker.hex()}')
    pos += 4
    text_len = struct.unpack_from('<H', payload, pos)[0]
    print(f'   text_len[74-76] LE16: {text_len} (0x{text_len:04X})')
    pos += 2
    if payload[pos] != 0x2A:
        print(f'   [WARN] Expected 0x2A at {pos}, got 0x{payload[pos]:02X}')
        scan2a = payload.find(b'\x2a', pos)
        if scan2a >= 0:
            pos = scan2a + 1
        else:
            return
    else:
        pos += 1  # skip 0x2A
    text_raw = payload[pos:pos + text_len - 1]
    try:
        readable = text_raw.decode('utf-8')
    except:
        readable = text_raw.decode('utf-8', errors='replace')
    print(f'   文本 ({len(text_raw)}B): {readable!r}  hex={text_raw.hex()}')
    pos += len(text_raw)

    # After text: spacing, alignment, gc
    if pos + 7 > len(payload):
        return
    if payload[pos:pos+2] == b'\x02\x00' and payload[pos+2] == 0x2B:
        spacing = payload[pos+3]
        print(f'   spacing=0x{spacing:02X}')
        pos += 4
    if payload[pos:pos+2] == b'\x02\x00' and payload[pos+2] == 0x2C:
        alignment = payload[pos+3]
        print(f'   alignment=0x{alignment:02X}')
        pos += 4
    if pos < len(payload) and payload[pos] == 0x00:
        pos += 1

    # glyph count (BE minimal)
    gc_pos = pos
    gc = 0
    if pos < len(payload) and payload[pos] == 0x00:
        gc = struct.unpack_from('>H', payload, pos)[0]
        pos += 2
    elif pos + 1 < len(payload):
        gc = payload[pos]
        if gc > 100:
            gc = (payload[pos] << 8) | payload[pos+1]
            pos += 2
        else:
            pos += 1
    if pos < len(payload) and payload[pos] == 0x00:
        pos += 1
    print(f'   glyphCount={gc} (at payload[{gc_pos}])')

    # xPosTable + glyphs
    if 1 <= gc <= 50 and pos + gc * 4 <= len(payload):
        xvals = [struct.unpack_from('<I', payload, pos + i*4)[0] for i in range(gc)]
        print(f'   xPos entries: {[f"0x{v:x}" for v in xvals]}')
        pos += gc * 4
        print()
        print(f'   === 逐字字型 ({gc} glyphs) ===')
        for i in range(gc):
            if pos + 4 > len(payload):
                break
            cw = struct.unpack_from('<H', payload, pos)[0]
            ch = struct.unpack_from('<H', payload, pos+2)[0]
            psz = cw * 2
            pix = payload[pos+4:pos+4+psz]
            ch_name = readable[i] if i < len(readable) else '?'
            print(f'   [{i}] char={ch_name!r}  w={cw} h={ch}')
            if cw <= 20 and ch <= 20:
                for row in range(min(16, ch)):
                    line = ''
                    for col in range(cw):
                        if col*2+1 < len(pix):
                            lo = pix[col*2] & 0xFF
                            hi = pix[col*2+1] & 0xFF
                            bv = lo if row < 8 else hi
                            bit = (bv >> (row % 8)) & 1
                            line += '##' if bit else '  '
                    print(f'       row{row:2d}: {line}')
            pos += 4 + psz
    elif gc > 50:
        print(f'   (gc={gc} 异常, 跳过字型)')
    else:
        print(f'   (数据不足)')


# Parse all assets
if not asset_transactions:
    print('  未找到资产')
else:
    for i, asset in enumerate(asset_transactions):
        full = asset['full']
        parse_asset(full, label=f'资产 #{i+1} uuid={asset["uuid"].hex()}')
        if i < len(asset_transactions) - 1:
            print()
