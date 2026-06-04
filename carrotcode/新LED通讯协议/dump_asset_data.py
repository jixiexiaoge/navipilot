#!/usr/bin/env python3
"""
从 HCI 抓包中提取完整的资产数据 payload, 拼接并分析格式.
"""
import struct
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_hci import BtsnoopReader, extract_att, parse_popusign_value, PAYLOAD_DATA, PAYLOAD_CMD

capture_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "bt_hci_20260604_110135_d.cfa")

reader = BtsnoopReader(capture_path)

# 提取所有 ATT 写入
att_ops = []
for pkt in reader.packets:
    for opcode, params in extract_att(pkt['data']):
        if opcode in (0x52, 0x12):
            att_ops.append((pkt['ts'], opcode, params))

att_ops.sort(key=lambda x: x[0])

# 找到 D001 命令 (资产下载开始) 和后续的 DATA 帧
assets = []
current_asset = None

for ts, opcode, params in att_ops:
    if len(params) < 4:
        continue
    handle = params[0] | (params[1] << 8)
    value = params[2:]
    p = parse_popusign_value(value)
    if not p or 'error' in p:
        continue

    if 'cmd_code' in p and p['cmd_code'] == 0xD001:
        # 新资产开始, args = content[2:] (跳过 cmd_code)
        args = p.get('args', b'')
        if len(args) >= 12:
            length = args[0] | (args[1] << 8) | (args[2] << 16) | (args[3] << 24)
            start_cuid = args[4] | (args[5] << 8)
            uuid = args[6:12]
            current_asset = {
                'uuid': uuid,
                'uuid_str': uuid.hex().upper(),
                'length': length,
                'start_cuid': start_cuid,
                'fragments': [],
            }
            assets.append(current_asset)
            print(f"[D001] start asset: UUID={current_asset['uuid_str']} length={length} start_CUID={start_cuid}")

    elif p.get('type') == PAYLOAD_DATA:
        content = p['content']
        cuid = p['cuid']
        if cuid == 0 and len(content) == 0:
            if current_asset is not None:
                print(f"  [DATA ACK] CUID=0 (end of asset)")
            continue

        if current_asset is not None:
            current_asset['fragments'].append((cuid, content))
            print(f"  [DATA] CUID={cuid} len={len(content)}B")

print(f"\n{'='*70}")
print(f"Found {len(assets)} assets")
print(f"{'='*70}")

for i, asset in enumerate(assets):
    uuid_str = asset['uuid_str']
    total_frag_len = sum(len(f[1]) for f in asset['fragments'])
    n_frags = len(asset['fragments'])

    print(f"\n--- Asset {i+1}: UUID={uuid_str} (declared {asset['length']}B, actual {total_frag_len}B, {n_frags} fragments) ---")

    # 拼接所有分片
    raw = b''.join(f[1] for f in asset['fragments'])
    print(f"Full asset: {len(raw)} bytes")

    # 分析格式: [uuid(6B)] [type(1B)] [data...]
    if len(raw) >= 7:
        asset_uuid = raw[:6]
        asset_type = raw[6]
        payload = raw[7:]

        type_names = {0x00: "text", 0x01: "bitmap", 0x02: "font", 0xFF: "color_image"}
        type_name = type_names.get(asset_type, f"0x{asset_type:02X}")

        print(f"  Header: UUID={asset_uuid.hex().upper()} type={type_name}")
        print(f"  Payload: {len(payload)} bytes")

        # Dump full payload hex
        print(f"\n  Full payload hex:")
        for block in range(0, len(payload), 48):
            chunk = payload[block:block+48]
            hex_str = chunk.hex().upper()
            # 每16B加空格
            spaced = ''
            for j in range(0, len(hex_str), 32):
                spaced += hex_str[j:j+32] + ' '
            print(f"  [{block:3d}] {spaced}")

        # 尝试解析元数据
        print(f"\n  --- Metadata fields (LE16) ---")
        for off in range(0, min(32, len(payload)), 2):
            val = payload[off] | (payload[off+1] << 8)
            print(f"  payload[{off:3d}] = {val:5d} (0x{val:04X})")

        # 寻找可能的位图数据
        # 96x16 1bpp = 192 bytes, 每行12B
        print(f"\n  --- Looking for 192B bitmap (96x16) ---")
        for offset in range(0, max(1, len(payload) - 192), 1):
            chunk = payload[offset:offset+192]
            if len(chunk) < 192:
                continue
            # 检查是否每行12B且有像素数据模式
            # 算非零字节数
            nonzero = sum(1 for b in chunk if b != 0)
            if nonzero > 20:  # 有足够多的非零字节才可能是位图
                # 检查行尾模式: 96px = 12B, 每行独立
                # 简单检查行间相似度
                if offset <= 160:  # 只关注前160B内的偏移
                    print(f"  Offset {offset}: {nonzero}/{192} non-zero bytes")
                    if offset < 10:
                        for row in range(min(4, 16)):
                            rs = row * 12
                            print(f"    row{row}: {chunk[rs:rs+12].hex().upper()}")

        # 检查 tail 192B
        if len(payload) > 200:
            tail = payload[-192:]
            nonzero = sum(1 for b in tail if b != 0)
            print(f"\n  Tail 192B: {nonzero}/{192} non-zero bytes")
            for row in range(min(4, 16)):
                rs = row * 12
                print(f"    row{row}: {tail[rs:rs+12].hex().upper()}")

# 分析 Asset 2, 3 (可能包含字母/数字文本)
for i, asset in enumerate(assets[1:], 2):
    raw = b''.join(f[1] for f in asset['fragments'])
    if len(raw) < 7:
        continue
    print(f"\n{'='*70}")
    print(f"Asset {i}: UUID={raw[:6].hex().upper()} type=0x{raw[6]:02X} total={len(raw)}B")
    print(f"{'='*70}")
    payload = raw[7:]
    for block in range(0, len(payload), 48):
        chunk = payload[block:block+48]
        hex_str = chunk.hex().upper()
        spaced = ''
        for j in range(0, len(hex_str), 32):
            spaced += hex_str[j:j+32] + ' '
        print(f"  [{block:3d}] {spaced}")

print(f"\n{'='*70}")
print("Done")
