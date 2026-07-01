#!/usr/bin/env bash
# Layer 1 baseline: kubestr provisions a throwaway PVC per StorageClass, runs
# fio inside a pod against it, tears everything down, and writes a JSON report.
#
# kubestr's built-in fio job is a reasonable general baseline. If you want the
# exact access patterns in baseline-randrw-4k.fio / baseline-seq-128k.fio
# instead, check `kubestr fio --help` for this driver version's flag to pass a
# custom fio job file, since it has changed across kubestr releases.
set -euo pipefail

SIZE="${1:-20Gi}"
OUTDIR="${2:-results/fio-baseline}"
STORAGE_CLASSES=(ebs-gp3 efs-elastic fsx-openzfs)

mkdir -p "$OUTDIR"

for sc in "${STORAGE_CLASSES[@]}"; do
    echo "==> kubestr fio against StorageClass: ${sc}"
    if ! kubestr fio -s "$sc" -z "$SIZE" -o "${OUTDIR}/${sc}.json"; then
        echo "!! kubestr failed for ${sc} — check that the StorageClass exists (kubectl get sc ${sc})"
    fi
done

echo "Baseline results written to ${OUTDIR}/"
