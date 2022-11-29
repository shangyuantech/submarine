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

package org.apache.submarine.server.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.Response.Status;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.submarine.commons.utils.SubmarineConfiguration;
import org.apache.submarine.commons.utils.exception.SubmarineRuntimeException;
import org.apache.submarine.server.SubmarineServer;
import org.apache.submarine.server.SubmitterManager;
import org.apache.submarine.server.api.experiment.Experiment;
import org.apache.submarine.server.api.experiment.ExperimentId;
import org.apache.submarine.server.api.Submitter;
import org.apache.submarine.server.api.experiment.ExperimentLog;
import org.apache.submarine.server.api.experiment.TensorboardInfo;
import org.apache.submarine.server.api.experiment.MlflowInfo;
import org.apache.submarine.server.api.spec.ExperimentSpec;
import org.apache.submarine.server.database.experiment.entity.ExperimentEntity;
import org.apache.submarine.server.database.experiment.service.ExperimentService;
import org.apache.submarine.server.rest.RestConstants;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.mlflow.tracking.MlflowClient;

/**
 * It's responsible for managing the experiment CRUD and cache them.
 */
public class ExperimentManager {
  private static final Logger LOG = LoggerFactory.getLogger(ExperimentManager.class);

  private static volatile ExperimentManager manager;

  private final AtomicInteger experimentCounter = new AtomicInteger(0);

  private Optional<org.mlflow.api.proto.Service.Experiment> MlflowExperimentOptional;
  private org.mlflow.api.proto.Service.Experiment MlflowExperiment;
  /**
   * Used to cache the specs by the experiment id.
   * key: the string of experiment id
   * value: Experiment object
   */
  private final ConcurrentMap<String, Experiment> cachedExperimentMap = new ConcurrentHashMap<>();

  private final Submitter submitter;
  private final ExperimentService experimentService;

  /**
   * Get the singleton instance.
   *
   * @return object
   */
  public static ExperimentManager getInstance() {
    if (manager == null) {
      synchronized (ExperimentManager.class) {
        if (manager == null) {
          manager = new ExperimentManager(SubmitterManager.loadSubmitter(), new ExperimentService());
        }
      }
    }
    return manager;
  }

  @VisibleForTesting
  protected ExperimentManager(Submitter submitter, ExperimentService experimentService) {
    this.submitter = submitter;
    this.experimentService = experimentService;
  }

  /**
   * Create experiment.
   *
   * @param spec spec
   * @return object
   * @throws SubmarineRuntimeException the service error
   */

  public Experiment createExperiment(ExperimentSpec spec) throws SubmarineRuntimeException {
    checkSpec(spec);

    // Submarine sdk will get experimentID and JDBC URL from environment variables in each worker,
    // and then log experiment metrics and parameters to submarine server
    ExperimentId id = generateExperimentId();
    String url = getSQLAlchemyURL();

    spec.getMeta().getEnvVars().put(RestConstants.JOB_ID, id.toString());
    spec.getMeta().getEnvVars().put(RestConstants.SUBMARINE_TRACKING_URI, url);
    spec.getMeta().getEnvVars().put(RestConstants.LOG_DIR_KEY, RestConstants.LOG_DIR_VALUE);

    String lowerName = spec.getMeta().getName().toLowerCase();
    spec.getMeta().setName(lowerName);
    spec.getMeta().setExperimentId(id.toString());

    Experiment experiment = submitter.createExperiment(spec);
    experiment.setExperimentId(id);

    spec.getMeta().getEnvVars().remove(RestConstants.JOB_ID);
    spec.getMeta().getEnvVars().remove(RestConstants.SUBMARINE_TRACKING_URI);
    spec.getMeta().getEnvVars().remove(RestConstants.LOG_DIR_KEY);

    experiment.setSpec(spec);
    ExperimentEntity entity = buildEntityFromExperiment(experiment);
    entity.setExperimentStatus(Experiment.Status.STATUS_ACCEPTED.toString());
    experimentService.insert(entity);
    return experiment;
  }

  /**
   * Get experiment.
   *
   * @param id experiment id
   * @return object
   * @throws SubmarineRuntimeException the service error
   */
  public Experiment getExperiment(String id) throws SubmarineRuntimeException {
    checkExperimentId(id);

    ExperimentEntity entity = experimentService.select(id);
    Experiment experiment = buildExperimentFromEntity(entity);

    return experiment;
  }

  /**
   * List experiments.
   *
   * @param status status, if null will return all experiments
   * @return list
   * @throws SubmarineRuntimeException the service error
   */
  public List<Experiment> listExperimentsByStatus(String status) throws SubmarineRuntimeException {
    List<Experiment> experimentList = new ArrayList<>();
    List<ExperimentEntity> entities = experimentService.selectAll();

    for (ExperimentEntity entity : entities) {
      Experiment experiment = buildExperimentFromEntity(entity);
      experimentList.add(experiment);
    }
    LOG.info("List experiment: {}", experimentList.size());
    return experimentList;
  }

  /**
   * List experiments.
   *
   * @param searchTag String, if null will return all experiments
   * @return list
   * @throws SubmarineRuntimeException the service error
   */
  public List<Experiment> listExperimentsByTag(String searchTag) throws SubmarineRuntimeException {
    List<Experiment> experimentList = new ArrayList<>();
    List<ExperimentEntity> entities = experimentService.selectAll();

    for (ExperimentEntity entity : entities) {
      Experiment experiment = buildExperimentFromEntity(entity);
      if (searchTag == null) {
        experimentList.add(experiment);
      } else {
        for (String tag: experiment.getSpec().getMeta().getTags()) {
          if (tag.equalsIgnoreCase(searchTag)) {
            experimentList.add(experiment);
            break;
          }
        }
      }
    }
    LOG.info("List experiment: {}", experimentList.size());
    return experimentList;
  }

  /**
   * Patch the experiment.
   *
   * @param id      experiment id
   * @param newSpec spec
   * @return object
   * @throws SubmarineRuntimeException the service error
   */
  public Experiment patchExperiment(String id, ExperimentSpec newSpec) throws SubmarineRuntimeException {
    checkExperimentId(id);
    checkSpec(newSpec);

    newSpec.getMeta().setExperimentId(id);

    ExperimentEntity entity = experimentService.select(id);
    Experiment experiment = buildExperimentFromEntity(entity);
    Experiment patchExperiment = submitter.patchExperiment(newSpec);

    // update spec in returned experiment
    experiment.setSpec(newSpec);

    // update entity and commit
    entity.setExperimentSpec(new GsonBuilder().disableHtmlEscaping().create().toJson(newSpec));
    experimentService.update(entity);

    // patch new information in experiment
    experiment.rebuild(patchExperiment);

    return experiment;
  }

  /**
   * Delete experiment.
   *
   * @param id experiment id
   * @return object
   * @throws SubmarineRuntimeException the service error
   */
  public Experiment deleteExperiment(String id) throws SubmarineRuntimeException {
    checkExperimentId(id);

    ExperimentEntity entity = experimentService.select(id);
    Experiment experiment = buildExperimentFromEntity(entity);
    Experiment deletedExperiment = submitter.deleteExperiment(experiment.getSpec());
    experimentService.delete(id);

    experiment.rebuild(deletedExperiment);

    MlflowClient mlflowClient = new MlflowClient("http://submarine-mlflow-service:5000");
    try {
      MlflowExperimentOptional = mlflowClient.getExperimentByName(id);
      MlflowExperiment = MlflowExperimentOptional.get();
      String mlflowId = MlflowExperiment.getExperimentId();
      mlflowClient.deleteExperiment(mlflowId);
    } finally {
      return experiment;
    }
  }

  /**
   * List experiment logs.
   *
   * @param status status, if null will return all experiment logs
   * @return log list
   * @throws SubmarineRuntimeException the service error
   */
  public List<ExperimentLog> listExperimentLogsByStatus(String status) throws SubmarineRuntimeException {
    List<ExperimentLog> experimentLogList = new ArrayList<>();
    List<ExperimentEntity> entities = experimentService.selectAll();

    for (ExperimentEntity entity : entities) {
      Experiment experiment = buildExperimentFromEntity(entity);

      if (status == null || status.toLowerCase().equals(experiment.getStatus().toLowerCase())) {

        experimentLogList.add(submitter.getExperimentLogName(
            experiment.getSpec(),
            experiment.getSpec().getMeta().getExperimentId()
        ));
      }

    }
    return experimentLogList;
  }

  /**
   * Get experiment log.
   *
   * @param id experiment id
   * @return object
   * @throws SubmarineRuntimeException the service error
   */
  public ExperimentLog getExperimentLog(String id) throws SubmarineRuntimeException {
    checkExperimentId(id);

    ExperimentEntity entity = experimentService.select(id);
    Experiment experiment = buildExperimentFromEntity(entity);

    return submitter.getExperimentLog(experiment.getSpec(), id);
  }

  /**
   * Get tensorboard meta data.
   *
   * @return TensorboardInfo
   * @throws SubmarineRuntimeException the service error
   */
  public TensorboardInfo getTensorboardInfo() throws SubmarineRuntimeException {
    return submitter.getTensorboardInfo();
  }

  /**
   * Get mlflow meta data.
   *
   * @return MlflowInfo
   * @throws SubmarineRuntimeException the service error
   */
  public MlflowInfo getMLflowInfo() throws SubmarineRuntimeException {
    return submitter.getMlflowInfo();
  }

  private void checkSpec(ExperimentSpec spec) throws SubmarineRuntimeException {
    if (spec == null) {
      throw new SubmarineRuntimeException(Status.OK.getStatusCode(), "Invalid experiment spec.");
    }
  }

  private void checkExperimentId(String id) throws SubmarineRuntimeException {
    ExperimentEntity entity = experimentService.select(id);
    if (entity == null) {
      throw new SubmarineRuntimeException(Status.NOT_FOUND.getStatusCode(), "Not found experiment.");
    }
  }

  private String getSQLAlchemyURL() {
    SubmarineConfiguration conf = SubmarineConfiguration.getInstance();
    String jdbcUrl = conf.getJdbcUrl();
    jdbcUrl = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2, jdbcUrl.indexOf("?"));
    String jdbcUserName = conf.getJdbcUserName();
    String jdbcPassword = conf.getJdbcPassword();
    return "mysql+pymysql://" + jdbcUserName + ":" + jdbcPassword + "@" + jdbcUrl;
  }

  public ExperimentId generateExperimentId() {
    return ExperimentId.newInstance(SubmarineServer.getServerTimeStamp(),
        experimentCounter.incrementAndGet());
  }

  /**
   * Create a new experiment instance from entity, and filled.
   * 1. experimentId
   * 2. spec
   *
   * @param entity ExperimentEntity
   * @return Experiment
   */
  private Experiment buildExperimentFromEntity(ExperimentEntity entity) {
    Experiment experiment = new Experiment();

    experiment.setExperimentId(ExperimentId.fromString(entity.getId()));
    experiment.setSpec(new Gson().fromJson(entity.getExperimentSpec(), ExperimentSpec.class));
    experiment.setStatus(entity.getExperimentStatus());

    if (entity.getCreateTime() != null) {
      experiment.setCreatedTime(new DateTime(entity.getCreateTime()).toString());
    } else {
      experiment.setCreatedTime(null);
    }
    if (entity.getAcceptedTime() != null) {
      experiment.setAcceptedTime(new DateTime(entity.getAcceptedTime()).toString());
    } else {
      experiment.setAcceptedTime(null);
    }
    if (entity.getRunningTime() != null) {
      experiment.setRunningTime(new DateTime(entity.getRunningTime()).toString());
    } else {
      experiment.setRunningTime(null);
    }
    if (entity.getFinishedTime() != null) {
      experiment.setFinishedTime(new DateTime(entity.getFinishedTime()).toString());
    } else {
      experiment.setFinishedTime(null);
    }
    experiment.setUid(entity.getUid());

    return experiment;
  }

  /**
   * Create a ExperimentEntity instance from experiment.
   *
   * @param experiment Experiment
   * @return ExperimentEntity
   */
  private ExperimentEntity buildEntityFromExperiment(Experiment experiment) {
    ExperimentEntity entity = new ExperimentEntity();
    entity.setId(experiment.getSpec().getMeta().getExperimentId());
    entity.setExperimentSpec(new GsonBuilder().disableHtmlEscaping().create().toJson(experiment.getSpec()));
    if (experiment.getCreatedTime() != null) {
      entity.setCreateTime(DateTime.parse(experiment.getCreatedTime()).toDate());
    } else {
      entity.setCreateTime(null);
    }
    if (experiment.getAcceptedTime() != null) {
      entity.setAcceptedTime(DateTime.parse(experiment.getAcceptedTime()).toDate());
    } else {
      entity.setAcceptedTime(null);
    }
    if (experiment.getRunningTime() != null) {
      entity.setRunningTime(DateTime.parse(experiment.getRunningTime()).toDate());
    } else {
      entity.setRunningTime(null);
    }
    if (experiment.getFinishedTime() != null) {
      entity.setFinishedTime(DateTime.parse(experiment.getFinishedTime()).toDate());
    } else {
      entity.setFinishedTime(null);
    }
    entity.setUid(experiment.getUid());
    return entity;
  }
}
