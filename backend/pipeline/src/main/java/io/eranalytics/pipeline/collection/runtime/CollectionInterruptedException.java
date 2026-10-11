package io.eranalytics.pipeline.collection.runtime;

public class CollectionInterruptedException extends RuntimeException {
    public CollectionInterruptedException() {
        super("Collection interrupted");
    }
}
