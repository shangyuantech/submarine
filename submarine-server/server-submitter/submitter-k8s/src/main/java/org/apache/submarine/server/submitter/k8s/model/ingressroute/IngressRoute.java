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

package org.apache.submarine.server.submitter.k8s.model.ingressroute;

import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1DeleteOptionsBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.submarine.commons.utils.exception.SubmarineRuntimeException;
import org.apache.submarine.server.submitter.k8s.K8sApi;
import org.apache.submarine.server.submitter.k8s.K8sSubmitter;
import org.apache.submarine.server.submitter.k8s.model.K8sResource;
import org.apache.submarine.server.submitter.k8s.util.OwnerReferenceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class IngressRoute implements K8sResource {

  private static final Logger LOG = LoggerFactory.getLogger(IngressRoute.class);

  public static final String CRD_INGRESSROUTE_GROUP_V1 = "traefik.containo.us";
  public static final String CRD_INGRESSROUTE_VERSION_V1 = "v1alpha1";
  public static final String CRD_APIVERSION_V1 = CRD_INGRESSROUTE_GROUP_V1 +
          "/" + CRD_INGRESSROUTE_VERSION_V1;
  public static final String CRD_INGRESSROUTE_KIND_V1 = "IngressRoute";
  public static final String CRD_INGRESSROUTE_PLURAL_V1 = "ingressroutes";

  @SerializedName("apiVersion")
  private String apiVersion;

  @SerializedName("kind")
  private String kind;

  @SerializedName("metadata")
  private V1ObjectMeta metadata;

  private transient String group;

  private transient String version;

  private transient String plural;

  @SerializedName("spec")
  private IngressRouteSpec spec;

  public IngressRoute() {
    setApiVersion(CRD_APIVERSION_V1);
    setKind(CRD_INGRESSROUTE_KIND_V1);
    setPlural(CRD_INGRESSROUTE_PLURAL_V1);
    setGroup(CRD_INGRESSROUTE_GROUP_V1);
    setVersion(CRD_INGRESSROUTE_VERSION_V1);
  }

  public IngressRoute(String namespace, String name) {
    this();
    V1ObjectMeta meta = new V1ObjectMeta();
    meta.setName(name);
    meta.setNamespace(namespace);
    meta.setOwnerReferences(OwnerReferenceUtils.getOwnerReference());
    this.setMetadata(meta);
    this.setSpec(parseIngressRouteSpec(meta.getNamespace(), meta.getName()));
  }

  private IngressRouteSpec parseIngressRouteSpec(String namespace, String name) {
    IngressRouteSpec spec = new IngressRouteSpec();
    Set<String> entryPoints = new HashSet<>();
    entryPoints.add("web");
    spec.setEntryPoints(entryPoints);

    SpecRoute route = new SpecRoute();
    route.setKind("Rule");
    route.setMatch("PathPrefix(`/notebook/" + namespace + "/" + name + "/`)");
    Set<Map<String, Object>> serviceMap = new HashSet<>();
    Map<String, Object> service = new HashMap<>();
    service.put("name", name);
    service.put("port", 80);
    serviceMap.add(service);
    route.setServices(serviceMap);
    Set<SpecRoute> routes = new HashSet<>();
    routes.add(route);
    spec.setRoutes(routes);
    return spec;
  }

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = apiVersion;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public V1ObjectMeta getMetadata() {
    return metadata;
  }

  public void setMetadata(V1ObjectMeta metadata) {
    this.metadata = metadata;
  }

  public String getPlural() {
    return plural;
  }

  public void setPlural(String plural) {
    this.plural = plural;
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

  public IngressRouteSpec getSpec() {
    return spec;
  }

  public void setSpec(IngressRouteSpec spec) {
    this.spec = spec;
  }

  @Override
  public IngressRoute read(K8sApi api) {
    return this;
  }

  @Override
  public Object create(K8sApi api) {
    try {
      return api.getApi().createNamespacedCustomObject(
              this.getGroup(), this.getVersion(),
              this.getMetadata().getNamespace(),
              this.getPlural(), this, "true", null, null);
    } catch (ApiException e) {
      LOG.error("K8s submitter: Create Traefik custom resource object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    } catch (JsonSyntaxException e) {
      LOG.error("K8s submitter: parse response object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(500, "K8s Submitter parse upstream response failed.");
    }
  }

  @Override
  public Object replace(K8sApi api) {
    return null;
  }

  @Override
  public Object delete(K8sApi api) {
    try {
      return api.getApi().deleteNamespacedCustomObject(getGroup(), getVersion(),
          this.getMetadata().getNamespace(), getPlural(), this.getMetadata().getName(),
          null, null, null,
          null, new V1DeleteOptionsBuilder().withApiVersion(IngressRoute.CRD_APIVERSION_V1).build());
    } catch (ApiException e) {
      return K8sSubmitter.API_EXCEPTION_404_CONSUMER.apply(e);
    }
  }
}
