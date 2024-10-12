package org.example;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import java.util.Properties;

public class App {
    public static final String STATE_STORE_NAME = "dummy-store";
    public static final String INPUT_TOPIC = "input-topic";
    public static final String TOPO_SOURCE = "source";

    public static void main(String[] args) throws InterruptedException {

        new Thread(App::startProducer).start();
        startStreamsApp();
    }

    private static void startProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serdes.String().serializer().getClass());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Serdes.String().serializer().getClass());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "my-app-without-quota");

        Producer<String, String> producer = new KafkaProducer<>(props);
        Runtime.getRuntime().addShutdownHook(new Thread(producer::close));

        while (true) {
            try {
                Thread.sleep(30);
            } catch (InterruptedException exn) {
                throw new RuntimeException(exn);
            }
            producer.send(new ProducerRecord<String,String>(INPUT_TOPIC, "value-doesnt-matter"));
            producer.flush();
        }
    }

    private static void startStreamsApp() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-app");
        props.put(StreamsConfig.CLIENT_ID_CONFIG, "my-app-with-quota");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StoreBuilder<KeyValueStore<String, String>> storeBuilder = Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE_NAME), Serdes.String(), Serdes.String());

        Topology topo = new Topology().addSource(TOPO_SOURCE, INPUT_TOPIC).addProcessor("dummy-processor", DummyProcessor::new, TOPO_SOURCE)
                .addStateStore(storeBuilder, "dummy-processor");

        KafkaStreams streams = new KafkaStreams(topo, props);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Closing Streams!");
            streams.close();
        }));

        System.out.println("starting streams!");
        streams.start();

    }
}