{{- define "nsangusa.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nsangusa.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "nsangusa.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "nsangusa.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
app.kubernetes.io/name: {{ include "nsangusa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "nsangusa.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nsangusa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "nsangusa.image" -}}
{{- $image := .image -}}
{{- if and (ne .environment "development") (contains "example.invalid" $image.repository) -}}
{{- fail "non-development image repositories must be configured" -}}
{{- end -}}
{{- if and (eq .environment "production") (not $image.digest) -}}
{{- fail "production images must be pinned by digest" -}}
{{- end -}}
{{- if $image.digest -}}
{{- if not (regexMatch "^sha256:[0-9a-f]{64}$" $image.digest) -}}
{{- fail "image digest must match sha256:<64 lowercase hexadecimal characters>" -}}
{{- end -}}
{{ printf "%s@%s" $image.repository $image.digest }}
{{- else -}}
{{ printf "%s:%s" $image.repository (required "image tag or digest is required" $image.tag) }}
{{- end -}}
{{- end }}

{{- define "nsangusa.validate" -}}
{{- range $name := list "AI_CREDENTIAL_MASTER_KEY" "AI_API_KEY" "IMAGE_API_KEY" -}}
{{- if or (hasKey $.Values.backendConfig $name) (hasKey $.Values.frontendConfig $name) -}}
{{- fail (printf "%s must use a runtime Secret reference, never a ConfigMap" $name) -}}
{{- end -}}
{{- end -}}
{{- range $name, $_ := .Values.secret.env -}}
{{- if or (hasKey $.Values.backendConfig $name) (hasKey $.Values.frontendConfig $name) -}}
{{- fail (printf "secret.env.%s must not also appear in a ConfigMap" $name) -}}
{{- end -}}
{{- end -}}
{{- if and (eq (.Values.backendConfig.AI_LIVE_ENABLED | toString) "true") (not (get .Values.secret.env "AI_CREDENTIAL_MASTER_KEY")) -}}
{{- fail "live AI configuration requires secret.env.AI_CREDENTIAL_MASTER_KEY" -}}
{{- end -}}
{{- if and (gt (len .Values.secret.env) 0) (not .Values.secret.existingSecret) -}}
{{- fail "secret.existingSecret is required when secret.env references keys" -}}
{{- end -}}
{{- if and (not .Values.backend.serviceAccount.create) (not .Values.backend.serviceAccount.name) -}}
{{- fail "backend.serviceAccount.name is required when service account creation is disabled" -}}
{{- end -}}
{{- if and (not .Values.frontend.serviceAccount.create) (not .Values.frontend.serviceAccount.name) -}}
{{- fail "frontend.serviceAccount.name is required when service account creation is disabled" -}}
{{- end -}}
{{- if and .Values.otelCollector.enabled (not .Values.otelCollector.serviceAccount.create) (not .Values.otelCollector.serviceAccount.name) -}}
{{- fail "otelCollector.serviceAccount.name is required when service account creation is disabled" -}}
{{- end -}}
{{- if gt (int .Values.backend.autoscaling.minReplicas) (int .Values.backend.autoscaling.maxReplicas) -}}
{{- fail "backend autoscaling minReplicas cannot exceed maxReplicas" -}}
{{- end -}}
{{- if gt (int .Values.frontend.autoscaling.minReplicas) (int .Values.frontend.autoscaling.maxReplicas) -}}
{{- fail "frontend autoscaling minReplicas cannot exceed maxReplicas" -}}
{{- end -}}
{{- if ne .Values.global.environment "development" -}}
{{- if not .Values.secret.existingSecret -}}
{{- fail "secret.existingSecret is required outside development" -}}
{{- end -}}
{{- if not .Values.ingress.enabled -}}
{{- fail "ingress.enabled must be true outside development" -}}
{{- end -}}
{{- if not .Values.ingress.tls.enabled -}}
{{- fail "ingress.tls.enabled must be true outside development" -}}
{{- end -}}
{{- if or (not .Values.ingress.host) (hasSuffix ".invalid" .Values.ingress.host) -}}
{{- fail "a real ingress.host is required outside development" -}}
{{- end -}}
{{- $requiredConfig := list "DATABASE_URL" "KAFKA_BOOTSTRAP_SERVERS" "REDIS_HOST" "PUBLIC_BASE_URL" "S3_ENDPOINT" "OIDC_ISSUER_URI" "OIDC_CLIENT_ID" -}}
{{- range $requiredConfig -}}
{{- $value := get $.Values.backendConfig . -}}
{{- if or (not $value) (contains "example.invalid" ($value | toString)) -}}
{{- fail (printf "backendConfig.%s must be configured outside development" .) -}}
{{- end -}}
{{- end -}}
{{- if not (regexMatch "^jdbc:postgresql://.+[?&]sslmode=verify-full(&|$)" .Values.backendConfig.DATABASE_URL) -}}
{{- fail "backendConfig.DATABASE_URL must use PostgreSQL sslmode=verify-full outside development" -}}
{{- end -}}
{{- if ne .Values.backendConfig.KAFKA_SECURITY_PROTOCOL "SASL_SSL" -}}
{{- fail "backendConfig.KAFKA_SECURITY_PROTOCOL must be SASL_SSL outside development" -}}
{{- end -}}
{{- if ne (.Values.backendConfig.REDIS_SSL_ENABLED | toString) "true" -}}
{{- fail "backendConfig.REDIS_SSL_ENABLED must be true outside development" -}}
{{- end -}}
{{- range $key := list "PUBLIC_BASE_URL" "S3_ENDPOINT" "OIDC_ISSUER_URI" "AI_BASE_URL" "IMAGE_BASE_URL" "X_API_BASE_URL" -}}
{{- if not (hasPrefix "https://" (get $.Values.backendConfig $key | toString)) -}}
{{- fail (printf "backendConfig.%s must use HTTPS outside development" $key) -}}
{{- end -}}
{{- end -}}
{{- if eq .Values.global.environment "production" -}}
{{- range $key := list "MAIL_HOST" "MAIL_USERNAME" "NEWSLETTER_FROM_ADDRESS" "S3_REGION" "S3_BUCKET" -}}
{{- $value := get $.Values.backendConfig $key -}}
{{- if or (not $value) (contains "example.invalid" ($value | toString)) -}}
{{- fail (printf "backendConfig.%s must be configured for production" $key) -}}
{{- end -}}
{{- end -}}
{{- if ne (.Values.backendConfig.AI_LIVE_ENABLED | toString) "true" -}}
{{- fail "backendConfig.AI_LIVE_ENABLED must be true in production" -}}
{{- end -}}
{{- range $name := list "DATABASE_USERNAME" "DATABASE_PASSWORD" "KAFKA_SASL_JAAS_CONFIG" "REDIS_PASSWORD" "MAIL_PASSWORD" "AI_CREDENTIAL_MASTER_KEY" "IMAGE_API_KEY" "S3_ACCESS_KEY" "S3_SECRET_KEY" "X_BEARER_TOKEN" "NEWSLETTER_TOKEN_SECRET" "NEWSLETTER_WEBHOOK_SECRET" "OIDC_CLIENT_SECRET" -}}
{{- if not (get $.Values.secret.env $name) -}}
{{- fail (printf "secret.env.%s must reference a key in secret.existingSecret for production" $name) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if not .Values.networkPolicy.enabled -}}
{{- fail "networkPolicy.enabled must be true outside development" -}}
{{- end -}}
{{- if and (not .Values.networkPolicy.externalEgressManagedExternally) (eq (len .Values.networkPolicy.backendExternalEgress) 0) -}}
{{- fail "configure networkPolicy.backendExternalEgress or set externalEgressManagedExternally after documenting platform egress controls" -}}
{{- end -}}
{{- if and .Values.otelCollector.enabled (not .Values.networkPolicy.externalEgressManagedExternally) (eq (len .Values.networkPolicy.otelExternalEgress) 0) -}}
{{- fail "configure networkPolicy.otelExternalEgress or set externalEgressManagedExternally after documenting platform egress controls" -}}
{{- end -}}
{{- if and .Values.otelCollector.enabled .Values.otelCollector.exporter.insecure -}}
{{- fail "the OTel exporter cannot use insecure transport outside development" -}}
{{- end -}}
{{- end -}}
{{- if and (eq .Values.global.environment "production") (ne .Values.backendConfig.PROVIDER_MODE "production") -}}
{{- fail "backendConfig.PROVIDER_MODE must be production in production" -}}
{{- end -}}
{{- end }}

{{- define "nsangusa.backendServiceAccount" -}}
{{- default (printf "%s-backend" (include "nsangusa.fullname" .)) .Values.backend.serviceAccount.name }}
{{- end }}

{{- define "nsangusa.frontendServiceAccount" -}}
{{- default (printf "%s-frontend" (include "nsangusa.fullname" .)) .Values.frontend.serviceAccount.name }}
{{- end }}

{{- define "nsangusa.otelServiceAccount" -}}
{{- default (printf "%s-otel" (include "nsangusa.fullname" .)) .Values.otelCollector.serviceAccount.name }}
{{- end }}
