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

package org.apache.submarine.server.submitter.k8s.model;

import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1DeleteOptionsBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.submarine.commons.utils.exception.SubmarineRuntimeException;
import org.apache.submarine.server.api.notebook.Notebook;
import org.apache.submarine.server.api.spec.NotebookSpec;
import org.apache.submarine.server.submitter.k8s.parser.NotebookSpecParser;
import org.apache.submarine.server.submitter.k8s.util.NotebookUtils;
import org.apache.submarine.server.submitter.k8s.util.OwnerReferenceUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotebookCR {

  private static final Logger LOG = LoggerFactory.getLogger(NotebookCR.class);

  public static final String CRD_NOTEBOOK_VERSION_V1 = "v1";
  public static final String CRD_NOTEBOOK_GROUP_V1 = "kubeflow.org";
  public static final String CRD_APIVERSION_V1 = CRD_NOTEBOOK_GROUP_V1 + "/" + CRD_NOTEBOOK_VERSION_V1;
  public static final String CRD_NOTEBOOK_KIND_V1 = "Notebook";
  public static final String CRD_NOTEBOOK_PLURAL_V1 = "notebooks";
  public static final String NOTEBOOK_OWNER_SELECTOR_KEY = "notebook-owner-id";
  public static final String NOTEBOOK_ID = "notebook-id";

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
  private NotebookCRSpec spec;

  @SerializedName("status")
  private NotebookStatus status;

  public NotebookCR() {
    setApiVersion(CRD_APIVERSION_V1);
    setKind(CRD_NOTEBOOK_KIND_V1);
    setPlural(CRD_NOTEBOOK_PLURAL_V1);
    setGroup(CRD_NOTEBOOK_GROUP_V1);
    setVersion(CRD_NOTEBOOK_VERSION_V1);
  }

  public NotebookCR(NotebookSpec spec, String namespace) {
    this();
    try {
      this.setMetadata(NotebookSpecParser.parseMetadata(spec));
      this.setSpec(NotebookSpecParser.parseNotebookCRSpec(spec));
      getMetadata().setNamespace(namespace);
      getMetadata().setOwnerReferences(OwnerReferenceUtils.getOwnerReference());
    } catch (JsonSyntaxException e) {
      LOG.error("K8s submitter: parse response object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(500, "K8s Submitter parse upstream response failed.");
    }
  }

  /**
   * Delete Notebook CRD
   */
  public Notebook deleteNotebookCRD(CustomObjectsApi api) {
    Notebook notebook = null;
    try {
      Object object = api.deleteNamespacedCustomObject(this.getGroup(), this.getVersion(),
              getMetadata().getNamespace(), this.getPlural(),
              this.getMetadata().getName(), null, null, null,
              null, new V1DeleteOptionsBuilder().withApiVersion(this.getApiVersion()).build());
      notebook = NotebookUtils.parseObject(object, NotebookUtils.ParseOpt.PARSE_OPT_DELETE);
    } catch (ApiException e) {
      NotebookUtils.API_EXCEPTION_404_CONSUMER.accept(e);
    } finally {
      if (notebook == null) {
        // add metadata time info
        this.getMetadata().setDeletionTimestamp(new DateTime());
        // build notebook response
        notebook = NotebookUtils.buildNotebookResponse(this);
        notebook.setStatus(Notebook.Status.STATUS_NOT_FOUND.getValue());
        notebook.setReason("The notebook instance is not found");
      }
    }
    return notebook;
  }

  public Notebook createNotebookCRD(CustomObjectsApi api) {
    return createNotebookCRD(api, false);
  }

  /**
   * Create Notebook CRD
   * @param api CustomObjectsApi
   * @param tolerate Update when create conflicts
   * @return Notebook
   */
  public Notebook createNotebookCRD(CustomObjectsApi api, boolean tolerate) {
    Notebook notebook = null;
    try {
      Object object = api.createNamespacedCustomObject(this.getGroup(), this.getVersion(),
              getMetadata().getNamespace(), this.getPlural(), this, "true", null, null);
      notebook = NotebookUtils.parseObject(object, NotebookUtils.ParseOpt.PARSE_OPT_CREATE);
    } catch (JsonSyntaxException e) {
      LOG.error("K8s submitter: parse response object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(500, "K8s Submitter parse upstream response failed.");
    } catch (ApiException e) {
      if (e.getCode() == 409 && tolerate) {// conflict
        // todo need to replace CRD
        LOG.warn("K8s submitter: resource already exists, need to replace it.", e);
        notebook = NotebookUtils.parseObject(this, NotebookUtils.ParseOpt.PARSE_OPT_REPLACE);
      } else {
        LOG.error("K8s submitter: parse Notebook object failed by " + e.getMessage(), e);
        throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: parse Notebook object failed by " +
                e.getMessage());
      }
    }
    return notebook;
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

  public String getGroup() {
    return this.group;
  }

  public void setGroup(String group) {
    this.group = group;
  }

  public String getVersion() {
    return this.version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getPlural() {
    return this.plural;
  }

  public void setPlural(String plural) {
    this.plural = plural;
  }

  public NotebookCRSpec getSpec() {
    return spec;
  }

  public void setSpec(NotebookCRSpec spec) {
    this.spec = spec;
  }

  public NotebookStatus getStatus() {
    return status;
  }

  public void setStatus(NotebookStatus status) {
    this.status = status;
  }
}
