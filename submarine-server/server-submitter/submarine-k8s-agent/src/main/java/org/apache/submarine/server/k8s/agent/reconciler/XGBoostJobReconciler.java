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

package org.apache.submarine.server.k8s.agent.reconciler;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.apache.submarine.server.api.common.CustomResourceType;
import org.apache.submarine.server.k8s.agent.model.training.resource.XGBoostJob;

@ControllerConfiguration
public class XGBoostJobReconciler extends JobReconciler<XGBoostJob> implements Reconciler<XGBoostJob> {

  @Override
  public UpdateControl<XGBoostJob> reconcile(XGBoostJob xgBoostJob, Context<XGBoostJob> context) throws Exception {
    triggerStatus(xgBoostJob);
    return UpdateControl.noUpdate();
  }

  @Override
  public CustomResourceType type() {
    return CustomResourceType.XGBoost;
  }
}
