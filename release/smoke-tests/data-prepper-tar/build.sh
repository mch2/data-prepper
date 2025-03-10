#!/bin/bash

# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0

set -e

if [ -z "${BUILD_NAME}" ]; then
    echo -e "build.sh requires environment variable BUILD_NAME to be defined."
    exit 1
fi
if [ -z "${DATA_PREPPER_VERSION}" ]; then
    echo -e "build.sh requires environment variable DATA_PREPPER_VERSION to be defined."
    exit 1
fi
if [ -z "${DOCKER_FILE_DIR}" ]; then
    echo -e "build.sh requires environment variable DOCKER_FILE_DIR to be defined."
    exit 1
fi
if [ -z "${FROM_IMAGE_NAME}" ]; then
    echo -e "build.sh requires environment variable FROM_IMAGE_NAME to be defined."
    exit 1
fi
if [ -z "${FROM_IMAGE_TAG}" ]; then
    echo -e "build.sh requires environment variable FROM_IMAGE_TAG to be defined."
    exit 1
fi
if [ -z "${TAR_FILE}" ]; then
    echo -e "build.sh requires environment variable TAR_FILE to be defined."
    exit 1
fi
if [ -z "${NAME}" ]; then
    echo -e "build.sh requires environment variable NAME to be defined."
    exit 1
fi

docker build \
    --build-arg BUILD_NAME="${BUILD_NAME}" \
    --build-arg DATA_PREPPER_VERSION="${DATA_PREPPER_VERSION}" \
    --build-arg DOCKER_FILE_DIR="${DOCKER_FILE_DIR}" \
    --build-arg FROM_IMAGE_NAME="${FROM_IMAGE_NAME}" \
    --build-arg FROM_IMAGE_TAG="${FROM_IMAGE_TAG}" \
    --build-arg TAR_FILE="${TAR_FILE}" \
    -t "${NAME}":"${DATA_PREPPER_VERSION}" \
    "${DOCKER_FILE_DIR}"

rm -f "${DOCKER_FILE_DIR}/${TAR_FILE}"

echo "Image created: \"${NAME}:${DATA_PREPPER_VERSION}\""
