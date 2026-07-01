#!/usr/bin/env bash
# Layer 4: pull the storage-side metrics for a benchmark run's time window so
# they can be joined against that run's bench-metrics.csv. Metric names below
# are verified against AWS's published CloudWatch metric/dimension docs for
# AWS/EBS, AWS/EFS, and AWS/FSx (FSx for OpenZFS) as of 2026-06.
#
# Each of EBS_VOLUME_ID / EFS_FILESYSTEM_ID / FSX_FILESYSTEM_ID is optional —
# only whichever ones are actually set get queried. A single-controller run
# (e.g. the EBS-backed pipeline calling this from vars/benchStages.groovy)
# only ever sets the one relevant to its own storage class.
set -euo pipefail

START="${1:?start time required, e.g. 2026-06-30T10:00:00Z}"
END="${2:?end time required, e.g. 2026-06-30T10:10:00Z}"
OUTDIR="${3:-results/cloudwatch}"
mkdir -p "$OUTDIR"

if [ -z "${EBS_VOLUME_ID:-}" ] && [ -z "${EFS_FILESYSTEM_ID:-}" ] && [ -z "${FSX_FILESYSTEM_ID:-}" ]; then
    echo "!! Set at least one of EBS_VOLUME_ID / EFS_FILESYSTEM_ID / FSX_FILESYSTEM_ID" >&2
    exit 1
fi

fetch() {
    local namespace="$1" metric="$2" dim_name="$3" dim_value="$4" out="$5"
    aws cloudwatch get-metric-statistics \
        --namespace "$namespace" \
        --metric-name "$metric" \
        --dimensions "Name=${dim_name},Value=${dim_value}" \
        --start-time "$START" --end-time "$END" \
        --period 60 --statistics Average Maximum Sum \
        > "${OUTDIR}/${out}"
}

if [ -n "${EBS_VOLUME_ID:-}" ]; then
    echo "==> EBS (AWS/EBS, VolumeId=${EBS_VOLUME_ID})"
    for m in VolumeReadOps VolumeWriteOps VolumeReadBytes VolumeWriteBytes VolumeThroughputPercentage BurstBalance; do
        fetch AWS/EBS "$m" VolumeId "$EBS_VOLUME_ID" "ebs-${m}.json"
    done
fi

if [ -n "${EFS_FILESYSTEM_ID:-}" ]; then
    echo "==> EFS (AWS/EFS, FileSystemId=${EFS_FILESYSTEM_ID})"
    for m in PermittedThroughput PercentIOLimit DataReadIOBytes DataWriteIOBytes MetaDataIOBytes TotalIOBytes; do
        fetch AWS/EFS "$m" FileSystemId "$EFS_FILESYSTEM_ID" "efs-${m}.json"
    done
fi

if [ -n "${FSX_FILESYSTEM_ID:-}" ]; then
    echo "==> FSx for OpenZFS (AWS/FSx, FileSystemId=${FSX_FILESYSTEM_ID})"
    for m in DataReadOperations DataWriteOperations DiskReadOperations DiskWriteOperations \
             NetworkThroughputUtilization FileServerDiskThroughputUtilization FileServerDiskIopsUtilization; do
        fetch AWS/FSx "$m" FileSystemId "$FSX_FILESYSTEM_ID" "fsx-${m}.json"
    done
fi

echo "Wrote CloudWatch snapshots to ${OUTDIR}/"
