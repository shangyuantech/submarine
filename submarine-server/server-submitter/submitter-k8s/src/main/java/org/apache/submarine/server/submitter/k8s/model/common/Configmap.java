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

package org.apache.submarine.server.submitter.k8s.model.common;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1DeleteOptionsBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.commons.lang3.StringUtils;
import org.apache.submarine.commons.utils.exception.SubmarineRuntimeException;
import org.apache.submarine.server.submitter.k8s.K8sApi;
import org.apache.submarine.server.submitter.k8s.K8sSubmitter;
import org.apache.submarine.server.submitter.k8s.model.K8sResource;
import org.apache.submarine.server.submitter.k8s.util.JsonUtils;
import org.apache.submarine.server.submitter.k8s.util.OwnerReferenceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class Configmap extends V1ConfigMap implements K8sResource {

  private static final Logger LOG = LoggerFactory.getLogger(Configmap.class);

  public Configmap(String namespace, String name, String... values) {
    Map<String, String> datas = new LinkedHashMap<>();
    for (int i = 0, size = values.length; i < size; i += 2) {
      try {
        datas.put(values[i], values[i + 1]);
      } catch (ArrayIndexOutOfBoundsException e) {// Avoid values by odd numbers
        LOG.warn("Can not find ConfigMap value in index[{}], skip this value", i + 1);
      }
    }
    init(namespace, name, datas);
  }

  public Configmap(String namespace, String name, Map<String, String> datas) {
    init(namespace, name, datas);
  }

  private void init(String namespace, String name, Map<String, String> datas) {
    /*
      Required value
      1. metadata.name
      2. metadata.namespace
      3. spec.data
      4. spec.resources
      Others are not necessary
     */
    V1ObjectMeta metadata = new V1ObjectMeta();
    metadata.setNamespace(namespace);
    metadata.setName(name);
    metadata.setOwnerReferences(OwnerReferenceUtils.getOwnerReference());;
    this.setMetadata(metadata);
    this.data(datas);
  }

  @Override
  public Configmap read(K8sApi api) {
    return this;
  }

  public void resetMeta(K8sApi api) {
    try {
      Object object = api.getCoreApi().readNamespacedConfigMap(this.getMetadata().getName(),
          this.getMetadata().getNamespace(), null, null, null);
      if (object != null) {
        String jsonString = JsonUtils.toJson(((Map<String, Object>) object).get("metadata"));
        V1ObjectMeta meta = JsonUtils.fromJson(jsonString, V1ObjectMeta.class);
        this.setMetadata(meta);
      }
    } catch (ApiException e) {
      LOG.error("K8s submitter: parse configmap object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: parse configmap object failed by " +
              e.getMessage());
    }
  }

  @Override
  public Object create(K8sApi api) {
    try {
      return api.getCoreApi().createNamespacedConfigMap(this.getMetadata().getNamespace(), this, "true",
          null, null);
    } catch (ApiException e) {
      LOG.error("Exception when creating configmap " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: create configmap failed by " +
              e.getMessage());
    }
  }

  @Override
  public Object replace(K8sApi api) {
    try {
      // reset metadata to get resource version so that we can replace configmap
      if (StringUtils.isBlank(this.getMetadata().getResourceVersion())) {
        resetMeta(api);
      }
      // replace
      return api.getCoreApi().replaceNamespacedConfigMap(this.getMetadata().getName(),
          getMetadata().getNamespace(), this, "true", null, null);
    } catch (ApiException e) {
      LOG.error("K8s submitter: replace configmap object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: replace configmap object failed by " +
              e.getMessage());
    }
  }

  @Override
  public Object delete(K8sApi api) {
    try {
      return api.getCoreApi().deleteNamespacedConfigMap(this.getMetadata().getName(),
          this.getMetadata().getNamespace(), "true", null, null,
          null, null, new V1DeleteOptionsBuilder().withApiVersion(this.getApiVersion()).build());
    } catch (ApiException e) {
      return K8sSubmitter.API_EXCEPTION_404_CONSUMER.apply(e);
    }
  }
}
