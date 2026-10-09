package dev.rgonz.catalog.job;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the messages that changes left unsent. A message is marked as sent only after the queue has
 * taken it, so a stop between the two sends it again and never loses it: a message can reach the
 * queue twice, and whoever handles it has to allow for that.
 */
@Component
@TalksToTheQueue
class Publisher {
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final JobQueue queue;

  Publisher(JdbcClient jdbc, TransactionTemplate transactions, JobQueue queue) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.queue = queue;
  }

  /**
   * Sends the oldest unsent messages, as many as the queue takes in one go at most, and answers
   * with how many it sent. The messages stay locked until they are marked, so two publishers at
   * once send none of them twice.
   */
  int sendUnsent() {
    return transactions.execute(
        transaction -> {
          var unsent =
              jdbc.sql(
                      """
                      SELECT o.id, o.payload::text AS body, j.type, o.traceparent
                      FROM outbox o
                      JOIN job j ON j.id = o.job_id
                      WHERE o.sent_at IS NULL
                      ORDER BY o.id
                      LIMIT :most
                      FOR UPDATE OF o SKIP LOCKED
                      """)
                  .param("most", JobQueue.MOST_AT_ONCE)
                  .query(Unsent.class)
                  .list();
          for (var message : unsent) {
            queue.send(message.id(), message.body(), message.type(), message.traceparent());
            jdbc.sql("UPDATE outbox SET sent_at = now() WHERE id = :id")
                .param("id", message.id())
                .update();
          }
          return unsent.size();
        });
  }

  private record Unsent(long id, String body, String type, String traceparent) {}
}
