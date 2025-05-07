#!/bin/sh

MOUNTS_DIR=$1
CONTAINER_NAME=self-modifying-agent-nodejs

if [ "$(docker ps -aq -f name=^${CONTAINER_NAME}$)" ]; then
    docker stop "${CONTAINER_NAME}" && docker rm "${CONTAINER_NAME}"
fi

docker run -d -it --name "${CONTAINER_NAME}" --network host ${MOUNTS} node:23.1.0

