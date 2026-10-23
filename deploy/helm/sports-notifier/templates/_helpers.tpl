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
OpenTelemetry settings (Phase 4.3), only when observability is on: without an endpoint the service's
telemetry stays off. The SDK reads these itself. Arg: dict with "root" (root context) and "name" (the
service key, which names its spans and metrics).
*/}}
{{- define "sports-notifier.otelEnv" -}}
{{- if .root.Values.observability.enabled }}
- name: OTEL_SERVICE_NAME
  value: {{ .name | quote }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: http://lgtm:4318
- name: OTEL_EXPORTER_OTLP_PROTOCOL
  value: http/protobuf
- name: OTEL_TRACES_SAMPLER
  value: parentbased_traceidratio
- name: OTEL_TRACES_SAMPLER_ARG
  value: {{ .root.Values.observability.sampleRate | quote }}
# A 60x replay lasts about 3 minutes; the default 60 s push would draw three points.
- name: OTEL_METRIC_EXPORT_INTERVAL
  value: "5000"
# One touchdown with 500 followers is over 1,000 spans at once; the default queue (2,048) could drop some.
- name: OTEL_BSP_MAX_QUEUE_SIZE
  value: "8192"
- name: OTEL_LOGS_EXPORTER
  value: none
{{- end }}
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

{{/* Postgres address and login, for every workload that uses Postgres. Arg: root context. */}}
{{- define "sports-notifier.postgresEnv" -}}
- name: PG_URL
  value: jdbc:postgresql://postgres:5432/sportsnotifier
- name: PG_USER
  value: sportsnotifier
- name: PG_PASSWORD
  valueFrom:
    secretKeyRef: { name: postgres, key: password }
{{- end }}

{{/* The key that lets the checker sign up ck_ users (#51; templates/checker-key.yaml). */}}
{{- define "sports-notifier.checkerKeyEnv" -}}
- name: CHECKER_KEY
  valueFrom:
    secretKeyRef: { name: checker-key, key: key }
{{- end }}

{{/* The Kafka bootstrap address (a Service Strimzi creates), for every workload that uses Kafka. Arg: root context. */}}
{{- define "sports-notifier.kafkaEnv" -}}
- name: KAFKA_BOOTSTRAP
  value: {{ printf "%s-kafka-bootstrap:9092" .Release.Name | quote }}
{{- end }}
