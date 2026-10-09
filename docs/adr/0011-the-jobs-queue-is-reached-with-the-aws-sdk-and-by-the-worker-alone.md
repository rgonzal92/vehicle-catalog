# The jobs' queue is reached with the AWS SDK, and by the worker alone

The backend sends and receives the messages of its jobs with the SQS client of the AWS SDK for Java, in three calls: send, receive, and delete. Only the worker makes them. The API writes a job and its unsent message into the database, in the transaction of the change that causes them, and the worker sends what is unsent and runs what arrives.

## Considered Options

- **Spring Cloud AWS, which receives messages for annotated methods.** On 2026-10-09 its 4.x releases listed Spring Boot 4.0 as what they are for, and the backend is on 4.1. What it would save is the loop that waits on the queue, which is a few lines. The backend already uses the SDK directly to reach the login provider.
- **The API sending each message itself, once its transaction has committed.** A stop between the commit and the send loses the message, and with it the job. The API would also need the queue to answer before it could answer its own caller.
- **The API sending what is unsent, in a loop of its own.** Nothing is lost that way either. The API would then need the queue's address and the right to send to it, and would report failures to reach a queue that it otherwise has no use for.

## Consequences

- A message can reach the queue twice, when the worker stops between sending it and marking it as sent, and the queue itself delivers at least once. Every handler has to allow for its job's message arriving again.
- The API starts and answers whether or not there is a queue. With the worker stopped, jobs wait in the database and nothing else is affected.
- A job starts up to a second after its change, which is how often the worker looks for unsent messages.
- The queue's address says what it is. One at Amazon is reached in the region it names, as whoever the process runs as. Any other is a stand-in, which is reached where it is and is told no one's name; locally and in tests that is ElasticMQ.
