package org.example;

import org.apache.kafka.streams.processor.api.ProcessorContext;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Random;
import java.util.UUID;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

public class DummyProcessor implements Processor<String, String, Void, Void> {

    private KeyValueStore<String, String> store;

    private int numRecordsProcessed;

    public DummyProcessor() {
        numRecordsProcessed = 0;
    }

    @Override
    public void init(ProcessorContext<Void, Void> ctx) {
        this.store = ctx.getStateStore(App.STATE_STORE_NAME);
    }

    @Override
    public void process(Record<String, String> record) {
        numRecordsProcessed++;
        store.put(UUID.randomUUID().toString(), generateRandomText());

        if (numRecordsProcessed % 500 == 0) {
            String batchSize = numRecordsProcessed == 0 ? "first" : "500";
            System.out.println("processed " + batchSize + " records at " + new Date());
        }
    }

    /////////////////////////////////////////////
    // The below is chatgpt, please dont judge me
    /////////////////////////////////////////////
    private static final int SIZE_IN_BYTES = 1024; // 1 KB
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random random = new Random();

    public static String generateRandomText() {
        StringBuilder sb = new StringBuilder();
        
        // Generate random characters until the resulting string is approximately 1024 bytes in length
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < SIZE_IN_BYTES) {
            int index = random.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }

        return sb.toString();
    }
    /////////////////////////////////////////////
    // End ChatGPT nonsense
    /////////////////////////////////////////////
}
