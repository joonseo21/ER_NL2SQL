package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.webapi.controller.LoginController;
import io.eranalytics.pipeline.webapi.service.InviteService;
import io.eranalytics.pipeline.webapi.service.LoginAccounts;
import io.eranalytics.pipeline.webapi.service.LoginService;
import io.eranalytics.pipeline.webapi.service.WebApiClock;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoginStartupTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            LoginAccounts.class, LoginService.class, LoginController.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(InviteService.class, () -> mock(InviteService.class))
                    .withBean(WebApiClock.class, () -> new WebApiClock(Clock.systemUTC()));

    @Test
    void missingFileDoesNotFailStartupWhenWebIsEnabled() {
        context.withPropertyValues(
                        "er.web-api.enabled=true", "er.web-api.accounts-file=missing-file")
                .run(
                        application -> {
                            assertThat(application)
                                    .hasNotFailed()
                                    .hasSingleBean(LoginService.class);
                            assertThat(application.getBean(LoginAccounts.class).enabled())
                                    .isFalse();
                        });
    }

    @Test
    void collectorOnlyDoesNotCreateLoginBeans() {
        context.withPropertyValues(
                        "er.web-api.enabled=false", "er.web-api.accounts-file=missing-file")
                .run(
                        application ->
                                assertThat(application)
                                        .hasNotFailed()
                                        .doesNotHaveBean(LoginAccounts.class)
                                        .doesNotHaveBean(LoginService.class)
                                        .doesNotHaveBean(LoginController.class));
    }
}
