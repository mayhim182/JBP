package com.jbp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables @Async so notification handling runs off the request thread.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
