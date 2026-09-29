# Infrastructure and observability notes

## Layout

- `infrastructure/compose`: disposable local dependencies only.
- `infrastructure/docker`: runtime-only image contracts; application build output is supplied by CI.
- `infrastructure/helm/nsangusa`: backend/frontend and in-cluster OTel collector.
- `infrastructure/observability`: local collector configuration.
- `infrastructure/k3s/staging`: pinned configuration and bounded qualification fixtures for the
  approved shared-VPS staging cluster, not a production/HA blueprint.
- `infrastructure/nginx/staging`: isolated staging HTTPS, ACME webroot and certificate-renewal
  assets for the existing host Nginx.

Production PostgreSQL, Kafka, Redis, object storage, identity, email, DNS, certificates, telemetry backend, AI, and X are external managed dependencies. The chart creates none of them. Provider features and credentials require real accounts.

## Shared-VPS staging bootstrap

On 2026-09-21 the owner approved sharing the Ubuntu 26.04.1 VPS, preserving its existing Docker
application, host Nginx and unrelated Actions runner, and confirmed a full backup, recent snapshot
and provider rescue access. `staging.nsangusa.com` resolves to this VPS. Kubernetes bootstrap is
separate from application promotion: the Nsangusa application and its data services have not yet
been deployed. Staging HTTPS was subsequently configured as recorded below.

The single-node cluster uses **k3s v1.36.4+k3s1**, matching the Kubernetes 1.36.4 baseline.
`infrastructure/k3s/staging/release.json` pins the official binary and checksum-manifest hashes.
The binary was checked against both release metadata and the vendor checksum manifest before
installation. No remote installer script, host package upgrade, Docker restart or host reboot
was used. The runtime has its own containerd socket and storage, separate from Docker.

The repository's `config.yaml`, `audit-policy.yaml`, systemd units and `guard.nft` are the installed
configuration. Important constraints:

- Nginx retains host ports 80/443 and the existing application retains loopback port 3000.
  Bundled Traefik and ServiceLB are disabled. A future staging vhost can use a specifically
  reserved loopback NodePort; no Nginx sites were changed by bootstrap.
- The API advertises the real node IP so pods can reach `kubernetes.default`. The independently
  owned `inet nsangusa_k3s_guard` table must load successfully before k3s starts. It allows local
  and actual CNI control traffic but blocks Internet control-plane access, direct cluster/pod
  ingress and external NodePorts. Pre-DNAT loopback protection precedes kube-proxy's
  `route_localnet` behavior. It replaces only its own table, never the host ruleset.
- Flannel `host-gw`, explicit iptables kube-proxy mode and `127.0.0.0/8` NodePort addresses
  support loopback-only host proxying without a VXLAN listener. Host iptables uses its existing
  `nf_tables` backend; bundled-tool preference and Docker alternatives were not changed.
  NetworkPolicy enforcement remains enabled. Pod/service CIDRs do not overlap Docker.
- Kubernetes Secrets use AES-CBC storage encryption; kubeconfig and bootstrap configuration
  remain root-only. API auditing records metadata, not request/response bodies. The bounded
  audit rotation settings are technical defaults, not approval of a retention policy.
- The service has explicit CPU/memory limits and kubelet reserves host resources. These are
  conservative starting limits, not an approved capacity/cost envelope.

Kubernetes necessarily shares the host kernel. Bridge filtering was activated separately and
existing traffic remained available. Kubelet changed `vm.overcommit_memory` from 0 to 1,
`kernel.panic` from 0 to 10 and `kernel.panic_on_oops` from 0 to 1; `vm.panic_on_oom` stayed 0.
Loopback NodePorts enabled `net.ipv4.conf.all.route_localnet=1` only after the external anti-loopback
guard was active. These changes are recorded rather than described as complete host isolation.
Docker's existing chains remain present and its FORWARD policy remains DROP.

Administrative access stays on the server:

```sh
ssh nsangusa 'sudo -n /usr/local/bin/k3s kubectl \
  --kubeconfig /etc/rancher/k3s/k3s.yaml get nodes'
```

Do not publish kubeconfig contents, the server token, encryption keys or datastore backups.
Do not run blanket Kubernetes cleanup/uninstall helpers, flush firewall rules or stop Docker on
this shared host. Re-evaluate the guard if interfaces, CIDRs, ports or topology change.

Bootstrap evidence covers a Ready node, CoreDNS and metrics, restricted digest-pinned probe pods,
CA-verified in-cluster API access, Internet control-port denial, localhost NodePort service,
ingress deny/selector-limited allow and egress deny/recovery. A synthetic Secret was observed
encrypted in the actual SQLite datastore and read back after restarting **k3s only**.
Post-restart checks must wait for a real pod exec/API request, not only a cached Node Ready
condition: the first immediate exec encountered the kubelet's warming cache before succeeding.
The disposable `nsangusa-k3s-qualification` namespace is reserved for the committed fixtures;
assert its absence before creating it and its owner label before deleting it.

`infrastructure/scripts/check-shared-host.py` records only selected container/service identities,
configuration fingerprints and a loopback HTTP status. Its version-2 baseline also records
configuration symlink topology/targets and excludes certificate/private-key files. Historical
version-1 pre/post-bootstrap evidence is retained as such; it is not silently upgraded to claim
observations it did not capture. Guard regressions run in the mandatory operational CI job.
Detailed, non-secret receipts remain in ignored local/session operational evidence, not in
qualification approval records.

**Limits remain:** this is one failure domain, with SQLite/local-path storage, not HA or managed
PITR. Service restart was exercised; host reboot, off-host Kubernetes/workload recovery, workload
identity, application deployment and capacity qualification remain separate gates. The packaged
metrics APIService uses `insecureSkipTLSVerify: true` on its aggregation hop; kubelet and the
tested pod-to-API TLS paths remain verified, but a managed metrics serving certificate/CA and
rotation are required before claiming fully verified platform TLS. No insecure kubelet flag was
added. Bootstrapping this cluster does not satisfy the reviewed staging/production promotion
records or authorize Step 7.
External IPv4 denial was exercised with active endpoints. The operator workstation had no IPv6
route to the VPS, so an independent external IPv6 probe is still outstanding; IPv6 loopback
listener/inet-guard inspection is not substituted for that network exercise.

## Staging HTTPS and certificate renewal

On **2026-09-22**, `https://staging.nsangusa.com` received a publicly trusted Let's Encrypt
certificate, initially expiring **2026-12-21**. The owner explicitly authorized subscriber-agreement
acceptance on 2026-09-21 and selected `contact@nsangusa.com` as the ACME contact.
Ubuntu's signed package repositories supplied Certbot **4.0.0-4** and seven new dependencies;
no installed package was upgraded, and automatic host-service restarts were deferred.

Only `/etc/nginx/sites-available/nsangusa-staging.conf` and its corresponding `sites-enabled`
symlink were added. Existing site files, Docker container identity/restart count, Nginx master
process and unrelated runner remained unchanged. Nginx was syntax-checked and gracefully
reloaded, never stopped. Preservation checks allow only the exact approved new file hashes
and symlink targets; an additions record cannot authorize altering any original path.

HTTP redirects to the fixed staging HTTPS hostname except for the isolated HTTP-01 challenge
location. HTTPS permits TLS 1.2/1.3 and currently returns **503**, plain text
`Nsangusa staging is not yet deployed.`, `Cache-Control: no-store` and
`X-Robots-Tag: noindex, nofollow`. This is an intentional unavailable response, not application
acceptance or access control. Before deploying real or sensitive staging content, configure
the appropriate access boundary. HSTS applies only to this hostname for one day: neither
`includeSubDomains` nor preload is enabled.

Certificate/account state stays root-only in `/etc/nsangusa-acme`; the private key is mode 0600.
The only public webroot is `/var/www/nsangusa-acme`, not the certificate/account directory.
Work/log paths are `/var/lib/nsangusa-acme` and `/var/log/nsangusa-acme`. Certbot uses `certonly`
with its webroot plugin, not the Nginx editor or a standalone port-80 listener.
The newly installed distribution-wide `certbot.service` and `certbot.timer` are deliberately
masked; issuance's generic "scheduled task" message is not evidence of this deployment's timer.

`nsangusa-acme-renew.timer` schedules the dedicated renewal service twice daily with up to one
hour of randomized delay and persistent missed-run handling. Renewal is scoped to
`staging.nsangusa.com` and the isolated configuration. Its deploy hook rejects other lineages,
checks the certificate hostname/lifetime and Nginx syntax, then gracefully reloads Nginx.
An `ExecStartPost` verifier uses hostname/CA-verified loopback TLS and compares the served leaf
with the certificate on disk; after bounded worker convergence, stale, untrusted or near-expiry
certificates fail the service. Missing renewal configuration also fails rather than silently
skipping the run. Failed services/logs remain visible; routed on-call notification is still an
operational qualification prerequisite.

```sh
ssh nsangusa 'sudo -n systemctl list-timers nsangusa-acme-renew.timer --no-pager'
ssh nsangusa 'sudo -n systemctl start nsangusa-acme-renew.service'
ssh nsangusa 'sudo -n /usr/local/libexec/nsangusa-acme-verify'
```

Real HTTP-01 issuance and a subsequent simulated renewal/deploy-hook exercise passed. The
renewal drill used the same filesystem sandbox as the persistent service, did not replace the
production certificate with a test-CA certificate, and reloaded only Nginx configuration.
External TLS 1.2/1.3, certificate hostname/chain/fingerprint, redirect, challenge 404 and exact
503 behavior were exercised without disabling certificate verification. The unrelated existing
site still returned HTTPS 200. Non-secret evidence is in ignored
`.local/operational-evidence/staging-https-20260922/`; no account keys or private certificates
were copied into that evidence or the repository.

The original preservation baseline remains intact. Future changes use
`/etc/nsangusa-k3s/protected-host-after-https-20260922.json`, which includes the new staging site.
The earlier baseline plus `/etc/nsangusa-k3s/approved-https-additions-20260922.json` can still
prove that this operation did not replace original configuration.
Application/data-service deployment, reviewer/hosted gates, access controls and the other
Step 5/6 qualifications remain open; this HTTPS setup is not Step 7.

## Shared-VPS test environment

On **2026-09-29** the owner chose to run the workflow's `test` environment on the shared VPS,
with data services in k3s, the GitHub-hosted runner reaching the API through an SSH tunnel and
the site behind basic auth. This is a running application for manual testing with fake
providers, **not staging qualification**: the `test` path skips the qualification-evidence step,
and none of the Step 5/6 gates above are closed by it.

`infrastructure/k3s/test-data/` holds the manifests and `bootstrap.sh`. Data services run in
`nsangusa-test-data` under the restricted Pod Security profile, reachable only from the
application's backend and migration pods:

| Service | Transport | Storage |
|---|---|---|
| PostgreSQL 18.4 | TLS; backend uses `sslmode=verify-full` | 10 Gi `local-path-retain` |
| Kafka 4.3.1 (KRaft, one node) | SASL_SSL with PLAIN on 9094; controller/inter-broker on loopback | 10 Gi `local-path-retain` |
| Redis 8.10.0 | TLS only; persistence off, as locally | ephemeral |
| MinIO (rebuilt, see below) | HTTPS; static KMS key so default SSE-S3 requests work | 20 Gi `local-path-retain` |
| Mailpit 1.27.8 | cluster-internal SMTP sink; no mail leaves the environment | ephemeral |

`bootstrap.sh` runs on the VPS as root and is idempotent. It creates a private EC P-256 CA and
per-service certificates under `/etc/nsangusa-test/tls`, generates credentials once into
`/etc/nsangusa-test/credentials.env`, and creates the `nsangusa-test-runtime` Secret the chart
references. It never rotates existing material; rotation means deliberately replacing the file
and Secret, then restarting the affected workloads. The backend trusts the CA through
configuration only: a pgjdbc single-certificate factory reading `NSANGUSA_INTERNAL_CA`, Kafka's
PEM trust store, and a Spring SSL bundle (`internal`) used by Redis and `S3_SSL_BUNDLE`.

The deploy identity is `nsangusa-deployer`: namespace `admin` in `nsangusa-test` plus get/patch
on that one Namespace object. The workflow reaches the API as the `nsangusa-deploy` system user,
whose root-owned `authorized_keys` entry allows only forwarding to `127.0.0.1:6443`
(`restrict,port-forwarding,permitopen=...,command="/bin/false"`). The k3s guard is unchanged:
the API port remains closed to the Internet.

Host Nginx proxies `staging.nsangusa.com` to loopback NodePorts (frontend `30380`, backend
`30381` for the chart's `backendPaths`) behind basic auth; see
[`nginx/staging/https.conf`](../infrastructure/nginx/staging/https.conf). Masqueraded loopback
NodePort traffic arrives from `cni0` (`10.42.0.1`), which `networkPolicy.hostIngress` admits.
The protected-host baseline recorded after HTTPS must be re-recorded to include this vhost.

The `test` profile seeds no accounts. Create the first administrator by registering through the
site, confirming through Mailpit (`kubectl -n nsangusa-test-data port-forward svc/mailpit
8025:8025` on the VPS) and granting the role once with an audited SQL statement.

MinIO no longer publishes images or binaries and its repository is archived.
[`storage-images.yml`](../.github/workflows/storage-images.yml) rebuilds the releases this
repository pinned from their tagged commits with a supported Go toolchain, scans and signs them;
consumers pin the resulting GHCR digests. These images receive no upstream fixes; replacing
MinIO with a maintained S3-compatible server is an open follow-up.

**Limits:** one node and failure domain, local-path volumes without backup or PITR, no
telemetry backend (the OTel collector is disabled), OIDC not configured, and a shared
basic-auth password rather than per-user access control.

## Configuration

Non-secret endpoints and flags use a ConfigMap. Credentials and sensitive identifiers come from `existingSecret`; production should use an external secret controller/workload identity. Secret values must not be committed or placed in Helm values. Restrict egress further with platform-supported FQDN/proxy controls because Kubernetes NetworkPolicy is IP/port based.

The frontend reads `NSANGUSA_API_URL` at server runtime for same-origin API forwarding and
server rendering. Its `PUBLIC_BASE_URL` defaults to the backend's configured public URL; an
explicit `frontendConfig.PUBLIC_BASE_URL` overrides it. Both values can change when promoting
the same image. `NEXT_PUBLIC_*` variables are build-time values, not runtime configuration.

Environment overlays under `helm/nsangusa/values` cover development, test, staging, and production
with safe sizing and policy defaults only. The GitHub deployment environment must provide a
`HELM_VALUES` secret containing reviewed non-secret environment overrides. Set image
repositories/digests, host/TLS secret, managed endpoints, runtime Secret name, storage/auth
identifiers, telemetry exporter, and explicit egress CIDRs/ports there.

Required runtime Secret keys are mapped under `secret.env`; the chart references but never creates that Secret. Each key is wired with a non-optional `secretKeyRef`, so a missing Secret/key blocks pod startup rather than silently falling back. Production admission/rendering rejects mutable image tags, incomplete secret mappings, insecure PostgreSQL/Kafka/Redis/provider settings, missing TLS ingress, and placeholder required endpoints.

The default-deny policies separate backend, frontend, migration, and collector egress. The backend
accepts application traffic from the ingress controller and the frontend workload so server-side
rendering can use the internal backend Service. DNS is restricted to selected CoreDNS pods.
Provider IPs can change, so use a platform egress gateway or continuously maintained CIDR
inventory; do not open `0.0.0.0/0` merely to make deployment pass.

Flyway executes as a Helm pre-install/pre-upgrade Job using the exact backend digest. Normal backend pods receive `SPRING_FLYWAY_ENABLED=false` when this mode is enabled. The Job disables web, Kafka listeners, scheduling, and tracing and enables lazy initialization, but still uses the application's normal startup path; validate it against a staging environment after changes that add eager provider clients. The Job is deliberately not auto-deleted after success so its logs/status remain available until TTL cleanup or the next hook execution.

## OTel

Applications send OTLP to the collector. The collector batches, limits memory, adds Kubernetes attributes, and exports through a configured OTLP endpoint. The local configuration uses debug output only. Production must enable TLS/auth, redact at the application first, size queues, and monitor collector refusal/export failure. Do not send article bodies, prompts, tokens, or sensitive headers as attributes.

Recommended attributes: service name/version, deployment environment, module, route template, result class, correlation ID, Kafka topic/partition, and provider class. Avoid user IDs unless irreversibly pseudonymized and approved. Trace sampling should retain errors and rare critical workflows while controlling normal traffic volume.

## Image and release controls

- CI produces SBOM/provenance, scans, signs, and promotes the same digest.
- GitHub environments provide approval gates and scoped cluster credentials; deployment consumes reviewed environment values, verifies application Cosign signatures, and deploys exact digests.
- Runtime containers are non-root, read-only, capability-free, and use seccomp defaults.
- Deployment labels the dedicated namespace for the Kubernetes Restricted Pod Security Standard; cluster admission remains the final enforcement point.
- Requested Compose/runtime tags were verified as present on Docker Hub on 2026-09-02; pin resolved, trusted digests because version labels remain mutable.
- Helm 4.2.4 rendering is validated for every overlay against Kubernetes 1.36 schemas. Cluster
  admission should enforce signatures, resources, probes, and restricted Pod Security.

## Opt-in operational monitoring

The backend already includes Micrometer's Prometheus registry. Operational instrumentation reuses
it; there is no second exporter, replacement telemetry stack or new application dependency.
`monitoring.enabled` is **false by default**. When enabled, the chart:

- Sets `MANAGEMENT_SERVER_PORT=8081` and `NEWS_OPERATIONS_INTERNAL_METRICS_ENABLED=true`, adds
  a dedicated ClusterIP metrics Service and moves health probes to the management port.
- Retains public application traffic on 8080; neither metrics Service nor `/actuator` belongs in
  ingress paths. Application security must permit anonymous Prometheus scraping only with the
  explicit internal flag on the separate management listener, never on the application port.
- Requires NetworkPolicy plus nonempty scraper **namespace and pod** selectors, and allows only
  their conjunction to reach 8081. The ingress controller/frontend policies still reach only 8080.
  Verify actual CNI enforcement, node probe behavior and service discovery in the target cluster.
- Supplies the actual rule/dashboard files as ConfigMaps. It installs no monitoring workloads,
  CRDs, secrets, Grafana sidecars or alert routing.

```sh
helm template local infrastructure/helm/nsangusa \
  --set monitoring.enabled=true
# Only after the cluster's Prometheus Operator/CRDs and selectors have been qualified:
helm template local infrastructure/helm/nsangusa \
  --set monitoring.enabled=true \
  --set monitoring.serviceMonitor.enabled=true \
  --set monitoring.prometheusRule.enabled=true \
  --api-versions monitoring.coreos.com/v1/ServiceMonitor \
  --api-versions monitoring.coreos.com/v1/PrometheusRule
```

Rendering refuses requested CRD resources when their kinds are absent from Helm capabilities.
Passing `--api-versions` only qualifies a local render; it does not prove installation/controllers.
Configure the operator's release selectors using `additionalLabels`. `ServiceMonitor` retains
per-pod discovery and consistently labels the job `nsangusa-backend`; rules group inventories by
namespace/job. Use a separate application namespace per deployment to avoid merging independent
database inventories. Raw Prometheus users can mount the rules ConfigMap and adapt
`infrastructure/observability/prometheus-example.yaml`; the example static Service target is for
one replica only, not multi-replica request-rate accounting.

Grafana receives an importable dashboard ConfigMap without an automatic sidecar label. Set
`monitoring.grafana.sidecarLabel` only for an installed, reviewed sidecar, or import the JSON
manually and select the Prometheus datasource. The dashboard and alerts distinguish database
gauges from restartable per-process counters. All thresholds remain provisional until D08 approval;
external exporter and routing gaps are described in [reliability-operations.md](reliability-operations.md).
Set `monitoring.runbookUrl` to the reviewed HTTPS location of this runbook in your documentation
site; Helm rewrites both alert anchors and the Grafana link. Its repository-relative default is
for local review, not proof of a reachable on-call documentation site.

The management scrape connection is internal HTTP, **not a TLS/mTLS qualification**. Production
must review encrypted service networking/mesh or a scoped authenticated TLS scrape design and
least-privilege monitoring access before enabling it. Do not open egress/ingress broadly to make a
scrape pass. Likewise, readiness/probe wiring, bounded requests/limits, HPA and PDB rendering are
not evidence of a real multi-replica outage exercise.

## Local recovery tooling boundary

`infrastructure/scripts/operational-drill.py` creates only its own digest-pinned, resource-bounded
PostgreSQL/MinIO resources, no exposed host ports, and synthetic configuration/objects. It restores
a logical `pg_dump` snapshot, replays newer deletion/suppression/provider-receipt evidence and
proves bounded explicit-policy payload retention/legal holds. Unique evidence directories are
retained; owned containers/volumes are normally cleaned even on failure. It never connects to
preview/phase4 volumes or services. The exact commands, limits, failure inspection and known
limitations are in the [recovery runbook](reliability-operations.md#executable-isolated-local-recovery-qualification).

Managed PostgreSQL PITR/failover/KMS, object versioning/offsite recovery, Kafka ACL/TLS, OIDC
identity, secret-manager rotation, production traffic/cost envelopes and operator escalation
require real approved environment evidence. The local drill does not close those gates and
performs no production deployment or Step 7 promotion.
