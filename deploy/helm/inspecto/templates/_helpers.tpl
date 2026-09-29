{{- define "inspecto.fullname" -}}{{ .Release.Name | trunc 63 | trimSuffix "-" }}{{- end -}}
{{- define "inspecto.labels" -}}
app.kubernetes.io/name: inspecto
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "inspecto.secretName" -}}
{{- if .Values.secrets.existingSecret -}}{{ .Values.secrets.existingSecret }}{{- else -}}{{ include "inspecto.fullname" . }}-env{{- end -}}
{{- end -}}
