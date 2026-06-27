#!/usr/bin/env python3
"""
Interactive dataset recorder for the BLE-IMU variant.

Connects to a BLE peripheral advertising the custom Gesture-IMU GATT
service, subscribes to the notify characteristic, and records 2-second
windows into per-gesture CSV files.

The expected payload is 12 bytes per notification: 6 × int16, raw, in the
order (ax, ay, az, gx, gy, gz). Scaling to physical units happens during
preprocessing in the training notebook.

Usage:
    python ble_imu_recorder.py [--address AA:BB:..]
                               [--n 50]
                               [--out ./dataset]
"""
import argparse
import asyncio
import csv
import struct
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner

SERVICE_UUID  = "0000ec00-b87f-490c-92cb-11ba5ea5167c"
CHAR_NOTIFY   = "0000ec01-b87f-490c-92cb-11ba5ea5167c"

GESTURES   = ['idle', 'single_tap', 'double_tap', 'shake', 'rotate']
WINDOW_SEC = 2.0
DEFAULT_N  = 50


async def discover():
    print('Scanning 5 s for BLE devices advertising Gesture-IMU service...')
    devices = await BleakScanner.discover(timeout=5.0, return_adv=True)
    for d, adv in devices.values():
        if SERVICE_UUID.lower() in [u.lower() for u in (adv.service_uuids or [])]:
            print(f'  found: {d.address}  {d.name}')
            return d.address
    return None


async def collect_window(client, seconds):
    rows = []
    t0 = time.time()
    done = asyncio.Event()

    def cb(_handle, data: bytearray):
        if len(data) != 12:
            return
        ax, ay, az, gx, gy, gz = struct.unpack('<6h', data)
        rows.append((time.time() - t0, ax, ay, az, gx, gy, gz))
        if time.time() - t0 >= seconds:
            done.set()

    await client.start_notify(CHAR_NOTIFY, cb)
    try:
        await asyncio.wait_for(done.wait(), timeout=seconds + 1.0)
    except asyncio.TimeoutError:
        pass
    await client.stop_notify(CHAR_NOTIFY)
    return rows


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--address', help='BLE MAC; auto-discover if omitted')
    ap.add_argument('--n', type=int, default=DEFAULT_N)
    ap.add_argument('--out', default='./dataset')
    args = ap.parse_args()

    addr = args.address or await discover()
    if not addr:
        raise SystemExit('No matching BLE peripheral found.')

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    async with BleakClient(addr) as client:
        print(f'Connected to {addr}')
        for g in GESTURES:
            (out / g).mkdir(exist_ok=True)
            existing = len(list((out / g).glob('*.csv')))
            print(f'\n=== {g}  ({existing}/{args.n}) ===')
            for i in range(existing, args.n):
                input(f'  sample {i+1}/{args.n}: press Enter, then perform the gesture...')
                rows = await collect_window(client, WINDOW_SEC)
                path = out / g / f'{i:03d}.csv'
                with path.open('w', newline='') as f:
                    w = csv.writer(f)
                    w.writerow(['t', 'ax', 'ay', 'az', 'gx', 'gy', 'gz'])
                    w.writerows(rows)
                print(f'  saved {len(rows)} rows -> {path}')


if __name__ == '__main__':
    asyncio.run(main())
