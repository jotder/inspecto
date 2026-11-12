{{- define "inspecto.fullname" -}}{{ .Release.Name | trunc 63 | trimSuffix "-" }}{{- end -}}
{{/* Selector labels: stable across upgrades, so a StatefulSet's selector never changes (it is immutable). */}}
{{- define "inspecto.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{/* Every object's labels: the selector labels plus the version and the Product build (PA-8, D-PA27). */}}
{{- define "inspecto.labels" -}}
{{ include "inspecto.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
inspecto.io/product: {{ .Values.product.id | quote }}
inspecto.io/edition: {{ .Values.product.edition | quote }}
{{- end -}}
{{- define "inspecto.secretName" -}}
{{- if .Values.secrets.existingSecret -}}{{ .Values.secrets.existingSecret }}{{- else -}}{{ include "inspecto.fullname" . }}-env{{- end -}}
{{- end -}}
