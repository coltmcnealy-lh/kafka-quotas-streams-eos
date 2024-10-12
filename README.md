# Quotas and Kafka Streams EOS

Our soak tests have recurring problems with `TaskCorruptedException`s, `ProducerFencedException`s, and other failures. All of them seem to be caused by crashes or transaction failures or timeouts in the producer. These failures become less common if we significantly over-provision our Kafka brokers, but even with a very healthy Kafka cluster, they still persist.

When doing local testing, we get the same exact stack traces _when we slightly exceed configured producer byte rate quotas._

This repo is an attempt to make it really easy to reproduce these errors so as to correct them.

## Background

Using [LittleHorse](https://github.com/littlehorse-enterprises/littlehorse) as the example application, I can with 100% reliability cause the following stacktrace to occur:

```
15:26:38 ERROR [KAFKA] TaskExecutor - stream-thread [my-cluster-1-core-StreamThread-1] Committing task(s) 1_10 failed.
org.apache.kafka.common.errors.TimeoutException: Timeout expired after 60000ms while awaiting AddOffsetsToTxn
15:26:38 WARN  [KAFKA] StreamThread - stream-thread [my-cluster-1-core-StreamThread-1] Detected the states of tasks [1_10] are corrupted. Will close the task as dirty and re-create and bootstrap from scratch.
org.apache.kafka.streams.errors.TaskCorruptedException: Tasks [1_10] are corrupted and hence need to be re-initialized
	at org.apache.kafka.streams.processor.internals.TaskExecutor.commitOffsetsOrTransaction(TaskExecutor.java:249) ~[kafka-streams-3.8.1-SNAPSHOT.jar:?]
	at org.apache.kafka.streams.processor.internals.TaskExecutor.commitTasksAndMaybeUpdateCommittableOffsets(TaskExecutor.java:154) ~[kafka-streams-3.8.1-SNAPSHOT.jar:?]
	at org.apache.kafka.streams.processor.internals.TaskManager.commitTasksAndMaybeUpdateCommittableOffsets(TaskManager.java:1915) ~[kafka-streams-3.8.1-SNAPSHOT.jar:?]
	at org.apache.kafka.streams.processor.internals.TaskManager.commit(TaskManager.java:1882) ~[kafka-streams-3.8.1-SNAPSHOT.jar:?]
	at org.apache.kafka.streams.processor.internals.StreamThread.maybeCommit(StreamThread.java:1384) ~[kafka-streams-3.8.1-SNAPSHOT.jar:?]
	at org.apache.kafka.streams.processor.internals.StreamThread.runOnceWithoutProcessingThreads(StreamThread.java:1033) ~[kafka-streams-3.8.1-SNAPSHOT.jar:?]
	at org.apache.kafka.streams.processor.internals.StreamThread.runLoop(StreamThread.java:711) [kafka-streams-3.8.1-SNAPSHOT.jar:?]
```

With the LittleHorse setup, the crash always involves a timeout on `AddOffsetsToTxn`. This repo unfortunately produces (pun intended) a different error. However, it's both related with quotas, so it is possible that the same root cause may fix both issues.

## Setup

Run kafka in docker, create the input topic, and configure quotas of 35 KB/s for produce_rate.

```
./setup.sh
```

Run the App, which uses a transactional producer to write roughly 40-45 KB/s:

```
gradle run
```

**NOTE: the `build.gradle` currently expects your `mavenLocal()` to have `3.8.1-SNAPSHOT` for kafka-clients and kafka-streams.**

Then wait and see exceptions happen.

### What the App does

The app is very simple. It has two threads:

1. A Kafka Streams topology which writes a random 1KB string to a state store for each input record it sees.
2. A "generator" thread which has a producer that sends a record and then sleeps 30ms in a tight loop.

The Kafka Streams app is minimal:
* Exactly-once semantics are configured, so the Changelog producer is a transactional producer.
* One stream thread.
* One input partition.
* One processor/state store.
* Processing each record is minimal and should occur in well under one millisecond.

The producer thread that sends data into the KS app is not transactional; and we do not configure a client quota for this producer.

The `setup.sh` script configures a quota of `producer_byte_rate=35000` for the client used by the Kafka Streams Stream Thread (in practice, it's just writing to the changelog).

### Expected Behavior

The generator thread should send roughly 30-40 records per second into the input topic due to the `Thread.sleep(25);` in the loop (1000/30). That is a requirement of about 30-40 KB/s of data produced by the changelog producer. This means we'll probably _at times_ go over the configured 35 KB/s quota, but not too far over it.

We would expect:
* The JMX metrics show that Kafka Streams producer requests are being _slightly_ throttled, probably by 100ms or so on average.
* The Generator thread is not throttled.
* The Kafka Streams app processes messages happily, but due to throttling there is a slowly-growing consumer lag that never catches up.

The application should print out every 15-20 seconds or so "processed 500 records" when healthy.

### Actual Behavior

If you comment out the line in `setup.sh` that creates quotas, the app will run happily without crashing for a long time, and you can observe that the consumer lag is basically zero.

Howver, with quotas, we get crashes.

I was hoping to reproduce the error with `AddOffsetsToTxn`, but I couldn't. Instead, I get rebalance storms, and also:

```
16:38:58 ERROR [KAFKA] RecordCollectorImpl - stream-thread [my-app-with-quota-StreamThread-1] stream-task [0_0] Error encountered sending record to topic test-app-dummy-store-changelog for task 0_0 due to:
org.apache.kafka.common.errors.InvalidProducerEpochException: Producer attempted to produce with an old epoch.
Written offsets would not be recorded and no more records would be sent since the producer is fenced, indicating the task may be migrated out
```

and

```
org.apache.kafka.common.errors.InvalidProducerEpochException: Producer attempted to produce with an old epoch.
Written offsets would not be recorded and no more records would be sent since the producer is fenced, indicating the task may be migrated out; it means all tasks belonging to this thread should be migrated.
        at org.apache.kafka.streams.processor.internals.RecordCollectorImpl.recordSendError(RecordCollectorImpl.java:306) ~[kafka-streams-3.8.0.jar:?]
        at org.apache.kafka.streams.processor.internals.RecordCollectorImpl.lambda$send$1(RecordCollectorImpl.java:286) ~[kafka-streams-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.KafkaProducer$AppendCallbacks.onCompletion(KafkaProducer.java:1565) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.ProducerBatch.completeFutureAndFireCallbacks(ProducerBatch.java:311) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.ProducerBatch.done(ProducerBatch.java:272) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.ProducerBatch.completeExceptionally(ProducerBatch.java:236) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.failBatch(Sender.java:829) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.failBatch(Sender.java:818) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.failBatch(Sender.java:770) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.completeBatch(Sender.java:702) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.lambda$null$2(Sender.java:627) ~[kafka-clients-3.8.0.jar:?]
        at java.util.ArrayList.forEach(ArrayList.java:1596) ~[?:?]
        at org.apache.kafka.clients.producer.internals.Sender.lambda$handleProduceResponse$3(Sender.java:612) ~[kafka-clients-3.8.0.jar:?]
        at java.lang.Iterable.forEach(Iterable.java:75) ~[?:?]
        at org.apache.kafka.clients.producer.internals.Sender.handleProduceResponse(Sender.java:612) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.lambda$sendProduceRequest$9(Sender.java:916) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.ClientResponse.onComplete(ClientResponse.java:154) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.NetworkClient.completeResponses(NetworkClient.java:618) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.NetworkClient.poll(NetworkClient.java:610) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.runOnce(Sender.java:348) ~[kafka-clients-3.8.0.jar:?]
        at org.apache.kafka.clients.producer.internals.Sender.run(Sender.java:250) ~[kafka-clients-3.8.0.jar:?]
        at java.lang.Thread.run(Thread.java:1583) ~[?:?]
Caused by: org.apache.kafka.common.errors.InvalidProducerEpochException: Producer attempted to produce with an old epoch.

```

With a single stream thread and a single application instance, low throughput (<35KB/s), and no broker or client crashes, we should NOT expect `InvalidProducerEpochException`s. That means there's some bug somewhere.

## Follow-Ups

### Playing Around

Feel free to play around with the following:

1. Quota value: check `setup.sh`
2. Number of partitions in the topic (currently we use `1` for simplicity).
3. Number of requests being sent by the Generator: `App.java` in `App#startProducer()`.
4. Amount of data written to the changelog for each record sent by the Generator: `DummyProcessor.java`.
5. Kafka Streams Configuration

### The `AddOffsetsToTxn` Error

As a follow-up, I will try to give instructions to produce the `AddOffsetsToTxn` error below this readme. That is 100% reliable when using LittleHorse, but LH is a heavier application and requires running two shell scripts to generate load.
