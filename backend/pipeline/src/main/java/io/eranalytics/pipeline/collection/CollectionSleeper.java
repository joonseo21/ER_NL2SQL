package io.eranalytics.pipeline.collection;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class CollectionSleeper {
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CollectionInterruptedException();
        }
    }
}
