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

import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { NotebookInfo } from '@submarine/interfaces/notebook-interfaces/notebook-info';
import { NzNotificationService } from 'ng-zorro-antd/notification';

@Component({
  selector: 'submarine-notebook-list',
  templateUrl: './notebook-list.component.html',
  styleUrls: ['./notebook-list.component.scss'],
})
export class NotebookListComponent implements OnInit {
  constructor(private nzNotificationService: NzNotificationService) {}

  @Input() notebookList: NotebookInfo[];

  @Output() deleteNotebook = new EventEmitter<string>();
  @Output() startNotebook = new EventEmitter<string>();
  @Output() stopNotebook = new EventEmitter<string>();

  statusColor: { [key: string]: string } = {
    creating: 'gold',
    waiting: 'gold',
    running: 'green',
    terminating: 'blue',
  };

  ngOnInit() {}

  showReason(reason: string) {
    this.nzNotificationService.blank('Notebook Status', reason);
  }

  onDeleteNotebook(id: string) {
    this.deleteNotebook.emit(id);
  }

  onStartNotebook(id: string) {
    this.startNotebook.emit(id);
  }

  onStopNotebook(id: string) {
    this.stopNotebook.emit(id);
  }
}
