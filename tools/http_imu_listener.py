#!/usr/bin/env python3
"""
Receive IMU data over HTTP POST from Sensor Logger (Logger Labs).

Sensor Logger's "HTTP Push" sends batched JSON to a configured URL, e.g.:
    POST http://<laptop-ip>:8080/ HTTP/1.1
    Content-Type: application/json

    {
        "messageId": 1234,
        "sessionId": "...",
        "payload": [
            {"name":"accelerometer","time":1718650123456000000,
             "values":{"x":0.12, "y":-0.04, "z":9.81}},
            {"name":"gyroscope","time":...,"values":{"x":..,"y":..,"z":..}},
            ...
        ]
    }

Each POST typically batches 10-100 samples — print the rate and a sample.

Usage:
    python http_imu_listener.py --port 8080
"""
import argparse
import json
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class State:
    n_packets = 0
    n_samples = 0
    t_first = None
    recent_sample_times = deque(maxlen=200)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):                       # noqa: N802
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)
        self.send_response(200)
        self.end_headers()

        now = time.time()
        if State.t_first is None:
            State.t_first = now
            print(f'First POST from {self.client_address[0]}, {length} bytes')

        State.n_packets += 1
        try:
            msg = json.loads(body)
            payload = msg.get('payload', [])
            # count accelerometer samples as the rate proxy
            acc_samples = [p for p in payload if p.get('name') == 'accelerometer']
            gyr_samples = [p for p in payload if p.get('name') == 'gyroscope']
            State.n_samples += len(acc_samples)
            for s in acc_samples:
                State.recent_sample_times.append(now)

            elapsed = now - State.t_first
            avg_rate = State.n_samples / elapsed if elapsed > 0 else 0
            if len(State.recent_sample_times) >= 2:
                w = State.recent_sample_times
                inst_rate = (len(w) - 1) / (w[-1] - w[0]) if (w[-1] - w[0]) > 0 else 0
            else:
                inst_rate = 0

            last_acc = acc_samples[-1]['values'] if acc_samples else None
            last_gyr = gyr_samples[-1]['values'] if gyr_samples else None
            if State.n_packets % 5 == 0 or State.n_packets < 3:
                print(f'[POST #{State.n_packets} samples={State.n_samples}'
                      f' avg={avg_rate:5.1f}Hz inst={inst_rate:5.1f}Hz]'
                      f' acc={last_acc} gyr={last_gyr}')
        except Exception as e:
            print(f'parse error: {e}, body[:80]={body[:80]!r}')

    def log_message(self, *_):              # noqa: N802
        # suppress default access log
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8080)
    args = ap.parse_args()

    server = ThreadingHTTPServer(('0.0.0.0', args.port), Handler)
    print(f'HTTP IMU listener on http://0.0.0.0:{args.port}  (Ctrl+C to stop)')
    print('In Sensor Logger app:')
    print(f'  Settings -> HTTP Push -> URL = http://<this-machine-ip>:{args.port}/')
    print('  Method = POST, Content-Type = application/json, Enable streaming')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\nBye.')


if __name__ == '__main__':
    main()
