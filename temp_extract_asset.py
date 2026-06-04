import sys
sys.path.insert(0, 'C:/Users/zhudo/AndroidStudioProjects/navipilot/carrotcode/新LED通讯协议')
from analyze_hci import BtsnoopReader, extract_att, parse_popusign_value, PAYLOAD_DATA, PAYLOAD_CMD

reader = BtsnoopReader('C:/Users/zhudo/AndroidStudioProjects/navipilot/carrotcode/新LED通讯协议/bt_hci_20260604_110135_d.cfa')
att_ops = []
for pkt in reader.packets:
    for opcode, params in extract_att(pkt['data']):
        if opcode in (0x52, 0x12):
            att_ops.append((pkt['ts'], opcode, params))
att_ops.sort(key=lambda x: x[0])

# Collect assets from the SECOND batch (the "CP搭子" text send)
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
            uuid = args[6:12]
            current_asset = {'uuid': uuid, 'length': length, 'frags': []}
            assets.append(current_asset)
    elif p.get('type') == PAYLOAD_DATA:
        if current_asset is not None:
            cuid = p['cuid']
            content = p['content']
            if cuid == 0 and len(content) == 0:
                continue
            current_asset['frags'].append(p['content'])

# Show all assets found
for i, a in enumerate(assets):
    raw = b''.join(a['frags'])
    print(f'Asset {i}: UUID={a["uuid"].hex().upper()} length={len(raw)}B')
    if len(raw) > 10:
        # skip uuid(6)+type(1)
        payload = raw[7:]
        # Show first 100 bytes
        print(f'  Type: 0x{raw[6]:02X}')
        print(f'  Payload: {len(payload)}B')
        print(f'  First 80B: {payload[:80].hex().upper()}')
        # Find text
        for pos in range(len(payload)-5):
            if payload[pos:pos+2] == b'\x22\x00':
                tlen = payload[pos+2] | (payload[pos+3] << 8)
                if 2 <= tlen <= 200 and pos+4+tlen <= len(payload):
                    t = payload[pos+4:pos+4+tlen]
                    try:
                        decoded = t.decode('utf-8')
                        print(f'  Text at [{pos}]: "{decoded}"')
                    except:
                        print(f'  Text at [{pos}]: {t.hex()}')
                break
        # Show last 80 bytes
        print(f'  Last 80B:  {payload[-80:].hex().upper()}')
    print()
