package io.eranalytics.pipeline.webapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.webapi.service.InviteService;
import io.eranalytics.pipeline.webapi.service.QuestionAdmissionGate;
import io.eranalytics.pipeline.webapi.service.QuestionProcessingStore;
import io.eranalytics.pipeline.webapi.service.WebApiClock;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class WebApiConfiguration {
    @Bean
    public ApplicationRunner recoverUnfinishedQuestions(
            QuestionProcessingStore store, QuestionAdmissionGate gate) {
        return new RecoveryRunner(store, gate);
    }

    /** Run after DB preflight and before the collector's continuous ApplicationRunner. */
    private record RecoveryRunner(QuestionProcessingStore store, QuestionAdmissionGate gate)
            implements ApplicationRunner, Ordered {
        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public void run(ApplicationArguments arguments) {
            store.failUnfinishedAfterRestart();
            gate.ready();
        }
    }

    @Bean
    public ThreadPoolTaskExecutor webApiQuestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(300);
        executor.setThreadNamePrefix("webapi-question-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    public WebApiClock webApiClock() {
        return new WebApiClock(Clock.systemUTC());
    }

    @Bean
    public FilterRegistrationBean<InviteCodeFilter> inviteCodeFilter(
            InviteService invites, ObjectMapper json) {
        FilterRegistrationBean<InviteCodeFilter> registration =
                new FilterRegistrationBean<>(new InviteCodeFilter(invites, json));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }
}
