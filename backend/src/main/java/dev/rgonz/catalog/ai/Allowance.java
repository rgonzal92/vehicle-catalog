package dev.rgonz.catalog.ai;

import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What asking the model may cost in a day: so much for the whole application, and so much of that
 * for one account. Before the model is asked, the most the request can cost is reserved here, and
 * the request is refused when that would pass either allowance. Afterwards the reservation becomes
 * what the request did cost. A request whose cost never became known keeps what it reserved.
 *
 * <p>A day is a UTC calendar day. One reservation is made at a time, by whichever process makes it,
 * so that two cannot both have the last of an allowance. What is reserved and what is spent is
 * written at once and for good, whatever becomes of the work that asked for it: a job that is
 * rolled back has asked the model all the same.
 */
@Component
class Allowance {
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  /**
   * What a request of an ordinary size reserves. An allowance that has less than this left counts
   * as spent, when someone asks whether the model can be asked.
   */
  private static final long ORDINARY_INPUT_BYTES = 10_000;

  private static final int ORDINARY_OUTPUT_TOKENS = 300;

  /** What a day's rows come to: what each did cost, or what it reserved while that is not known. */
  private static final String USED =
      """
      SELECT coalesce(sum(coalesce(spent, reserved)), 0) AS total,
             coalesce(sum(coalesce(spent, reserved)) FILTER (WHERE user_id = :account), 0) AS own
      FROM ai_spend
      WHERE day = (now() AT TIME ZONE 'UTC')::date
      """;

  /** Which allowance is spent, and how a person is told so. */
  enum Spent {
    DAILY_ALLOWANCE("Today's allowance for the model is spent. It renews at 00:00 UTC."),
    ACCOUNT_ALLOWANCE(
        "This account's allowance for the model is spent for today. It renews at 00:00 UTC.");

    final String words;

    Spent(String words) {
      this.words = words;
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final MeterRegistry metrics;
  private final BigDecimal daily;
  private final BigDecimal dailyPerAccount;
  private final BigDecimal inputPrice;
  private final BigDecimal outputPrice;
  private final BigDecimal embeddingPrice;

  Allowance(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      MeterRegistry metrics,
      @Value("${app.ai.allowance.daily}") BigDecimal daily,
      @Value("${app.ai.allowance.daily-per-account}") BigDecimal dailyPerAccount,
      @Value("${app.ai.price.input-per-million}") BigDecimal inputPrice,
      @Value("${app.ai.price.output-per-million}") BigDecimal outputPrice,
      @Value("${app.ai.price.embedding-per-million}") BigDecimal embeddingPrice) {
    this.jdbc = jdbc;
    // A transaction of its own, so that the turn it takes ends when the reservation is written
    // and not when a transaction around it does, which may last as long as the model takes.
    this.transactions = new TransactionTemplate(transactions.getTransactionManager());
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.metrics = metrics;
    this.daily = daily;
    this.dailyPerAccount = dailyPerAccount;
    this.inputPrice = inputPrice;
    this.outputPrice = outputPrice;
    this.embeddingPrice = embeddingPrice;
  }

  /**
   * Reserves the most a request can cost and answers with the reservation.
   *
   * @param accountId whose request it is, or null for one of the application's own
   * @param purpose what the model is asked for
   * @param inputBytes the size of what is sent, which is at least as many bytes as it is tokens
   * @param mostOutputTokens the most tokens the model may answer with
   * @throws ApiException when the day's allowance or the account's would be passed
   */
  long reserve(Long accountId, String purpose, long inputBytes, int mostOutputTokens) {
    return reserve(accountId, purpose, cost(inputBytes, mostOutputTokens));
  }

  /**
   * Reserves the most a request to the embedding model can cost, which answers with numbers and is
   * paid for by what it is sent alone.
   *
   * @param inputBytes the size of the texts, which is at least as many bytes as it is tokens
   * @throws ApiException when the day's allowance or the account's would be passed
   */
  long reserveForMeanings(Long accountId, String purpose, long inputBytes) {
    return reserve(accountId, purpose, costOfMeanings(inputBytes));
  }

  private long reserve(Long accountId, String purpose, BigDecimal cost) {
    return transactions.execute(
        reserving -> {
          // Held until this transaction ends, so that what the day comes to is read and added to
          // by one request at a time.
          jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('ai_spend'))").query(row -> {});
          refusal(accountId, cost)
              .ifPresent(
                  why -> {
                    throw refused(why);
                  });

          return jdbc.sql(
                  """
                  INSERT INTO ai_spend (day, user_id, purpose, reserved)
                  VALUES ((now() AT TIME ZONE 'UTC')::date, :account, :purpose, :cost)
                  RETURNING id
                  """)
              .param("account", accountId)
              .param("purpose", purpose)
              .param("cost", cost)
              .query(Long.class)
              .single();
        });
  }

  /** Makes a reservation what its request did cost, by the tokens that were used. */
  void spent(long reservation, long inputTokens, long outputTokens) {
    spent(reservation, cost(inputTokens, outputTokens));
  }

  /** Makes a reservation what its request to the embedding model did cost. */
  void spentOnMeanings(long reservation, long tokens) {
    spent(reservation, costOfMeanings(tokens));
  }

  private void spent(long reservation, BigDecimal cost) {
    transactions.executeWithoutResult(
        recording ->
            jdbc.sql("UPDATE ai_spend SET spent = :spent WHERE id = :id")
                .param("spent", cost)
                .param("id", reservation)
                .update());
  }

  /**
   * Why a request of an ordinary size could not be reserved for now, if it could not: which
   * allowance is spent.
   */
  Optional<Spent> spentFor(Long accountId) {
    return refusal(accountId, cost(ORDINARY_INPUT_BYTES, ORDINARY_OUTPUT_TOKENS));
  }

  /** When the allowances are whole again: at the start of the next UTC calendar day. */
  static Instant renewsAt() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  /** Which allowance a request of this cost would pass, if it would pass one. */
  private Optional<Spent> refusal(Long accountId, BigDecimal cost) {
    var used = jdbc.sql(USED).param("account", accountId).query(Used.class).single();
    if (used.total().add(cost).compareTo(daily) > 0) {
      return Optional.of(Spent.DAILY_ALLOWANCE);
    }
    if (accountId != null && used.own().add(cost).compareTo(dailyPerAccount) > 0) {
      return Optional.of(Spent.ACCOUNT_ALLOWANCE);
    }
    return Optional.empty();
  }

  /** The refusal of a request for an allowance that is spent, which is counted under which. */
  ApiException refused(Spent why) {
    metrics.counter("ai.refused", "reason", why.name()).increment();
    return ApiException.allowanceSpent(why.words, renewsAt());
  }

  /** What the day has come to so far, reserved and spent, in US dollars. */
  BigDecimal usedToday() {
    return jdbc.sql(USED).param("account", null).query(Used.class).single().total();
  }

  /** What so many input and output tokens cost, in US dollars. */
  private BigDecimal cost(long inputTokens, long outputTokens) {
    return inputPrice
        .multiply(BigDecimal.valueOf(inputTokens))
        .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
        .divide(MILLION, 8, RoundingMode.UP);
  }

  /** What so many tokens sent to the embedding model cost, in US dollars. */
  private BigDecimal costOfMeanings(long tokens) {
    return embeddingPrice.multiply(BigDecimal.valueOf(tokens)).divide(MILLION, 8, RoundingMode.UP);
  }

  private record Used(BigDecimal total, BigDecimal own) {}
}
