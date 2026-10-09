package dev.rgonz.catalog.job;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Marks what exists only where the process is told of a queue. The worker is; the API is not, and
 * so starts and answers whether or not there is a queue to reach.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnExpression("!@environment.getProperty('app.jobs.queue-url', '').isBlank()")
@interface TalksToTheQueue {}
