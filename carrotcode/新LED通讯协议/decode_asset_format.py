#!/usr/bin/env python3
"""
深度分析 a800 资产格式：结构化文本布局文档，不是位图。
"""
import struct
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_hci import BtsnoopReader, extract_att, parse_popusign_value, PAYLOAD_DATA, PAYLOAD_CMD

capture_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "bt_hci_20260604_110135_d.cfa")

reader = BtsnoopReader(capture_path)

att_ops = []
for pkt in reader.packets:
    for opcode, params in extract_att(pkt['data']):
        if opcode in (0x52, 0x12):
            att_ops.append((pkt['ts'], opcode, params))
att_ops.sort(key=lambda x: x[0])

def extract_text(data):
    """尝试从字节流中提取 UTF-8 文本"""
    texts = []
    i = 0
    while i < len(data):
        b = data[i]
        # ASCII printable
        if 0x20 <= b < 0x7F:
            start = i
            while i < len(data) and 0x20 <= data[i] < 0x7F:
                i += 1
            if i - start >= 2:
                texts.append(('ascii', data[start:i].decode('ascii'), start))
        # UTF-8 leading byte (3-byte)
        elif b >= 0xE0 and i + 2 < len(data):
            if (data[i+1] & 0xC0) == 0x80 and (data[i+2] & 0xC0) == 0x80:
                cp = (b & 0x0F) << 12 | (data[i+1] & 0x3F) << 6 | (data[i+2] & 0x3F)
                if 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF:
                    texts.append(('cjk', data[i:i+3].decode('utf-8'), i))
                i += 3
                continue
            i += 1
        elif 0xC0 <= b < 0xE0 and i + 1 < len(data):
            if (data[i+1] & 0xC0) == 0x80:
                cp = (b & 0x1F) << 6 | (data[i+1] & 0x3F)
                if 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF:
                    texts.append(('cjk', data[i:i+2].decode('utf-8'), i))
                i += 2
                continue
            i += 1
        else:
            i += 1
    return texts

# 拼接资产
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
        args = p.get('args', b'')
        if len(args) >= 12:
            length = args[0] | (args[1] << 8) | (args[2] << 16) | (args[3] << 24)
            start_cuid = args[4] | (args[5] << 8)
            uuid = args[6:12]
            current_asset = {'uuid': uuid, 'uuid_str': uuid.hex().upper(), 'length': length, 'fragments': []}
            assets.append(current_asset)
    elif p.get('type') == PAYLOAD_DATA:
        cuid = p['cuid']
        content = p['content']
        if cuid == 0 and len(content) == 0:
            continue
        if current_asset is not None:
            current_asset['fragments'].append(content)

for i, asset in enumerate(assets):
    raw = b''.join(asset['fragments'])
    payload = raw[7:]  # skip UUID(6) + type(1)

    print(f"\n{'='*70}")
    print(f"Asset {i+1}: UUID={asset['uuid_str']} total={len(raw)}B")
    print(f"{'='*70}")
    print(f"  Header: UUID={raw[:6].hex().upper()} type=0x{raw[6]:02X}")
    print(f"  Payload: {len(payload)}B")

    # 提取文本
    texts = extract_text(payload)
    if texts:
        print(f"\n  --- Embedded text strings ---")
        for kind, t, off in texts:
            print(f"    [{off:3d}] ({kind}) '{t}'")

    # 查找所有 22 00 xx 00 2A 模式的文本元素
    print(f"\n  --- Text elements (pattern 22 00 len 00 2A) ---")
    pos = 0
    while pos < len(payload) - 5:
        if payload[pos:pos+2] == b'\x22\x00':
            text_len = payload[pos+2] | (payload[pos+3] << 8)
            if text_len > 0 and text_len < 200 and pos+4+text_len <= len(payload):
                text_raw = payload[pos+4:pos+4+text_len]
                try:
                    text_str = text_raw.decode('utf-8', errors='replace')
                except:
                    text_str = text_raw.hex()
                safe_text = text_str.encode('ascii', errors='replace').decode('ascii')
                print(f"    [{pos:3d}] len={text_len} text='{safe_text}'  [utf8: {text_raw.hex().upper()}]")
                # 显示周围的上下文 (前8B + 后4B)
                ctx_start = max(0, pos-8)
                ctx_end = min(len(payload), pos+4+text_len+4)
                ctx = payload[ctx_start:ctx_end]
                print(f"          context: {ctx.hex().upper()}")
                pos += 4 + text_len
            else:
                pos += 2
        else:
            pos += 1

    # 查找 02 00 xx xx 模式的定位/颜色元素
    print(f"\n  --- Position/color elements (type 02 00, 03 00) ---")
    pos = 16  # Skip initial metadata
    while pos < len(payload) - 3:
        if payload[pos:pos+2] == b'\x02\x00':
            val = payload[pos+2] | (payload[pos+3] << 8)
            ctx = payload[max(0,pos-2):min(len(payload),pos+6)]
            print(f"    [{pos:3d}] TYPE=0x0002 param=0x{val:04X} ({val})  ctx={ctx.hex().upper()}")
            pos += 4
        elif payload[pos:pos+2] == b'\x03\x00':
            if pos + 6 <= len(payload):
                p1 = payload[pos+2] | (payload[pos+3] << 8)
                p2 = payload[pos+4] | (payload[pos+5] << 8)
                ctx = payload[max(0,pos-2):min(len(payload),pos+8)]
                print(f"    [{pos:3d}] TYPE=0x0003 param=0x{p1:04X} value=0x{p2:04X}  ctx={ctx.hex().upper()}")
            pos += 6
        else:
            pos += 1

    # Dump raw bytes in 16B rows with annotations
    print(f"\n  --- Raw payload with annotations ---")
    for block in range(0, len(payload), 16):
        chunk = payload[block:block+16]
        hex_str = ' '.join(f'{b:02X}' for b in chunk)
        # 尝试显示 ascii
        ascii_str = ''.join(chr(b) if 0x20 <= b < 0x7F else '.' for b in chunk)
        print(f"  [{block:3d}] {hex_str:<48s} |{ascii_str}|")

print(f"\n{'='*70}")
print("Done")
