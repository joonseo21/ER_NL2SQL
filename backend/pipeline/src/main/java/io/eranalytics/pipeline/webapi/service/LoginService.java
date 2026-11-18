package io.eranalytics.pipeline.webapi.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class LoginService {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private final LoginAccounts accounts;
    private final InviteService invites;
    private final WebApiClock clock;
    private final String dummyHash = PasswordHashes.create(new char[32]);
    private final Map<String, Attempts> attempts = new HashMap<>();
    private final Deque<Instant> globalFailures = new ArrayDeque<>();

    public LoginService(LoginAccounts accounts, InviteService invites, WebApiClock clock) {
        this.accounts = accounts;
        this.invites = invites;
        this.clock = clock;
    }

    /** Serialize checking and recording so concurrent failures cannot bypass either limit. */
    public synchronized Map<String, Object> login(String username, String password) {
        if (!accounts.enabled()) {
            throw new WebApiException(
                    HttpStatus.NOT_FOUND, "LOGIN_DISABLED", "지금은 로그인을 사용할 수 없습니다. 초대 코드로 입장해 주세요.");
        }
        Instant now = clock.instant();
        requireAllowed(username, now);
        LoginAccounts.Account account = accounts.find(username);
        boolean matches = matches(password, account);
        if (account != null && matches) {
            Integer remaining = activeRemaining(account.inviteCode());
            if (remaining != null) {
                return Map.of("invite_code", account.inviteCode(), "remaining_today", remaining);
            }
        }
        recordFailure(username, now);
        throw new WebApiException(
                HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    private void requireAllowed(String username, Instant now) {
        expire(globalFailures, now);
        attempts.values().forEach(value -> expire(value.failures, now));
        attempts.values().removeIf(value -> value.failures.isEmpty() && !locked(value, now));
        if (globalFailures.size() > 30 || locked(attempts.get(username), now)) {
            throw limited();
        }
    }

    private static boolean locked(Attempts state, Instant now) {
        return state != null && state.lockedUntil != null && now.isBefore(state.lockedUntil);
    }

    private boolean matches(String password, LoginAccounts.Account account) {
        char[] secret = password.toCharArray();
        try {
            return PasswordHashes.matches(
                    secret, account == null ? dummyHash : account.passwordHash());
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private Integer activeRemaining(String code) {
        try {
            return invites.remaining(code);
        } catch (WebApiException error) {
            if (!"INVITE_INVALID".equals(error.getCode())) throw error;
            return null;
        }
    }

    private void recordFailure(String username, Instant now) {
        Attempts state = attempts.computeIfAbsent(username, key -> new Attempts());
        state.failures.addLast(now);
        globalFailures.addLast(now);
        if (state.failures.size() >= 5) state.lockedUntil = now.plus(WINDOW);
        if (locked(state, now) || globalFailures.size() > 30) throw limited();
    }

    private static void expire(Deque<Instant> failures, Instant now) {
        while (!failures.isEmpty() && !failures.getFirst().isAfter(now.minus(WINDOW))) {
            failures.removeFirst();
        }
    }

    private static WebApiException limited() {
        return new WebApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_RATE_LIMITED",
                "로그인 시도가 너무 많습니다. 10분 뒤 다시 시도해 주세요.");
    }

    private static final class Attempts {
        private final Deque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil;
    }
}
