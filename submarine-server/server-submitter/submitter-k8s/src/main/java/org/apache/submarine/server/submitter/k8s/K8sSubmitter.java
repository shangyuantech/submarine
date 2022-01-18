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

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import okhttp3.OkHttpClient;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1DeleteOptionsBuilder;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.util.Watch;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;

import org.apache.commons.lang3.StringUtils;
import org.apache.submarine.commons.utils.SubmarineConfiguration;
import org.apache.submarine.commons.utils.exception.SubmarineRuntimeException;
import org.apache.submarine.serve.istio.IstioVirtualService;
import org.apache.submarine.serve.pytorch.SeldonPytorchServing;
import org.apache.submarine.serve.seldon.SeldonDeployment;
import org.apache.submarine.serve.tensorflow.SeldonTFServing;
import org.apache.submarine.server.api.Submitter;
import org.apache.submarine.server.api.exception.InvalidSpecException;
import org.apache.submarine.server.api.experiment.Experiment;
import org.apache.submarine.server.api.experiment.ExperimentLog;
import org.apache.submarine.server.api.experiment.MlflowInfo;
import org.apache.submarine.server.api.experiment.TensorboardInfo;
import org.apache.submarine.server.api.model.ServeSpec;
import org.apache.submarine.server.api.notebook.Notebook;
import org.apache.submarine.server.api.spec.ExperimentMeta;
import org.apache.submarine.server.api.spec.ExperimentSpec;
import org.apache.submarine.server.api.spec.NotebookSpec;
import org.apache.submarine.server.submitter.k8s.model.MLJob;
import org.apache.submarine.server.submitter.k8s.model.NotebookCR;
import org.apache.submarine.server.submitter.k8s.model.common.Configmap;
import org.apache.submarine.server.submitter.k8s.model.K8sResource;
import org.apache.submarine.server.submitter.k8s.model.common.NullResource;
import org.apache.submarine.server.submitter.k8s.model.common.PersistentVolumeClaim;
import org.apache.submarine.server.submitter.k8s.model.ingressroute.IngressRoute;
import org.apache.submarine.server.submitter.k8s.model.prometheus.PodMonitor;
import org.apache.submarine.server.submitter.k8s.model.pytorchjob.PyTorchJob;
import org.apache.submarine.server.submitter.k8s.model.tfjob.TFJob;
import org.apache.submarine.server.submitter.k8s.parser.ExperimentSpecParser;
import org.apache.submarine.server.submitter.k8s.parser.NotebookSpecParser;
import org.apache.submarine.server.submitter.k8s.util.MLJobConverter;
import org.apache.submarine.server.submitter.k8s.util.NotebookUtils;
import org.apache.submarine.server.submitter.k8s.util.OwnerReferenceUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JobSubmitter for Kubernetes Cluster.
 */
public class K8sSubmitter implements Submitter {

  private static final Logger LOG = LoggerFactory.getLogger(K8sSubmitter.class);

  private static final String KUBECONFIG_ENV = "KUBECONFIG";

  private static final String TF_JOB_SELECTOR_KEY = "tf-job-name=";
  private static final String PYTORCH_JOB_SELECTOR_KEY = "pytorch-job-name=";

  // Add an exception Consumer, handle the problem that delete operation does not have the resource
  public static final Function<ApiException, Object> API_EXCEPTION_404_CONSUMER = e -> {
    if (e.getCode() != 404) {
      LOG.error("When submit resource to k8s get ApiException with code " + e.getCode(), e);
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    } else {
      return null;
    }
  };

  // K8s API client for CRD
  private CustomObjectsApi api;

  private CoreV1Api coreApi;

  private AppsV1Api appsV1Api;

  private ApiClient client = null;

  private K8sApi k8sApi;

  @VisibleForTesting
  protected K8sApi getK8sApi() {
    return k8sApi;
  }

  public K8sSubmitter() {
  }

  @Override
  public void initialize(SubmarineConfiguration conf) {
    try {
      String path = System.getenv(KUBECONFIG_ENV);
      KubeConfig config = KubeConfig.loadKubeConfig(new FileReader(path));
      client = ClientBuilder.kubeconfig(config).build();
    } catch (Exception e) {
      LOG.info("Maybe in cluster mode, try to initialize the client again.");
      try {
        client = ClientBuilder.cluster().build();
      } catch (IOException e1) {
        LOG.error("Initialize K8s submitter failed. " + e.getMessage(), e1);
        throw new SubmarineRuntimeException(500, "Initialize K8s submitter failed.");
      }
    } finally {
      // let watcher can wait until the next change
      client.setReadTimeout(0);
      OkHttpClient httpClient = client.getHttpClient();
      client.setHttpClient(httpClient);
      Configuration.setDefaultApiClient(client);
    }

    if (api == null) {
      api = new CustomObjectsApi();
    }
    if (coreApi == null) {
      coreApi = new CoreV1Api(client);
    }
    if (appsV1Api == null) {
      appsV1Api = new AppsV1Api();
    }
    if (k8sApi == null) {
      k8sApi = new K8sApi(api, coreApi, appsV1Api);
    }

    try {
      watchExperiment();
    } catch (Exception e){
      LOG.error("Experiment watch failed. " + e.getMessage(), e);
    }

  }

  @Override
  public Experiment createExperiment(ExperimentSpec spec) throws SubmarineRuntimeException {
    Experiment experiment;
    try {
      MLJob mlJob = ExperimentSpecParser.parseJob(spec);
      mlJob.getMetadata().setNamespace(getServerNamespace());
      mlJob.getMetadata().setOwnerReferences(OwnerReferenceUtils.getOwnerReference());

      Object object = api.createNamespacedCustomObject(mlJob.getGroup(), mlJob.getVersion(),
          mlJob.getMetadata().getNamespace(), mlJob.getPlural(), mlJob, "true", null, null);
      experiment = parseExperimentResponseObject(object, ParseOp.PARSE_OP_RESULT);
    } catch (InvalidSpecException e) {
      LOG.error("K8s submitter: parse Job object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(400, e.getMessage());
    } catch (ApiException e) {
      LOG.error("K8s submitter: parse Job object failed by " + e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), "K8s submitter: parse Job object failed by " +
          e.getMessage());
    }
    return experiment;
  }

  @Override
  public Experiment findExperiment(ExperimentSpec spec) throws SubmarineRuntimeException {
    Experiment experiment;
    try {
      MLJob mlJob = ExperimentSpecParser.parseJob(spec);
      mlJob.getMetadata().setNamespace(getServerNamespace());

      Object object = api.getNamespacedCustomObject(mlJob.getGroup(), mlJob.getVersion(),
          mlJob.getMetadata().getNamespace(), mlJob.getPlural(), mlJob.getMetadata().getName());
      experiment = parseExperimentResponseObject(object, ParseOp.PARSE_OP_RESULT);

    } catch (InvalidSpecException e) {
      throw new SubmarineRuntimeException(200, e.getMessage());
    } catch (ApiException e) {
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }

    return experiment;
  }

  @Override
  public Experiment patchExperiment(ExperimentSpec spec) throws SubmarineRuntimeException {
    Experiment experiment;
    try {
      MLJob mlJob = ExperimentSpecParser.parseJob(spec);
      mlJob.getMetadata().setNamespace(getServerNamespace());

      Object object = api.patchNamespacedCustomObject(mlJob.getGroup(), mlJob.getVersion(),
          mlJob.getMetadata().getNamespace(), mlJob.getPlural(), mlJob.getMetadata().getName(),
          mlJob, null, null, false);
      experiment = parseExperimentResponseObject(object, ParseOp.PARSE_OP_RESULT);
    } catch (InvalidSpecException e) {
      throw new SubmarineRuntimeException(200, e.getMessage());
    } catch (ApiException e) {
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
    return experiment;
  }

  @Override
  public Experiment deleteExperiment(ExperimentSpec spec) throws SubmarineRuntimeException {
    Experiment experiment;
    try {
      MLJob mlJob = ExperimentSpecParser.parseJob(spec);
      mlJob.getMetadata().setNamespace(getServerNamespace());

      Object object = api.deleteNamespacedCustomObject(mlJob.getGroup(), mlJob.getVersion(),
          mlJob.getMetadata().getNamespace(), mlJob.getPlural(), mlJob.getMetadata().getName(), 0,
              false, null, null, MLJobConverter.toDeleteOptionsFromMLJob(mlJob));
      experiment = parseExperimentResponseObject(object, ParseOp.PARSE_OP_DELETE);
    } catch (InvalidSpecException e) {
      throw new SubmarineRuntimeException(200, e.getMessage());
    } catch (ApiException e) {
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
    return experiment;
  }

  private Experiment parseExperimentResponseObject(Object object, ParseOp op)
      throws SubmarineRuntimeException {
    Gson gson = new JSON().getGson();
    String jsonString = gson.toJson(object);
    LOG.info("Upstream response JSON: {}", jsonString);
    try {
      if (op == ParseOp.PARSE_OP_RESULT) {
        MLJob mlJob = gson.fromJson(jsonString, MLJob.class);
        return MLJobConverter.toJobFromMLJob(mlJob);
      } else if (op == ParseOp.PARSE_OP_DELETE) {
        V1Status status = gson.fromJson(jsonString, V1Status.class);
        return MLJobConverter.toJobFromStatus(status);
      }
    } catch (JsonSyntaxException e) {
      LOG.error("K8s submitter: parse response object failed by " + e.getMessage(), e);
    }
    throw new SubmarineRuntimeException(500, "K8s Submitter parse upstream response failed.");
  }

  @Override
  public ExperimentLog getExperimentLogName(ExperimentSpec spec, String id) {
    ExperimentLog experimentLog = new ExperimentLog();
    experimentLog.setExperimentId(id);
    try {
      final V1PodList podList = coreApi.listNamespacedPod(
          getServerNamespace(),
          "false", false,  null, null,
          getJobLabelSelector(spec), null, null,
          null, null, null);
      for (V1Pod pod : podList.getItems()) {
        String podName = pod.getMetadata().getName();
        experimentLog.addPodLog(podName, null);
      }
    } catch (final ApiException e) {
      LOG.error("Error when listing pod for experiment:" + spec.getMeta().getName(), e.getMessage());
    }
    return experimentLog;
  }

  @Override
  public ExperimentLog getExperimentLog(ExperimentSpec spec, String id) {
    ExperimentLog experimentLog = new ExperimentLog();
    experimentLog.setExperimentId(id);
    try {
      final V1PodList podList = coreApi.listNamespacedPod(
              getServerNamespace(),
              "false", false,  null, null,
              getJobLabelSelector(spec), null, null,
              null, null, null);

      for (V1Pod pod : podList.getItems()) {
        String podName = pod.getMetadata().getName();
        String podLog = coreApi.readNamespacedPodLog(
            podName, getServerNamespace(), null, Boolean.FALSE, null,
            Integer.MAX_VALUE, null, Boolean.FALSE,
            Integer.MAX_VALUE, null, Boolean.FALSE);

        experimentLog.addPodLog(podName, podLog);
      }
    } catch (final ApiException e) {
      LOG.error("Error when listing pod for experiment:" + spec.getMeta().getName(), e.getMessage());
    }
    return experimentLog;
  }

  @Override
  public TensorboardInfo getTensorboardInfo() throws SubmarineRuntimeException {
    final String name = "submarine-tensorboard";
    final String ingressRouteName = "submarine-tensorboard-ingressroute";
    String namespace = getServerNamespace();

    try {
      V1Deployment deploy = appsV1Api.readNamespacedDeploymentStatus(name, namespace, "true");
      boolean available = deploy.getStatus().getAvailableReplicas() > 0; // at least one replica is running

      IngressRoute ingressRoute = new IngressRoute();
      V1ObjectMeta meta = new V1ObjectMeta();
      meta.setName(ingressRouteName);
      meta.setNamespace(namespace);
      ingressRoute.setMetadata(meta);
      Object object = api.getNamespacedCustomObject(
          ingressRoute.getGroup(), ingressRoute.getVersion(),
          ingressRoute.getMetadata().getNamespace(),
          ingressRoute.getPlural(), ingressRouteName
      );

      Gson gson = new JSON().getGson();
      String jsonString = gson.toJson(object);
      IngressRoute result = gson.fromJson(jsonString, IngressRoute.class);


      String route = result.getSpec().getRoutes().stream().findFirst().get().getMatch();

      //  replace "PathPrefix(`/tensorboard`)" with "/tensorboard/"
      String url = route.replace("PathPrefix(`", "").replace("`)", "/");

      TensorboardInfo tensorboardInfo = new TensorboardInfo(available, url);

      return tensorboardInfo;
    } catch (ApiException e) {
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
  }

  @Override
  public MlflowInfo getMlflowInfo() throws SubmarineRuntimeException {
    final String name = "submarine-mlflow";
    final String ingressRouteName = "submarine-mlflow-ingressroute";
    String namespace = getServerNamespace();

    try {
      V1Deployment deploy = appsV1Api.readNamespacedDeploymentStatus(name, namespace, "true");
      boolean available = deploy.getStatus().getAvailableReplicas() > 0; // at least one replica is running

      IngressRoute ingressRoute = new IngressRoute();
      V1ObjectMeta meta = new V1ObjectMeta();
      meta.setName(ingressRouteName);
      meta.setNamespace(namespace);
      ingressRoute.setMetadata(meta);
      Object object = api.getNamespacedCustomObject(
          ingressRoute.getGroup(), ingressRoute.getVersion(),
          ingressRoute.getMetadata().getNamespace(),
          ingressRoute.getPlural(), ingressRouteName
      );

      Gson gson = new JSON().getGson();
      String jsonString = gson.toJson(object);
      IngressRoute result = gson.fromJson(jsonString, IngressRoute.class);


      String route = result.getSpec().getRoutes().stream().findFirst().get().getMatch();

      String url = route.replace("PathPrefix(`", "").replace("`)", "/");

      MlflowInfo mlflowInfo = new MlflowInfo(available, url);

      return mlflowInfo;
    } catch (ApiException e) {
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
  }


  @Override
  public Notebook createNotebook(NotebookSpec spec) throws SubmarineRuntimeException {
    final String name = spec.getMeta().getName();
    final String namespace = getServerNamespace();

    // parse notebook custom resource
    NotebookCR notebookCR = new NotebookCR(spec, namespace);
    // overwrite.json configmap
    Configmap overwrite = null;
    if (StringUtils.isNotBlank(NotebookUtils.OVERWRITE_JSON)) {
      overwrite = new Configmap(namespace, String.format("%s-%s", NotebookUtils.OVERWRITE_PREFIX, name),
          NotebookUtils.DEFAULT_OVERWRITE_FILE_NAME, NotebookUtils.OVERWRITE_JSON);
    }
    // workspace pvc
    PersistentVolumeClaim workspace = new PersistentVolumeClaim(namespace,
        String.format("%s-%s", NotebookUtils.PVC_PREFIX, name), NotebookUtils.STORAGE);
    // user setting pvc
    PersistentVolumeClaim userset = new PersistentVolumeClaim(namespace,
        String.format("%s-user-%s", NotebookUtils.PVC_PREFIX, name), NotebookUtils.DEFAULT_USER_STORAGE);
    // ingress route
    IngressRoute ingressRoute = new IngressRoute(namespace, name);
    // pod monitor
    PodMonitor podMonitor = null;
    if (NotebookUtils.PROMETHEUS_ENABLE) {
      podMonitor = new PodMonitor(notebookCR);
    }

    // commit resources/CRD with transaction
    List<Object> values = resourceTransaction(workspace, userset, overwrite, notebookCR,
        ingressRoute, podMonitor);

    return (Notebook) values.get(3);
  }

  /**
   * Commit resources with transaction
   * @return committed return objects
   */
  public List<Object> resourceTransaction(K8sResource ... resources) {
    Map<K8sResource, Object> commits = new LinkedHashMap<>();
    try {
      for (K8sResource resource : resources) {
        if (resource != null) {
          commits.put(resource, resource.create(k8sApi));
        } else {
          commits.put(new NullResource(), null);
        }
      }
      return new ArrayList<>(commits.values());
    } catch (Exception e) {
      if (!commits.isEmpty()) {
        // Rollback is performed in the reverse order of commits
        List<K8sResource> rollbacks = new ArrayList<>(commits.keySet());
        for (int i = rollbacks.size() - 1; i >= 0; i--) {
          K8sResource rollback = rollbacks.get(i);
          if (!(rollback instanceof NullResource)) {
            LOG.debug("Rollback resources {}/{}", rollback.getKind(), rollback.getMetadata().getName());
            rollbacks.get(i).delete(k8sApi);
          }
        }
      }
      throw e;
    }
  }

  @Override
  public Notebook findNotebook(NotebookSpec spec) throws SubmarineRuntimeException {
    Notebook notebook = null;
    String namespace = getServerNamespace();

    try {
      NotebookCR notebookCR = NotebookSpecParser.parseNotebook(spec);

      Object object = api.getNamespacedCustomObject(notebookCR.getGroup(), notebookCR.getVersion(),
          namespace,
          notebookCR.getPlural(), notebookCR.getMetadata().getName());
      notebook = NotebookUtils.parseObject(object, NotebookUtils.ParseOpt.PARSE_OPT_GET);
      if (notebook.getStatus().equals(Notebook.Status.STATUS_WAITING.toString())) {
        LOG.info(String.format("notebook status: waiting; check the pods in namespace:[%s] to "
            + "ensure is the waiting caused by image pulling", namespace));
        String podLabelSelector = String.format("%s=%s", NotebookCR.NOTEBOOK_ID,
            spec.getMeta().getLabels().get(NotebookCR.NOTEBOOK_ID).toString());

        V1PodList podList = coreApi.listNamespacedPod(namespace, null, null, null, null,
                podLabelSelector, null, null, null, null, null);
        String podName = podList.getItems().get(0).getMetadata().getName();
        String fieldSelector = String.format("involvedObject.name=%s", podName);
        CoreV1EventList events = coreApi.listNamespacedEvent(namespace, null, null, null, fieldSelector,
            null, null, null, null, null, null);
        CoreV1Event latestEvent = events.getItems().get(events.getItems().size() - 1);

        if (latestEvent.getReason().equalsIgnoreCase("Pulling")) {
          notebook.setStatus(Notebook.Status.STATUS_PULLING.getValue());
          notebook.setReason(latestEvent.getReason());
        }
      }
    } catch (ApiException e) {
      // SUBMARINE-1124
      // The exception that obtaining CRD resources is not necessarily because the CRD is deleted,
      // but maybe due to timeout or API error caused by network and other reasons.
      // Therefore, the status of the notebook should be set to a new enum NOTFOUND.
      LOG.warn(String.format("Get error when submitter is finding notebook: %s",
          spec.getMeta().getName()), e);
      if (notebook == null) {
        notebook = new Notebook();
      }
      notebook.setName(spec.getMeta().getName());
      notebook.setSpec(spec);
      notebook.setReason(e.getMessage());
      notebook.setStatus(Notebook.Status.STATUS_NOT_FOUND.getValue());
    }
    return notebook;
  }

  @Override
  public Notebook deleteNotebook(NotebookSpec spec) throws SubmarineRuntimeException {
    final String name = spec.getMeta().getName();
    String namespace = getServerNamespace();
    NotebookCR notebookCR = new NotebookCR(spec, namespace);

    // delete crd
    Notebook notebook = notebookCR.delete(k8sApi);

    // delete ingress route
    new IngressRoute(namespace, name).delete(k8sApi);

    // delete pvc
    // workspace pvc
    new PersistentVolumeClaim(namespace, String.format("%s-%s", NotebookUtils.PVC_PREFIX, name),
        NotebookUtils.STORAGE).delete(k8sApi);
    // user set pvc
    new PersistentVolumeClaim(namespace, String.format("%s-user-%s", NotebookUtils.PVC_PREFIX, name),
        NotebookUtils.DEFAULT_USER_STORAGE).delete(k8sApi);

    // configmap
    if (StringUtils.isNoneBlank(NotebookUtils.OVERWRITE_JSON)) {
      new Configmap(namespace, String.format("%s-%s", NotebookUtils.OVERWRITE_PREFIX, name))
          .delete(k8sApi);
    }

    // prometheus
    if (NotebookUtils.PROMETHEUS_ENABLE) {
      new PodMonitor(notebookCR).delete(k8sApi);
    }

    return notebook;
  }

  @Override
  public List<Notebook> listNotebook(String id) throws SubmarineRuntimeException {
    List<Notebook> notebookList;
    String namespace = getServerNamespace();

    try {
      Object object = api.listNamespacedCustomObject(NotebookCR.CRD_NOTEBOOK_GROUP_V1,
          NotebookCR.CRD_NOTEBOOK_VERSION_V1, namespace, NotebookCR.CRD_NOTEBOOK_PLURAL_V1,
          "true", null, null, NotebookCR.NOTEBOOK_OWNER_SELECTOR_KEY + "=" + id,
          null, null, null, null);
      notebookList = NotebookUtils.parseObjectForList(object);
    } catch (ApiException e) {
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
    return notebookList;
  }

  @Override
  public void createServe(ServeSpec spec)
      throws SubmarineRuntimeException {
    SeldonDeployment seldonDeployment = parseServeSpec(spec);
    IstioVirtualService istioVirtualService = new IstioVirtualService(spec.getModelName(),
        spec.getModelVersion());
    try {
      api.createNamespacedCustomObject(seldonDeployment.getGroup(),
               seldonDeployment.getVersion(),
               "default",
               seldonDeployment.getPlural(),
               seldonDeployment,
               "true", null, null);
    } catch (ApiException e) {
      LOG.error(e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
    try {
      api.createNamespacedCustomObject(istioVirtualService.getGroup(),
              istioVirtualService.getVersion(),
              "default",
              istioVirtualService.getPlural(),
              istioVirtualService,
              "true", null, null);
    } catch (ApiException e) {
      LOG.error(e.getMessage(), e);
      try {
        api.deleteNamespacedCustomObject(seldonDeployment.getGroup(),
              seldonDeployment.getVersion(),
              "default",
              seldonDeployment.getPlural(),
              seldonDeployment.getMetadata().getName(),
              null, null, null, null,
              new V1DeleteOptionsBuilder().withApiVersion(seldonDeployment.getApiVersion()).build());
      } catch (ApiException e1) {
        LOG.error(e1.getMessage(), e1);
      }
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
  }

  @Override
  public void deleteServe(ServeSpec spec)
      throws SubmarineRuntimeException {
    SeldonDeployment seldonDeployment = parseServeSpec(spec);
    IstioVirtualService istioVirtualService = new IstioVirtualService(spec.getModelName(),
        spec.getModelVersion());
    try {
      api.deleteNamespacedCustomObject(seldonDeployment.getGroup(),
              seldonDeployment.getVersion(),
              "default",
              seldonDeployment.getPlural(),
              seldonDeployment.getMetadata().getName(), null, null, null,
              null, new V1DeleteOptionsBuilder().withApiVersion(seldonDeployment.getApiVersion()).build());
      api.deleteNamespacedCustomObject(istioVirtualService.getGroup(),
              istioVirtualService.getVersion(),
              "default",
              istioVirtualService.getPlural(),
              istioVirtualService.getMetadata().getName(),
              null, null, null, null,
              new V1DeleteOptionsBuilder().withApiVersion(istioVirtualService.getApiVersion()).build());
    } catch (ApiException e) {
      LOG.error(e.getMessage(), e);
      throw new SubmarineRuntimeException(e.getCode(), e.getMessage());
    }
  }

  public void watchExperiment() throws ApiException {

    ExecutorService experimentThread = Executors.newFixedThreadPool(2);

    try (Watch<MLJob> watchTF = Watch.createWatch(
        client,
        api.listNamespacedCustomObjectCall(
            TFJob.CRD_TF_GROUP_V1,
            TFJob.CRD_TF_VERSION_V1,
            getServerNamespace(),
            TFJob.CRD_TF_PLURAL_V1,
            "true",
            null,
            null,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            null
        ),
        new TypeToken<Watch.Response<MLJob>>() {
        }.getType()
    )) {
      experimentThread.execute(new Runnable() {
        @Override
        public void run() {
          try {
            LOG.info("Start watching on TFJobs...");

            for (Watch.Response<MLJob> experiment : watchTF) {
              LOG.info("{}", experiment.object.getStatus());
            }
          } finally {
            LOG.info("WATCH TFJob END");
            try {
              watchTF.close();
            } catch (Exception e) {
              LOG.error("{}", e.getMessage());
            }
          }
        }
      });
    } catch (Exception ex) {
      throw new RuntimeException();
    }

    try (Watch<MLJob> watchPytorch = Watch.createWatch(
        client,
        api.listNamespacedCustomObjectCall(
            PyTorchJob.CRD_PYTORCH_GROUP_V1,
            PyTorchJob.CRD_PYTORCH_VERSION_V1,
            getServerNamespace(),
            PyTorchJob.CRD_PYTORCH_PLURAL_V1,
            "true",
            null,
            null,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            null
        ),
        new TypeToken<Watch.Response<MLJob>>() {
        }.getType()
    )) {
      experimentThread.execute(new Runnable() {
        @Override
        public void run() {
          try {
            LOG.info("Start watching on PytorchJobs...");

            ;
            for (Watch.Response<MLJob> experiment : watchPytorch) {
              LOG.info("{}", experiment.object.getStatus());
            }
          } finally {
            LOG.info("WATCH PytorchJob END");
            try {
              watchPytorch.close();
            } catch (Exception e) {
              LOG.error("{}", e.getMessage());
            }
          }
        }
      });
    } catch (Exception ex) {
      throw new RuntimeException();
    }
  }

  private String getJobLabelSelector(ExperimentSpec experimentSpec) {
    if (experimentSpec.getMeta().getFramework()
        .equalsIgnoreCase(ExperimentMeta.SupportedMLFramework.TENSORFLOW.getName())) {
      return TF_JOB_SELECTOR_KEY + experimentSpec.getMeta().getExperimentId();
    } else {
      return PYTORCH_JOB_SELECTOR_KEY + experimentSpec.getMeta().getExperimentId();
    }
  }

  private SeldonDeployment parseServeSpec(ServeSpec spec) throws SubmarineRuntimeException {
    String modelName = spec.getModelName();
    String modelType = spec.getModelType();
    String modelURI = spec.getModelURI();

    SeldonDeployment seldonDeployment;
    if (modelType.equals("tensorflow")){
      seldonDeployment = new SeldonTFServing(modelName, modelURI);
    } else if (modelType.equals("pytorch")){
      seldonDeployment = new SeldonPytorchServing(modelName, modelURI);
    } else {
      throw new SubmarineRuntimeException("Given serve type: " + modelType + " is not supported.");
    }
    return seldonDeployment;
  }

  private String getServerNamespace() {
    return SubmarineConfiguration.getDefaultNamespace();
  }

  private enum ParseOp {
    PARSE_OP_RESULT,
    PARSE_OP_DELETE
  }

  @Override
  public Notebook startNotebook(NotebookSpec spec) throws SubmarineRuntimeException {
    String namespace = getServerNamespace();
    NotebookCR notebookCR = new NotebookCR(spec, namespace);
    // create crd
    return notebookCR.create(k8sApi, true);
  }

  @Override
  public Notebook stopNotebook(NotebookSpec spec) throws SubmarineRuntimeException {
    String namespace = getServerNamespace();
    NotebookCR notebookCR = new NotebookCR(spec, namespace);
    // delete crd
    return notebookCR.delete(k8sApi);
  }
}
