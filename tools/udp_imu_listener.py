#!/usr/bin/env python3
"""
Receive IMU data over UDP from Sensor Logger app, print rate & sample.

Sensor Logger UDP packets are JSON, one packet per sample, e.g.:
    {
        "messageId": 1234,
        "sessionId": "...",
        "deviceId": "...",
        "payload": [
            {"name":"accelerometer","time":...,"values":{"x":..,"y":..,"z":..}},
            {"name":"gyroscope","time":...,"values":{"x":..,"y":..,"z":..}}
        ]
    }

Usage:
    python udp_imu_listener.py --port 8080
"""
import argparse
import json
import socket
import time
from collections import deque


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument('--port', type=int, default=8080)
    p.add_argument('--print-every', type=int, default=50, help='print every N samples')
    return p.parse_args()


def main():
    args = parse_args()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', args.port))
    print(f'Listening on UDP :{args.port} ... (Ctrl+C to stop)')

    n = 0
    t_first = None
    last_print_t = time.time()
    recent_intervals = deque(maxlen=50)

    while True:
        data, addr = sock.recvfrom(8192)
        now = time.time()
        if t_first is None:
            t_first = now
            print(f'First packet from {addr}, {len(data)} bytes')

        n += 1
        recent_intervals.append(now)

        if n % args.print_every == 0:
            elapsed = now - t_first
            avg_rate = n / elapsed if elapsed > 0 else 0
            if len(recent_intervals) >= 2:
                inst_rate = (len(recent_intervals) - 1) / (recent_intervals[-1] - recent_intervals[0])
            else:
                inst_rate = 0
            try:
                msg = json.loads(data.decode('utf-8', errors='ignore'))
                payload = msg.get('payload', [])
                acc = next((p['values'] for p in payload if p.get('name') == 'accelerometer'), None)
                gyr = next((p['values'] for p in payload if p.get('name') == 'gyroscope'), None)
                summary = f'acc={acc} gyr={gyr}'
            except Exception:
                summary = data[:80].decode('latin-1', errors='replace')
            print(f'[#{n} avg={avg_rate:5.1f}Hz inst={inst_rate:5.1f}Hz] {summary}')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\nBye.')
