{{- define "nsangusa.operationalRules" -}}
{{- $package := .Files.Get "files/observability/rules.json" | fromJson -}}
{{- range $group := $package.groups -}}
{{- range $rule := $group.rules -}}
{{- if hasKey $rule "annotations" -}}
{{- $_ := set $rule.annotations "runbook_url" (replace "docs/reliability-operations.md" $.Values.monitoring.runbookUrl $rule.annotations.runbook_url) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- toYaml $package -}}
{{- end -}}

{{- define "nsangusa.operationalDashboard" -}}
{{- $dashboard := .Files.Get "files/observability/dashboard.json" | fromJson -}}
{{- range $link := $dashboard.links -}}
{{- $_ := set $link "url" (replace "docs/reliability-operations.md" $.Values.monitoring.runbookUrl $link.url) -}}
{{- end -}}
{{- toPrettyJson $dashboard -}}
{{- end -}}
