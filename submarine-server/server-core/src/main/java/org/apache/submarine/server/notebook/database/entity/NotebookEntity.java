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

package org.apache.submarine.server.notebook.database.entity;

import org.apache.submarine.server.database.entity.BaseEntity;

public class NotebookEntity extends BaseEntity {
  /*
    Take id (inherited from BaseEntity) as the primary key for notebook table
  */
  private String notebookSpec;

  private boolean started = true;

  public NotebookEntity() {
  }

  public String getNotebookSpec() {
    return notebookSpec;
  }

  public void setNotebookSpec(String notebookSpec) {
    this.notebookSpec = notebookSpec;
  }

  public boolean isStarted() {
    return started;
  }

  public void setStarted(boolean started) {
    this.started = started;
  }

  @Override
  public String toString() {
    return "NotebookEntity{" +
        "notebookSpec='" + notebookSpec + '\'' +
        ", id='" + id + '\'' +
        ", started='" + started + '\'' +
        ", createBy='" + createBy + '\'' +
        ", createTime=" + createTime +
        ", updateBy='" + updateBy + '\'' +
        ", updateTime=" + updateTime +
        '}';
  }
}
