import sys
sys.path.insert(0, 'C:/Users/zhudo/AndroidStudioProjects/navipilot/carrotcode/新LED通讯协议')
from analyze_hci import BtsnoopReader, extract_att, parse_popusign_value, PAYLOAD_CMD, PAYLOAD_DATA

reader = BtsnoopReader('C:/Users/zhudo/AndroidStudioProjects/navipilot/carrotcode/新LED通讯协议/bt_hci_20260604_110135_d.cfa')
att_ops = []
for pkt in reader.packets:
    for opcode, params in extract_att(pkt['data']):
        if opcode in (0x52, 0x12):
            att_ops.append((pkt['ts'], opcode, params))
att_ops.sort(key=lambda x: x[0])
writes = [(ts, p) for ts, oc, p in att_ops if oc == 0x52]
print(f'Total app writes: {len(writes)}')
for ts, params in writes:
    value = params[2:]
    p = parse_popusign_value(value)
    if p and 'cmd_code' in p:
        cuid = p['cuid']
        args = p.get('args', b'')
        extra = ' ★PLAY★' if p['cmd_code'] == 0x0A01 else ''
        extra += ' ★EDIT★' if p['cmd_code'] == 0xED01 else ''
        print(f'{p["seq"]:3d} CMD=0x{p["cmd_code"]:04X}({p["cmd_name"]}) cuid={cuid:3d} args={args.hex()}{extra}')
    elif p and p.get('type') == PAYLOAD_DATA:
        print(f'{p["seq"]:3d} DATA cuid={p["cuid"]:3d} ({len(p["content"])}B)')
    elif p and p.get('len', 0) == 3:
        print(f'{p["seq"]:3d} ACK cuid={p["cuid"]}')
