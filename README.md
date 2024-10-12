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

The `AddOffsetsToTxn` is always noted. 

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

### Actual Behavior

Well, just watch...there's a few crashes. That's not good for such a simple setup.

I was hoping to reproduce the error with `AddOffsetsToTxn`, but I couldn't. Instead, I periodically get this error:
```
16:38:58 ERROR [KAFKA] RecordCollectorImpl - stream-thread [my-app-with-quota-StreamThread-1] stream-task [0_0] Error encountered sending record to topic test-app-dummy-store-changelog for task 0_0 due to:
org.apache.kafka.common.errors.InvalidProducerEpochException: Producer attempted to produce with an old epoch.
Written offsets would not be recorded and no more records would be sent since the producer is fenced, indicating the task may be migrated out
```

With a single stream thread and a single application instance, low throughput (<35KB/s), and no broker or client crashes, we should NOT expect `InvalidProducerEpochException`s. That means there's some bug somewhere.

As a follow-up, I will try to give instructions to produce the `AddOffsetsToTxn` error below this readme. That is 100% reliable when using LittleHorse, but LH is a heavier application and requires running two shell scripts to generate load.
