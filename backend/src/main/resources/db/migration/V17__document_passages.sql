-- pgvector keeps what a text means as a list of numbers, and finds the texts whose meaning is
-- closest to another's.
CREATE EXTENSION IF NOT EXISTS vector;

-- How often a document was given to the worker to be read, which tells its jobs apart.
ALTER TABLE document ADD COLUMN processings int NOT NULL DEFAULT 0;

-- The passages a document's text was split into, in its order, each with what it means. A
-- passage's meaning has as many numbers as the embedding model gives a text, which for
-- text-embedding-3-small is 1,536. There is no index for finding the closest: with the most
-- documents and the most passages there can be, the table has 6,000 rows, and all are read.
CREATE TABLE document_passage (
    document_id bigint       NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    position    int          NOT NULL,
    text        text         NOT NULL,
    meaning     vector(1536) NOT NULL,
    PRIMARY KEY (document_id, position)
);
