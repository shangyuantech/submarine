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

package org.apache.submarine.rest;

import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.submarine.server.AbstractSubmarineServerTest;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.core.Response;

public class OpenApiRestTest extends AbstractSubmarineServerTest {

  @BeforeClass
  public static void startUp() {
    Assert.assertTrue(checkIfServerIsRunning());
  }

  @Test
  public void checkOpenApiJsonIsSame() throws Exception {
    // check if openapi endpoint is ok
    GetMethod getMethod = httpGet("/v1/openapi.json");
    Assert.assertEquals(Response.Status.OK.getStatusCode(),
            getMethod.getStatusCode());

    // check if openapi json content is ok
    String apijson = getMethod.getResponseBodyAsString();
    Assert.assertNotNull(apijson);
    // todo(cdmikechen): API changes may affect the sdk,
    //                   so it's best to think about keeping up with the sdk in the future
  }
}
