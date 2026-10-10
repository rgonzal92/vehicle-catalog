package dev.rgonz.catalog.document;

import dev.rgonz.catalog.ai.Model;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.document.Documents.Kind;
import dev.rgonz.catalog.job.JobHandler;
import dev.rgonz.catalog.job.JobType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Makes an uploaded document one that can be searched: reads its text, splits it into passages, has
 * the embedding model say what each passage means, and keeps the passages with their meanings.
 *
 * <p>What the file itself brings about is no failure of the job: a file that cannot be read, has no
 * text, or is too long ends its document as failed, with the reason, and so does a model that
 * cannot be asked. The job is then done, since another try would end the same way. Anything else
 * that goes wrong is the job's own failure, and is tried again as every job is.
 */
@Component
class DocumentProcessing implements JobHandler {
  /** The names a job of this type gives its document and which of the document's jobs it is. */
  static final String DOCUMENT = "documentId";

  static final String PROCESSING = "processing";

  /** How many passages the embedding model is sent in one request. */
  private static final int PASSAGES_A_REQUEST = 50;

  private final JdbcClient jdbc;
  private final DocumentFiles files;
  private final Model model;
  private final TransactionTemplate apart;

  DocumentProcessing(
      JdbcClient jdbc, DocumentFiles files, Model model, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.files = files;
    this.model = model;
    // A transaction of its own, so that a document is seen to be running while its job runs and
    // not only once the job is done.
    this.apart = new TransactionTemplate(transactions.getTransactionManager());
    this.apart.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public JobType type() {
    return JobType.PROCESS_DOCUMENT;
  }

  @Override
  public void handle(JsonNode subject) {
    long id = subject.path(DOCUMENT).asLong();
    var found =
        jdbc.sql("SELECT kind, uploaded_by, processings FROM document WHERE id = :id")
            .param("id", id)
            .query(Uploaded.class)
            .optional();
    // A document that was deleted, or given to the worker again since, is not this job's to read.
    if (found.isEmpty() || found.get().processings() != subject.path(PROCESSING).asInt()) {
      return;
    }
    var document = found.get();
    apart.executeWithoutResult(
        running ->
            jdbc.sql("UPDATE document SET status = 'RUNNING', reason = NULL WHERE id = :id")
                .param("id", id)
                .update());

    List<String> passages;
    try {
      passages =
          Passages.of(
              DocumentText.of(Kind.valueOf(document.kind()), files.read(String.valueOf(id))));
    } catch (DocumentText.Unreadable why) {
      failed(id, why.getMessage());
      return;
    }
    if (passages.isEmpty()) {
      failed(id, "It holds no text.");
      return;
    }
    if (passages.size() > Passages.MOST) {
      failed(id, Passages.TOO_LONG);
      return;
    }

    var meanings = new ArrayList<float[]>();
    try {
      for (int from = 0; from < passages.size(); from += PASSAGES_A_REQUEST) {
        meanings.addAll(
            model.meaningsOf(
                document.uploadedBy(),
                "DOCUMENT_EMBEDDING",
                passages.subList(from, Math.min(passages.size(), from + PASSAGES_A_REQUEST))));
      }
    } catch (ApiException refused) {
      failed(id, refused.getBody().getDetail());
      return;
    }

    // In the job's own transaction: the passages there were are replaced by these or not at all.
    forget(id);
    for (int position = 0; position < passages.size(); position++) {
      jdbc.sql(
              """
              INSERT INTO document_passage (document_id, position, text, meaning)
              VALUES (:document, :position, :text, :meaning::vector)
              """)
          .param("document", id)
          .param("position", position)
          .param("text", passages.get(position))
          .param("meaning", asAVector(meanings.get(position)))
          .update();
    }
    jdbc.sql("UPDATE document SET status = 'READY', reason = NULL WHERE id = :id")
        .param("id", id)
        .update();
  }

  private void failed(long id, String reason) {
    forget(id);
    jdbc.sql("UPDATE document SET status = 'FAILED', reason = :reason WHERE id = :id")
        .param("reason", reason)
        .param("id", id)
        .update();
  }

  private void forget(long id) {
    jdbc.sql("DELETE FROM document_passage WHERE document_id = :id").param("id", id).update();
  }

  /** The numbers as pgvector reads a list of them. */
  static String asAVector(float[] numbers) {
    var written = new StringBuilder("[");
    for (int at = 0; at < numbers.length; at++) {
      written.append(at == 0 ? "" : ",").append(numbers[at]);
    }
    return written.append(']').toString();
  }

  private record Uploaded(String kind, long uploadedBy, int processings) {}
}
