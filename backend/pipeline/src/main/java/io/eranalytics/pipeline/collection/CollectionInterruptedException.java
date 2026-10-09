package io.eranalytics.pipeline.collection;

public class CollectionInterruptedException extends RuntimeException {
    public CollectionInterruptedException() {
        super("Collection interrupted");
    }
}
