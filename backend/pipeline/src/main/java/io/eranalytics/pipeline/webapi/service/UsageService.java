package io.eranalytics.pipeline.webapi.service;

import io.eranalytics.pipeline.webapi.entity.DailyUsageEntity;
import io.eranalytics.pipeline.webapi.entity.DailyUsageId;
import io.eranalytics.pipeline.webapi.entity.InviteCodeEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Retries each conflict in a fresh transaction, including first-row insert races. */
@Service
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class UsageService {
    @PersistenceContext(unitName = "webapi")
    private EntityManager entities;

    private final TransactionTemplate transaction;
    private final WebApiClock clock;

    public UsageService(
            @Qualifier("webApiTransactionManager") PlatformTransactionManager manager,
            WebApiClock webApiClock) {
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = webApiClock;
    }

    public <T> T withQuestion(String code, IntFunction<T> operation) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return transaction.execute(status -> operation.apply(consume(code)));
            } catch (RuntimeException conflict) {
                if (!retryable(conflict)) throw conflict;
                if (attempt == 19) {
                    throw new WebApiException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "STORAGE_BUSY",
                            "요청이 겹쳐 처리하지 못했습니다. 잠시 뒤 다시 요청해 주세요.");
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException
                    || cause instanceof jakarta.persistence.OptimisticLockException) return true;
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private int consume(String code) {
        if (code == null || code.isBlank()) throw WebApiException.invalidInvite();
        InviteCodeEntity invite =
                entities.find(InviteCodeEntity.class, code, LockModeType.PESSIMISTIC_WRITE);
        if (invite == null || !invite.isActive()) throw WebApiException.invalidInvite();
        LocalDate date = clock.today();
        DailyUsageEntity global = counter(date, null);
        DailyUsageEntity personal = counter(date, code);
        if (personal.getRequestCount() >= 50) {
            throw new WebApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "INVITE_LIMIT_EXCEEDED", "오늘 질문 횟수를 모두 사용했습니다.");
        }
        if (global.getRequestCount() >= 300) {
            throw new WebApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "GLOBAL_LIMIT_EXCEEDED",
                    "오늘 전체 질문 횟수를 모두 사용했습니다.");
        }
        global.increment();
        personal.increment();
        entities.flush();
        return Math.min(50 - personal.getRequestCount(), 300 - global.getRequestCount());
    }

    private DailyUsageEntity counter(LocalDate date, String code) {
        DailyUsageId id = new DailyUsageId(date, code == null ? "GLOBAL" : "INVITE:" + code);
        DailyUsageEntity counter = entities.find(DailyUsageEntity.class, id);
        if (counter == null) {
            counter = new DailyUsageEntity(date, code);
            entities.persist(counter);
        }
        return counter;
    }
}
