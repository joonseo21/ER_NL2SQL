package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** Avoid merge/select for freshly constructed rows with assigned composite IDs. */
@MappedSuperclass
public abstract class AssignedIdEntity<K> implements Persistable<K> {
    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    protected void markPersisted() {
        newEntity = false;
    }
}
