package dev.rgonz.catalog.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that a person reads the notifications that were left for them, and for no one else, and
 * marks them as read. Each test starts with no notification at all.
 */
class NotificationsIT extends ApplicationIT {
  @Autowired Notifications notifications;

  @BeforeEach
  void noNotifications() {
    jdbc.sql("DELETE FROM notification").update();
  }

  private RequestPostProcessor ana() {
    return signedInAs(Role.AUTHOR, "ana");
  }

  private RequestPostProcessor ben() {
    return signedInAs(Role.AUTHOR, "ben");
  }

  /** Tells the person that a catalog of that name was approved, and answers with the id. */
  private long tell(String subject, String catalog) {
    notifications.tell(
        person(subject),
        "CATALOG_APPROVED",
        "approved:" + catalog,
        Map.of(
            "catalogId", 41,
            "catalog", catalog,
            "lineageId", 3,
            "vehicleLine", "Compact SUV",
            "modelYear", 2026,
            "versionNumber", 3,
            "reviewer", "Mia Manager"));

    return jdbc.sql("SELECT max(id) FROM notification").query(Long.class).single();
  }

  @Test
  void aPersonReadsTheirOwnNotificationsNewestFirstWithHowManyAreUnread() {
    tell("ana", "Winter update");
    var later = tell("ana", "Spring update");
    tell("ben", "Bens own");

    var inbox = inbox(ana());

    assertThat(inbox).hasStatusOk().bodyJson().extractingPath("$.unread").isEqualTo(2);
    assertThat(ApplicationIT.<List<String>>read(inbox, "$.items[*].payload.catalog"))
        .containsExactly("Spring update", "Winter update");
    assertThat(ApplicationIT.<Map<String, Object>>read(inbox, "$.items[0]"))
        .containsEntry("id", (int) later)
        .containsEntry("kind", "CATALOG_APPROVED")
        .containsEntry("read", false)
        .containsKey("createdAt");
    assertThat(ApplicationIT.<Map<String, Object>>read(inbox, "$.items[0].payload"))
        .containsEntry("lineageId", 3)
        .containsEntry("vehicleLine", "Compact SUV")
        .containsEntry("modelYear", 2026)
        .containsEntry("versionNumber", 3)
        .containsEntry("reviewer", "Mia Manager");
    assertThat(ApplicationIT.<List<String>>read(inbox(ben()), "$.items[*].payload.catalog"))
        .containsExactly("Bens own");
  }

  @Test
  void atMostTwentyAreListedAndEveryUnreadOneIsCounted() {
    for (int number = 1; number <= 22; number++) {
      tell("ana", "Catalog " + number);
    }

    var inbox = inbox(ana());

    assertThat(inbox).bodyJson().extractingPath("$.unread").isEqualTo(22);
    assertThat(ApplicationIT.<List<String>>read(inbox, "$.items[*].payload.catalog"))
        .hasSize(20)
        .startsWith("Catalog 22")
        .endsWith("Catalog 3");
  }

  @Test
  void markingAsReadReachesTheCallersOwnNotificationsUpToTheOneNamed() {
    tell("ana", "First");
    var second = tell("ana", "Second");
    var bens = tell("ben", "Bens own");
    tell("ana", "Third");

    var marked = markRead(ana(), "{\"upTo\": %d}".formatted(second));

    assertThat(marked).hasStatusOk().bodyJson().extractingPath("$.unread").isEqualTo(1);
    assertThat(ApplicationIT.<List<Boolean>>read(inbox(ana()), "$.items[*].read"))
        .as("the third, the second, the first")
        .containsExactly(false, true, true);

    // Ben's is older than Ana's third. Her marking everything she has leaves it as it is.
    assertThat(markRead(ana(), "{\"upTo\": %d}".formatted(bens + 100)))
        .bodyJson()
        .extractingPath("$.unread")
        .isEqualTo(0);
    assertThat(inbox(ben())).bodyJson().extractingPath("$.unread").isEqualTo(1);
  }

  @Test
  void aPersonWithoutNotificationsHasAnEmptyList() {
    var inbox = inbox(ana());

    assertThat(inbox).hasStatusOk().bodyJson().extractingPath("$.unread").isEqualTo(0);
    assertThat(ApplicationIT.<List<Object>>read(inbox, "$.items")).isEmpty();
  }

  @Test
  void notificationsAreForSomeoneWhoIsSignedInAndMarkingNamesWhereToStop() {
    assertThat(mvc.get().uri("/api/notifications")).hasStatus(401);
    assertThat(
            mvc.post()
                .uri("/api/notifications/read")
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"upTo\": 1}"))
        .hasStatus(401);
    assertThat(markRead(ana(), "{}"))
        .hasStatus(400)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("BAD_REQUEST");
  }

  private MvcTestResult inbox(RequestPostProcessor who) {
    return mvc.get().uri("/api/notifications").with(who).exchange();
  }

  private MvcTestResult markRead(RequestPostProcessor who, String body) {
    return mvc.post()
        .uri("/api/notifications/read")
        .with(who)
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }
}
