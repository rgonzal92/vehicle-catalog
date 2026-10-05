package dev.rgonz.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Starts the vehicle catalog backend. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VehicleCatalogApplication {

  public static void main(String[] args) {
    SpringApplication.run(VehicleCatalogApplication.class, args);
  }
}
