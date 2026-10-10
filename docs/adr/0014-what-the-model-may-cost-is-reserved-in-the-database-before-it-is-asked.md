# What the model may cost is reserved in the database before it is asked

Every request to the model is paid for, and the demo's accounts are shared by whoever visits. So the app spends no more than US$1 in a UTC calendar day, and no more than US$0.25 of that for one account. Before a request is sent, the most it can cost is written to a table as a reservation, and the request is refused when the day's total or the account's would pass its allowance. When the model has answered, the reservation becomes what OpenAI reports was used.

The most a request can cost is known without counting tokens: its size in bytes is at least as many bytes as it is tokens, and what the model may answer with is capped. Both are priced as OpenAI lists them for the model.

## Considered Options

- **Counting in the application's memory.** The API and the worker are two processes, and a restart would forget the day. The database is what both share, and it keeps the day through a release.
- **Recording what was spent after the answer, and checking that before the next request.** Requests at the same moment would all see the same total and all be sent. What is reserved first is counted by the next request, whenever the answer comes.
- **A conditional update of one row for the day.** It reserves in one statement, and leaves no record of what each request was for and cost. A row for each request does, and one reservation at a time is had by taking a lock of the database's for as long as the reservation takes.
- **A limit set at OpenAI alone.** OpenAI's limits are by the month and by the project. They stay the last line, and say nothing to a visitor about why the app has stopped answering.

## Consequences

- A reservation is far more than a request costs: about four times for the input, since a token is about four bytes, and the whole of the cap for the output. Many requests waiting for their answers at one moment can therefore hold an allowance that they will not use. They give it back as they are answered.
- A request that failed or was not answered in time keeps its reservation, because what OpenAI billed for it is not known.
- When an allowance is spent, the model is not contacted: the request is answered with `429 AI_ALLOWANCE_SPENT` and the time the allowance renews, and `GET /api/ai` says the same before anyone asks.
- The demo reset leaves the table alone, so a reset does not give the day back. The table refers to no table that a reset empties.
- The prices are configuration, and are what the model cost when they were written down. When OpenAI changes them, or the model is changed, they are changed with it.
- One class sends requests to OpenAI, and it reserves first. A test fails when another class names the client.
