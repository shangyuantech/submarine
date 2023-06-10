# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# flake8: noqa

# Import all APIs into this package.
# If you have many APIs here with many many models used in each API this may
# raise a `RecursionError`.
# In order to avoid this, import only the API that you directly need like:
#
#   from .api.environment_api import EnvironmentApi
#
# or import this package, but before doing it, use:
#
#   import sys
#   sys.setrecursionlimit(n)

# Import APIs into API package:
from submarine.client.api.environment_api import EnvironmentApi
from submarine.client.api.experiment_api import ExperimentApi
from submarine.client.api.experiment_template_api import ExperimentTemplateApi
from submarine.client.api.experiment_templates_api import ExperimentTemplatesApi
from submarine.client.api.model_version_api import ModelVersionApi
from submarine.client.api.notebook_api import NotebookApi
from submarine.client.api.registered_model_api import RegisteredModelApi
from submarine.client.api.serve_api import ServeApi
