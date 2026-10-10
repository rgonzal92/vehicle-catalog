package dev.rgonz.catalog.ai;

import tools.jackson.databind.JsonNode;

/**
 * Something the model may ask the application to look up while it answers a person. The model says
 * which tool and with what; the application does the looking up, as the person who is signed in,
 * whom the model is never told of and cannot name.
 */
public interface Tool {
  /** The name the model calls it by. */
  String name();

  /** What it looks up, said for the model to choose by. */
  String description();

  /** The JSON schema of what it is asked with. */
  String arguments();

  /**
   * Looks up what was asked.
   *
   * @param asked what the model asked with, which is whatever the model made of the schema
   * @param accountId who is signed in
   * @return what is sent to the model, as JSON
   */
  Object answer(JsonNode asked, long accountId);
}
