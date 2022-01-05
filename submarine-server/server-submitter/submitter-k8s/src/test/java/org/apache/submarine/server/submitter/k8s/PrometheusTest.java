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

package org.apache.submarine.server.submitter.k8s;


import com.coreos.monitoring.models.V1PodMonitorSpec;
import com.coreos.monitoring.models.V1PodMonitorSpecPodMetricsEndpoints;
import com.coreos.monitoring.models.V1PodMonitorSpecSelector;
import com.coreos.monitoring.models.V1ServiceMonitorSpecNamespaceSelector;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.submarine.server.submitter.k8s.model.prometheus.PodMonitor;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

public class PrometheusTest {

  private K8sSubmitter submitter;

  private PodMonitor podMonitor;

  @Before
  public void before() throws ApiException {
    submitter = new K8sSubmitter();
    submitter.initialize(null);

    // check if pod monitor crd is exists

    // init pod monitor
    podMonitor = new PodMonitor();
    V1ObjectMeta meta = new V1ObjectMeta();
    meta.setNamespace("submarine");
    meta.setName("notebook-test");
    meta.setLabels(Collections.singletonMap("k8s-app-pod", "true"));
    podMonitor.setMetadata(meta);

    // init spec
    V1PodMonitorSpec spec = new V1PodMonitorSpec();
    spec.addPodMetricsEndpointsItem(new V1PodMonitorSpecPodMetricsEndpoints()
        .path("/notebook/submarine/test/metrics")
        .port("notebook-port"));
    spec.setNamespaceSelector(new V1ServiceMonitorSpecNamespaceSelector().addMatchNamesItem("submarine"));
    spec.setSelector(new V1PodMonitorSpecSelector().putMatchLabelsItem("statefulset", "test"));
    podMonitor.setSpec(spec);
  }

  @Test
  public void testCreateAndReplacePodMonitor() {
    podMonitor.createPodMonitor(submitter.getApi());
    podMonitor.replacePodMonitor(submitter.getApi());
  }

  @Test
  public void testDeletePodMonitor() {
    podMonitor.deletePodMonitor(submitter.getApi());
  }
}
