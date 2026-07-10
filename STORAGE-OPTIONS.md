# Storage options for Jenkins (CloudBees CI) on EKS/AWS

Reference document: what the options actually are, how they differ, what we
found on the real cluster (`cbci-demo-cone`, us-east-1), and where our
configuration preference currently stands. Companion to
[STORAGE-CONFIGURATION.md](STORAGE-CONFIGURATION.md) (fixing the existing
cluster) and [STORAGE-SETUP-FRESH.md](STORAGE-SETUP-FRESH.md) (building a new
one) — this document is the "why," those two are the "how."

---

## 1. Benchmark cluster architecture

One EKS cluster hosts the entire comparison. All three storage backends are
tested from **the same namespace and the same dedicated node group** — this
is deliberate, not incidental: it holds compute, tenancy, and network
constant so storage backend is the *only* variable that differs between the
three controllers. Putting them on different node groups (different instance
types, different noisy-neighbor exposure) or different namespaces (different
resource quotas, network policies) would let a compute-side difference
masquerade as a storage-side one.

```
EKS Cluster
└── Dedicated Node Group (tainted — nothing else schedules here;
    spans multiple AZs for scheduling flexibility, see §6)
    │
    └── Namespace: <controllers namespace>  (single namespace, shared by all three)
        ├── Managed Controller 1 — PVC → StorageClass → EBS gp3
        ├── Managed Controller 2 — PVC → StorageClass → EFS
        └── Managed Controller 3 — PVC → StorageClass → FSx for OpenZFS
```

| | Controller 1 | Controller 2 | Controller 3 |
|---|---|---|---|
| Namespace | Same namespace as the other two | Same namespace as the other two | Same namespace as the other two |
| Node group | Same dedicated node group as the other two | Same dedicated node group as the other two | Same dedicated node group as the other two |
| Storage backend | EBS gp3 | EFS | FSx for OpenZFS |
| PVC → StorageClass | `storageClassName: <ebs-storage-class>` | `storageClassName: <efs-storage-class>` | `storageClassName: <fsx-storage-class>` |

**Why "same node group" doesn't mean "same node, or same AZ, per pod"**: a
node group is a pool that can span multiple AZs — each individual
controller's pod still lands on exactly one node, in exactly one AZ, at
schedule time. This matters most for the FSx-backed controller specifically,
since (per §6) its storage has a fixed AZ decided once at filesystem
creation — the shared-node-group design doesn't by itself guarantee that
controller lands in the matching AZ; that still needs the pod-level
`nodeAffinity` discussed in §6.

**A separate, second namespace** typically holds the ephemeral build-agent
pods (the workload that actually runs Jenkins builds) — kept apart from the
controllers namespace since agents are transient and don't need persistent
storage themselves, only the controllers do.

---

## 2. Problem statement

CloudBees CI managed controllers each need persistent storage for
`JENKINS_HOME` — the directory holding job configs, build history, console
logs, and plugin state. On EKS, three fundamentally different AWS storage
services can back that directory: **EBS**, **EFS**, and **FSx for OpenZFS**.
They are not three tiers of the same thing — they have different
architectures, different provisioning models, and different relationships to
Availability Zones, and a configuration choice that's correct for one is
sometimes actively wrong for another.

This document exists because auditing the current demo cluster
(`cbci-demo-cone`) found that none of the three backends were configured
**deliberately** — each was left at an incidental default or a
manifest-authoring mistake rather than a chosen target:

- EBS was running at AWS's free-tier defaults (3,000 IOPS / 125 MB/s), never explicitly set.
- EFS was in Bursting throughput mode on a nearly-empty filesystem, giving a real sustained throughput of ~73 KiB/s — effectively non-functional for any load test.
- FSx was provisioned below AWS's own recommended floor for this workload shape (64 MBps), with its NFS export set to `async` (a durability trade-off, not just a performance one), and its StorageClass had malformed parameter values.

The goal of this document is to make every one of these choices **explicit
and defensible** — a documented target, a documented reason, not an
inherited accident.

---

## 3. Why Jenkins' I/O pattern is the thing that actually matters

Before comparing the three backends, the workload shape matters more than
any single number. Per CloudBees' own guidance (from `docs.cloudbees.com`):
Jenkins controllers are **memory and disk IOPS bound** — performance is
dominated by a high volume of *small* reads/writes (build console logs, job
`config.xml`, queue/build-number state), not by large sequential transfers.

| Concept | What it measures | Why it matters here |
|---|---|---|
| **IOPS** | How many separate read/write operations per second | Dominant factor for Jenkins — small config/log writes are IOPS-shaped, not throughput-shaped |
| **Throughput (MB/s)** | How much total data moves per second | Matters for the few genuinely large/continuous writes Jenkins does — streamed console log output, archived artifacts |

A storage backend can look identical on a raw throughput benchmark and still
perform very differently under real Jenkins load, because the thing that
actually differs between backends (EBS's direct block access vs. EFS/FSx's
NFS round-trip per operation) shows up in IOPS/latency, not MB/s.

---

## 4. The three options — architecture comparison

| | EBS (gp3) | EFS | FSx for OpenZFS |
|---|---|---|---|
| **What it is** | Network-attached block device | Managed, regional NFSv4.1 share | Managed, single-tenant NFS share running real ZFS |
| **Attachment model** | Single-attach — one volume per controller, cannot be shared | Shareable across many controllers/pods simultaneously | Shareable across many controllers/pods simultaneously |
| **Provisioner (K8s)** | `ebs.csi.aws.com` | `efs.csi.aws.com` | `fsx.openzfs.csi.aws.com` |
| **How throughput is set** | `iops` + `throughput` params, decoupled from volume size | Filesystem-level `ThroughputMode` (Bursting/Provisioned/Elastic) | Filesystem-level `ThroughputCapacity`, discrete tiers |
| **How IOPS is set** | `iops` param, decoupled from throughput | **Not exposed at all** — no IOPS concept | `DiskIopsConfiguration` (Automatic = 3/GiB, or User-provisioned) |
| **AZ relationship** | Bound to one AZ per volume | Regional — data redundant across all AZs by design | Single-AZ tiers: one file server, one fixed AZ, no built-in cross-AZ redundancy (Multi-AZ tier available separately) |
| **Special capability** | None beyond standard block storage | Built-in cross-AZ durability at no extra config | Real ZFS: snapshots, compression, ARC/L2ARC caching, ACLs |
| **Structural risk unique to this backend** | None (well-understood, oldest/simplest model) | Bursting-mode throughput scales with filesystem size — a small/new filesystem can have a near-zero sustained baseline | `async` NFS export mode trades write durability for speed; Single-AZ has no AZ-outage protection |

---

## 5. Configuration cheat sheet

### 5.1 Target values (current preference — see §8)

| | EBS gp3 | EFS | FSx OpenZFS |
|---|---|---|---|
| Throughput | 128 MB/s (see open item in §8 re: 160) | 128 MiB/s (Provisioned mode) | 128 MBps (Single-AZ 1) or 160 MBps (Single-AZ 2) |
| IOPS | 3,000 (explicit) | N/A — not configurable | 3,000 (User-provisioned) |
| Durability setting | N/A (block device) | N/A (managed) | `sync` NFS export (not `async`) |
| AZ binding | `volumeBindingMode: WaitForFirstConsumer` | `volumeBindingMode: Immediate` | Pod-level `nodeAffinity` (binding mode has no effect for `ResourceType: volume`) |

### 5.2 StorageClass snippets

```yaml
# EBS gp3
parameters:
  type: gp3
  iops: "3000"
  throughput: "128"
volumeBindingMode: WaitForFirstConsumer
```

```yaml
# EFS
parameters:
  provisioningMode: efs-ap
  fileSystemId: <fs-id>      # must already be in Provisioned throughput mode
  directoryPerms: "700"
volumeBindingMode: Immediate
mountOptions: [nfsvers=4.1, rsize=32768, wsize=32768]
```

```yaml
# FSx OpenZFS
parameters:
  ParentVolumeId: <volume-id>      # no embedded quotes — see §7.4
  DataCompressionType: LZ4          # no embedded quotes
  ResourceType: volume
volumeBindingMode: WaitForFirstConsumer   # harmless but doesn't solve AZ — see §6
mountOptions: [nfsvers=4.1, rsize=1048576, wsize=1048576, nconnect=16]
```

### 5.3 Verification commands

```bash
# Node/AZ topology
kubectl get nodes -L topology.kubernetes.io/zone
kubectl get pods -n cloudbees-core -o wide

# EBS
kubectl get sc ebs-gp3-sc -o yaml
aws ec2 describe-volumes --volume-ids <vol-id> --region us-east-1 \
  --query 'Volumes[0].{Type:VolumeType,IOPS:Iops,Throughput:Throughput,State:State}'

# EFS
kubectl get sc efs-sc -o yaml
aws efs describe-file-systems --file-system-id <fs-id> --region us-east-1 \
  --query 'FileSystems[0].{ThroughputMode:ThroughputMode,ProvisionedMibps:ProvisionedThroughputInMibps}'

# FSx
kubectl get sc openzfs-sc -o yaml
aws fsx describe-file-systems --file-system-id <fs-id> --region us-east-1 \
  --query 'FileSystems[0].{DeploymentType:OpenZFSConfiguration.DeploymentType,ThroughputMBps:OpenZFSConfiguration.ThroughputCapacity,DiskIops:OpenZFSConfiguration.DiskIopsConfiguration,SubnetIds:SubnetIds}'
aws fsx describe-volumes --volume-ids <volume-id> --region us-east-1 \
  --query 'Volumes[0].OpenZFSConfiguration.NfsExports'
```

---

## 6. Availability Zone model — mental map

A pod lives in exactly one AZ; a node group can span several. The three
backends relate to that fact completely differently:

```
AWS Region (us-east-1)
└── EKS Cluster
    └── Node Group (spans multiple AZs, e.g. us-east-1a + us-east-1b)
        └── Pod — lands on ONE node → inherits ONE AZ, until rescheduled
```

| Backend | When is the AZ decided? | What connects it to the pod's AZ? |
|---|---|---|
| EBS | Fresh, at PV-creation time, per PVC | `volumeBindingMode: WaitForFirstConsumer` — CSI driver matches the new volume's AZ to the scheduled pod's node automatically |
| EFS | Never — Regional, redundant across all AZs | Nothing needed; mount targets per AZ are just network doors into the same data |
| FSx (`ResourceType: volume`) | **Once, permanently, when the filesystem was created** — before any PVC exists | Nothing at the storage layer (`CreateVolume` has no AZ parameter for child volumes) — must use pod-level `nodeAffinity` instead |

**Rule of thumb**: `volumeBindingMode` only matters when the provisioner makes
a *fresh* AZ decision per-PVC. EBS always does; EFS never needs to; FSx's
`volume` resource type looks like it should but the decision already
happened one layer up.

### 6.1 If Multi-AZ FSx is required

Multi-AZ isn't "Single-AZ with a flag" — it's two full file servers in two
AZs, kept in sync, behind one floating endpoint:

```
   AZ-A (Preferred/Active)                AZ-B (Standby)
 ┌────────────────────────┐          ┌────────────────────────┐
 │   FSx file server       │◄────────►│   FSx file server       │
 │   (serving traffic)     │  sync    │   (mirrored copy)       │
 └───────────┬─────────────┘  repl.   └───────────┬─────────────┘
             └──────────────┬──────────────────────┘
                   One floating DNS endpoint (AWS re-points
                   it during failover — client config never changes)
```

Extra parameters required: two `SubnetIds` (not one), `PreferredSubnetId`,
`RouteTableIds`, `EndpointIpAddressRange`. Security groups must allow NFS
from both AZs.

**Pod-affinity guidance flips for Multi-AZ**: a *hard* `nodeAffinity` (right
for Single-AZ) becomes actively harmful here — if the preferred AZ fails over
and the pod is hard-pinned there, the pod can't be rescheduled anywhere. Use
a *soft* preference instead:

```yaml
affinity:
  nodeAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        preference:
          matchExpressions:
            - key: topology.kubernetes.io/zone
              operator: In
              values: ["<preferred-az>"]
```

**Cannot convert in place** — `DeploymentType` is immutable; moving an
existing Single-AZ filesystem to Multi-AZ means creating a new filesystem
(restore from backup or on-demand replication), not an update call.

---

## 7. Durability details

### 7.1 Sync vs. async (FSx NFS export)

| | `sync` | `async` |
|---|---|---|
| When does the server acknowledge a write? | Only after it's committed to durable disk | As soon as it's received, before disk commit |
| Speed | Slower — waits for the full disk round trip | Faster — skips that wait |
| Risk | None beyond normal disk failure risk | **Acknowledged writes can be lost if the server crashes before flushing to disk** — the client (Jenkins) believes the write succeeded when it may not have |

**Recommendation: `sync`.** `JENKINS_HOME` writes are build records and
config Jenkins treats as durably saved the moment it's acknowledged — `async`
breaks that assumption silently, with no error surfaced anywhere. This is
found already misconfigured on the demo cluster (`async` in
`fs-01bbc0b1019aac1c4`'s `NfsExports`) — see §8 and
[STORAGE-CONFIGURATION.md](STORAGE-CONFIGURATION.md) for the fix command.

### 7.2 Single-AZ vs Multi-AZ (FSx) — see §6.1 for the architecture

| | Single-AZ | Multi-AZ |
|---|---|---|
| Performance | Same as Multi-AZ, at equivalent tier | Same as Single-AZ 2, at equivalent tier |
| AZ-outage protection | None | Automatic failover to standby in a second AZ |
| Write latency | Direct, no replication step | Every write waits for synchronous cross-AZ replication acknowledgment |
| Relative cost | Lower — one file server | Higher — pays for a standing standby |

This is a **business risk decision, not a technical default** — see §8.3.

### 7.3 Single-AZ 1 vs Single-AZ 2 (FSx)

Two different hardware generations, not a config toggle:

| | Single-AZ 1 | Single-AZ 2 |
|---|---|---|
| Cache | In-memory ARC only | In-memory ARC **+ NVMe L2ARC** (up to 2.5 TB) |
| Valid throughput tiers (MBps) | 64, 128, 256, 512, 1024, 2048, 3072, 4096 | 160, 320, 640, 1280, 2560, 3840, 5120, 7680, 10240 |
| Max disk IOPS at smallest tier | 2,500 baseline / 40,000 burst (64 MBps) | 6,250 baseline / 100,000 burst (160 MBps) |
| Memory at ~128/160 MBps tier | 4 GB | 16 GB + 80 GB NVMe |

**Important**: Single-AZ 2 has no 128 MBps tier — its floor is 160 MBps. This
is an open item — see §8.1.

### 7.4 The StorageClass quoting bug (FSx)

Found on the demo cluster's `openzfs-sc`: parameter values with **literal
embedded quote characters** — `ParentVolumeId: '"fsvol-..."'` and
`DataCompressionType: '"LZ4"'` instead of unquoted strings. Doesn't affect
already-provisioned volumes, but would likely break the next dynamically
provisioned one. Fix: recreate the StorageClass with correctly-unquoted
values (see [STORAGE-CONFIGURATION.md](STORAGE-CONFIGURATION.md) §4(B)).

---

## 8. Current preference and open decisions

### 8.1 Throughput target — leaning toward 160 MB/s, not yet confirmed

Original target was 128 MB/s (AWS's documented floor for metadata-intensive
workloads, and close to EBS's free-tier default). But Single-AZ 2 FSx has no
128 MBps tier — its floor is 160. **Current preference: raise the shared
target to 160 MB/s across all three backends** (EBS `throughput=160`, EFS
Provisioned at 160 MiB/s, FSx Single-AZ 2 at its 160 MBps floor) so all three
land on one real, valid number instead of approximating it for one of them.
**Not yet applied to STORAGE-CONFIGURATION.md/STORAGE-SETUP-FRESH.md — those
still say 128 pending confirmation.**

### 8.2 FSx deployment tier — Single-AZ 2 preferred, given the NVMe cache

Single-AZ 2's added disk-IOPS ceiling and NVMe L2ARC cache tier make it the
better choice for a benchmark that specifically cares about cold/warm cache
behavior (see the earlier research on Jenkins' cache-sensitivity). Requires
adopting the 160 MB/s target above, since Single-AZ 2 can't do 128.

### 8.3 Single-AZ vs Multi-AZ — open, needs Capital One risk/business sign-off

**Not a technical decision — do not default this without stakeholder input.**
Key considerations already surfaced:
- A CJOC outage is an internal engineering-productivity impact, not
  customer-facing — likely (not confirmed) a lower criticality tier than
  production banking systems, but that classification is Capital One's call.
- Multi-AZ costs ~2x FSx compute and adds a permanent write-latency tax, in
  exchange for automatic AZ-outage failover.
- **EFS gets equivalent cross-AZ durability for free, by default, with no
  configuration or cost premium** — if AZ-outage protection turns out to be a
  hard requirement, that's a real point in EFS's favor independent of raw
  throughput results, and worth weighing before assuming FSx Multi-AZ is the
  only path to that guarantee.
- Fallback middle ground if forced to choose without further input:
  **Single-AZ + automated backups + a tested restore runbook** — bounds an
  AZ outage's impact to "time to restore from backup" rather than
  unrecoverable loss, without Multi-AZ's ongoing cost/latency cost.

**Action needed**: get RTO/RPO requirements and criticality classification
for CJOC from Capital One's risk/operational-resilience owners before
finalizing this axis.

### 8.4 Durability (sync/async) and StorageClass correctness — resolved

Both `sync` (not `async`) and fixing the StorageClass quoting bug are
straightforward corrections with no real tradeoff against them — applied in
[STORAGE-CONFIGURATION.md](STORAGE-CONFIGURATION.md).

### 8.5 IOPS — resolved

3,000 explicit on both EBS and FSx (User-provisioned), matching rather than
leaving FSx on its incidental `Automatic` value. EFS has no equivalent
setting — not an oversight, the platform doesn't expose one.

---

## 9. Summary table — decided vs. open

| Item | Status | Current value/preference |
|---|---|---|
| EBS IOPS/throughput explicit | Decided | 3,000 IOPS, target MB/s per §8.1 |
| EFS throughput mode | Decided | Provisioned, target MiB/s per §8.1 |
| FSx IOPS | Decided | 3,000 (User-provisioned) |
| FSx NFS export mode | Decided | `sync` |
| FSx StorageClass quoting | Decided | Fix, unquoted values |
| EBS/FSx AZ binding mechanism | Decided | `WaitForFirstConsumer` (EBS) / pod `nodeAffinity` (FSx) |
| Shared throughput target number | **Open** | Leaning 160 MB/s, not yet applied to other docs |
| FSx deployment tier (SAZ1 vs SAZ2) | **Open** | Leaning SAZ2, contingent on above |
| Single-AZ vs Multi-AZ FSx | **Open — needs Capital One business input** | Default-if-forced: Single-AZ + backups + runbook |

---

## 10. Durability and cache — why both matter as much as storage class

Two dimensions can move the numbers as much as, or more than, which storage
backend is under test. Both need to be controlled and reported deliberately,
not left to whatever a given run happens to land on.

### 10.1 Pipeline durability level — a native Jenkins setting, not custom to this benchmark

Jenkins Pipeline has three native durability tiers (Manage Jenkins → System →
"Pipeline Speed/Durability Settings," globally, or overridden per-job/folder
via the `durabilityHint` property):

| Tier | Behavior | Native default? |
|---|---|---|
| `MAX_SURVIVABILITY` | Writes state to disk after every step, atomically — slowest, safest | **Yes** — Jenkins' out-of-the-box default if nothing is configured |
| `SURVIVABLE_NONATOMIC` | Still writes after every step, but skips atomic (rename-based) writes | Opt-in |
| `PERFORMANCE_OPTIMIZED` | Minimal disk writes — fastest, but can lose in-progress state if the controller isn't shut down gracefully | Opt-in |

This benchmark's two Jenkinsfiles currently only exercise the two extremes.
That's a real gap: Jenkins' own documentation states `SURVIVABLE_NONATOMIC` is
faster than max durability **"especially on networked filesystems"** —
directly relevant to the EFS/FSx comparison this project is built around, and
worth adding as a third variant rather than treating durability as binary.

### 10.2 Build cache state (cold vs. warm) — a Jenkins/build-level setting, not a storage one

`COLD_CACHE` is this benchmark's own job parameter, but the concept behind it
is a standard build-pipeline concern, not something specific to storage
comparison:

| | Cold cache | Warm cache |
|---|---|---|
| Workspace | Wiped before the build starts | Reused from the previous build on that node |
| Local Maven repository (`.m2`) | Wiped — every dependency is re-downloaded | Reused — dependencies already present, only changed modules rebuild |
| What gets measured | Full dependency download + full compile + full test run | Incremental build — a much smaller amount of real work |

This matters for a storage-backend comparison specifically because **cold
and warm builds have a fundamentally different I/O and duration profile,
independent of which storage class is under test**. A cold build downloads
and writes far more data than a warm one. If `COLD_CACHE` isn't held
constant across the runs being compared, a difference in build time can be
misread as a storage-class effect when it's actually just cache-state noise
from one run happening to be cold and another warm.

**Practical rule**: sweep `COLD_CACHE` across `true`/`false` deliberately (as
already called out in the README's test matrix), and only ever compare
cold-vs-cold or warm-vs-warm across storage backends — never a cold run on
one backend against a warm run on another.
