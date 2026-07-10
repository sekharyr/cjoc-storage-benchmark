# Doc updates for "Storage Options for Jenkins (CloudBees CI) on EKS/AWS"

Changelist generated after reconciling the document against the live demo cluster
`cbci-demo-cone` (us-east-1, account 324005994172) on 2026-07-09. Each item cites the
section to edit, the current text, the replacement, and why. Apply with track-changes.

Decisions locked in during this pass: **128 MB/s** shared throughput target; **FSx stays
Single-AZ 1** (in-place, not recreated as SAZ2); **IOPS 3,600** across EBS and FSx;
**EFS 4.1 / FSx 4.2** NFS versions (mismatch accepted).

---

## A. Factual corrections forced by AWS reality (these are wrong as written)

### A1. FSx IOPS target: 3,000 → 3,600  (§5.1, §8.5, §9)
FSx for OpenZFS enforces a **minimum of 3 IOPS/GiB** of filesystem storage. The demo
filesystem is 1,200 GiB, so its floor is **3,600 IOPS** — `UpdateFileSystem` with 3,000
is rejected (`BadRequest: Disk IOPS (3000) are incompatible with file system storage
capacity (1200 GiB)`). The document's "3,000 (User-provisioned)" is **infeasible at this
filesystem size**.

- **§5.1 Target Values table** — FSx OpenZFS IOPS: `3,000 (User-provisioned)` →
  `3,600 (User-provisioned — = 3 IOPS/GiB floor at 1,200 GiB; scales with storage size)`.
- **§5.1** — EBS IOPS: `3,000 (explicit)` → `3,600 (explicit)` so EBS and FSx hold IOPS
  equal (see A2).
- **§8.5 (IOPS — Resolved)** — replace "3,000 explicit on both EBS and FSx" with:
  "3,600 explicit on both EBS and FSx. FSx cannot go below 3 IOPS/GiB (3,600 at 1,200 GiB),
  so 3,600 is FSx's floor at this size, not a free choice; EBS gp3 is raised from its 3,000
  default to 3,600 to match. EFS has no equivalent setting — a platform limitation."
- **§9 summary table** — "EBS IOPS/throughput explicit" and "FSx IOPS" rows: 3,000 → 3,600.

### A2. IOPS parity note (new sentence, §8.5 or §13)
Add: "IOPS is held equal across EBS and FSx at 3,600. This is only possible because EBS gp3
allows arbitrary IOPS up to 16,000; FSx's value is dictated by storage size. If the FSx
filesystem is ever resized, its IOPS floor moves and EBS must be re-matched."

### A3. EFS transfer size: rsize/wsize 32768 → 1048576  (§5.2)
The document's EFS StorageClass uses `rsize=32768, wsize=32768` — 32× smaller than the FSx
block (1 MiB). That is a configuration handicap that would understate EFS throughput and
masquerade as a backend difference (the exact trap §1/§3 warn against). AWS EFS guidance is
1 MiB, and the EFS CSI mount helper already negotiates 1 MiB by default (verified in-pod).

- **§5.2 EFS mountOptions** — `mountOptions: [nfsvers=4.1, rsize=32768, wsize=32768]` →
  `mountOptions: [nfsvers=4.1, rsize=1048576, wsize=1048576]`.
- Add one line: "Read/write transfer size (1 MiB) is held equal across both NFS backends so
  it cannot be mistaken for a storage-backend difference."

---

## B. Open items now resolved by decision

### B1. Shared throughput target: OPEN(leaning 160) → RESOLVED 128 MB/s  (§8.1, §9)
- **§8.1** — retitle from "Leaning Toward 160 MB/s, Not Yet Confirmed" to
  "Resolved: 128 MB/s across all three." Body: "128 MB/s is used across EBS (throughput=128),
  EFS (Provisioned 128 MiB/s), and FSx (Single-AZ 1 @ 128 MBps). 160 MB/s was considered
  because FSx Single-AZ **2** has no 128 tier (floor 160), but Single-AZ 2 was declined
  (see §8.2), so the 128 target holds for all three."
- **§9 summary** — "Shared throughput target number": `Open — Leaning 160` → `Decided — 128 MB/s`.

### B2. FSx deployment tier: OPEN(leaning SAZ2) → RESOLVED Single-AZ 1  (§8.2, §9)
The document leaned Single-AZ 2 for its NVMe L2ARC. Decision went the other way: **stay on
Single-AZ 1** and bump it in place (64 → 128 MBps, IOPS → 3,600 user-provisioned), because
SAZ1→SAZ2 **cannot be converted in place** — it requires destroying and recreating the
filesystem and migrating JENKINS_HOME. The in-place SAZ1 change is non-destructive and works
at the 128 target.

- **§8.2** — retitle to "Resolved: Single-AZ 1, bumped in place." Keep the SAZ2/NVMe-cache
  discussion as a documented future option, but state the decision and its reason
  (non-destructive, no data migration, 128 target achievable on SAZ1).
- **§9 summary** — "FSx deployment tier (SAZ1 vs SAZ2)": `Open — Leaning SAZ2` →
  `Decided — Single-AZ 1 (in-place bump; SAZ2 needs fs recreate)`.
- **Consequence for §7.3 / §8.2 cache discussion**: because SAZ1 has ARC (in-memory) but no
  NVMe L2ARC, the "warm cache advantage" is smaller than SAZ2 — but ARC still exists and is
  still not controlled by COLD_CACHE (see C2).

### B3. Durability (sync) — confirm applied  (§7.1, §9)
Already "Decided" in the doc; note it was found **live as `async`** on the demo cluster's
parent volume and corrected to `sync` on both the mounted and parent FSx volumes. No text
change needed beyond optionally noting "verified/corrected on the demo cluster."

---

## C. New "Confounds: residual (accepted, not eliminated)" subsection
Recommend adding under §1 or as a new §13.x. These are real differences we chose to accept;
listing them is what makes the benchmark defensible.

### C1. NFS version mismatch: EFS 4.1 vs FSx 4.2
EFS supports only NFS v4.0/4.1 (4.1 is its ceiling); FSx OpenZFS negotiates 4.2 by default.
We kept EFS at 4.1 and FSx at 4.2 (parity was only possible by *downgrading* FSx to 4.1,
which was declined). Impact is low: the 4.1→4.2 additions (server-side copy, sparse files,
IO_ADVISE) are throughput/bulk features that don't touch Jenkins' small-file metadata path;
the material jump (sessions) was 4.0→4.1, which both have. Documented, accepted.
> Note: `nconnect=16` (16 parallel TCP connections, applied to FSx) is a mount option
> available on both versions — not a version feature.

### C2. Storage-side cache (ARC) is not controlled by COLD_CACHE  (relates to §10.2)
COLD_CACHE wipes the Jenkins workspace/.m2 but does **not** evict the FSx ARC. FSx serves
repeat reads from a warm cache EBS/EFS don't have. Even on Single-AZ 1 (ARC, no L2ARC) this
is a residual read-side advantage for FSx. Mitigate by using a working set larger than ARC,
or lean on write/metadata paths for the cross-backend claim; report FSx read numbers as
cache-advantaged.

### C3. CloudWatch IOPS is not directly comparable across backends  (relates to §13)
EBS `VolumeReadOps` (block ops), EFS `MetadataIOBytes`/`DataReadIOBytes`, and FSx
`MetadataOperations` count physically different "operations." Compare CloudWatch IOPS only
*within* a backend across configs; for cross-backend ranking use delivered outcomes (build
wall-clock, ops completed, error rate).

### C4. Node group not dedicated/tainted; controllers can co-locate  (contradicts §1)
The demo cluster runs one shared, **untainted** `application` node group (not the dedicated,
tainted group §1 describes). Both NFS controllers were initially co-located on one m5.xlarge,
sharing that node's network bandwidth (EFS + FSx both use the network path; EBS uses separate
Nitro EBS bandwidth). m5.xlarge network baseline (~1.25 Gbps ≈ 156 MB/s) is close to the
128 MB/s-per-backend target, so co-location can network-cap the NFS backends. For clean runs:
dedicate/taint the node group, or ensure the three controllers don't share a node, or run
sequentially. State execution order (concurrent vs sequential) explicitly.

---

## D. FSx AZ placement — reinforce §6 with the demo finding
The demo FSx filesystem is Single-AZ in **us-east-1a**, but `mc-fsx3` had **no** zone
nodeAffinity — it was on a 1a node by luck. A reschedule to 1b would break/cross-AZ the mount.
Fixed by adding `requiredDuringSchedulingIgnoredDuringExecution` for
`topology.kubernetes.io/zone In [us-east-1a]`.
- **§6** — add: "Observed live: the FSx controller had no zone affinity and was correctly
  placed only by chance; the required pod nodeAffinity is mandatory, not optional."
- **Durability caveat**: this affinity was applied via `kubectl patch` on the Deployment.
  CloudBees CI provisions controllers from CJOC, so the affinity must also be set in the
  controller's provisioning YAML in CJOC, or it will be lost on the next re-provision.

---

## E. Minor exactness
- **§8.1** — "close to EBS's free-tier default" — gp3's throughput baseline is **125 MB/s**,
  not 128. Reword to "just above gp3's 125 MB/s default."
- **§13 results template** — label the build-eviction (.nfs lock) metric **NFS-only**; it is
  structurally N/A for EBS (no NFS locks), so EBS's clean score isn't misread as a win.

---

## Applied-to-cluster summary (for reference / an appendix)
| Backend | IOPS | Throughput | Durability | NFS ver | Mount opts | AZ |
|---|---|---|---|---|---|---|
| EBS gp3 (mc-ebs) | 3,600 | 128 MB/s | block | — | — | WaitForFirstConsumer |
| EFS (mc-efs) | n/a | 128 MiB/s Provisioned | regional | 4.1 | rsize/wsize=1M | Immediate (regional) |
| FSx OpenZFS SAZ1 (mc-fsx3) | 3,600 (user-prov) | 128 MB/s | sync | 4.2 | rsize/wsize=1M, nconnect=16 | pinned us-east-1a |

StorageClasses recreated with explicit params.

> **CORRECTION (supersedes earlier note): FSx SC values MUST be JSON-quoted.** The FSx for
> OpenZFS CSI driver parses `ParentVolumeId`/`DataCompressionType` as JSON, so they must be
> `'"fsvol-…"'` and `'"LZ4"'` (quotes included). An earlier pass here (and doc §7.4)
> incorrectly called the quoted form a "bug" and unquoted them — that BREAKS the next dynamic
> provision (`invalid character 'L' looking for beginning of value`). Verified empirically on
> driver v1.2.0: quoted → provisions; bare → fails. `ResourceType: volume` stays bare.
> The live `openzfs-sc` has been restored to the JSON-quoted form.

**FSx IAM (Pod Identity):** `AmazonFSxFullAccess` replaced with a least-privilege inline policy
(`DescribeFileSystems, DescribeVolumes, CreateVolume, DeleteVolume, UpdateVolume, TagResource,
UntagResource, ListTagsForResource`) — create + delete verified end-to-end. `ListTagsForResource`
is required by the delete path.
