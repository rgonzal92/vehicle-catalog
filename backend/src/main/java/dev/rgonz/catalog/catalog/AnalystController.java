package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.ai.Model;
import dev.rgonz.catalog.user.AppUsers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Lets anyone with a role ask the analyst what the Approved catalogs offer. */
@RestController
class AnalystController {
  private final Analyst analyst;
  private final AppUsers people;

  AnalystController(Analyst analyst, AppUsers people) {
    this.analyst = analyst;
    this.people = people;
  }

  /**
   * Answers the question a conversation ends with. The conversation is the caller's to keep and to
   * send again with the next question: none of it is kept here.
   */
  @PostMapping("/api/analyst")
  Answered ask(@Valid @RequestBody Asked asked, Authentication caller) {
    var answer =
        analyst.answer(
            people.idOf(caller),
            asked.turns().stream()
                .map(turn -> new Model.Turn(turn.by() == By.PERSON, turn.text()))
                .toList());

    return new Answered(answer.text(), answer.called(), answer.stopped());
  }

  /** Who said a turn of a conversation: the person who asks, or the analyst in answer. */
  enum By {
    PERSON,
    ANALYST
  }

  record Said(@NotNull By by, String text) {}

  /**
   * A conversation so far.
   *
   * @param turns oldest first, ending with the question to answer
   */
  record Asked(@NotNull List<@NotNull @Valid Said> turns) {}

  /**
   * The analyst's answer.
   *
   * @param toolCalls the tools the model had the application use for it, in order, each with what
   *     it was asked as JSON
   * @param stopped whether the answer ended because it had taken as many requests to the model as
   *     one answer may
   */
  record Answered(String answer, List<Model.Called> toolCalls, boolean stopped) {}
}
