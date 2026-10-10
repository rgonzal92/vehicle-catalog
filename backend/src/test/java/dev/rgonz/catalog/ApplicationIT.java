package dev.rgonz.catalog;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.core.Role;
import jakarta.servlet.http.Cookie;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Runs the application against one shared PostgreSQL container, one shared mock login server, and
 * one shared stand-in each for the queue and for the bucket of exported spreadsheets. The
 * containers start once and are never stopped between classes, so the cached Spring context stays
 * connected to them.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.public-url=" + ApplicationIT.PUBLIC_URL,
      "app.logout-url=https://login.example.test/logout",
      "app.demo-accounts[0].role=author",
      "app.demo-accounts[0].username=demo-author",
      "app.demo-accounts[0].password=demo-password",
      "app.demo-accounts[0].subject=author",
      "app.demo-accounts[0].display-name=Demo Author",
      "app.demo-accounts[1].role=manager",
      "app.demo-accounts[1].username=demo-manager",
      "app.demo-accounts[1].password=demo-password",
      "app.demo-accounts[1].subject=manager",
      "app.demo-accounts[1].display-name=Demo Manager",
      "app.sandbox-accounts[0].subject=sandbox-visitor",
      "app.sandbox-accounts[0].username=visitor",
      "app.sandbox-accounts[0].email=visitor@example.test",
      "app.sandbox-accounts[0].role=author",
      "app.protected-accounts[0]=operator",
      // The demo reset runs only when a test runs it, never because the clock says so.
      "app.demo-reset.cron=-",
      // A stand-in answers for the model, which is not waited for as long as the real one is.
      "app.ai.api-key=a-key-for-the-tests",
      "app.ai.timeout=2s",
      "spring.security.oauth2.client.registration.cognito.client-id=" + ApplicationIT.CLIENT_ID,
      "spring.security.oauth2.client.registration.cognito.client-secret=test-secret"
    })
@AutoConfigureMockMvc
// A trace is passed on with a message as it is where the application really runs. A test is
// otherwise started with that turned off.
@AutoConfigureTracing
public abstract class ApplicationIT {
  /** The address visitors use, which differs from the address the tests call. */
  protected static final String PUBLIC_URL = "https://catalog.example.test";

  protected static final String CLIENT_ID = "catalog-test";

  @ServiceConnection
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18-trixie");

  /**
   * Signing in as "author", "manager", or "admin" yields the claims Amazon Cognito would send for
   * that demo account. Any other username signs in as a person who is in no group.
   */
  static final GenericContainer<?> LOGIN_SERVER =
      new GenericContainer<>("ghcr.io/navikt/mock-oauth2-server:6.0.4")
          .withExposedPorts(8080)
          .withEnv(
              "JSON_CONFIG",
              """
              {
                "interactiveLogin": true,
                "tokenCallbacks": [
                  {
                    "issuerId": "default",
                    "requestMappings": [
                      {
                        "requestParam": "subject",
                        "match": "author",
                        "claims": {
                          "cognito:username": "demo-author",
                          "email": "author@example.test",
                          "name": "Demo Author",
                          "cognito:groups": ["author"]
                        }
                      },
                      {
                        "requestParam": "subject",
                        "match": "manager",
                        "claims": {
                          "cognito:username": "demo-manager",
                          "email": "manager@example.test",
                          "name": "Demo Manager",
                          "cognito:groups": ["manager"]
                        }
                      },
                      {
                        "requestParam": "subject",
                        "match": "admin",
                        "claims": {
                          "cognito:username": "demo-admin",
                          "email": "admin@example.test",
                          "name": "Demo Admin",
                          "cognito:groups": ["admin", "a-group-the-app-does-not-know"]
                        }
                      }
                    ]
                  }
                ]
              }
              """);

  /**
   * A stand-in for the queue that carries the jobs' messages. It hides a message that was received
   * for one second, so a test of a message that is delivered again does not wait long.
   */
  static final GenericContainer<?> QUEUE =
      new GenericContainer<>("softwaremill/elasticmq-native:1.7.1").withExposedPorts(9324);

  /** A stand-in for the bucket that keeps the exported spreadsheets. */
  static final GenericContainer<?> STORAGE =
      new GenericContainer<>("adobe/s3mock:5.2.3").withExposedPorts(9090);

  /** Reads the stand-in's bucket, for the tests that look at what is kept there. */
  private static final S3Client STORED;

  private static final String EXPORTS = "exports";

  private static final String DOCUMENTS = "documents";

  static {
    DATABASE.start();
    LOGIN_SERVER.start();
    QUEUE.start();
    STORAGE.start();
    STORED =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(storageUrl()))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("a-test", "a-test")))
            .forcePathStyle(true)
            .build();
    STORED.createBucket(bucket -> bucket.bucket(EXPORTS));
    STORED.createBucket(bucket -> bucket.bucket(DOCUMENTS));
    try (var sqs =
        SqsClient.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(queueServerUrl()))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .build()) {
      // What was delivered three times and never deleted goes to the queue named after this one.
      var failed = sqs.createQueue(queue -> queue.queueName("jobs-failed")).queueUrl();
      var failedArn =
          sqs.getQueueAttributes(
                  queue -> queue.queueUrl(failed).attributeNames(QueueAttributeName.QUEUE_ARN))
              .attributes()
              .get(QueueAttributeName.QUEUE_ARN);
      sqs.createQueue(
          queue ->
              queue
                  .queueName("jobs")
                  .attributes(
                      Map.of(
                          QueueAttributeName.VISIBILITY_TIMEOUT,
                          "1",
                          QueueAttributeName.REDRIVE_POLICY,
                          "{\"deadLetterTargetArn\": \"%s\", \"maxReceiveCount\": \"3\"}"
                              .formatted(failedArn))));
    }
  }

  /**
   * The application is told of the queue, so it has what sends and receives messages. Nothing does
   * either by itself here: a test has the worker's steps done when it wants them done.
   */
  @DynamicPropertySource
  static void queue(DynamicPropertyRegistry registry) {
    registry.add("app.jobs.queue-url", () -> queueServerUrl() + "/queue/jobs");
  }

  private static String queueServerUrl() {
    return "http://127.0.0.1:" + QUEUE.getMappedPort(9324);
  }

  @DynamicPropertySource
  static void model(DynamicPropertyRegistry registry) {
    registry.add("app.ai.base-url", ModelStandIn::url);
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("app.exports.bucket", () -> EXPORTS);
    registry.add("app.exports.endpoint", ApplicationIT::storageUrl);
    registry.add("app.documents.bucket", () -> DOCUMENTS);
    registry.add("app.documents.endpoint", ApplicationIT::storageUrl);
  }

  private static String storageUrl() {
    return "http://127.0.0.1:" + STORAGE.getMappedPort(9090);
  }

  /** The keys of the files the bucket of exported spreadsheets holds. */
  protected static List<String> exportedFiles() {
    return STORED.listObjectsV2(bucket -> bucket.bucket(EXPORTS)).contents().stream()
        .map(S3Object::key)
        .toList();
  }

  /** The keys of the files the bucket of uploaded documents holds. */
  protected static List<String> documentFiles() {
    return STORED.listObjectsV2(bucket -> bucket.bucket(DOCUMENTS)).contents().stream()
        .map(S3Object::key)
        .toList();
  }

  /** Takes a file out of the bucket of uploaded documents. */
  protected static void removeDocumentFile(String key) {
    STORED.deleteObject(file -> file.bucket(DOCUMENTS).key(key));
  }

  /** Puts a file into the bucket of uploaded documents, as an upload would have. */
  protected static void documentFile(String key) {
    STORED.putObject(file -> file.bucket(DOCUMENTS).key(key), RequestBody.fromString("a note"));
  }

  /** Puts a file into the bucket of exported spreadsheets, as an export would have. */
  protected static void exportedFile(String key) {
    STORED.putObject(
        file -> file.bucket(EXPORTS).key(key), RequestBody.fromString("a spreadsheet"));
  }

  /** Takes every file out of the bucket of exported spreadsheets. */
  protected static void noExportedFiles() {
    exportedFiles().forEach(key -> STORED.deleteObject(file -> file.bucket(EXPORTS).key(key)));
  }

  @DynamicPropertySource
  static void loginProvider(DynamicPropertyRegistry registry) {
    var provider = "spring.security.oauth2.client.provider.cognito.";
    registry.add(provider + "authorization-uri", () -> loginServerUrl() + "/authorize");
    registry.add(provider + "token-uri", () -> loginServerUrl() + "/token");
    registry.add(provider + "jwk-set-uri", () -> loginServerUrl() + "/jwks");
  }

  protected static String loginServerUrl() {
    return "http://127.0.0.1:" + LOGIN_SERVER.getMappedPort(8080) + "/default";
  }

  /** A signed-in person holding the role. Without a role, use {@code oidcLogin()} itself. */
  protected static OidcLoginRequestPostProcessor signedInAs(Role role) {
    return oidcLogin().authorities(new SimpleGrantedAuthority(role.authority()));
  }

  /**
   * A signed-in person holding the role who is on record under the subject, as everyone is once
   * they have signed in.
   */
  protected OidcLoginRequestPostProcessor signedInAs(Role role, String subject) {
    person(subject);
    return signedInAs(role).idToken(token -> token.subject(subject));
  }

  /** The id of the person on record under the subject, who is recorded first if need be. */
  protected long person(String subject) {
    return jdbc.sql(
            """
            INSERT INTO app_user (cognito_sub, username, display_name)
            VALUES (:subject, :subject, :subject)
            ON CONFLICT (cognito_sub) DO UPDATE SET cognito_sub = app_user.cognito_sub
            RETURNING id
            """)
        .param("subject", subject)
        .query(Long.class)
        .single();
  }

  /** Reads one value out of a JSON response. */
  protected static <T> T read(MvcTestResult result, String path) {
    try {
      return JsonPath.read(result.getResponse().getContentAsString(), path);
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  @LocalServerPort protected int port;
  @Autowired protected MockMvcTester mvc;
  @Autowired protected JdbcClient jdbc;

  /**
   * What the application runs once it has started, in order: the seeds and what else fills the
   * database at startup. They are reached this way because each is private to its own package.
   */
  @Autowired private List<ApplicationRunner> startup;

  /**
   * Empties the library and removes every catalog, and with them anything else that refers to
   * either. A test class does this before each test that needs a library of its own making,
   * whatever the tests before it left behind.
   */
  protected void emptyLibraryAndCatalogs() {
    jdbc.sql("TRUNCATE lineage, catalog, vehicle_line, trim, region, feature CASCADE").update();
  }

  /**
   * Leaves the library and the catalogs as a first start leaves them: the seeded ones and nothing
   * else. People and their sessions stay as they are.
   */
  protected void seedLibraryAndCatalogs() throws Exception {
    emptyLibraryAndCatalogs();
    for (var runner : startup) {
      runner.run(new DefaultApplicationArguments());
    }
  }

  /**
   * Sends the CSRF token the way the frontend does: the cookie's value echoed in a header. Spring
   * Security's own {@code csrf()} test helper is not used, because it replaces the application's
   * token store for every later test that shares this context, including the ones over real HTTP.
   */
  protected static RequestPostProcessor csrfToken() {
    return request -> {
      request.setCookies(new Cookie("XSRF-TOKEN", "csrf-test-token"));
      request.addHeader("X-XSRF-TOKEN", "csrf-test-token");
      return request;
    };
  }
}
