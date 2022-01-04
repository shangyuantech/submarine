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

import com.google.gson.annotations.SerializedName;
import org.apache.submarine.server.submitter.k8s.model.prometheus.common.Endpoint;
import org.apache.submarine.server.submitter.k8s.model.prometheus.common.NamespaceSelector;
import org.apache.submarine.server.submitter.k8s.model.prometheus.common.Selector;

import java.util.ArrayList;
import java.util.List;

public class PodMonitorSpec {

  @SerializedName("selector")
  private Selector selector;

  @SerializedName("podMetricsEndpoints")
  private List<Endpoint> podMetricsEndpoints = new ArrayList<>();

  @SerializedName("namespaceSelector")
  private NamespaceSelector namespaceSelector;

  public Selector getSelector() {
    return selector;
  }

  public void setSelector(Selector selector) {
    this.selector = selector;
  }

  public List<Endpoint> getPodMetricsEndpoints() {
    return podMetricsEndpoints;
  }

  public void setPodMetricsEndpoints(List<Endpoint> podMetricsEndpoints) {
    this.podMetricsEndpoints = podMetricsEndpoints;
  }

  public void addEndpoint(Endpoint endpoint) {
    this.podMetricsEndpoints.add(endpoint);
  }

  public NamespaceSelector getNamespaceSelector() {
    return namespaceSelector;
  }

  public void setNamespaceSelector(NamespaceSelector namespaceSelector) {
    this.namespaceSelector = namespaceSelector;
  }

}
