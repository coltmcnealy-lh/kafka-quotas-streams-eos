#!/bin/bash

# Cleanup any old runs
rm -r /tmp/kafka-streams/test-app
docker kill test-kafka
docker rm test-kafka

set -ex

# Launch Kafka
docker run -d --name test-kafka -p 9092:9092 apache/kafka:3.8.0
sleep 3

# Create input topic
docker exec -it test-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --topic input-topic \
    --partitions 1 \
    --create

# Create quotas
docker exec -it test-kafka /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server localhost:9092 \
    --entity-type clients \
    --entity-name my-app-with-quota-StreamThread-1-producer \
    --alter \
    --add-config producer_byte_rate=35000
