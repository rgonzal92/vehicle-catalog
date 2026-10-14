-- A migration later than any this build has, as a release that was taken back leaves behind: the
-- image before runs on a database its successor migrated. Only LaterMigrationIT reads it.
CREATE TABLE later_release (
    id bigint PRIMARY KEY
);
