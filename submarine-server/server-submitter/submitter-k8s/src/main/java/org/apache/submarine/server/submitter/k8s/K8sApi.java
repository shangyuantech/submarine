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

import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;

public class K8sApi {

  private final CustomObjectsApi api;

  private final CoreV1Api coreApi;

  private final AppsV1Api appsV1Api;

  public K8sApi(CustomObjectsApi api, CoreV1Api coreApi, AppsV1Api appsV1Api) {
    this.api = api;
    this.coreApi = coreApi;
    this.appsV1Api = appsV1Api;
  }

  public CustomObjectsApi getApi() {
    return api;
  }

  public CoreV1Api getCoreApi() {
    return coreApi;
  }

  public AppsV1Api getAppsV1Api() {
    return appsV1Api;
  }
}
