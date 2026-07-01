#!/usr/bin/env python3
"""Turns the raw CloudWatch JSON from collect-cloudwatch.sh into actual IOPS/
throughput numbers. `aws cloudwatch get-metric-statistics` returns per-period
Sum/Average/Maximum, not a ready ops-per-second or MB-per-second figure --
Ops/Operations-suffixed metrics need their Sum divided by the period length
to become IOPS; Bytes-suffixed metrics need the same division plus a unit
conversion to become MB/sec. Everything else (Percent/Utilization/Balance
metrics like PercentIOLimit or BurstBalance) is already a rate and gets
reported as-is from each datapoint's own Average/Maximum.

Stdlib only -- no install step needed on a Jenkins agent or controller.

Writes cloudwatch-summary.csv into the same directory as the input JSON
(for archiving alongside it) as well as printing a markdown table to stdout.

Usage: scripts/summarize-cloudwatch.py [results/cloudwatch]
"""
import csv
import glob
import json
import os
import sys

PERIOD_SECONDS = 60  # must match collect-cloudwatch.sh's --period flag
BYTES_PER_MB = 1_000_000  # decimal MB, matching AWS's own published specs (e.g. gp3's 125 MB/s baseline)


def classify(metric_name):
    if metric_name.endswith('Ops') or metric_name.endswith('Operations'):
        return 'iops'
    if metric_name.endswith('Bytes'):
        return 'throughput'
    return 'other'


def metric_name_from_filename(filename):
    # e.g. "ebs-VolumeWriteOps.json" -> "VolumeWriteOps"
    stem = filename[:-len('.json')] if filename.endswith('.json') else filename
    return stem.split('-', 1)[1] if '-' in stem else stem


def summarize_file(path):
    with open(path) as f:
        data = json.load(f)
    datapoints = data.get('Datapoints', [])
    if not datapoints:
        return None

    filename = os.path.basename(path)
    metric_name = metric_name_from_filename(filename)
    kind = classify(metric_name)

    if kind == 'iops':
        avg_rates = [dp['Sum'] / PERIOD_SECONDS for dp in datapoints if 'Sum' in dp]
        max_rates = avg_rates
        unit = 'ops/sec'
    elif kind == 'throughput':
        avg_rates = [dp['Sum'] / PERIOD_SECONDS / BYTES_PER_MB for dp in datapoints if 'Sum' in dp]
        max_rates = avg_rates
        unit = 'MB/sec'
    else:
        # Percent/Utilization/Balance-style metrics are already a rate, not a
        # count to divide — and unlike iops/throughput, CloudWatch's own
        # Maximum field here is a genuinely different (and more useful) number
        # than the highest per-period Average, so track them separately rather
        # than deriving "max" from the same series as "avg".
        avg_rates = [dp['Average'] for dp in datapoints if 'Average' in dp]
        max_rates = [dp['Maximum'] for dp in datapoints if 'Maximum' in dp]
        unit = datapoints[0].get('Unit', '')

    if not avg_rates or not max_rates:
        return None

    return {
        'file': filename,
        'metric': metric_name,
        'kind': kind,
        'unit': unit,
        'n': len(avg_rates),
        'avg': sum(avg_rates) / len(avg_rates),
        'max': max(max_rates),
    }


def main():
    pattern = sys.argv[1] if len(sys.argv) > 1 else 'results/cloudwatch'
    paths = sorted(glob.glob(os.path.join(pattern, '*.json')))
    if not paths:
        print(f'No CloudWatch JSON found under: {pattern}')
        return

    header = ('metric', 'kind', 'n', 'avg', 'max', 'unit')
    print(' | '.join(header))
    print(' | '.join(['---'] * len(header)))
    summaries = []
    for path in paths:
        summary = summarize_file(path)
        if summary is None:
            print(f"{os.path.basename(path)} | no datapoints (check the time window / CloudWatch's 1-2 min ingestion delay)")
            continue
        summaries.append(summary)
        print(' | '.join([
            summary['metric'],
            summary['kind'],
            str(summary['n']),
            f"{summary['avg']:.2f}",
            f"{summary['max']:.2f}",
            str(summary['unit']),
        ]))

    if summaries:
        csv_path = os.path.join(pattern, 'cloudwatch-summary.csv')
        with open(csv_path, 'w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=header)
            writer.writeheader()
            for summary in summaries:
                writer.writerow({
                    'metric': summary['metric'],
                    'kind': summary['kind'],
                    'n': summary['n'],
                    'avg': f"{summary['avg']:.2f}",
                    'max': f"{summary['max']:.2f}",
                    'unit': summary['unit'],
                })
        print(f'\nWrote {csv_path}')


if __name__ == '__main__':
    main()
