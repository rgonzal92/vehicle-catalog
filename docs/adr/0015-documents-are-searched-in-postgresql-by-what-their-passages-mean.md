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
- A PDF is given 30 seconds to be read, and its document fails when they are over. PDFBox does not stop when it is told to, so a reading whose time is over is left to end by itself, on a thread of its own that nothing waits for, and the worker goes on to its next job. One PDF is read at a time, and a reading that was left behind is that one until it ends: the next PDF waits for it within its own 30 seconds, and fails if they are over first. So the worker never has more than one reading going, and has none once it is started again.
- The pages of a PDF may unpack to 32 MB, which is sixteen times the largest file, and its document fails once they have unpacked to more. A file of 2 MB can hold 2 GB of packed drawing, which PDFBox reads for most of a minute, or unpacks into memory first when it is in a form that a page draws. PDFBox has no limit for either. So the contents of each page and of each form are unpacked before PDFBox is given them, with nothing kept, and counted, and one that is drawn again counts again. A reading that is refused so has ended: no thread is left behind, and the next PDF is read at once. The 30 seconds stay for what the count does not see.
- What a PDF holds beside the contents of its pages is not counted: a font's files and tables, and the streams that hold the file's own objects. PDFBox unpacks those into memory, and one that unpacks to more than the worker's heap has room for ends the reading for want of memory. The document then fails as one that could not be read as a PDF, and the worker goes on.
- A file that cannot be read fails once and is not tried three times. Only a failure that another try could end differently is the job's.
