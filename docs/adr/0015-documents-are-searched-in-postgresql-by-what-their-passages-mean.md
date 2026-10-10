# Documents are searched in PostgreSQL, by what their passages mean

A document an admin uploads is split into passages, and OpenAI's embedding model says what each passage means, as a list of numbers. The passages and their meanings are kept in PostgreSQL, in a column of pgvector's, and a question finds the passages whose meaning is closest to its own by a query that reads all of them.

The embedding model is `text-embedding-3-small`, which gives 1,536 numbers for a text. The column is that size. The model is a setting, and what it gives is not: another model gives other numbers, so changing it means a migration and processing every document again.

A passage is about 400 tokens, measured as 1,600 characters, and starts with up to 200 characters of how the one before it ended. It ends where a paragraph does when it can, or else where a sentence does.

## Considered Options

- **A vector database of its own, or OpenSearch.** One more thing to run, pay for, back up, and keep in step with the documents' rows. The passages belong to rows the database already has, are deleted with them by a foreign key, and are emptied with them by the demo reset.
- **An index for finding the closest passages.** pgvector has two, and both find the closest only nearly: a search may miss a passage. There are at most 20 documents of at most 300 passages, which is 6,000 rows, and reading all of them is exact and takes milliseconds. An index is for a table too large to read through.
- **Spring AI's store for pgvector.** It keeps passages in a table of its own shape, with what tells them apart in a JSON column. Here a passage is a row with a foreign key to its document, and which documents a search may read is a join on the document's vehicle line and model year, which the database then holds to, and not a filter over JSON.
- **The larger embedding model.** It gives 3,072 numbers and costs six and a half times as much. With a few dozen pages to search, the cases with known answers are what would show that the small one finds the wrong passage, and they are where to look before paying for more.
- **Counting a passage's size in tokens.** That takes a tokenizer and the model's own vocabulary. The model takes texts twenty times a passage's size, so nothing depends on the count being exact, and four characters to a token is what English comes to.

## Consequences

- The database's image is PostgreSQL with pgvector, in every place the database runs. A database in which the extension was created does not start on an image without it.
- What a search may read is decided in SQL, by the document's vehicle line and model year, before any passage reaches the model.
- A document is read as at most 300 passages. One with more text fails, and says so. A PDF can hold many times that much text in 2 MB, so reading one stops as soon as it has more.
- Reading a file and asking the model take seconds, and are done by the worker, in a job. A document that is being read says so, which is written apart from the job's own work.
- A file that cannot be read fails once and is not tried three times. Only a failure that another try could end differently is the job's.
