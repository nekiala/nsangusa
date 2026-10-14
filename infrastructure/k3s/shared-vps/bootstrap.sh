#!/usr/bin/env bash
# Bootstraps a shared-VPS environment's data services and deploy identity.
#
# Run on the VPS as root from a copy of this directory:
#   NSANGUSA_ENVIRONMENT=staging \
#     DEPLOY_SSH_PUBLIC_KEY='ssh-ed25519 AAAA... nsangusa-deploy' ./bootstrap.sh
#
# NSANGUSA_ENVIRONMENT is `test` (the default) or `staging`. The manifests name the `test`
# namespaces; they are rendered for the selected environment before they are applied.
#
# Idempotent: existing CA material, credentials and Secrets are never rotated here. Generated
# secrets stay on the server (root-only files and Kubernetes Secrets); nothing is printed.
set -euo pipefail
umask 077

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
environment="${NSANGUSA_ENVIRONMENT:-test}"
[[ "$environment" == test || "$environment" == staging ]] || {
  echo "NSANGUSA_ENVIRONMENT must be test or staging" >&2
  exit 1
}
state="/etc/nsangusa-$environment"
tls="$state/tls"
kubectl() { /usr/local/bin/k3s kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml "$@"; }
# Renders a manifest from this directory, or standard input, for the selected environment.
render() { sed "s/nsangusa-test/nsangusa-$environment/g" ${1:+"$here/$1"}; }
data_ns="nsangusa-$environment-data"
app_ns="nsangusa-$environment"

install -d -m 0700 "$state" "$tls"

# --- Private CA and service certificates ---------------------------------------------------
if [[ ! -s "$tls/ca.key" ]]; then
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tls/ca.key"
  openssl req -x509 -new -key "$tls/ca.key" -sha256 -days 730 \
    -subj "/CN=Nsangusa $environment internal CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$tls/ca.crt"
fi

issue() {
  local name="$1"
  [[ -s "$tls/$name.crt" ]] && return
  local fqdn="$name.$data_ns.svc"
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tls/$name.key"
  openssl req -new -key "$tls/$name.key" -subj "/CN=$fqdn" -out "$tls/$name.csr"
  printf '%s\n' \
    "basicConstraints=critical,CA:FALSE" \
    "keyUsage=critical,digitalSignature" \
    "extendedKeyUsage=serverAuth" \
    "subjectAltName=DNS:$name,DNS:$name.$data_ns,DNS:$fqdn,DNS:$fqdn.cluster.local,IP:127.0.0.1" \
    > "$tls/$name.ext"
  openssl x509 -req -in "$tls/$name.csr" -CA "$tls/ca.crt" -CAkey "$tls/ca.key" \
    -CAcreateserial -sha256 -days 365 -extfile "$tls/$name.ext" -out "$tls/$name.crt"
  rm -f "$tls/$name.csr" "$tls/$name.ext"
}
for service in postgres kafka redis seaweedfs; do issue "$service"; done

# --- Credentials (generated once, hex so they are safe in URLs, JAAS and config files) ---------
credentials="$state/credentials.env"
if [[ ! -s "$credentials" ]]; then
  {
    printf 'DATABASE_USERNAME=news\n'
    printf 'DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KAFKA_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'REDIS_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'S3_ACCESS_KEY=nsangusa%s\n' "$(openssl rand -hex 6)"
    printf 'S3_SECRET_KEY=%s\n' "$(openssl rand -hex 24)"
    printf 'S3_SSE_KEY=%s\n' "$(openssl rand -base64 32)"
    printf 'NEWSLETTER_TOKEN_SECRET=%s\n' "$(openssl rand -hex 32)"
    printf 'NEWSLETTER_WEBHOOK_SECRET=%s\n' "$(openssl rand -hex 32)"
  } > "$credentials"
fi
# shellcheck disable=SC1090
source "$credentials"
broker_jaas="org.apache.kafka.common.security.plain.PlainLoginModule required user_news=\"$KAFKA_PASSWORD\";"
client_jaas="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"news\" password=\"$KAFKA_PASSWORD\";"

# --- Namespaces, storage class and policies -----------------------------------------------
render 00-namespace.yaml | kubectl apply -f -

create_secret() {
  local namespace="$1" name="$2"
  shift 2
  if kubectl -n "$namespace" get secret "$name" >/dev/null 2>&1; then
    echo "secret/$name exists in $namespace; left unchanged"
    return
  fi
  kubectl -n "$namespace" create secret "$@" --dry-run=client -o yaml \
    | kubectl -n "$namespace" apply -f - >/dev/null
  echo "secret/$name created in $namespace"
}

for service in postgres kafka redis seaweedfs; do
  create_secret "$data_ns" "$service-tls" generic "$service-tls" \
    --from-file=tls.crt="$tls/$service.crt" \
    --from-file=tls.key="$tls/$service.key" \
    --from-file=ca.crt="$tls/ca.crt"
done

create_secret "$data_ns" test-data-credentials generic test-data-credentials \
  --from-literal=DATABASE_USERNAME="$DATABASE_USERNAME" \
  --from-literal=DATABASE_PASSWORD="$DATABASE_PASSWORD" \
  --from-literal=KAFKA_BROKER_JAAS="$broker_jaas" \
  --from-literal=KAFKA_CLIENT_JAAS="$client_jaas" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" \
  --from-literal=S3_ACCESS_KEY="$S3_ACCESS_KEY" \
  --from-literal=S3_SECRET_KEY="$S3_SECRET_KEY" \
  --from-literal=S3_SSE_KEY="$S3_SSE_KEY"

create_secret "$app_ns" "$app_ns-runtime" generic "$app_ns-runtime" \
  --from-literal=DATABASE_USERNAME="$DATABASE_USERNAME" \
  --from-literal=DATABASE_PASSWORD="$DATABASE_PASSWORD" \
  --from-literal=KAFKA_SASL_JAAS_CONFIG="$client_jaas" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" \
  --from-literal=S3_ACCESS_KEY="$S3_ACCESS_KEY" \
  --from-literal=S3_SECRET_KEY="$S3_SECRET_KEY" \
  --from-literal=NEWSLETTER_TOKEN_SECRET="$NEWSLETTER_TOKEN_SECRET" \
  --from-literal=NEWSLETTER_WEBHOOK_SECRET="$NEWSLETTER_WEBHOOK_SECRET"

# The application images on GHCR are private; data services use public upstream images.
# Place a read:packages registry credential at $state/ghcr-pull.json (root-only) beforehand.
if [[ -s "$state/ghcr-pull.json" ]]; then
  create_secret "$app_ns" ghcr-pull generic ghcr-pull \
    --type=kubernetes.io/dockerconfigjson \
    --from-file=.dockerconfigjson="$state/ghcr-pull.json"
fi

# --- Data services -------------------------------------------------------------------------
for manifest in 10-postgres.yaml 20-kafka.yaml 30-redis.yaml 40-seaweedfs.yaml 50-mailpit.yaml; do
  render "$manifest" | kubectl apply -f -
done

# --- Deploy identity: namespace-scoped ServiceAccount reached through a forward-only SSH key ---
render <<'EOF' | kubectl apply -f -
apiVersion: v1
kind: ServiceAccount
metadata:
  name: nsangusa-deployer
  namespace: nsangusa-test
automountServiceAccountToken: false
---
apiVersion: v1
kind: Secret
metadata:
  name: nsangusa-deployer-token
  namespace: nsangusa-test
  annotations:
    kubernetes.io/service-account.name: nsangusa-deployer
type: kubernetes.io/service-account-token
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: nsangusa-deployer
  namespace: nsangusa-test
subjects:
  - {kind: ServiceAccount, name: nsangusa-deployer, namespace: nsangusa-test}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: admin
---
# The workflow re-applies and labels its own namespace; allow exactly that namespace.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: nsangusa-test-namespace
rules:
  - apiGroups: [""]
    resources: [namespaces]
    resourceNames: [nsangusa-test]
    verbs: [get, patch, update]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: nsangusa-test-namespace
subjects:
  - {kind: ServiceAccount, name: nsangusa-deployer, namespace: nsangusa-test}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: nsangusa-test-namespace
EOF

if [[ -n "${DEPLOY_SSH_PUBLIC_KEY:-}" ]]; then
  [[ "$DEPLOY_SSH_PUBLIC_KEY" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+(\ [A-Za-z0-9@._-]+)?$ ]] || {
    echo "DEPLOY_SSH_PUBLIC_KEY must be a single ssh-ed25519 public key" >&2
    exit 1
  }
  if ! getent passwd nsangusa-deploy >/dev/null; then
    useradd --system --create-home --home-dir /var/lib/nsangusa-deploy \
      --shell /usr/sbin/nologin nsangusa-deploy
  fi
  install -d -m 0755 -o root -g root /var/lib/nsangusa-deploy/.ssh
  # Root-owned: the account cannot change its own restrictions. Forwarding to the API only.
  printf 'restrict,port-forwarding,permitopen="127.0.0.1:6443",command="/bin/false" %s\n' \
    "$DEPLOY_SSH_PUBLIC_KEY" > /var/lib/nsangusa-deploy/.ssh/authorized_keys
  chmod 0644 /var/lib/nsangusa-deploy/.ssh/authorized_keys
fi

# Kubeconfig for the deploy workflow, written root-only for transfer to the GitHub secret.
for _ in $(seq 1 30); do
  token="$(kubectl -n "$app_ns" get secret nsangusa-deployer-token -o jsonpath='{.data.token}' | base64 -d)"
  [[ -n "$token" ]] && break
  sleep 1
done
[[ -n "$token" ]] || { echo "deployer token was not issued" >&2; exit 1; }
ca_data="$(base64 -w0 /var/lib/rancher/k3s/server/tls/server-ca.crt)"
cat > "$state/deployer.kubeconfig" <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: $app_ns
    cluster:
      server: https://127.0.0.1:6443
      certificate-authority-data: $ca_data
users:
  - name: nsangusa-deployer
    user:
      token: $token
contexts:
  - name: $app_ns
    context: {cluster: $app_ns, user: nsangusa-deployer, namespace: $app_ns}
current-context: $app_ns
EOF
echo "bootstrap complete"
