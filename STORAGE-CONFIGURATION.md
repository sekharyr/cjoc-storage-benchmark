# Storage backend configuration — target state and commands

Reference for bringing `ebs-gp3-sc`, `efs-sc`, and `openzfs-sc` on the
`cbci-demo-cone` (us-east-1) cluster to a matched, documented throughput
ceiling before running any comparative benchmark. Captures what was found via
live `kubectl`/`aws` inspection, the target configuration, and the exact
commands to get there — split into **(A) fix the already-provisioned
resource now** and **(B) fix the StorageClass so future provisioning matches**,
since these are two different, non-overlapping mechanisms.

**Target throughput ceiling: 128 MB/s across all three backends.** Chosen
because it's AWS's own documented floor for FSx OpenZFS "request- or
metadata-intensive workloads" (the category Jenkins falls into), it's a valid
discrete FSx throughput-capacity tier, and it's only marginally above EBS
gp3's free baseline (125 MB/s) — cheap to hit on all three. Revisit if a
different target is decided later; every command below references `128`
explicitly so it's a single find-and-replace.

---

## 1. EBS gp3 (`ebs-gp3-sc`)

### Current state (found via `kubectl get sc ebs-gp3-sc -o yaml`)

| Field | Value |
|---|---|
| `parameters.type` | `gp3` |
| `parameters.iops` / `parameters.throughput` | **not set** → AWS free defaults: 3,000 IOPS / 125 MB/s |
| `volumeBindingMode` | `WaitForFirstConsumer` (correct, no change needed) |
| `reclaimPolicy` | `Delete` |
| Real volume backing the existing controller | `vol-01d464727c847df38` |

### Target

| Field | Value | Rationale |
|---|---|---|
| IOPS | `3000` (explicit, same as current default) | 128 MB/s throughput needs far less than 3,000 IOPS worth of headroom on gp3 (the IOPS/throughput relationship on gp3 supports 128 MB/s well under 1,000 provisioned IOPS) — pinning it explicitly at the current default just removes "relying on an undocumented default" as a variable, not because more IOPS is needed for this target |
| Throughput | `128` (MB/s) | Matches the common ceiling |

### (A) Fix the existing volume now — live, no downtime

```bash
aws ec2 modify-volume --volume-id vol-01d464727c847df38 \
  --iops 3000 --throughput 128 --region us-east-1
```

- Takes effect without detaching/restarting the controller.
- **Rate limit**: AWS requires the volume to be `in-use`/`available` and at least 6 hours since its last modification before you can modify it again — don't plan on iterating same-day.
- Check progress (`ModificationState` goes `modifying` → `optimizing` → `completed`):
  ```bash
  aws ec2 describe-volumes-modifications --volume-ids vol-01d464727c847df38 --region us-east-1
  ```

### (B) Fix the StorageClass for future provisioning

`parameters` is immutable on an existing StorageClass object — patching won't
work; delete and recreate (safe: doesn't touch already-bound PVs, only
affects the next dynamically-provisioned volume against this class name):

```bash
kubectl delete sc ebs-gp3-sc

cat <<'EOF' | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: ebs-gp3-sc
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
parameters:
  type: gp3
  iops: "3000"
  throughput: "128"
provisioner: ebs.csi.aws.com
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
EOF
```

### Verify

```bash
kubectl get sc ebs-gp3-sc -o yaml
aws ec2 describe-volumes --volume-ids vol-01d464727c847df38 --region us-east-1 \
  --query 'Volumes[0].{Type:VolumeType,IOPS:Iops,Throughput:Throughput,State:State}'
```

---

## 2. EFS (`efs-sc`)

### Current state (found via `kubectl get sc efs-sc -o yaml` + `aws efs describe-file-systems`)

| Field | Value |
|---|---|
| `provisioningMode` | `efs-ap` (access-point-based; one shared filesystem, per-PVC access point) |
| `fileSystemId` | `fs-0246a526db0adaedf` |
| `ThroughputMode` | **`bursting`** |
| Filesystem size | 1,580,658,688 bytes ≈ 1.47 GiB |
| Bursting baseline at that size | ≈ 73 KiB/s — ~1,700× below EBS's untouched default |
| `volumeBindingMode` | `Immediate` (correct for EFS — mount targets exist in every AZ) |
| `reclaimPolicy` | `Delete` (safe here — only deletes the per-PVC access point, not the shared filesystem) |

### Target

| Field | Value |
|---|---|
| `ThroughputMode` | `provisioned` |
| `ProvisionedThroughputInMibps` | `128` |

Provisioned (not Elastic) chosen specifically so the number is an explicit,
fixed value directly comparable to EBS's and FSx's fixed throughput settings
— Elastic's auto-scaling behavior isn't a single number to hold constant
across benchmark runs.

### (A) Fix the existing filesystem now — live

```bash
aws efs update-file-system --file-system-id fs-0246a526db0adaedf \
  --throughput-mode provisioned --provisioned-throughput-in-mibps 128 \
  --region us-east-1
```

- Takes effect within a few minutes, no remount needed.
- **Rate limit**: decreasing throughput-mode/provisioned value is limited to once per 24 hours; increasing is less restricted. Confirm the target before applying — don't plan on adjusting downward same-day.
- **Cost note**: Provisioned mode is billed per provisioned MiB/s regardless of actual usage, unlike Bursting.

### (B) StorageClass — no change needed

Throughput mode is a filesystem-level property, not a StorageClass parameter — `efs-sc` doesn't need editing for this.

### Verify

```bash
aws efs describe-file-systems --file-system-id fs-0246a526db0adaedf --region us-east-1 \
  --query 'FileSystems[0].{ThroughputMode:ThroughputMode,ProvisionedMibps:ProvisionedThroughputInMibps}'
```

---

## 3. FSx for OpenZFS (`openzfs-sc`)

### Current state (found via `kubectl get sc openzfs-sc -o yaml` + `aws fsx describe-volumes`/`describe-file-systems`)

| Field | Value |
|---|---|
| Filesystem | `fs-01bbc0b1019aac1c4` |
| `DeploymentType` | `SINGLE_AZ_1` (no NVMe read cache tier — that's Single-AZ 2 only) |
| `ThroughputCapacity` | **64 MBps** — below AWS's own recommended floor (≥128 MBps) for metadata-intensive workloads like Jenkins |
| Volume | `fsvol-0c2e3448e193c5192` (`cbci-controllers`), `AVAILABLE`, `StorageCapacityQuotaGiB: 1200` |
| Filesystem `StorageCapacity` | 1200 GiB |
| `DiskIopsConfiguration` | `Mode: AUTOMATIC`, `Iops: 3600` — i.e. the default 3 IOPS/GiB × 1200 GiB, an incidental byproduct of storage size, not a deliberately chosen number |
| `DataCompressionType` (actual, on the volume) | `LZ4` — correct, no change needed |
| **`NfsExports[].Options`** | `["rw", "crossmnt", "async", "no_root_squash"]` — **`async`**, not AWS's recommended `sync` |
| StorageClass `volumeBindingMode` | `Immediate` — flagged below as needing a fix, but not the fix originally proposed here (see note) |
| StorageClass `parameters.ParentVolumeId` / `DataCompressionType` | Values have **literal embedded quote characters** (`'"fsvol-..."'`, `'"LZ4"'`) — likely a manifest-authoring bug; doesn't affect the already-existing `cbci-controllers` volume, but would likely break the *next* dynamically-provisioned child volume |

### Target

| Field | Value | Priority |
|---|---|---|
| `ThroughputCapacity` | `128` MBps | Benchmark-validity fix |
| `DiskIopsConfiguration` | `Mode: USER_PROVISIONED`, `Iops: 3000` | Matches EBS's explicit 3,000 exactly, replacing an incidental Automatic value with a deliberate one — same rationale as pinning EBS's IOPS explicitly instead of relying on its default |
| NFS export `Options` | `sync` instead of `async` | **Higher priority than throughput** — this is a durability correctness issue, not just a benchmark-calibration one (see below) |
| Pod scheduling | `nodeAffinity`/`nodeSelector` on `topology.kubernetes.io/zone`, pinned to the FSx filesystem's actual AZ | See correction below — `volumeBindingMode` can't fix this for `ResourceType: volume` |
| StorageClass parameter quoting | Strip the embedded literal quotes | Prevents future PVC provisioning failures |

**Correction — `volumeBindingMode` is not the fix for the Single-AZ risk**: an
earlier version of this doc recommended switching `openzfs-sc` to
`WaitForFirstConsumer` to avoid cross-AZ mounts, matching what works for EBS.
That's wrong for this StorageClass specifically. `openzfs-sc` uses
`ResourceType: volume` — it provisions a *child volume* inside the
already-existing `fs-01bbc0b1019aac1c4` filesystem. The FSx OpenZFS CSI
driver's `CreateVolume` API (used for `ResourceType: volume`) has no
`SubnetIds`/AZ parameter at all — unlike `ResourceType: filesystem` (a
different provisioning mode this StorageClass doesn't use), which does take
one and *would* make `WaitForFirstConsumer` meaningful. Since the AZ was
already fixed when the filesystem itself was created, `volumeBindingMode` has
nothing to act on here. The real mitigation is scheduling-level: pin whatever
pod mounts this PVC to the same AZ as the filesystem's subnet.

```bash
# Find the FSx filesystem's actual AZ
aws fsx describe-file-systems --file-system-id fs-01bbc0b1019aac1c4 --region us-east-1 \
  --query 'FileSystems[0].SubnetIds' --output text | \
  xargs -I{} aws ec2 describe-subnets --subnet-ids {} --region us-east-1 \
  --query 'Subnets[0].AvailabilityZone' --output text
```

Then add a matching `nodeAffinity` requirement on the controller/`bench-agent`
pod spec for `topology.kubernetes.io/zone`.

**Why `sync` matters more than the throughput number**: `async` acknowledges
writes before they're committed to durable disk — a file-server crash can
lose Jenkins build records/config that were already reported as saved. This
is a real risk if this volume is ever used as a production controller's
`JENKINS_HOME`, not just a benchmark artifact. It's also a benchmark-fidelity
problem on its own: `async` writes look faster than they'd be under `sync`,
so any FSx numbers collected before this is fixed aren't durability-comparable
to EBS/EFS.

### (A) Fix the existing filesystem/volume now

**Throughput capacity and IOPS** (async, takes a few minutes — filesystem goes through an `UPDATING` lifecycle state). Combined into one call since both live under `OpenZFSConfiguration`:

```bash
aws fsx update-file-system --file-system-id fs-01bbc0b1019aac1c4 \
  --open-zfs-configuration ThroughputCapacity=128,DiskIopsConfiguration={Mode=USER_PROVISIONED,Iops=3000} \
  --region us-east-1
```

**NFS export mode → `sync`** (this replaces the entire `NfsExports` block, so keep the other options as-is):

```bash
aws fsx update-volume --volume-id fsvol-0c2e3448e193c5192 --region us-east-1 \
  --open-zfs-configuration '{
    "NfsExports": [{
      "ClientConfigurations": [{
        "Clients": "10.0.0.0/16",
        "Options": ["rw", "crossmnt", "sync", "no_root_squash"]
      }]
    }]
  }'
```

Check progress on either (both go through an admin-action lifecycle):

```bash
aws fsx describe-file-systems --file-system-id fs-01bbc0b1019aac1c4 --region us-east-1 \
  --query 'FileSystems[0].{Lifecycle:Lifecycle,AdministrativeActions:AdministrativeActions[].{Type:AdministrativeActionType,Status:Status}}'
```

### (B) Fix the StorageClass for future provisioning

`parameters`/`volumeBindingMode` are immutable on an existing StorageClass —
delete and recreate (safe: doesn't touch the already-`AVAILABLE`
`cbci-controllers` volume, only affects the next dynamically-provisioned
child volume against this class name):

```bash
kubectl delete sc openzfs-sc

cat <<'EOF' | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: openzfs-sc
parameters:
  DataCompressionType: LZ4
  ParentVolumeId: fsvol-0c2e3448e193c5192
  ResourceType: volume
provisioner: fsx.openzfs.csi.aws.com
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
EOF
```

### Verify

```bash
kubectl get sc openzfs-sc -o yaml
aws fsx describe-file-systems --file-system-id fs-01bbc0b1019aac1c4 --region us-east-1 \
  --query 'FileSystems[0].OpenZFSConfiguration.{ThroughputCapacity:ThroughputCapacity,DiskIops:DiskIopsConfiguration}'
aws fsx describe-volumes --volume-ids fsvol-0c2e3448e193c5192 --region us-east-1 \
  --query 'Volumes[0].OpenZFSConfiguration.NfsExports'
```

---

## Summary — before / after

| Backend | Before | After |
|---|---|---|
| EBS gp3 | 3,000 IOPS / 125 MB/s (implicit default) | 3,000 IOPS / **128 MB/s** (explicit) |
| EFS | Bursting, ~73 KiB/s baseline at current size | **Provisioned, 128 MiB/s** |
| FSx OpenZFS | 64 MBps, 3,600 IOPS (Automatic), `async` export, Single-AZ 1 | **128 MBps, 3,000 IOPS (User-provisioned), `sync` export**, Single-AZ 1 (unchanged — see caveat below) |

**Caveats carried forward, not fixed by any command above**:
- Single-AZ 1 has no NVMe read-cache tier (that's Single-AZ 2 only), so the
  earlier cold/warm-cache research findings for FSx apply less strongly here
  than they would on Single-AZ 2 — worth noting in any report so a smaller
  cold/warm delta on FSx isn't misread as "FSx doesn't benefit from caching,"
  when it's actually "this deployment tier has a smaller cache to begin
  with."
- Single-AZ deployment means this filesystem has no cross-AZ redundancy at
  all (unlike EFS, whose data is redundant across AZs regardless of mount
  target placement) — an outage in whichever AZ `fs-01bbc0b1019aac1c4` lives
  in takes the filesystem down entirely. This is a real availability gap
  relative to EFS, not just a benchmark-calibration detail, worth surfacing
  in any report alongside the throughput numbers.

None of the commands above have been run yet — this is the reviewable plan.
