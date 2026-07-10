# Fresh EKS storage setup for CJOC + 3 managed controllers

Companion to [STORAGE-CONFIGURATION.md](STORAGE-CONFIGURATION.md), which
documents *fixing* the existing `cbci-demo-cone` cluster's storage. This
document is the from-scratch version: what to provision, in what order, on a
brand-new EKS cluster so it starts at the target state instead of needing
remediation later. Same target: **128 MB/s matched throughput ceiling across
EBS gp3 / EFS / FSx OpenZFS**, plus every correctness lesson found while
auditing the demo cluster (durable NFS export mode, correct AZ-binding
behavior, no manifest-quoting bugs) applied from the start.

---

## 0. Prerequisites — once per cluster

**Dedicated node group.** Put the 3 controllers and the `bench-agent` pool on
tainted nodes nothing else can schedule onto — otherwise noisy neighbors on
shared nodes show up as a false storage-class difference. (Already called out
in the main README; repeated here because it belongs at the start of a fresh
build, not as an afterthought.)

**Networking**, needed before EFS/FSx exist:
- Identify the VPC/subnets the EKS node group runs in (one subnet per AZ you
  want mount targets/file servers in).
- A security group allowing inbound **NFS (port 2049)** from the node
  group's/pod's security group or CIDR — both EFS and FSx OpenZFS speak NFS.

```bash
aws ec2 create-security-group --group-name cbci-storage-nfs \
  --description "NFS access for CBCI JENKINS_HOME (EFS/FSx)" \
  --vpc-id <vpc-id> --region us-east-1

aws ec2 authorize-security-group-ingress --group-id <sg-id> \
  --protocol tcp --port 2049 --cidr 10.0.0.0/16 --region us-east-1
```

(Scope the CIDR to the actual node/pod CIDR range — `10.0.0.0/16` here matches
what the demo cluster's existing FSx `NfsExports` client config already
uses, per the audit.)

**CSI drivers**, installed once as EKS add-ons, each with an IRSA role
(scoped to least privilege — `AmazonEBSCSIDriverPolicy` /
`AmazonEFSCSIDriverPolicy` / FSx equivalent, not broader):

```bash
aws eks create-addon --cluster-name <cluster> --addon-name aws-ebs-csi-driver \
  --service-account-role-arn <ebs-csi-irsa-role-arn> --region us-east-1

aws eks create-addon --cluster-name <cluster> --addon-name aws-efs-csi-driver \
  --service-account-role-arn <efs-csi-irsa-role-arn> --region us-east-1

aws eks create-addon --cluster-name <cluster> --addon-name aws-fsx-openzfs-csi-driver \
  --service-account-role-arn <fsx-csi-irsa-role-arn> --region us-east-1
```

---

## 1. EBS gp3

No filesystem to pre-create — EBS volumes are provisioned dynamically, one
per PVC, straight from the StorageClass. This is the only one of the three
where "fresh setup" is just the StorageClass itself.

```bash
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
  encrypted: "true"
provisioner: ebs.csi.aws.com
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
EOF
```

Notes vs. the demo cluster's config:
- `iops`/`throughput` set explicitly from the start, not left to defaults.
- `encrypted: "true"` added — the demo cluster's `ebs-gp3-sc` didn't set this; CloudBees' own EKS reference architecture recommends encryption at rest, cheap to bake in now rather than retrofit onto a running volume later (encryption status is immutable per-volume once created).
- `allowVolumeExpansion: true` added — lets a controller's PVC be resized later without a full recreate, useful given `JENKINS_HOME` only grows.
- `WaitForFirstConsumer` from day one (the demo cluster already got this right).

---

## 2. EFS

Unlike EBS, the filesystem itself must exist before the StorageClass can
reference it.

### Create the filesystem — Provisioned mode from the start

```bash
aws efs create-file-system \
  --creation-token cbci-jenkins-home-$(date -u +%Y%m%d) \
  --throughput-mode provisioned --provisioned-throughput-in-mibps 128 \
  --encrypted \
  --performance-mode generalPurpose \
  --tags Key=Name,Value=cbci-jenkins-home \
  --region us-east-1
```

- `--performance-mode generalPurpose`, not `maxIO` — `maxIO` trades higher
  per-operation latency for higher aggregate parallel throughput, which is
  backwards for Jenkins' latency-sensitive small-op pattern (see the earlier
  research: CloudBees' own guidance ties Jenkins performance to per-op
  latency, not aggregate parallelism).
- `--throughput-mode provisioned` set at creation — never starts in Bursting
  mode at all, avoiding the exact trap found on the demo cluster (a nearly
  empty filesystem stuck at a ~73 KiB/s Bursting baseline).

### Mount targets — one per AZ the node group uses

```bash
aws efs create-mount-target --file-system-id <fs-id> \
  --subnet-id <subnet-id-az1> --security-groups <sg-id> --region us-east-1
aws efs create-mount-target --file-system-id <fs-id> \
  --subnet-id <subnet-id-az2> --security-groups <sg-id> --region us-east-1
```

### StorageClass

```bash
cat <<EOF | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: efs-sc
parameters:
  provisioningMode: efs-ap
  fileSystemId: <fs-id>
  directoryPerms: "700"
provisioner: efs.csi.aws.com
reclaimPolicy: Delete
volumeBindingMode: Immediate
mountOptions:
  - nfsvers=4.1
  - rsize=32768
  - wsize=32768
EOF
```

- `mountOptions` added explicitly — the demo cluster's `efs-sc` had none set,
  relying on client defaults. CloudBees' NFS Guide recommends NFS v4.1+ and
  `rsize=32768,wsize=32768` specifically because Jenkins does high-volume
  small reads/writes (build logs) — baking this in at StorageClass creation
  means every dynamically-provisioned access point inherits it automatically.
- `volumeBindingMode: Immediate` is correct here (EFS mount targets exist in
  every AZ) — this is the one backend where `Immediate` isn't a risk, unlike
  FSx Single-AZ below.

---

## 3. FSx for OpenZFS

Also needs the filesystem (and its root volume) created before the
StorageClass can reference it.

### Create the filesystem

```bash
aws fsx create-file-system \
  --file-system-type OPENZFS \
  --storage-capacity 1200 \
  --storage-type SSD \
  --subnet-ids <subnet-id-az1> \
  --security-group-ids <sg-id> \
  --open-zfs-configuration '{
    "DeploymentType": "SINGLE_AZ_2",
    "ThroughputCapacity": 128,
    "DiskIopsConfiguration": {
      "Mode": "USER_PROVISIONED",
      "Iops": 3000
    },
    "RootVolumeConfiguration": {
      "DataCompressionType": "LZ4",
      "NfsExports": [{
        "ClientConfigurations": [{
          "Clients": "10.0.0.0/16",
          "Options": ["rw", "crossmnt", "sync", "no_root_squash"]
        }]
      }],
      "RecordSizeKiB": 128
    }
  }' \
  --tags Key=Name,Value=cbci-jenkins-home \
  --region us-east-1
```

Deliberate differences from what the demo cluster ended up with:
- **`SINGLE_AZ_2`, not `SINGLE_AZ_1`** — per AWS's own performance docs,
  Single-AZ 2 offers double the performance scalability plus an NVMe read
  cache (up to 2.5 TB) that Single-AZ 1 doesn't have at all. For a workload
  where cold/warm cache state is a first-class thing you want to measure (see
  the earlier benchmark-methodology discussion), starting on the tier that
  actually has a meaningful cache is the right default — Single-AZ 1 would
  understate what FSx is capable of.
- **`ThroughputCapacity: 128`** from creation — not the demo cluster's 64,
  which sits below AWS's own recommended floor for metadata-intensive
  workloads.
- **`DiskIopsConfiguration` set explicitly to `USER_PROVISIONED`/`3000`** —
  FSx OpenZFS decouples IOPS from throughput the same way EBS gp3 does, via
  this separate field. Left on the default `AUTOMATIC` mode, IOPS becomes an
  incidental byproduct of `StorageCapacity` (3 IOPS per GB) rather than a
  deliberate number — the demo cluster's filesystem ended up at 3,600 IOPS
  this way, purely because it happened to be sized at 1,200 GiB. Setting it
  explicitly to `3000` matches EBS's explicit IOPS value exactly instead of
  leaving two backends' IOPS numbers to coincidence.
- **`"sync"` in `NfsExports.Options` from the start** — never provisioned
  with `async` at all, avoiding the durability trade-off found on the demo
  cluster entirely rather than fixing it after the fact.
- `StorageCapacity: 1200` GiB chosen to match the demo cluster's existing
  volume quota (`StorageCapacityQuotaGiB: 1200`) for continuity — adjust to
  actual real sizing needs.

### Get the root volume ID (needed for the StorageClass's `ParentVolumeId`)

```bash
aws fsx describe-volumes --region us-east-1 \
  --filters Name=file-system-id,Values=<fs-id> \
  --query 'Volumes[?Name==`root`].VolumeId' --output text
```

### StorageClass

```bash
cat <<EOF | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: openzfs-sc
parameters:
  DataCompressionType: LZ4
  ParentVolumeId: <root-volume-id>
  ResourceType: volume
provisioner: fsx.openzfs.csi.aws.com
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
mountOptions:
  - nfsvers=4.1
  - rsize=1048576
  - wsize=1048576
  - nconnect=16
EOF
```

- **No quoted-string values** (`DataCompressionType: LZ4`, not `'"LZ4"'`) —
  this is the exact manifest-authoring bug found on the demo cluster's
  `openzfs-sc`; writing the YAML by hand here instead of through whatever
  templating produced the buggy one avoids reintroducing it.
- **AZ placement is handled by pod scheduling, not `volumeBindingMode`** —
  this StorageClass uses `ResourceType: volume` (a child volume inside the
  already-existing filesystem created above), and the FSx OpenZFS CSI
  driver's `CreateVolume` API has no subnet/AZ parameter at all for that
  resource type (unlike `ResourceType: filesystem`, which does, and where
  `WaitForFirstConsumer` would actually matter). The filesystem's AZ was
  already fixed by `--subnet-ids <subnet-id-az1>` in the `create-file-system`
  call above — no StorageClass setting can change that after the fact. Pin
  the controller pods that mount this PVC to that same AZ via
  `nodeAffinity`/`nodeSelector` on `topology.kubernetes.io/zone` instead.
- `mountOptions` set to FSx's own documented recommendations (`rsize`/`wsize`
  of 1 MiB, larger than EFS's 32 KiB — FSx supports bigger I/O sizes at this
  throughput tier) plus `nconnect=16` for multiplexed TCP connections.

---

## 4. Per-controller PVCs

Same shape for all three backends — one PVC per controller, referencing
whichever StorageClass that controller's JENKINS_HOME should use:

```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-home-ebs
  namespace: cloudbees-core
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: ebs-gp3-sc
  resources:
    requests:
      storage: 100Gi
EOF
```

Swap `storageClassName`/`accessModes` per backend — EFS/FSx PVCs typically
use `ReadWriteMany` since the underlying filesystem supports concurrent
mounts, even though this topology only mounts each one from a single
controller.

**For the FSx-backed controller specifically**, add `nodeAffinity` pinning it
to the FSx filesystem's AZ (found via the `describe-file-systems` +
`describe-subnets` lookup in section 3) — this is the actual fix for the
Single-AZ cross-AZ risk, not the StorageClass's `volumeBindingMode`:

```yaml
apiVersion: apps/v1
kind: StatefulSet   # or whatever workload type hosts this controller
metadata:
  name: cbci-controller-fsx
spec:
  template:
    spec:
      affinity:
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
              - matchExpressions:
                  - key: topology.kubernetes.io/zone
                    operator: In
                    values: ["<az-the-fsx-filesystem-lives-in>"]
      # ...containers, volumes referencing the jenkins-home-fsx PVC, etc.
```

---

## 5. Post-provisioning verification checklist

Same commands used to audit the demo cluster — run these against the fresh
cluster to confirm it actually came up at the intended target, rather than
assuming the create calls above did what they say:

```bash
# EBS
kubectl get sc ebs-gp3-sc -o yaml
aws ec2 describe-volumes --filters Name=tag:Name,Values=cbci-jenkins-home \
  --query 'Volumes[].{Id:VolumeId,Type:VolumeType,IOPS:Iops,Throughput:Throughput}' --region us-east-1

# EFS
aws efs describe-file-systems --file-system-id <fs-id> --region us-east-1 \
  --query 'FileSystems[0].{ThroughputMode:ThroughputMode,ProvisionedMibps:ProvisionedThroughputInMibps}'

# FSx
aws fsx describe-file-systems --file-system-id <fs-id> --region us-east-1 \
  --query 'FileSystems[0].OpenZFSConfiguration.{DeploymentType:DeploymentType,ThroughputMBps:ThroughputCapacity,DiskIops:DiskIopsConfiguration}'
aws fsx describe-volumes --volume-ids <root-volume-id> --region us-east-1 \
  --query 'Volumes[0].OpenZFSConfiguration.NfsExports'
```

Expect: EBS at `3000`/`128`, EFS at `provisioned`/`128`, FSx at
`SINGLE_AZ_2`/`128` with `USER_PROVISIONED`/`3000` IOPS and `sync` (not
`async`) in its NFS export options. If any of these don't match, something in
the create step above didn't take — don't proceed to running the benchmark
matrix until they do.
