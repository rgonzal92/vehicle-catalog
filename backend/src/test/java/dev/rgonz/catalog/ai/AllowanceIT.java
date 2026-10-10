package dev.rgonz.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks what asking the model may cost in a day: a dollar for the whole application and a quarter
 * of one for an account, at ten cents for a million input tokens and fifty for a million output
 * tokens. A reservation of 500,000 bytes and no output is five cents.
 */
class AllowanceIT extends ApplicationIT {
  private static final long FIVE_CENTS = 500_000;

  @Autowired Allowance allowance;

  @BeforeEach
  @AfterEach
  void nothingSpent() {
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  private BigDecimal reservedToday() {
    return jdbc.sql("SELECT coalesce(sum(reserved), 0) FROM ai_spend")
        .query(BigDecimal.class)
        .single();
  }

  @Test
  void manyReservationsAtOnceLeaveAtMostTheDaysAllowanceReserved() throws Exception {
    // Ten accounts ask four times each, at once: two dollars in all, of which none passes its own.
    var asking = new ArrayList<Callable<Boolean>>();
    for (int account = 0; account < 10; account++) {
      long id = person("an-account-" + account);
      for (int time = 0; time < 4; time++) {
        asking.add(
            () -> {
              try {
                allowance.reserve(id, "A_TEST", FIVE_CENTS, 0);
                return true;
              } catch (ApiException refused) {
                assertThat(refused.getStatusCode().value()).isEqualTo(429);
                return false;
              }
            });
      }
    }

    long served = 0;
    try (var threads = Executors.newFixedThreadPool(16)) {
      for (Future<Boolean> answer : threads.invokeAll(asking)) {
        served += answer.get() ? 1 : 0;
      }
    }

    assertThat(served).as("how many had five cents of the dollar").isEqualTo(20);
    assertThat(reservedToday()).isEqualByComparingTo("1.00");
  }

  @Test
  void anAccountThatHasSpentItsOwnAllowanceIsRefusedWhileOthersAreServed() {
    long ana = person("ana");
    for (int time = 0; time < 5; time++) {
      allowance.reserve(ana, "A_TEST", FIVE_CENTS, 0);
    }

    assertThatThrownBy(() -> allowance.reserve(ana, "A_TEST", FIVE_CENTS, 0))
        .isInstanceOf(ApiException.class)
        .satisfies(
            refused ->
                assertThat(((ApiException) refused).getBody().getDetail())
                    .startsWith("This account's allowance"));
    assertThat(allowance.spentFor(ana)).as("and she is told so before she asks").isPresent();
    assertThat(allowance.spentFor(person("ben"))).isEmpty();
    allowance.reserve(person("ben"), "A_TEST", FIVE_CENTS, 0);
    allowance.reserve(null, "A_TEST", FIVE_CENTS, 0);
    assertThat(reservedToday()).isEqualByComparingTo("0.35");
  }

  @Test
  void whatADayComesToIsCountedByTheUtcCalendarDay() {
    jdbc.sql(
            """
            INSERT INTO ai_spend (day, purpose, reserved)
            VALUES ((now() AT TIME ZONE 'UTC')::date - 1, 'A_TEST', 1.00)
            """)
        .update();

    allowance.reserve(null, "A_TEST", FIVE_CENTS, 0);

    jdbc.sql(
            """
            INSERT INTO ai_spend (day, purpose, reserved)
            VALUES ((now() AT TIME ZONE 'UTC')::date, 'A_TEST', 0.95)
            """)
        .update();
    assertThatThrownBy(() -> allowance.reserve(null, "A_TEST", FIVE_CENTS, 0))
        .isInstanceOf(ApiException.class)
        .satisfies(
            refused -> {
              var body = ((ApiException) refused).getBody();
              assertThat(body.getDetail()).startsWith("Today's allowance for the model is spent.");
              assertThat(body.getProperties())
                  .containsEntry("code", "AI_ALLOWANCE_SPENT")
                  .containsEntry("renewsAt", Allowance.renewsAt());
            });
  }

  @Test
  void aReservationIsTheMostARequestCanCostAndBecomesWhatItDidCost() {
    long reservation = allowance.reserve(null, "A_TEST", 10_000, 300);

    assertThat(reservedToday())
        .as("10,000 bytes in and 300 tokens out")
        .isEqualByComparingTo("0.00115");

    allowance.spent(reservation, 1234, 56);

    assertThat(jdbc.sql("SELECT spent FROM ai_spend").query(BigDecimal.class).single())
        .as("1,234 tokens in and 56 out")
        .isEqualByComparingTo("0.0001514");
    // What it did cost is what counts from then on.
    for (int time = 0; time < 19; time++) {
      allowance.reserve(null, "A_TEST", FIVE_CENTS, 0);
    }
    assertThatThrownBy(() -> allowance.reserve(null, "A_TEST", FIVE_CENTS, 0))
        .isInstanceOf(ApiException.class);
  }
}
