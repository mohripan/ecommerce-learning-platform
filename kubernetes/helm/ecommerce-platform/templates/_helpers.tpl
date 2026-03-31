{{/*
Expand the name of the chart.
*/}}
{{- define "ecommerce-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "ecommerce-platform.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "ecommerce-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "ecommerce-platform.labels" -}}
helm.sh/chart: {{ include "ecommerce-platform.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: ecommerce-platform
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{/*
Service-specific labels
*/}}
{{- define "ecommerce-platform.serviceLabels" -}}
{{ include "ecommerce-platform.labels" . }}
app.kubernetes.io/name: {{ .serviceName }}
app.kubernetes.io/instance: {{ .Release.Name }}-{{ .serviceName }}
app: {{ .serviceName }}
version: {{ .serviceValues.image.tag | default .Chart.AppVersion | quote }}
{{- end }}

{{/*
Service-specific selector labels
*/}}
{{- define "ecommerce-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ .serviceName }}
app.kubernetes.io/instance: {{ .Release.Name }}-{{ .serviceName }}
{{- end }}

{{/*
Namespace
*/}}
{{- define "ecommerce-platform.namespace" -}}
{{- default "ecommerce" .Values.global.namespace }}
{{- end }}

{{/*
ConfigMap name
*/}}
{{- define "ecommerce-platform.configmapName" -}}
{{- printf "%s-config" (include "ecommerce-platform.fullname" .) }}
{{- end }}
