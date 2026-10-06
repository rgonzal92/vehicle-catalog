package dev.rgonz.catalog.library;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Stores features. */
interface FeatureRepository
    extends JpaRepository<Feature, Long>, JpaSpecificationExecutor<Feature> {}
