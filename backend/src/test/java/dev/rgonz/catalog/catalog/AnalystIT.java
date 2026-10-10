package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.core.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks what the analyst's tools return and what they may reach: Approved versions only, with the
 * names they had at their approval, for anyone with a role. A stand-in answers for the model, and
 * says which tool it asks for. Each test starts from the seeded catalogs.
 */
class AnalystIT extends WorkingCopyTests {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @BeforeEach
  void theSeed() throws Exception {
    seedLibraryAndCatalogs();
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  @AfterEach
  void nothingSpent() {
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  private static Map<String, String> question(String text) {
    return Map.of("by", "PERSON", "text", text);
  }

  private static Map<String, String> answer(String text) {
    return Map.of("by", "ANALYST", "text", text);
  }

  private MvcTestResult ask(RequestPostProcessor who, List<Map<String, String>> turns) {
    return edit(
        who, mvc.post().uri("/api/analyst"), null, JSON.writeValueAsString(Map.of("turns", turns)));
  }

  private MvcTestResult ask(RequestPostProcessor who, String question) {
    return ask(who, List.of(question(question)));
  }

  /**
   * What a tool returned to the model, when the model asks for it with this and then answers in
   * words.
   */
  private JsonNode returned(RequestPostProcessor who, String tool, Map<String, Object> asked) {
    ModelStandIn.forgets();
    ModelStandIn.asksFor(tool, JSON.writeValueAsString(asked));
    ModelStandIn.says("An answer.");

    assertThat(ask(who, "A question?")).hasStatusOk();

    for (var message : ModelStandIn.asked().getLast().path("messages")) {
      if (message.path("role").asString().equals("tool")) {
        return JSON.readTree(message.path("content").asString());
      }
    }
    throw new AssertionError("The model was sent no answer of a tool.");
  }

  private static List<String> texts(Iterable<JsonNode> list) {
    var texts = new ArrayList<String>();
    list.forEach(one -> texts.add(one.asString()));
    return texts;
  }

  @Test
  void aQuestionAboutAnApprovedCatalogIsAnsweredFromWhatAToolReturnedAndListsThatToolCall() {
    person("zoe-who-asks");
    long catalog = approved("COMPACT_SUV", 2027, 1);
    ModelStandIn.asksFor("get_approved_catalog", "{\"catalogId\": %d}".formatted(catalog));
    ModelStandIn.says("In Asia it has Base and Touring.");

    var answer =
        ask(signedInAs(Role.AUTHOR, "zoe-who-asks"), "Which trims does the Compact SUV have?");

    assertThat(answer).hasStatusOk();
    assertThat(answer)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "answer": "In Asia it has Base and Touring.",
              "toolCalls": [{"tool": "get_approved_catalog", "arguments": "{\\"catalogId\\": %d}"}],
              "stopped": false
            }
            """
                .formatted(catalog));
    var requests = ModelStandIn.asked();
    assertThat(requests).hasSize(2);
    assertThat(texts(requests.getFirst().path("tools").findValues("name")))
        .containsExactlyInAnyOrder(
            "list_lineages", "get_approved_catalog", "search_features", "feature_availability");
    // The question is sent as data, in a message of the person's own.
    assertThat(requests.getFirst().path("messages").get(1).path("content").asString())
        .isEqualTo("{\"question\":\"Which trims does the Compact SUV have?\"}");
    var returned =
        JSON.readTree(requests.getLast().path("messages").get(3).path("content").asString());
    assertThat(returned.path("vehicleLine").asString()).isEqualTo("Compact SUV");
    assertThat(returned.path("modelYear").asInt()).isEqualTo(2027);
    assertThat(texts(returned.path("trims")))
        .containsExactly("Base", "Sport", "Touring", "Off-Road");
    assertThat(texts(returned.path("offerings").path("ASIA"))).containsExactly("Base", "Touring");
    assertThat(returned.path("regions").toString())
        .isEqualTo(
            "[{\"code\":\"NA\",\"name\":\"North America\"},{\"code\":\"EU\",\"name\":\"Europe\"},"
                + "{\"code\":\"ASIA\",\"name\":\"Asia\"}]");
    assertThat(returned.path("features").path("INTERIOR").toString())
        .contains("{\"code\":\"SEAT_LEATHER\",\"name\":\"Leather Seats\"}");
    // Who asks is no part of what the model is sent.
    assertThat(requests.toString()).doesNotContain("zoe-who-asks").doesNotContain("accountId");
  }

  @Test
  void aToolAskedForAWorkingCopyOrASubmittedCatalogAnswersThatThereIsNoSuchApprovedCatalog() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    var none = "{\"error\":\"There is no such Approved catalog.\"}";

    assertThat(returned(ana(), "get_approved_catalog", Map.of("catalogId", copy)).toString())
        .as("its owner's Draft")
        .isEqualTo(none);

    assertThat(
            edit(
                ana(),
                mvc.post().uri("/api/catalogs/{id}/submit", copy),
                "\"%d\"".formatted(revision(copy)),
                "{}"))
        .hasStatusOk();
    for (var who : List.of(ana(), signedInAs(Role.MANAGER, "mia"), signedInAs(Role.ADMIN, "ada"))) {
      assertThat(returned(who, "get_approved_catalog", Map.of("catalogId", copy)).toString())
          .as("a Submitted catalog")
          .isEqualTo(none);
    }
    assertThat(returned(ana(), "get_approved_catalog", Map.of("catalogId", "the first")).toString())
        .isEqualTo(none);
  }

  @Test
  void theToolsReturnTheNamesAnApprovedVersionHadAtItsApprovalAfterTheLibraryRenamedThem() {
    jdbc.sql("UPDATE feature SET name = 'Hide Seats' WHERE code = 'SEAT_LEATHER'").update();
    jdbc.sql("UPDATE trim SET name = 'Grand Touring' WHERE name = 'Touring'").update();
    jdbc.sql("UPDATE region SET name = 'Europa' WHERE code = 'EU'").update();
    long catalog = approved("COMPACT_SUV", 2027, 1);

    var held = returned(ana(), "get_approved_catalog", Map.of("catalogId", catalog)).toString();
    var available =
        returned(ana(), "feature_availability", Map.of("feature", "SEAT_LEATHER")).toString();

    for (var said : List.of(held, available)) {
      assertThat(said)
          .contains("Leather Seats", "\"Touring\"", "Europe")
          .doesNotContain("Hide Seats", "Grand Touring", "Europa");
    }
  }

  @Test
  void aFeaturesAvailabilityIsSaidTrimByTrimInTheRegionsOfTheCatalogsThatHaveIt() {
    var everywhere = returned(ana(), "feature_availability", Map.of("feature", "ENGINE_20T_I4"));
    var narrowed =
        returned(
            ana(),
            "feature_availability",
            Map.of(
                "feature", "engine_20t_i4",
                "vehicleLine", "compact suv",
                "modelYear", 2027,
                "region", "north america"));

    // The current Approved version of each lineage that has the feature, and no earlier one.
    assertThat(everywhere.path("catalogs").findValuesAsString("catalogId"))
        .contains(
            String.valueOf(approved("COMPACT_SUV", 2026, 2)),
            String.valueOf(approved("COMPACT_SUV", 2027, 1)))
        .doesNotContain(String.valueOf(approved("COMPACT_SUV", 2026, 1)));
    assertThat(narrowed.path("catalogs").toString())
        .isEqualTo(
            """
            [{"catalogId":%d,"vehicleLine":"Compact SUV","modelYear":2027,"version":1,\
            "name":"%s","regions":[{"region":"NA","name":"North America",\
            "standard":["Touring","Off-Road"],"available":["Sport"],"notOffered":["Base"]}]}]\
            """
                .formatted(
                    approved("COMPACT_SUV", 2027, 1),
                    jdbc.sql("SELECT name FROM feature WHERE code = 'ENGINE_20T_I4'")
                        .query(String.class)
                        .single()));
    assertThat(
            returned(ana(), "feature_availability", Map.of("feature", "NO_SUCH_FEATURE"))
                .path("catalogs"))
        .isEmpty();
  }

  @Test
  void theLineagesAreListedWithTheirCurrentApprovedVersionAndWithoutWhoApprovedThem() {
    var lineages = returned(ana(), "list_lineages", Map.of()).path("lineages");

    assertThat(lineages).hasSize(4);
    assertThat(lineages.toString())
        .contains(
            "{\"catalogId\":%d,\"vehicleLine\":\"Compact SUV\",\"modelYear\":2026,\"version\":2}"
                .formatted(approved("COMPACT_SUV", 2026, 2)))
        .doesNotContain("approvedBy");
  }

  @Test
  void featuresAreFoundByPartOfACodeOrOfAName() {
    assertThat(
            returned(ana(), "search_features", Map.of("query", "leather seat"))
                .path("features")
                .toString())
        .contains(
            "{\"code\":\"SEAT_LEATHER\",\"name\":\"Leather Seats\",\"kind\":\"FEATURE\","
                + "\"category\":\"INTERIOR\",\"status\":\"ACTIVE\"}");

    var manyFound = returned(ana(), "search_features", Map.of("query", "SEAT_"));
    assertThat(manyFound.path("features").findValuesAsString("code"))
        .isNotEmpty()
        .allMatch(code -> code.contains("SEAT_"));

    var all = returned(ana(), "search_features", Map.of("query", "e"));
    assertThat(all.path("features")).hasSize(25);
    assertThat(all.path("more").asBoolean()).isTrue();

    // What would be a pattern of the database's is looked for as it was typed.
    assertThat(returned(ana(), "search_features", Map.of("query", "%")).path("features")).isEmpty();
  }

  @Test
  void eachRoleAsksAndSomeoneNotSignedInDoesNot() {
    for (var who : List.of(ana(), signedInAs(Role.MANAGER, "mia"), signedInAs(Role.ADMIN, "ada"))) {
      ModelStandIn.says("An answer.");

      assertThat(ask(who, "A question?")).hasStatusOk();
    }

    assertThat(
            mvc.post()
                .uri("/api/analyst")
                .with(csrfToken())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"turns\": [{\"by\": \"PERSON\", \"text\": \"A question?\"}]}")
                .exchange())
        .hasStatus(401);
  }

  @Test
  void onlyTheLastTenTurnsOfAConversationAreSentAndItEndsWithAQuestionThatIsNotTooLong() {
    var turns = new ArrayList<Map<String, String>>();
    for (int turn = 1; turn <= 6; turn++) {
      turns.add(question("Question " + turn + "?"));
      turns.add(answer("Answer " + turn + "."));
    }
    turns.add(question("The last question?"));
    ModelStandIn.says("An answer.");

    assertThat(ask(ana(), turns)).hasStatusOk();

    var sent = ModelStandIn.asked().getLast().path("messages");
    assertThat(sent).hasSize(11);
    assertThat(sent.get(1).path("role").asString()).isEqualTo("assistant");
    assertThat(sent.get(1).path("content").asString()).isEqualTo("Answer 2.");
    assertThat(sent.get(10).path("content").asString())
        .isEqualTo("{\"question\":\"The last question?\"}");

    ModelStandIn.forgets();
    assertThat(ask(ana(), "x".repeat(501))).hasStatus(422);
    assertThat(ask(ana(), " ")).hasStatus(422);
    assertThat(ask(ana(), List.of(question("A question?"), answer("An answer.")))).hasStatus(422);
    assertThat(ask(ana(), List.of())).hasStatus(422);
    assertThat(ModelStandIn.asked()).isEmpty();
  }
}
