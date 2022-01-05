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

package org.apache.submarine.server.scheduler.autostop;

import com.google.gson.Gson;
import org.apache.submarine.commons.utils.SubmarineConfVars;
import org.apache.submarine.commons.utils.SubmarineConfiguration;
import org.apache.submarine.server.api.notebook.Notebook;
import org.apache.submarine.server.notebook.NotebookManager;
import org.apache.submarine.server.scheduler.SchedulerTask;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class AutoStopTask implements SchedulerTask {

  private static final Logger LOG = LoggerFactory.getLogger(AutoStopTask.class);

  private static final boolean ENABLE_AUTOSTOP;
  private static final int AUTOSTOP_DURATION;
  private static final String PROMETHEUS_URL;
  private static final NotebookManager notebookManager;

  private static final Gson gson = new Gson();

  static {
    final SubmarineConfiguration conf = SubmarineConfiguration.getInstance();
    ENABLE_AUTOSTOP = conf.getBoolean(SubmarineConfVars.ConfVars.SUBMARINE_SERVER_TASKS_AUTOSTOP_ENABLE) &&
        conf.getBoolean(SubmarineConfVars.ConfVars.SUBMARINE_NOTEBOOK_PROMETHEUS_ENABLE);
    AUTOSTOP_DURATION = conf.getInt(
        SubmarineConfVars.ConfVars.SUBMARINE_SERVER_TASKS_AUTOSTOP_DURATION);
    PROMETHEUS_URL = conf.getString(
        SubmarineConfVars.ConfVars.SUBMARINE_SERVER_TASKS_AUTOSTOP_PROMETHEUS_URL);
    notebookManager = NotebookManager.getInstance();
  }

  @Override
  public boolean enable() {
    return ENABLE_AUTOSTOP;
  }

  @Override
  public long period() {
    return 1;
  }

  @Override
  public void execute() {
    // Query the notebook that is not stopped
    // and query from Prometheus whether there are no running processes within the duration
    LOG.trace("Start automatically tasks that close no active notebooks for more than {} minutes",
        AUTOSTOP_DURATION);
    List<Notebook> notebooks = notebookManager.listStartingNotebooks(AUTOSTOP_DURATION);
    for (Notebook notebook : notebooks) {
      Notebook.Status status = Notebook.Status.getStatus(notebook.getStatus());
      if (notebook.isStarted() && status == Notebook.Status.STATUS_RUNNING) {
        try {
          double max = PrometheusClient.CLIENT.maxOverTime(notebook.getName());
          if (max == 0) {
            LOG.info("Notebook {} have not been executed within {} minutes, close this notebook!",
                notebook.getName(), AUTOSTOP_DURATION);
            notebookManager.stopNotebook(notebook.getNotebookId().toString());
          }
        } catch (Exception e) {
          LOG.warn(String.format("An exception occurred while getting the indicator. " +
              "Skip notebook %s", notebook.getName()), e);
        }
      }
    }
  }

  enum PrometheusClient {

    CLIENT;

    private HttpClient httpClient = null;

    private HttpClient getHttpClient() throws Exception {
      if (this.httpClient == null) {
        this.httpClient = new HttpClient();
        httpClient.setFollowRedirects(false);
        httpClient.start();
      }
      return this.httpClient;
    }

    public double maxOverTime(String name) throws Exception {
      // promql kernel_currently_running_total query
      StringBuilder kquery = new StringBuilder();
      kquery.append("max_over_time(kernel_currently_running_total");
      // {container="
      kquery.append("%7Bcontainer%3D%22");
      kquery.append(name);
      // "}[
      kquery.append("%22%7D%5B");
      kquery.append(AUTOSTOP_DURATION);
      // m])
      kquery.append("m%5D)");

      double kmax = query(kquery.toString());
      if (kmax == -1) {
        // promql terminal_currently_running_total query
        StringBuilder tquery = new StringBuilder();
        tquery.append("max_over_time(terminal_currently_running_total");
        tquery.append("%7Bcontainer%3D%22");
        tquery.append(name);
        tquery.append("%22%7D%5B");
        tquery.append(AUTOSTOP_DURATION);
        tquery.append("m%5D)");
        return query(tquery.toString());
      } else {
        return kmax;
      }
    }

    /**
     * Query promql
     * @return -1 no results; others max value
     */
    public double query(String query) throws Exception  {
      HttpClient httpClient = getHttpClient();
      ContentResponse response = httpClient
              .newRequest(String.format("%s/api/v1/query?query=%s", PROMETHEUS_URL, query))
              .timeout(5, TimeUnit.SECONDS)
              .send();
      PromqlResult resultData = gson.fromJson(response.getContentAsString(), PromqlResult.class);
      if ("success".equals(resultData.getStatus())) {
        List<PromqlResult.Result> results = resultData.getData().getResult();
        if (results.isEmpty()) {
          return -1;
        } else {
          return Double.parseDouble(String.valueOf(results.get(0).getValue().get(1)));
        }
      } else {
        return -1;
      }
    }

    public void stop() throws Exception {
      if (httpClient != null) {
        httpClient.stop();
      }
    }
  }
}
