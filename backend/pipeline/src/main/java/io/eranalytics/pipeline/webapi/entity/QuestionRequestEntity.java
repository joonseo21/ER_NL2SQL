package io.eranalytics.pipeline.webapi.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Spring receipt ID and lifecycle; the agent's log ID remains a separate value. */
@Entity
@Table(name = "question_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionRequestEntity {
    @Id
    @Column(name = "request_id", columnDefinition = "text")
    private String requestId;

    @Column(name = "invite_code", columnDefinition = "text", nullable = false)
    private String inviteCode;

    @Column(name = "question", columnDefinition = "text", nullable = false)
    private String question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_conditions", columnDefinition = "jsonb", nullable = false)
    private JsonNode requestConditions;

    @Column(name = "status", columnDefinition = "text", nullable = false)
    private String status;

    @Column(name = "agent_log_id", columnDefinition = "text")
    private String agentLogId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response", columnDefinition = "jsonb")
    private JsonNode response;

    @Column(name = "error_code", columnDefinition = "text")
    private String errorCode;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public QuestionRequestEntity(
            String id, String code, String question, JsonNode conditions, Instant now) {
        this.requestId = id;
        this.inviteCode = code;
        this.question = question;
        this.requestConditions = conditions.deepCopy();
        this.status = "accepted";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void processing(Instant now) {
        this.status = "processing";
        this.updatedAt = now;
    }

    public void complete(JsonNode result, Instant now) {
        this.status = "completed";
        this.agentLogId = result.required("log_id").asText();
        this.response = result.deepCopy();
        this.updatedAt = now;
    }

    public void fail(String code, String message, Instant now) {
        this.status = "failed";
        this.errorCode = code;
        this.message = message;
        this.updatedAt = now;
    }
}
