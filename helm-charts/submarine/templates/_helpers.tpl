#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

{{/*
Set up storage class fields
*/}}
{{ define "storageClass.fields" }}
{{ with .Values.storageClass }}
reclaimPolicy: {{ .reclaimPolicy | default "Delete" }}
volumeBindingMode: {{ .volumeBindingMode | default "Immediate" }}
provisioner: {{ .provisioner | default "k8s.io/minikube-hostpath" }}
{{ if .parameters }}
parameters:
  {{ range $key, $val := .parameters }}
  {{ $key }}: {{ $val | quote }}
  {{ end }}
{{ end }}
{{ end }}
{{ end }}

{{/*
Return the appropriate apiGroup for PodSecurityPolicy.
*/}}
{{- define "podSecurityPolicy.apiGroup" -}}
{{- if semverCompare ">=1.14-0" .Capabilities.KubeVersion.GitVersion -}}
{{- print "policy" -}}
{{- else -}}
{{- print "extensions" -}}
{{- end -}}
{{- end -}}

{{- define "podSecurityPolicy.name" -}}
{{- printf "%s-security-policy" .Release.Name -}}
{{-end }}
