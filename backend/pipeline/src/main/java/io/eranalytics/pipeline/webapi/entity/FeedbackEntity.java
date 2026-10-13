package io.eranalytics.pipeline.webapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedbackEntity {
    @Id
    @Column(name = "log_id", columnDefinition = "text")
    private String logId;

    @Column(name = "liked", nullable = false)
    private boolean liked;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FeedbackEntity(String logId, boolean liked, String comment, Instant createdAt) {
        this.logId = logId;
        this.liked = liked;
        this.comment = comment;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void update(boolean liked, String comment, Instant updatedAt) {
        this.liked = liked;
        this.comment = comment;
        this.updatedAt = updatedAt;
    }
}
