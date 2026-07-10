# Runbook — CloudBees CI storage-benchmark cluster (`cbci-demo-cone`)

End-to-end rebuild: **EKS cluster → networking/SG → CSI drivers → storage (EBS/EFS/FSx) →
Gateway API (NGINX Gateway Fabric) → CloudBees CI (Helm) → controllers**.

Reverse-engineered from the live cluster (us-east-1, account `324005994172`) on 2026-07-10.
Values that are environment-specific (domain, TLS cert, git repo) are marked `⟨…⟩`.

> Ordering matters: each stage assumes the previous one exists. The whole thing is
> idempotent enough to re-run stage-by-stage.

---

## 0. Tooling & variables

```bash
# tools: awscli v2, eksctl, kubectl, helm v3
aws --version && eksctl version && kubectl version --client && helm version

export AWS_PROFILE=324005994172_infra-admin      # SSO: infra-admin
export R=us-east-1
export CLUSTER=cbci-demo-cone
export NS=cloudbees-core
export DOMAIN=aws.syadavali.ps.beescloud.com     # ⟨your base domain⟩

# VPC, subnets and the NFS SG do NOT exist on a fresh build — Stage 1 (eksctl) creates the
# VPC/subnets, Stage 2 the SG. They are DERIVED at point of use (see §2 and §4), not set here.
# This runbook targets the HARDENED topology: nodes + all storage in PRIVATE subnets.
# Live-cluster reference IDs (note: live currently has PUBLIC nodes + one public EFS MT):
#   VPC vpc-0b821877dbfb5825a
#   priv-1a subnet-0f72356475a9a0ec8   priv-1b subnet-0cb1f56f0e2c8165c
#   pub-1a  subnet-0b74b0b52337139a8   pub-1b  subnet-055d43966a4477c8e
#   NFS_SG sg-0b703c4aae772d33b
```

---

## 1. EKS cluster + VPC + node groups (eksctl)

eksctl builds the VPC (`192.168.0.0/16`), 2 public + 2 private subnets across 1a/1b,
IGW, a single NAT gateway, the cluster/control-plane SGs, OIDC, both node groups, and the
EBS/EFS CSI add-ons — all from one config file.

`cluster/cbci-demo-cone.yaml`:
```yaml
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig
metadata:
  name: cbci-demo-cone
  region: us-east-1
  version: "1.35"
availabilityZones: ["us-east-1a", "us-east-1b"]
vpc:
  cidr: "192.168.0.0/16"
  nat:
    gateway: Single
  clusterEndpoints:
    publicAccess: true
    privateAccess: true          # NOTE: originally false; enabled 2026-07-10 (see §9)
iam:
  withOIDC: true                 # required for IRSA / CSI drivers
managedNodeGroups:
  - name: application
    instanceType: m5.xlarge
    amiFamily: AmazonLinux2023
    privateNetworking: true      # nodes in PRIVATE subnets (no public IPs; egress via NAT)
    desiredCapacity: 3
    minSize: 3
    maxSize: 3
    volumeSize: 20
    labels: { role: application }
  - name: agents
    instanceType: m5.xlarge
    amiFamily: AmazonLinux2023
    privateNetworking: true      # nodes in PRIVATE subnets
    spot: true
    desiredCapacity: 1
    minSize: 0
    maxSize: 10
    labels: { role: agent }
    taints:
      - { key: role, value: "agent", effect: NoSchedule }
# NOTE: the LIVE cluster currently runs nodes in PUBLIC subnets (privateNetworking was unset).
# This config hardens that; applying it to the live cluster = recreate node groups (disruptive).
addons:
  - name: vpc-cni
  - name: coredns
  - name: kube-proxy
  - name: eks-pod-identity-agent    # IAM via Pod Identity (this cluster uses it, NOT IRSA)
  - name: aws-ebs-csi-driver
  - name: aws-efs-csi-driver
```

```bash
eksctl create cluster -f cluster/cbci-demo-cone.yaml
aws eks update-kubeconfig --region $R --name $CLUSTER
```

---

## 2. NFS security group (manual — eksctl does NOT create this)

Used by both EFS mount targets and the FSx filesystem. NFSv4 needs only 2049/tcp.

```bash
# derive the VPC eksctl created in §1
export VPC=$(aws eks describe-cluster --region $R --name $CLUSTER \
  --query 'cluster.resourcesVpcConfig.vpcId' --output text)

export NFS_SG=$(aws ec2 create-security-group --region $R \
  --group-name cbci-demo-cone-efs-sg \
  --description "Security group for EFS mount targets" \
  --vpc-id $VPC --query GroupId --output text)

aws ec2 authorize-security-group-ingress --region $R --group-id $NFS_SG \
  --ip-permissions 'IpProtocol=tcp,FromPort=2049,ToPort=2049,IpRanges=[{CidrIp=192.168.0.0/16},{CidrIp=10.0.0.0/16}]'
```

---

## 3. FSx for OpenZFS CSI driver (Helm — not an EKS add-on)

EBS + EFS CSI came from §1 add-ons. FSx OpenZFS CSI is separate:

```bash
helm repo add aws-fsx-openzfs-csi-driver https://kubernetes-sigs.github.io/aws-fsx-openzfs-csi-driver
helm repo update
helm upgrade --install aws-fsx-openzfs-csi-driver \
  aws-fsx-openzfs-csi-driver/aws-fsx-openzfs-csi-driver -n kube-system   # live: chart 1.2.0
```

### 3.1 IAM via EKS Pod Identity (this cluster uses Pod Identity, NOT IRSA)

The `eks-pod-identity-agent` add-on (§1) vends role creds to pods. Each CSI driver SA (and the
bench agent) gets an IAM role via a **pod identity association** — no OIDC annotations.

```bash
ACCT=324005994172

# trust policy for all Pod Identity roles
cat > pi-trust.json <<'JSON'
{ "Version":"2012-10-17","Statement":[{
  "Effect":"Allow","Principal":{"Service":"pods.eks.amazonaws.com"},
  "Action":["sts:AssumeRole","sts:TagSession"] }] }
JSON

# helper: create role, attach a policy, associate a k8s SA -> role
assoc() {  # $1=roleName $2=ns $3=sa
  aws iam create-role --role-name "$1" --assume-role-policy-document file://pi-trust.json >/dev/null
  aws eks create-pod-identity-association --region $R --cluster-name $CLUSTER \
    --namespace "$2" --service-account "$3" \
    --role-arn arn:aws:iam::$ACCT:role/$1 ; }

# EBS
assoc cbci-demo-cone-EBS-PodIdentityRole kube-system ebs-csi-controller-sa
aws iam attach-role-policy --role-name cbci-demo-cone-EBS-PodIdentityRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy

# EFS
assoc cbci-demo-cone-EFS-PodIdentityRole kube-system efs-csi-controller-sa
aws iam attach-role-policy --role-name cbci-demo-cone-EFS-PodIdentityRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonEFSCSIDriverPolicy

# FSx OpenZFS — LEAST-PRIVILEGE inline policy (do NOT use AmazonFSxFullAccess).
# Both controller AND node SAs share this role.
cat > fsx-openzfs-csi.json <<'JSON'
{ "Version":"2012-10-17","Statement":[{ "Effect":"Allow","Resource":"*","Action":[
  "fsx:DescribeFileSystems","fsx:DescribeVolumes","fsx:CreateVolume","fsx:DeleteVolume",
  "fsx:UpdateVolume","fsx:TagResource","fsx:UntagResource","fsx:ListTagsForResource"
]}]}
JSON
aws iam create-role --role-name cbci-demo-cone-OpenZFS-PodIdentityRole \
  --assume-role-policy-document file://pi-trust.json >/dev/null
aws iam put-role-policy --role-name cbci-demo-cone-OpenZFS-PodIdentityRole \
  --policy-name FSxOpenZFSLifecycleOverrides --policy-document file://fsx-openzfs-csi.json
for sa in fsx-openzfs-csi-controller-sa fsx-openzfs-csi-node-sa; do
  aws eks create-pod-identity-association --region $R --cluster-name $CLUSTER \
    --namespace kube-system --service-account $sa \
    --role-arn arn:aws:iam::$ACCT:role/cbci-demo-cone-OpenZFS-PodIdentityRole
done

# Benchmark agent — CloudWatch read only (for ground-truth IOPS/MB-s)
cat > bench-cw.json <<'JSON'
{ "Version":"2012-10-17","Statement":[{
  "Effect":"Allow","Action":"cloudwatch:GetMetricStatistics","Resource":"*" }] }
JSON
aws iam create-role --role-name cjoc-bench-cloudwatch-role \
  --assume-role-policy-document file://pi-trust.json >/dev/null
aws iam put-role-policy --role-name cjoc-bench-cloudwatch-role \
  --policy-name cjoc-bench-cloudwatch-read --policy-document file://bench-cw.json
aws eks create-pod-identity-association --region $R --cluster-name $CLUSTER \
  --namespace cloudbees-agents --service-account bench-agent-sa \
  --role-arn arn:aws:iam::$ACCT:role/cjoc-bench-cloudwatch-role
```
> `ListTagsForResource` is required — the FSx CSI **delete** path calls it; omitting it leaves
> volumes stuck (PV `Released`, `AccessDeniedException`). The CloudBees controllers (`cjoc`,
> `mc-*`) get **no** association — they don't call AWS APIs.

---

## 4. Storage backends

### 4.0 Resolve VPC + subnet + SG IDs (created in §1/§2) — run before 4a/4b
```bash
export VPC=$(aws eks describe-cluster --region $R --name $CLUSTER \
  --query 'cluster.resourcesVpcConfig.vpcId' --output text)

# eksctl tags subnets: eksctl-<cluster>-cluster/Subnet{Public,Private}<AZ>
subnet_by_tag() {   # $1 = e.g. SubnetPrivateUSEAST1A
  aws ec2 describe-subnets --region $R \
    --filters "Name=vpc-id,Values=$VPC" "Name=tag:Name,Values=*$1" \
    --query 'Subnets[0].SubnetId' --output text; }
export SUBNET_PRIV_1A=$(subnet_by_tag SubnetPrivateUSEAST1A)   # FSx single-AZ target + EFS MT (1a)
export SUBNET_PRIV_1B=$(subnet_by_tag SubnetPrivateUSEAST1B)   # second EFS mount target (1b)

export NFS_SG=$(aws ec2 describe-security-groups --region $R \
  --filters "Name=group-name,Values=cbci-demo-cone-efs-sg" "Name=vpc-id,Values=$VPC" \
  --query 'SecurityGroups[0].GroupId' --output text)          # created in §2

echo "VPC=$VPC  PRIV_1A=$SUBNET_PRIV_1A  PRIV_1B=$SUBNET_PRIV_1B  NFS_SG=$NFS_SG"
# storage (FSx + both EFS mount targets) lives in PRIVATE subnets — no internet-adjacency.
# subnet choice matters by AZ, not ID — FSx pins its single file server to $SUBNET_PRIV_1A's AZ.
```

### 4a. EFS filesystem + mount targets
```bash
export FS_EFS=$(aws efs create-file-system --region $R \
  --performance-mode generalPurpose \
  --throughput-mode provisioned --provisioned-throughput-in-mibps 128 \
  --encrypted --tags Key=Name,Value=cbci-demo-cone-efs \
  --query FileSystemId --output text)                       # live: fs-0246a526db0adaedf

# one mount target per AZ subnet (regional reachability) — both in PRIVATE subnets
aws efs create-mount-target --region $R --file-system-id $FS_EFS --subnet-id $SUBNET_PRIV_1A --security-groups $NFS_SG
aws efs create-mount-target --region $R --file-system-id $FS_EFS --subnet-id $SUBNET_PRIV_1B --security-groups $NFS_SG
```

### 4b. FSx for OpenZFS filesystem + parent volume
```bash
export FS_FSX=$(aws fsx create-file-system --region $R --file-system-type OPENZFS \
  --storage-capacity 1200 --subnet-ids $SUBNET_PRIV_1A --security-group-ids $NFS_SG \
  --open-zfs-configuration \
    'DeploymentType=SINGLE_AZ_1,ThroughputCapacity=128,DiskIopsConfiguration={Mode=USER_PROVISIONED,Iops=3600},RootVolumeConfiguration={DataCompressionType=LZ4,NfsExports=[{ClientConfigurations=[{Clients=*,Options=[rw,crossmnt,sync,no_root_squash]}]}]}' \
  --query FileSystem.FileSystemId --output text)            # live: fs-01bbc0b1019aac1c4

# IOPS floor = 3 IOPS/GiB → 1200 GiB = 3600 min (3000 is rejected at this size)

export PARENT_VOL=$(aws fsx describe-file-systems --region $R --file-system-id $FS_FSX \
  --query 'FileSystems[0].OpenZFSConfiguration.RootVolumeId' --output text)
```

### 4c. StorageClasses (all three)
`storage/storageclasses.yaml`:
```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: ebs-gp3-sc
  annotations: { storageclass.kubernetes.io/is-default-class: "true" }
provisioner: ebs.csi.aws.com
parameters: { type: gp3, iops: "3600", throughput: "128" }
reclaimPolicy: Delete
allowVolumeExpansion: true
volumeBindingMode: WaitForFirstConsumer
---
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: efs-sc }
provisioner: efs.csi.aws.com
parameters:
  provisioningMode: efs-ap
  fileSystemId: fs-0246a526db0adaedf      # $FS_EFS
  directoryPerms: "700"
reclaimPolicy: Delete
volumeBindingMode: Immediate
mountOptions: [nfsvers=4.1, rsize=1048576, wsize=1048576]
---
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: openzfs-sc }
provisioner: fsx.openzfs.csi.aws.com
parameters:
  ResourceType: volume                      # NOT json-parsed — leave bare
  ParentVolumeId: '"fsvol-0c2e3448e193c5192"'  # $PARENT_VOL — MUST be JSON-quoted
  DataCompressionType: '"LZ4"'                 # MUST be JSON-quoted
reclaimPolicy: Delete
volumeBindingMode: Immediate
mountOptions: [nfsvers=4.2, rsize=1048576, wsize=1048576, nconnect=16]
# IMPORTANT: the FSx-OpenZFS CSI driver parses these params as JSON. String values must be
# JSON-quoted ("LZ4", "fsvol-…") or dynamic provisioning fails with
# 'invalid character … looking for beginning of value'. ResourceType is the exception (bare).
```
```bash
kubectl apply -f storage/storageclasses.yaml
```

### 4d. (Optional) FSx Single-AZ **storage HA** line — `SINGLE_AZ_HA_2`
FSx offers single-AZ *storage* HA (primary+standby file servers in one AZ, <60s failover) via
`SINGLE_AZ_HA_1`/`_2`. **Availability gotcha:** `SINGLE_AZ_HA_1` (the 128-capable one) is NOT
offered in this account's AZs (`us-east-1a`/`1b` = physical `use1-az6`/`use1-az1`); only
**`SINGLE_AZ_HA_2`** is — and it **floors at 160 MB/s** and adds an **NVMe L2ARC** cache. So run
it as a *separate* backend line and keep the non-HA `SINGLE_AZ_1` @ 128 for the 128-baseline.
```bash
aws fsx create-file-system --region $R --file-system-type OPENZFS \
  --storage-capacity 1200 --subnet-ids $SUBNET_PRIV_1A --security-group-ids $NFS_SG \
  --tags Key=Name,Value=cbci-demo-cone-fsx-ha \
  --open-zfs-configuration '{
     "DeploymentType":"SINGLE_AZ_HA_2","ThroughputCapacity":160,
     "DiskIopsConfiguration":{"Mode":"USER_PROVISIONED","Iops":3600},
     "RootVolumeConfiguration":{"DataCompressionType":"LZ4",
       "NfsExports":[{"ClientConfigurations":[{"Clients":"*","Options":["rw","crossmnt","sync"]}]}]}}'
# live: fs-05a991b7fe8fcc00f, root vol fsvol-081f072bd4193097e
```
Dedicated StorageClass (JSON-quoted, points at the HA fs root volume):
```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: openzfs-ha-sc }
provisioner: fsx.openzfs.csi.aws.com
parameters:
  ResourceType: volume
  ParentVolumeId: '"fsvol-081f072bd4193097e"'   # HA_2 fs root volume
  DataCompressionType: '"LZ4"'
reclaimPolicy: Delete
volumeBindingMode: Immediate
mountOptions: [nfsvers=4.2, rsize=1048576, wsize=1048576, nconnect=16]
```
A 4th controller (e.g. `mc-fsx-ha`) is created in CJOC on `openzfs-ha-sc`, pinned to the HA fs's
AZ (`us-east-1a`). Benchmark caveats to label: **160 MB/s** (not 128) and the **L2ARC read cache**
(COLD_CACHE can't evict it — a storage-side cache confound). Storage HA itself: survives a
file-server failure via <60s failover, but NOT an AZ outage.

---

## 5. Gateway API — NGINX Gateway Fabric (ingress layer)

The cluster uses the **Gateway API** (not classic ingress-nginx). NGF provisions an AWS ELB
LoadBalancer as the external entry point; CloudBees creates the `Gateway` + `HTTPRoute`s.

```bash
# install NGINX Gateway Fabric (installs Gateway API CRDs + controller)
helm install ngf oci://ghcr.io/nginx/charts/nginx-gateway-fabric \
  --create-namespace -n nginx-gateway            # live: chart 2.6.6, GatewayClass "nginx"
```

Prerequisites for CloudBees to wire itself to it:
- A **wildcard DNS** record `*.$DOMAIN` → the NGF LoadBalancer address
  (live ELB: `aaddee34…-1051906210.us-east-1.elb.amazonaws.com`).
- A **TLS certificate** for `*.$DOMAIN` (referenced by the Gateway's HTTPS listener).
- Label the CloudBees namespace so its routes are accepted:
  ```bash
  kubectl label namespace $NS cloudbees.com/gateway-routes=enabled
  ```

CloudBees (§6) creates:
- `Gateway/cloudbees-gateway` (class `nginx`) → LB `cloudbees-gateway-nginx` (80/443).
- One `HTTPRoute` per tenant: `cjoc`, `mc-ebs`, `mc-efs`, `mc-fsx3`
  on `⟨tenant⟩.$DOMAIN` (Subdomain routing).

---

## 6. CloudBees CI (Helm) — operations center (CJOC)

```bash
helm repo add cloudbees https://public-charts.artifacts.cloudbees.com/repository/public/
helm repo update
kubectl create namespace $NS
kubectl label namespace $NS cloudbees.com/gateway-routes=enabled
```

`cloudbees/values.yaml` (the live user-supplied values):
```yaml
Platform: eks
Subdomain: true

OperationsCenter:
  HostName: aws.syadavali.ps.beescloud.com     # $DOMAIN
  Protocol: https
  Image:
    tag: 2.555.2.36756                          # (cluster currently runs 2.555.3.36985)
  NodeSelector: { role: application }
  NumExecutors: 0
  Persistence:
    StorageClass: ebs-gp3-sc
    Size: 20Gi
  Resources:
    Requests: { Cpu: "2", Memory: 4Gi }
    Limits:   { Cpu: "2", Memory: 4Gi }
  Annotations:
    cluster-autoscaler.kubernetes.io/safe-to-evict: "false"
  JavaOpts: >-
    -XX:+AlwaysPreTouch -XX:+HeapDumpOnOutOfMemoryError -XX:+UseG1GC
    -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled -XX:+DisableExplicitGC
    -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=50.0
    -Djenkins.model.Jenkins.logStartupPerformance=true
  CasC: { Enabled: false }

Gateway:
  Enabled: true
  Name: cloudbees-gateway
  Namespace: cloudbees-core

Agents:
  SeparateNamespace:
    Enabled: true
    Create: false            # cloudbees-agents ns pre-exists
    Name: cloudbees-agents
  NodeSelector: { role: agent }
  PodTolerations:
    - { key: role, value: agent, effect: NoSchedule }

CascBundleService: { enabled: false }
SsoRelay: { enabled: false }
```

```bash
helm install cloudbees-core cloudbees/cloudbees-core -n $NS -f cloudbees/values.yaml
helm status cloudbees-core -n $NS

# first-login admin password
kubectl exec cjoc-0 -n $NS -- cat /var/jenkins_home/secrets/initialAdminPassword
# open: https://cjoc.$DOMAIN/cjoc/
```

---

## 7. Create the managed controllers (in CJOC)

Controllers are created in the **CJOC UI** (or CasC); CJOC then creates the tenant's PVC
(via the StorageClass you pick), Deployment/StatefulSet, and HTTPRoute.

For each controller, in **CJOC → New → Managed controller → Configure**:
| Controller | Storage class name | Disk space | Notes |
|---|---|---|---|
| `mc-ebs`  | `ebs-gp3-sc` | any (e.g. 50Gi) | RWO → StatefulSet, single replica |
| `mc-efs`  | `efs-sc`     | any (e.g. 50Gi) | RWX → Deployment, HA-capable, no AZ pin |
| `mc-fsx3` | `openzfs-sc` | **`1Gi` (required)** | RWX → Deployment, **must pin AZ** (below) |
| `mc-fsx-ha` | `openzfs-ha-sc` | **`1Gi` (required)** | FSx storage-HA line (§4d), **must pin AZ** |

> ⚠️ **FSx OpenZFS controllers MUST set Disk space = `1Gi`.** The CSI driver
> (`ResourceType: volume`) rejects any other size: `resourceType Volume expects storage capacity
> to be 1Gi`. The PVC size is nominal — real capacity comes from the filesystem (1,200 GiB), not
> the PVC. A normal 50Gi default leaves the PVC (and pods) `Pending`. EBS/EFS have no such rule.

**FSx controller — required AZ pin** (FSx is single-AZ `us-east-1a`). Put this in the
controller's provisioning YAML in CJOC so it survives re-provisioning:
```yaml
spec:
  template:
    spec:
      affinity:
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
            - matchExpressions:
              - { key: topology.kubernetes.io/zone, operator: In, values: [us-east-1a] }
```

---

## 8. (Optional) High Availability — TWO independent layers

Don't conflate them:

**8a. Controller HA (CloudBees active/active)** — multiple controller *replicas* sharing one
RWX `JENKINS_HOME`. RWX-only (EBS cannot). Enable per controller in **CJOC → controller →
Configure → High Availability**, set **replicas ≥ 2**.
- **EFS** = ideal (regional; replicas legitimately span AZs → survives AZ outage).
- **FSx Single-AZ** = replicas confined to the fs's AZ → **node-level** controller HA only.
  Put the AZ affinity (§7) in the CJOC provisioning YAML *first* (else it's dropped on
  re-provision — this actually happened), and ensure ≥ replica-count nodes exist in that AZ.
- Requires the Gateway to support `sessionPersistence` (sticky sessions).
- Audit HA-incompatible plugins (SDA Data → remove; Docker/Blue Ocean/EC2/K8s → config).

**8b. Storage HA (the filesystem itself)** — a different thing from controller replicas.
- **EFS**: ✅ storage HA built-in (regional, multi-AZ redundant).
- **FSx**: ❌ on `SINGLE_AZ_1` (single file server). ✅ available via `SINGLE_AZ_HA_2`
  (primary+standby in one AZ, <60s failover) — see §4d. `SINGLE_AZ_HA_1` (128-capable) is
  **not offered in these AZs**; HA_2 means **160 MB/s + L2ARC**. Full AZ-outage storage HA
  needs `MULTI_AZ_1` (also 160 floor + cross-AZ write latency).
- **EBS**: ❌ single-AZ block, single-attach.

> Running controller HA (8a) on top of `SINGLE_AZ_1` storage gives you N front-ends to a
> single point of failure — the *storage* is still non-HA. For FSx storage HA, use §4d.

---

## 9. Cluster API endpoint privacy (applied 2026-07-10)

```bash
aws eks update-cluster-config --region $R --name $CLUSTER \
  --resources-vpc-config endpointPublicAccess=true,endpointPrivateAccess=true,publicAccessCidrs=0.0.0.0/0
```
Now `Public:true / Private:true`. Node→API traffic stays in-VPC. To harden later: restrict
`publicAccessCidrs` to office/VPN IPs (only disable public once a VPN/bastion exists).

---

## 10. Verification (quick pass)

```bash
export AWS_PROFILE=324005994172_infra-admin R=us-east-1
kubectl get sc
kubectl get pvc -n cloudbees-core
kubectl get pods -n cloudbees-core -o wide -L topology.kubernetes.io/zone
kubectl get gateway,httproute -n cloudbees-core

# AWS-side storage truth
aws ec2 describe-volumes --region $R --volume-ids <mc-ebs-vol> --query 'Volumes[0].{IOPS:Iops,Tput:Throughput}'
aws efs describe-file-systems --region $R --file-system-id fs-0246a526db0adaedf --query 'FileSystems[0].{Mode:ThroughputMode,MiBps:ProvisionedThroughputInMibps}'
aws fsx describe-file-systems --region $R --file-system-id fs-01bbc0b1019aac1c4 --query 'FileSystems[0].OpenZFSConfiguration.{Tput:ThroughputCapacity,Iops:DiskIopsConfiguration}'

# in-pod mounts
for d in mc-ebs mc-efs mc-fsx3; do kubectl exec -n cloudbees-core deploy/$d -- sh -c 'cat /proc/mounts | grep jenkins_home' 2>/dev/null; done
```

---

## Build-order summary
```
eksctl create cluster (VPC, subnets, IGW/NAT, SGs, nodegroups, OIDC, EBS+EFS CSI)
  → NFS SG (2049)  → FSx-OpenZFS CSI (helm)
  → EFS fs + mount targets  →  FSx fs + volume  →  StorageClasses
  → NGINX Gateway Fabric (helm) + wildcard DNS + TLS cert
  → CloudBees CI (helm: cloudbees-core) → CJOC + Gateway + HTTPRoutes
  → controllers in CJOC (PVC per tenant; FSx AZ-pinned)  → [optional] HA on EFS/FSx
```

## Live resource reference
| Thing | Value |
|---|---|
| Cluster / version | `cbci-demo-cone` / 1.35 |
| VPC | `vpc-0b821877dbfb5825a` (192.168.0.0/16) |
| Node groups | `application` (on-demand 3×), `agents` (spot 0–10, tainted role=agent) |
| NFS SG | `sg-0b703c4aae772d33b` (tcp/2049 from 192.168.0.0/16, 10.0.0.0/16) |
| EFS | `fs-0246a526db0adaedf` — Provisioned 128 MiB/s |
| FSx (non-HA) | `fs-01bbc0b1019aac1c4` — SINGLE_AZ_1, 128 MB/s, 3600 IOPS, sync, 1a (`mc-fsx3`) |
| FSx (storage HA) | `fs-05a991b7fe8fcc00f` — SINGLE_AZ_HA_2, 160 MB/s, 3600 IOPS, sync, L2ARC, 1a (root vol `fsvol-081f072bd4193097e`; SC `openzfs-ha-sc`; 4th line) |
| Gateway ELB | `aaddee34…-1051906210.us-east-1.elb.amazonaws.com` |
| Domain | `*.aws.syadavali.ps.beescloud.com` |
| CloudBees chart | `cloudbees-core-3.36986.0` (app 2.555.3.36985) |
