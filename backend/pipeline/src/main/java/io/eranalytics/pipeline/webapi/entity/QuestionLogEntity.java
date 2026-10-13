package io.eranalytics.pipeline.webapi.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** API-owned snapshot keyed by the agent's opaque log ID; no changes to agent logs. */
@Entity
@Table(name = "question_logs")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionLogEntity {
    @Id
    @Column(name = "log_id", columnDefinition = "text")
    private String logId;

    @Column(name = "invite_code", columnDefinition = "text", nullable = false)
    private String inviteCode;

    @Column(name = "question", columnDefinition = "text", nullable = false)
    private String question;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_conditions", columnDefinition = "jsonb", nullable = false)
    private JsonNode requestConditions = JsonNodeFactory.instance.objectNode();

    @Column(name = "response_type", columnDefinition = "text", nullable = false)
    private String responseType;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_conditions", columnDefinition = "jsonb", nullable = false)
    private JsonNode appliedConditions = JsonNodeFactory.instance.objectNode();

    @Column(name = "sql_text", columnDefinition = "text")
    private String sqlText;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb", nullable = false)
    private JsonNode columns = JsonNodeFactory.instance.arrayNode();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rows", columnDefinition = "jsonb", nullable = false)
    private JsonNode rows = JsonNodeFactory.instance.arrayNode();

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", columnDefinition = "jsonb", nullable = false)
    private JsonNode warnings = JsonNodeFactory.instance.arrayNode();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private JsonNode evidence;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
