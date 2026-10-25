package com.homektv.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MvcAsyncExecutorConfigurationTest {
    @Test
    void mvcAsyncResponsesUseAnExplicitExecutorBoundToTheConfiguredStreamLimit() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test", Map.of("app.resource-budget.media-stream-limit", "2")));
        context.register(MediaStreamExecutorConfiguration.class, TranscodeAsyncWebConfig.class);
        context.refresh();

        try {
            TranscodeAsyncWebConfig webConfig = context.getBean(TranscodeAsyncWebConfig.class);
            ExposableAsyncSupportConfigurer configurer = new ExposableAsyncSupportConfigurer();
            webConfig.configureAsyncSupport(configurer);

            assertThat(configurer.configuredDefaultTimeout())
                    .as("media responses must not inherit the MVC async request cutoff")
                    .isEqualTo(0L);
            AsyncTaskExecutor configuredExecutor = configurer.configuredExecutor();
            assertThat(configuredExecutor)
                    .as("MVC streaming responses must not fall back to an unbounded default executor")
                    .isSameAs(context.getBean("mediaStreamMvcTaskExecutor"));
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuredExecutor;
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .as("MVC streaming work queue must be finite")
                    .isEqualTo(2);

            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            CountDownLatch firstTwoStarted = new CountDownLatch(2);
            CountDownLatch releaseWorkers = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            try {
                for (int index = 0; index < 4; index++) {
                    tasks.add(executor.submit(() -> {
                        int nowActive = active.incrementAndGet();
                        maximumActive.accumulateAndGet(nowActive, Math::max);
                        firstTwoStarted.countDown();
                        try {
                            if (!releaseWorkers.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test did not release MVC workers");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("MVC worker interrupted", interrupted);
                        } finally {
                            active.decrementAndGet();
                        }
                    }));
                }

                assertThat(firstTwoStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(maximumActive.get())
                        .as("configured stream limit must bound simultaneous MVC response workers")
                        .isLessThanOrEqualTo(2);
            } finally {
                releaseWorkers.countDown();
                for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            context.close();
        }
    }

    private static final class ExposableAsyncSupportConfigurer extends AsyncSupportConfigurer {
        AsyncTaskExecutor configuredExecutor() {
            return getTaskExecutor();
        }

        Long configuredDefaultTimeout() {
            return getTimeout();
        }
    }
}
