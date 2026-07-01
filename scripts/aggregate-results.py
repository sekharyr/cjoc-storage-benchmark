#!/usr/bin/env python3
"""Summarize bench-metrics CSVs (from vars/bench.groovy) into a
storage_class x label x concurrency x durability table with median/p95.
Stdlib only — no install step needed on a Jenkins agent or controller.

Usage: scripts/aggregate-results.py ["results/**/*.csv"]
"""
import csv
import glob
import statistics
import sys
from collections import defaultdict


def load_rows(pattern):
    rows = []
    for path in glob.glob(pattern, recursive=True):
        with open(path, newline='') as f:
            rows.extend(csv.DictReader(f))
    return rows


def main():
    pattern = sys.argv[1] if len(sys.argv) > 1 else 'results/**/*.csv'
    rows = load_rows(pattern)
    if not rows:
        print(f'No rows found for pattern: {pattern}')
        return

    buckets = defaultdict(list)
    for row in rows:
        key = (row['storage_class'], row['label'], row['concurrency'], row['durability'])
        buckets[key].append(int(row['duration_ms']))

    header = ('storage_class', 'label', 'concurrency', 'durability', 'n', 'median_ms', 'p95_ms')
    print(' | '.join(header))
    print(' | '.join(['---'] * len(header)))
    for key, durations in sorted(buckets.items()):
        durations.sort()
        median = statistics.median(durations)
        p95 = durations[min(len(durations) - 1, int(len(durations) * 0.95))]
        print(' | '.join(str(x) for x in (*key, len(durations), median, p95)))


if __name__ == '__main__':
    main()
