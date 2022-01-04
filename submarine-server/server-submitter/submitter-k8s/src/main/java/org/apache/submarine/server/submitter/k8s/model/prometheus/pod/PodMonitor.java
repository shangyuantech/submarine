/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.submarine.server.submitter.k8s.model.prometheus.pod;

import com.coreos.monitoring.models.V1PodMonitor;
import com.coreos.monitoring.models.V1PodMonitorSpec;
import com.coreos.monitoring.models.V1PodMonitorSpecPodMetricsEndpoints;
import com.coreos.monitoring.models.V1PodMonitorSpecSelector;
import com.coreos.monitoring.models.V1ServiceMonitorSpecNamespaceSelector;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1DeleteOptionsBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.commons.lang3.StringUtils;
import org.apache.submarine.commons.utils.SubmarineConfVars;
import org.apache.submarine.commons.utils.SubmarineConfiguration;
import org.apache.submarine.commons.utils.exception.SubmarineRuntimeException;
import org.apache.submarine.server.submitter.k8s.model.NotebookCR;
import org.apache.submarine.server.submitter.k8s.util.JsonUtils;
import org.apache.submarine.server.submitter.k8s.util.NotebookUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class PodMonitor extends V1PodMonitor {

  private static final Logger LOG = LoggerFactory.getLogger(PodMonitor.class);

  private static final Map<String, String> PROMETHEUS_MONITOR_LABELS = new LinkedHashMap<>();

  static {
    final SubmarineConfiguration conf = SubmarineConfiguration.getInstance();
    String labels = conf.getString(
            SubmarineConfVars.ConfVars.SUBMARINE_NOTEBOOK_PROMETHEUS_LABELS);
    if (StringUtils.isNotBlank(labels)) {
      String[] labelsSplit = labels.split(",");
      for (String label : labelsSplit) {
        String[] labelKV = label.split(":");
        PROMETHEUS_MONITOR_LABELS.put(labelKV[0], labelKV[1]);
      }
    }
  }

  public static final String CRD_POD_MONITOR_KIND_V1 = "PodMonitor";
  public static final String CRD_POD_MONITOR_PLURAL_V1 = "podmonitors";
  public static final String CRD_POD_MONITOR_GROUP_V1 = "monitoring.coreos.com";
  public static final String CRD_POD_MONITOR_VERSION_V1 = "v1";
  public static final String CRD_POD_MONITOR_API_VERSION_V1 = CRD_POD_MONITOR_GROUP_V1 +
      "/" + CRD_POD_MONITOR_VERSION_V1;

  public PodMonitor() {
    setApiVersion(CRD_POD_MONITOR_API_VERSION_V1);
    setKind(CRD_POD_MONITOR_KIND_V1);
    setPlural(CRD_POD_MONITOR_PLURAL_V1);
    setGroup(CRD_POD_MONITOR_GROUP_V1);
    setVersion(CRD_POD_MONITOR_VERSION_V1);
  }

  public PodMonitor(NotebookCR notebook) {
    this();
    String name = notebook.getMetadata().getName();
    String namespace = notebook.getMetadata().getNamespace();

    // init meta from notebook
    V1ObjectMeta meta = new V1ObjectMeta();
    meta.setNamespace(namespace);
    meta.setName(String.format("submarine-%s", name));
    meta.setLabels(PROMETHEUS_MONITOR_LABELS);
    this.setMetadata(meta);
    // init spec
    V1PodMonitorSpec spec = new V1PodMonitorSpec();
    spec.addPodMetricsEndpointsItem(new V1PodMonitorSpecPodMetricsEndpoints()
        .path(String.format("/notebook/submarine/%s/metrics", name))
        .port("notebook-port"));
    spec.setNamespaceSelector(new V1ServiceMonitorSpecNamespaceSelector().addMatchNamesItem(namespace));
    spec.setSelector(new V1PodMonitorSpecSelector().putMatchLabelsItem("statefulset", name));
    this.setSpec(spec);
  }

  private transient String group;

  private transient String version;

  private transient String plural;

  /**
   * Reset Metadata so that we can replace PodMonitor
   */
  private void resetMeta(CustomObjectsApi api) {
    try {
      Object object = api.getNamespacedCustomObject(this.getGroup(), this.getVersion(),
          this.getMetadata().getNamespace(), this.getPlural(), this.getMetadata().getName());
      if (object != null) {
        String jsonString = JsonUtils.toJson(((Map<String, Object>) object).get("metadata"));
        V1ObjectMeta meta = JsonUtils.fromJson(jsonString, V1ObjectMeta.class);
        meta.setLabels(this.getMetadata().getLabels());
        this.setMetadata(meta);
      }
    } catch (ApiException e) {
      LOG.error("K8s submitter: parse PodMonitor object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: parse PodMonitor object failed by " +
              e.getMessage());
    }
  }

  /**
   * Create PodMonitor
   */
  public void createPodMonitor(CustomObjectsApi api) {
    try {
      api.createNamespacedCustomObject(this.getGroup(), this.getVersion(),
          this.getMetadata().getNamespace(), this.getPlural(), this, "true", null, null);
    } catch (ApiException e) {
      if (e.getCode() == 409) {// conflict
        LOG.warn("K8s submitter: resource already exists, need to replace it.", e);
        this.replacePodMonitor(api);
      } else {
        LOG.error("K8s submitter: create PodMonitor object failed by " + e.getMessage(), e);
        throw new SubmarineRuntimeException(e.getCode(),
                "K8s submitter: create PodMonitor object failed by " + e.getMessage());
      }
    }
  }

  /**
   * Replace PodMonitor
   */
  public void replacePodMonitor(CustomObjectsApi api) {
    try {
      // reset metadata to get resource version so that we can replace PodMonitor
      if (StringUtils.isBlank(this.getMetadata().getResourceVersion())) {
        resetMeta(api);
      }
      // replace
      api.replaceNamespacedCustomObject(this.getGroup(), this.getVersion(),
          getMetadata().getNamespace(), this.getPlural(), this.getMetadata().getName(), this, null, null);
    } catch (ApiException e) {
      LOG.error("K8s submitter: replace PodMonitor object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: replace PodMonitor object failed by " +
          e.getMessage());
    }
  }

  /**
   * Delete PodMonitor
   */
  public void deletePodMonitor(CustomObjectsApi api) {
    try {
      api.deleteNamespacedCustomObject(this.getGroup(), this.getVersion(),
          this.getMetadata().getNamespace(), this.getPlural(),
          this.getMetadata().getName(), null, null, null,
          null, new V1DeleteOptionsBuilder().withApiVersion(this.getApiVersion()).build());
    } catch (ApiException e) {
      NotebookUtils.API_EXCEPTION_404_CONSUMER.accept(e);
    }
  }

  public String getGroup() {
    return group;
  }

  public void setGroup(String group) {
    this.group = group;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getPlural() {
    return plural;
  }

  public void setPlural(String plural) {
    this.plural = plural;
  }

}
