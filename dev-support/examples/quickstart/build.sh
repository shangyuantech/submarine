#!/usr/bin/env bash
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

set -euxo pipefail

SUBMARINE_VERSION=0.8.0-SNAPSHOT
SUBMARINE_IMAGE_NAME="apache/submarine:quickstart-${SUBMARINE_VERSION}"

if [ -L ${BASH_SOURCE-$0} ]; then
  PWD=$(dirname $(readlink "${BASH_SOURCE-$0}"))
else
  PWD=$(dirname ${BASH_SOURCE-$0})
fi
export CURRENT_PATH=$(cd "${PWD}">/dev/null; pwd)
export SUBMARINE_HOME=${CURRENT_PATH}/../../..

if [ -d "${CURRENT_PATH}/tmp" ] # if old tmp folder is still there, delete it.
then
  rm -rf "${CURRENT_PATH}/tmp"
fi

mkdir -p "${CURRENT_PATH}/tmp/submarine-sdk/pysubmarine"
cd "${SUBMARINE_HOME}/submarine-sdk/pysubmarine"
# Exclude the `venv` folder under dev and copy it
cp -r `ls | grep -v venv | xargs` "${CURRENT_PATH}/tmp/submarine-sdk/pysubmarine"

# build image
cd ${CURRENT_PATH}
echo "Start building the ${SUBMARINE_IMAGE_NAME} docker image ..."
docker build -t ${SUBMARINE_IMAGE_NAME} .

# clean temp file
rm -rf "${CURRENT_PATH}/tmp"
