package io.eranalytics.pipeline.webapi.service;

import io.eranalytics.pipeline.webapi.entity.DailyUsageId;
import io.eranalytics.pipeline.webapi.entity.InviteCodeEntity;
import io.eranalytics.pipeline.webapi.repository.DailyUsageRepository;
import io.eranalytics.pipeline.webapi.repository.InviteCodeRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class InviteService {
    private final InviteCodeRepository invites;
    private final DailyUsageRepository usage;
    private final WebApiClock webApiClock;

    @Transactional(value = "webApiTransactionManager", readOnly = true)
    public String requireActive(String code) {
        if (code == null || code.isBlank()) throw WebApiException.invalidInvite();
        InviteCodeEntity invite =
                invites.findById(code).orElseThrow(WebApiException::invalidInvite);
        if (!invite.isActive()) throw WebApiException.invalidInvite();
        return invite.getCode();
    }

    @Transactional(value = "webApiTransactionManager", readOnly = true)
    public int remaining(String code) {
        requireActive(code);
        LocalDate date = webApiClock.today();
        int personal = count(new DailyUsageId(date, "INVITE:" + code));
        int global = count(new DailyUsageId(date, "GLOBAL"));
        return Math.max(0, Math.min(50 - personal, 300 - global));
    }

    private int count(DailyUsageId id) {
        return usage.findById(id).map(value -> value.getRequestCount()).orElse(0);
    }
}
