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

package org.apache.submarine.server.scheduler;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class TaskManager {

  private static final Logger LOG = LoggerFactory.getLogger(TaskManager.class);

  public void scheduler() {

    ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(4,
        new BasicThreadFactory.Builder().namingPattern("submarine-schedule-pool-%d")
            .daemon(true).build());

    ServiceLoader<SchedulerTask> tasks = ServiceLoader.load(SchedulerTask.class);
    for (SchedulerTask task : tasks){
      if (task.enable()) {
        LOG.info("Start scheduler task {}", task.getClass().getName());
        executorService.scheduleAtFixedRate(task::execute, 1,
            task.period() < 1 ? 1 : task.period(), task.getTimeUnit());
      }
    }
  }

}
