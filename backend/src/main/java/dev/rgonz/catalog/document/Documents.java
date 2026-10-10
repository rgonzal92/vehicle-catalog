package dev.rgonz.catalog.document;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.ResetCleanup;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.job.Jobs;
import dev.rgonz.catalog.reference.FixedLists;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notes admins upload, each about one vehicle line's model year. A document is a row here and a
 * file in a bucket, kept and removed together. What may be uploaded is limited, because the demo
 * lets anyone be an admin.
 */
@Service
class Documents implements ResetCleanup {
  static final int LARGEST_FILE_BYTES = 2 * 1024 * 1024;
  static final int MOST_DOCUMENTS = 20;
  static final int LONGEST_TITLE = 80;

  private static final byte[] HOW_A_PDF_STARTS = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  /**
   * A document as the list shows it. One whose job has failed for good while it waited or ran has
   * failed too: nothing will read it until it is given to the worker again.
   */
  private static final String LISTED =
      """
      SELECT d.id, d.title, d.vehicle_line_id, v.name AS vehicle_line, d.model_year, d.file_name,
             d.size_bytes, u.display_name AS uploaded_by, d.uploaded_at,
             CASE WHEN d.status IN ('WAITING', 'RUNNING') AND j.status = 'FAILED'
                  THEN 'FAILED' ELSE d.status END AS status,
             CASE WHEN d.status IN ('WAITING', 'RUNNING') AND j.status = 'FAILED'
                  THEN 'The job that reads it has failed. The page Jobs has what it last said.'
                  ELSE d.reason END AS reason,
             (SELECT count(*) FROM document_passage p WHERE p.document_id = d.id) AS passages
      FROM document d
      JOIN vehicle_line v ON v.id = d.vehicle_line_id
      JOIN app_user u ON u.id = d.uploaded_by
      LEFT JOIN job j ON j.dedupe_key = 'document-' || d.id || '-' || d.processings
      """;

  private final JdbcClient jdbc;
  private final DocumentFiles files;
  private final FixedLists fixedLists;
  private final Jobs jobs;

  Documents(JdbcClient jdbc, DocumentFiles files, FixedLists fixedLists, Jobs jobs) {
    this.jdbc = jdbc;
    this.files = files;
    this.fixedLists = fixedLists;
    this.jobs = jobs;
  }

  /** What a file is, which its name says: the three kinds a document may be. */
  enum Kind {
    MD("text/markdown"),
    TXT("text/plain"),
    PDF("application/pdf");

    final String contentType;

    Kind(String contentType) {
      this.contentType = contentType;
    }
  }

  /**
   * A document as the list shows it.
   *
   * @param status WAITING until the worker has it, RUNNING while it does, and then READY or FAILED
   * @param reason why it failed, for one that did
   * @param passages how many passages it was split into, which a ready one is searched by
   */
  record Listed(
      long id,
      String title,
      long vehicleLineId,
      String vehicleLine,
      int modelYear,
      String fileName,
      int sizeBytes,
      String uploadedBy,
      Instant uploadedAt,
      String status,
      String reason,
      int passages) {}

  /** Every document, newest first. */
  List<Listed> all() {
    return jdbc.sql(LISTED + "ORDER BY d.id DESC").query(Listed.class).list();
  }

  /**
   * Takes a document in: its row, and its file under the row's id. A file that cannot be kept takes
   * the row with it.
   *
   * @param namedAs the file's name as the browser gave it, which may come with a path
   * @param title what to call it, or nothing for its file's name without what follows the last dot
   * @throws ApiException when the upload is not what a document may be, with the reason
   */
  @Transactional
  Listed add(
      long actorId, String namedAs, byte[] file, String title, long vehicleLineId, int modelYear) {
    var fileName =
        namedAs.substring(Math.max(namedAs.lastIndexOf('/'), namedAs.lastIndexOf('\\')) + 1);
    var dot = fileName.lastIndexOf('.');
    var kind = kindOf(dot < 0 ? "" : fileName.substring(dot + 1));
    if (file.length == 0) {
      throw ApiException.invalid("This file is empty.");
    }
    if (file.length > LARGEST_FILE_BYTES) {
      throw ApiException.limitExceeded("A document is at most 2 MB.");
    }
    requireToBe(kind, file);
    var called = title == null || title.isBlank() ? fileName.substring(0, dot) : title.strip();
    if (called.isEmpty() || called.length() > LONGEST_TITLE) {
      throw ApiException.invalid(
          "Give the document a title of at most %d characters.".formatted(LONGEST_TITLE));
    }
    if (!fixedLists.hasModelYear(modelYear)) {
      throw ApiException.invalid("Choose one of the model years.");
    }
    var active =
        jdbc.sql("SELECT active FROM vehicle_line WHERE id = :id")
            .param("id", vehicleLineId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(() -> ApiException.invalid("Choose one of the vehicle lines."));
    if (!active) {
      throw ApiException.conflict(
          "VEHICLE_LINE_INACTIVE",
          "This vehicle line is deactivated, so it takes no new documents.");
    }
    // Held until this transaction ends, so that two uploads at once are counted one after the
    // other and cannot both be the last there is room for.
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('document'))").query(rows -> {});
    if (jdbc.sql("SELECT count(*) FROM document").query(Long.class).single() >= MOST_DOCUMENTS) {
      throw ApiException.limitExceeded(
          "There are at most %d documents. Delete one first.".formatted(MOST_DOCUMENTS));
    }

    long id =
        jdbc.sql(
                """
                INSERT INTO document
                    (title, vehicle_line_id, model_year, file_name, kind, size_bytes, uploaded_by)
                VALUES (:title, :line, :year, :fileName, :kind, :size, :actor)
                RETURNING id
                """)
            .param("title", called)
            .param("line", vehicleLineId)
            .param("year", modelYear)
            .param("fileName", fileName)
            .param("kind", kind.name())
            .param("size", file.length)
            .param("actor", actorId)
            .query(Long.class)
            .single();
    files.put(String.valueOf(id), file, kind.contentType);
    giveToTheWorker(id, 0);

    return find(id).orElseThrow();
  }

  /**
   * Gives a document that has failed to the worker once more, with a job of its own.
   *
   * @throws ApiException when there is no such document, or it has not failed
   */
  @Transactional
  Listed processAgain(long id) {
    var document = find(id).orElseThrow(ApiException::notFound);
    if (!document.status().equals("FAILED")) {
      throw ApiException.conflict(
          "NOT_FAILED", "Only a document that has failed is processed again.");
    }
    int processing =
        jdbc.sql(
                """
                UPDATE document
                SET processings = processings + 1, status = 'WAITING', reason = NULL
                WHERE id = :id
                RETURNING processings
                """)
            .param("id", id)
            .query(Integer.class)
            .single();
    giveToTheWorker(id, processing);

    return find(id).orElseThrow();
  }

  private Optional<Listed> find(long id) {
    return jdbc.sql(LISTED + "WHERE d.id = :id").param("id", id).query(Listed.class).optional();
  }

  /** Leaves the job that reads a document, with the change that calls for it. */
  private void giveToTheWorker(long id, int processing) {
    jobs.queue(
        JobType.PROCESS_DOCUMENT,
        "document-%d-%d".formatted(id, processing),
        Map.of(DocumentProcessing.DOCUMENT, id, DocumentProcessing.PROCESSING, processing));
  }

  /** Removes a document: its row, and its file. A file that cannot be removed keeps the row. */
  @Transactional
  void delete(long id) {
    if (jdbc.sql("DELETE FROM document WHERE id = :id").param("id", id).update() == 0) {
      throw ApiException.notFound();
    }
    files.delete(String.valueOf(id));
  }

  /** The demo reset empties the table with the others. This empties the bucket. */
  @Override
  public void clean() {
    files.deleteAll();
  }

  private static Kind kindOf(String extension) {
    try {
      return Kind.valueOf(extension.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException other) {
      throw ApiException.invalid("A document is a .md, a .txt, or a .pdf file.");
    }
  }

  /** Refuses a file that is not what its name says: a PDF by how it starts, text by being UTF-8. */
  private static void requireToBe(Kind kind, byte[] file) {
    if (kind == Kind.PDF) {
      for (int at = 0; at < HOW_A_PDF_STARTS.length; at++) {
        if (at >= file.length || file[at] != HOW_A_PDF_STARTS[at]) {
          throw ApiException.invalid("This file is named as a PDF and is not one.");
        }
      }
      return;
    }
    try {
      var text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(file));
      if (text.chars().anyMatch(character -> character == 0)) {
        throw new CharacterCodingException();
      }
    } catch (CharacterCodingException notText) {
      throw ApiException.invalid("This file is named as text and is not text in UTF-8.");
    }
  }
}
