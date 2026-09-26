package com.homektv.config;

import com.homektv.HomeKtvApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigurationTest {

    private static final String SCHEDULING_CONFIGURATION =
            "com.homektv.config.SchedulingConfiguration";

    @Test
    void applicationEntryPointDoesNotUnconditionallyEnableScheduling() {
        assertThat(HomeKtvApplication.class.isAnnotationPresent(EnableScheduling.class)).isFalse();
    }

    @Test
    void disabledPropertyDoesNotRegisterScheduledMethods() {
        contextRunner()
                .withPropertyValues("app.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                            .isEmpty();
                });
    }

    @Test
    void schedulingRemainsEnabledWhenPropertyIsMissing() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            var processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(processor.getScheduledTasks()).hasSize(1);
        });
    }

    private ApplicationContextRunner contextRunner() {
        Class<?> schedulingConfiguration;
        try {
            schedulingConfiguration = Class.forName(SCHEDULING_CONFIGURATION);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Conditional SchedulingConfiguration has not been added", exception);
        }

        return new ApplicationContextRunner()
                .withUserConfiguration(schedulingConfiguration, ProbeConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ScheduledProbe scheduledProbe() {
            return new ScheduledProbe();
        }
    }

    static class ScheduledProbe {
        @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
        void tick() {
        }
    }
}
