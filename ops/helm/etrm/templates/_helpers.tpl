{{- define "etrm.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "etrm.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "etrm.labels" -}}
app.kubernetes.io/name: {{ include "etrm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "etrm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "etrm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Database connection: the runtime role and, when separate, the schema owner (deployment and migrations Job). */}}
{{- define "etrm.databaseEnv" -}}
{{- if .Values.database.runtimeSecret -}}
# Requests run as the runtime role; the owner from existingSecret migrates and creates partitions.
- name: ETRM_DB_URL
  value: {{ required "database.url is required with database.runtimeSecret" .Values.database.url | quote }}
- name: ETRM_DB_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.runtimeSecret }}
      key: {{ .Values.database.usernameKey }}
- name: ETRM_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.runtimeSecret }}
      key: {{ .Values.database.passwordKey }}
- name: ETRM_DB_MIGRATION_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret }}
      key: {{ .Values.database.usernameKey }}
- name: ETRM_DB_MIGRATION_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret }}
      key: {{ .Values.database.passwordKey }}
{{- else -}}
- name: ETRM_DB_URL
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret }}
      key: {{ .Values.database.urlKey }}
- name: ETRM_DB_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret }}
      key: {{ .Values.database.usernameKey }}
- name: ETRM_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret }}
      key: {{ .Values.database.passwordKey }}
{{- end }}
{{- end -}}
