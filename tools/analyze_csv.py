#!/usr/bin/env python3
"""
Analyze a Sensor Logger CSV export (Accelerometer.csv / Gyroscope.csv).
Reports mean/std/p95 of inter-sample intervals → tells us if the rate is
stable enough for downstream TinyML.

Usage:
    python analyze_csv.py path/to/Accelerometer.csv
"""
import argparse
import csv
import sys
from pathlib import Path


def load(path):
    """Return list of (time_ns, ax, ay, az) — column order: time, sec, z, y, x."""
    rows = []
    with open(path) as f:
        r = csv.reader(f)
        header = next(r)
        for row in r:
            rows.append((int(row[0]), float(row[2]), float(row[3]), float(row[4])))
    return header, rows


def stats(values):
    n = len(values)
    s = sum(values)
    mean = s / n
    var = sum((v - mean) ** 2 for v in values) / n
    std = var ** 0.5
    sv = sorted(values)
    p50 = sv[n // 2]
    p95 = sv[int(n * 0.95)]
    p99 = sv[int(n * 0.99)] if n >= 100 else sv[-1]
    return mean, std, p50, p95, p99, min(values), max(values)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('csv', type=Path)
    args = ap.parse_args()

    _, rows = load(args.csv)
    if len(rows) < 2:
        sys.exit('not enough samples')

    # inter-sample intervals in milliseconds
    ts = [r[0] for r in rows]
    intervals_ms = [(ts[i + 1] - ts[i]) / 1e6 for i in range(len(ts) - 1)]
    duration_s = (ts[-1] - ts[0]) / 1e9

    mean, std, p50, p95, p99, lo, hi = stats(intervals_ms)
    avg_rate = (len(rows) - 1) / duration_s

    print(f'File           : {args.csv.name}')
    print(f'Samples        : {len(rows)}')
    print(f'Duration       : {duration_s:.2f} s')
    print(f'Average rate   : {avg_rate:.2f} Hz')
    print()
    print(f'Inter-sample interval (ms):')
    print(f'  mean         : {mean:.3f}')
    print(f'  std          : {std:.3f}   (jitter)')
    print(f'  min / p50    : {lo:.3f}   /   {p50:.3f}')
    print(f'  p95 / p99    : {p95:.3f}   /   {p99:.3f}')
    print(f'  max          : {hi:.3f}')
    print()

    # quick judgment
    expected_ms = 1000.0 / avg_rate
    jitter_pct = std / expected_ms * 100
    print(f'Verdict:')
    if jitter_pct < 5:
        print(f'  ✅ Very stable (jitter {jitter_pct:.1f} %% of period)')
    elif jitter_pct < 15:
        print(f'  🟢 Acceptable (jitter {jitter_pct:.1f} %%) — fine for gesture recognition')
    elif jitter_pct < 40:
        print(f'  🟡 Noticeable jitter ({jitter_pct:.1f} %%) — need resampling in preprocess')
    else:
        print(f'  🔴 High jitter ({jitter_pct:.1f} %%) — risk for downstream ML')

    # find gaps > 3× expected (dropped samples)
    big = [(i, x) for i, x in enumerate(intervals_ms) if x > 3 * expected_ms]
    if big:
        print(f'  ⚠️  {len(big)} sample gaps > 3× expected period (possible dropouts)')
        for i, x in big[:5]:
            print(f'      at sample {i}: {x:.1f} ms')
    else:
        print(f'  ✅ No large gaps (no dropped samples)')


if __name__ == '__main__':
    main()
