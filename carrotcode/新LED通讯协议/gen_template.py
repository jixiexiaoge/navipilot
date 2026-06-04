#!/usr/bin/env python3
"""
从官方 APP HCI 抓包提取资产模板，分析每个字段语义，
生成可直接嵌入 Kotlin 的模板字节数组。
"""
import struct, os, sys
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

# Collect assets
current_asset = None
assets = []
for ts, opcode, params in att_ops:
    if len(params) < 4: continue
    value = params[2:]
    p = parse_popusign_value(value)
    if not p or 'error' in p: continue
    if 'cmd_code' in p and p['cmd_code'] == 0xD001:
        args = p.get('args', b'')
        if len(args) >= 12:
            uuid = args[6:12]
            length = args[0] | (args[1] << 8) | (args[2] << 16) | (args[3] << 24)
            cuid = args[4] | (args[5] << 8)
            current_asset = {'uuid': uuid, 'length': length, 'start_cuid': cuid, 'frags': []}
            assets.append(current_asset)
    elif p.get('type') == PAYLOAD_DATA:
        if current_asset is not None:
            if p['cuid'] == 0 and len(p['content']) == 0:
                continue
            current_asset['frags'].append(p['content'])

# Analysis
for i, a in enumerate(assets):
    raw = b''.join(a['frags'])
    uuid = a['uuid']
    payload = raw[7:]  # after uuid(6)+type(1)

    print(f"// Asset {i+1}: UUID={uuid.hex().upper()} total={len(raw)}B")
    print(f"//   declared_length={a['length']}")

    # Find text content
    text_info = "(none)"
    for pos in range(len(payload)-5):
        if payload[pos:pos+2] == b'\x22\x00':
            tlen = payload[pos+2] | (payload[pos+3] << 8)
            if 2 <= tlen <= 200 and pos+4+tlen <= len(payload):
                t = payload[pos+4:pos+4+tlen]
                try:
                    decoded = t.decode('utf-8')
                    safe = decoded.encode('unicode_escape').decode('ascii')
                    text_info = f"'{safe}' at payload[{pos}]"
                except:
                    pass

    # Extract field values for template generation
    print(f"//   text: {text_info}")
    print(f"//")

    # Dump as Kotlin byteArray template
    print(f"val TEMPLATE_{i+1} = byteArrayOf(", end="")
    for j, b in enumerate(raw):
        if j % 16 == 0:
            print(f"\n    // offset {j:3d}", end="")
        print(f" 0x{b:02X},", end="")
    print(f"\n)")
    print(f"// Template {i+1} payload size: {len(raw)} bytes (D001 length)")
    print()

# Focus on Asset 2 as primary template (simpler, moving text)
print("// === FIELD ANALYSIS (Asset 2, 260B template) ===")
raw2 = b''.join(assets[1]['frags'])
print("// Structure:")
print(f"//   [0-5]   UUID: {raw2[0:6].hex().upper()}")
print(f"//   [6]     type: 0x{raw2[6]:02X} (1=bitmap/compound, but contains text)")
p = raw2[7:]
print(f"//   [7~]    payload: {len(p)}B")

# Layout analysis
# bytes 0-1: might be count or flags
print(f"//   pl[0-1]: {p[0]:02X}{p[1]:02X} = 0x{p[0]<<8|p[1]:04X} / LE={p[0]|p[1]<<8}")
# bytes 16-27: second block
print(f"//   pl[16-21]: UUID again: {p[16:22].hex().upper()}")
print(f"//   pl[22-23]: 0x{p[22]:02X}{p[23]:02X} = {p[22]|p[23]<<8} (width?)")
print(f"//   pl[24-25]: 0x{p[24]:02X}{p[25]:02X} = {p[24]|p[25]<<8} (color/anim?)")
print(f"//   pl[26-27]: 0x{p[26]:02X}{p[27]:02X} = {p[26]|p[27]<<8}")
print(f"//   pl[28-29]: 0x{p[28]:02X}{p[29]:02X} = {p[28]|p[29]<<8} (height?)")
print(f"//   pl[30-31]: 0x{p[30]:02X}{p[31]:02X} = {p[30]|p[31]<<8}")

# Bytes 28-47
print(f"//   pl[32-47]: {p[32:48].hex().upper()}   (identical across all 3 assets)")

# Bytes 48-63 (text element header)
print(f"//   pl[48-63]: {p[48:64].hex().upper()}")

# Text element
for pos in range(64, min(80, len(p))):
    if p[pos:pos+2] == b'\x22\x00':
        tlen = p[pos+2] | (p[pos+3] << 8)
        t = p[pos+4:pos+4+tlen]
        decoded = t.decode('utf-8', errors='replace')
        print(f"//   pl[{pos}]: text element, len={tlen}, content='{decoded.encode('unicode_escape').decode('ascii')}'")
        print(f"//             surrounding (pos-8 to pos+4+tlen+4):")
        ctx = p[max(0,pos-8):min(len(p),pos+4+tlen+8)]
        print(f"//             {ctx.hex().upper()}")

# Tail part
print(f"//   pl[80-end]: {p[80:].hex().upper()}")
print(f"//   tail length: {len(p)-80}B")

# Also look at Asset 3 vs 2 differences for animation/color
raw3 = b''.join(assets[2]['frags'])
p3 = raw3[7:]

print(f"//")
print(f"// === DIFF Asset2 vs Asset3 (animation/color bytes) ===")
print(f"// A2 pl[22-23]: 0x{p[22]:02X}{p[23]:02X} = {p[22]|p[23]<<8}")
print(f"// A3 pl[22-23]: 0x{p3[22]:02X}{p3[23]:02X} = {p3[22]|p3[23]<<8}")
print(f"// A2 pl[56-57]: 0x{p[56]:02X}{p[57]:02X} (after 03 00 21)")
print(f"// A3 pl[56-57]: 0x{p3[56]:02X}{p3[57]:02X} (after 03 00 21)")
print(f"// A2 text: '*' + '向左移动'")
print(f"// A3 text: '*' + '闪烁蓝色'")

print(f"//")
print(f"// === TEMPLATE for Kotlin (Asset 2 payload, 260B) ===")
# Generate clean byte array for Kotlin
for j, b in enumerate(raw2):
    if j % 16 == 0:
        print(f"    /* {j:3d} */ ", end="")
    print(f"0x{b:02X}, ", end="")
    if j % 16 == 15:
        print()
print()
