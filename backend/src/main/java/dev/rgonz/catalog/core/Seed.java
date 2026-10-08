package dev.rgonz.catalog.core;

import org.springframework.boot.ApplicationRunner;

/**
 * Fills a part of the database that holds nothing with what the app starts out with. Every seed
 * runs once the application has started and again in each demo reset, in the order its {@code
 * Order} gives it, and leaves a part that already holds something as it is.
 */
public interface Seed extends ApplicationRunner {}
