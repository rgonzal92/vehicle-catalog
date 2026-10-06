package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.library.Feature.Kind;
import dev.rgonz.catalog.library.Feature.Status;
import dev.rgonz.catalog.reference.FixedLists;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library's features and packages. A feature is added, edited, retired, and reactivated, but
 * never deleted. It is a package exactly when its category is Packages.
 */
@Service
class Features {
  private static final String PACKAGES = "PACKAGES";

  private final FeatureRepository repository;
  private final FixedLists fixedLists;

  Features(FeatureRepository repository, FixedLists fixedLists) {
    this.repository = repository;
    this.fixedLists = fixedLists;
  }

  /** One page of the features that pass every given filter, in code order. */
  @Transactional(readOnly = true)
  Page<Feature> search(FeatureSearch search, int page, int size) {
    return repository.findAll(search.matching(), PageRequest.of(page, size, Sort.by("code")));
  }

  @Transactional
  Feature add(NewFeature given) {
    requireCategory(given.kind(), given.categoryCode());

    try {
      return repository.saveAndFlush(
          new Feature(
              given.code(), given.name(), given.description(), given.categoryCode(), given.kind()));
    } catch (DataIntegrityViolationException taken) {
      // The database keeps codes unique, which also settles two admins racing.
      throw ApiException.conflict("NAME_TAKEN", "Another feature already uses this code.");
    }
  }

  /** Applies the change only if nobody has changed the feature since the given version. */
  @Transactional
  Feature change(long id, FeatureChange given) {
    var feature = repository.findById(id).orElseThrow(ApiException::notFound);
    requireCategory(feature.getKind(), given.categoryCode());
    if (feature.getVersion() != given.version()) {
      throw new ObjectOptimisticLockingFailureException(Feature.class, id);
    }
    feature.change(given.name(), given.description(), given.categoryCode());

    return repository.saveAndFlush(feature);
  }

  @Transactional
  Feature setStatus(long id, Status status) {
    var feature = repository.findById(id).orElseThrow(ApiException::notFound);
    feature.setStatus(status);

    return repository.saveAndFlush(feature);
  }

  private void requireCategory(Kind kind, String categoryCode) {
    if (!fixedLists.hasCategory(categoryCode)) {
      throw ApiException.invalid("Choose one of the categories.");
    }
    if ((kind == Kind.PACKAGE) != PACKAGES.equals(categoryCode)) {
      throw ApiException.invalid(
          kind == Kind.PACKAGE
              ? "A package stays in the Packages category."
              : "Only a package can be in the Packages category.");
    }
  }

  /** The filters of a search. An empty query or a null filter lets every feature through. */
  record FeatureSearch(String query, String category, Kind kind, Status status) {
    Specification<Feature> matching() {
      return (feature, select, where) -> {
        var all = new ArrayList<Predicate>();
        if (!query.isBlank()) {
          var containsQuery = "%" + literal(query.strip().toLowerCase(Locale.ROOT)) + "%";
          all.add(
              where.or(
                  where.like(where.lower(feature.get("code")), containsQuery, '\\'),
                  where.like(where.lower(feature.get("name")), containsQuery, '\\')));
        }
        if (category != null) {
          all.add(where.equal(feature.get("categoryCode"), category));
        }
        if (kind != null) {
          all.add(where.equal(feature.get("kind"), kind));
        }
        if (status != null) {
          all.add(where.equal(feature.get("status"), status));
        }
        return where.and(all.toArray(Predicate[]::new));
      };
    }

    /** The text with the characters a pattern treats specially made to stand for themselves. */
    private static String literal(String text) {
      return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
  }

  /** What an admin gives to add a feature. Its code and kind cannot change afterwards. */
  record NewFeature(
      @NotNull(message = "Enter a code.")
          @Pattern(
              regexp = "[A-Z][A-Z0-9_]{1,39}",
              message =
                  "Use 2 to 40 capital letters, digits, or underscores for the code, starting"
                      + " with a letter.")
          String code,
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name,
      @Size(max = 500, message = "Keep the description to 500 characters or fewer.")
          String description,
      @NotBlank(message = "Choose a category.") String categoryCode,
      @NotNull(message = "Choose a kind.") Kind kind) {
    NewFeature {
      name = Trims.stripped(name);
      description = described(description);
    }
  }

  /** What an admin gives to edit a feature, with the version of the feature they saw. */
  record FeatureChange(
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name,
      @Size(max = 500, message = "Keep the description to 500 characters or fewer.")
          String description,
      @NotBlank(message = "Choose a category.") String categoryCode,
      @NotNull(message = "Give the version of the feature you are changing.") Long version) {
    FeatureChange {
      name = Trims.stripped(name);
      description = described(description);
    }
  }

  /** A description is optional and stored without the spaces around it. */
  private static String described(String description) {
    return description == null ? "" : description.strip();
  }
}
