package uk.gov.justice.laa.dstew.access.config;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Provides the application executor before JPA bootstrap resolves async executors.
 *
 * <p>Spring Boot 4.1 resolves all {@code AsyncTaskExecutor} beans while creating its JPA entity
 * manager factory builder. Defining the standard application executor directly prevents that lookup
 * from re-entering Axon's JPA auto-configuration while Boot is creating its own executor.
 */
@Configuration(proxyBeanMethods = false)
public class TaskExecutionConfig {

  @Bean(name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
  ThreadPoolTaskExecutor applicationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(Integer.MAX_VALUE);
    executor.setQueueCapacity(Integer.MAX_VALUE);
    executor.setKeepAliveSeconds(60);
    executor.setThreadNamePrefix("task-");
    return executor;
  }
}
