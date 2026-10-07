{{/* Labels for a workload. Arg: the service name. The name label doubles as the selector. */}}
{{- define "sports-notifier.labels" -}}
app.kubernetes.io/name: {{ . }}
app.kubernetes.io/part-of: sports-notifier
{{- end }}

{{/* Arg: root context. */}}
{{- define "sports-notifier.image" -}}
{{ .Values.image.repository }}:{{ .Values.image.tag }}
{{- end }}

{{/* Env every Java service needs. Arg: root context. */}}
{{- define "sports-notifier.baseEnv" -}}
- name: REDIS_HOST
  value: redis
- name: REDIS_PORT
  value: "6379"
{{- end }}

{{/*
Startup, readiness and liveness checks against /health.
Readiness and liveness only begin once startup passes, so a slow JVM boot is not restarted.
Arg: dict with "port" and "probes" (.Values.probes).
*/}}
{{- define "sports-notifier.httpProbes" -}}
startupProbe:
  httpGet: { path: /health, port: {{ .port }} }
  periodSeconds: {{ .probes.startup.periodSeconds }}
  failureThreshold: {{ .probes.startup.failureThreshold }}
readinessProbe:
  httpGet: { path: /health, port: {{ .port }} }
  periodSeconds: {{ .probes.readiness.periodSeconds }}
  failureThreshold: {{ .probes.readiness.failureThreshold }}
livenessProbe:
  httpGet: { path: /health, port: {{ .port }} }
  periodSeconds: {{ .probes.liveness.periodSeconds }}
  failureThreshold: {{ .probes.liveness.failureThreshold }}
{{- end }}

{{/* ESPN host and User-Agent, for every workload that calls ESPN. Arg: root context. */}}
{{- define "sports-notifier.espnEnv" -}}
- name: ESPN_HOST
  value: {{ .Values.espn.host | quote }}
- name: ESPN_USER_AGENT
  value: {{ .Values.espn.userAgent | quote }}
{{- end }}
