package dev.rgonz.catalog.document;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.core.Seed;
import dev.rgonz.catalog.user.DemoPeople;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the documents the demo starts with: a few made-up notes about seeded catalogs, listed in
 * {@code seed/documents.json} with their files beside it, so that someone who opens the demo has
 * documents to ask the analyst about. They are uploaded as the demo's admin would upload them, and
 * the worker makes each ready as it does any upload. Where there are documents already, nothing is
 * added: the demo reset removes every document first, and so puts these back.
 *
 * <p>Nothing is seeded where the installation has no bucket for documents, where its vehicle lines
 * are not the seeded ones, or where the admin demo account does not say who it is.
 */
@Component
@Order(4)
class DocumentSeed implements Seed {
  private static final Logger log = LoggerFactory.getLogger(DocumentSeed.class);

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Documents documents;
  private final DocumentFiles files;
  private final DemoPeople demoPeople;
  private final boolean wanted;

  DocumentSeed(
      JdbcClient jdbc,
      JsonMapper json,
      Documents documents,
      DocumentFiles files,
      DemoPeople demoPeople,
      @Value("${app.documents.seeded}") boolean wanted) {
    this.jdbc = jdbc;
    this.json = json;
    this.documents = documents;
    this.files = files;
    this.demoPeople = demoPeople;
    this.wanted = wanted;
  }

  /** Runs once the application has started, and in each demo reset, in one transaction. */
  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    if (!wanted
        || !files.hasABucket()
        || jdbc.sql("SELECT EXISTS (SELECT 1 FROM document)").query(Boolean.class).single()) {
      return;
    }
    var uploader = demoPeople.record(Role.ADMIN);
    if (uploader.isEmpty()) {
      log.warn(
          "No documents were seeded, because the admin demo account needs a subject, a username,"
              + " and a display name in app.demo-accounts");
      return;
    }
    var notes = read();
    for (var note : notes) {
      if (lineOf(note) == null) {
        log.warn("No documents were seeded, because the library has no {}", note.vehicleLine());
        return;
      }
    }

    for (var note : notes) {
      documents.add(
          uploader.get(), note.file(), bytesOf(note), note.title(), lineOf(note), note.modelYear());
    }
  }

  private Long lineOf(SeededNote note) {
    return jdbc.sql("SELECT id FROM vehicle_line WHERE code = :code")
        .param("code", note.vehicleLine())
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private List<SeededNote> read() {
    try (var content = new ClassPathResource("seed/documents.json").getInputStream()) {
      return List.of(json.readValue(content, SeededNote[].class));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private static byte[] bytesOf(SeededNote note) {
    try (var content = new ClassPathResource("seed/documents/" + note.file()).getInputStream()) {
      return content.readAllBytes();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * A note as the file lists it.
   *
   * @param file its file, beside the list
   * @param vehicleLine the code of the vehicle line it is about
   */
  private record SeededNote(String title, String file, String vehicleLine, int modelYear) {}
}
