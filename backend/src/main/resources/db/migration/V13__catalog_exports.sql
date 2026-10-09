-- A catalog as a spreadsheet, which a person who may open the catalog asked for and the worker
-- builds. The file is kept outside the database, under the export's id. How the export stands is
-- how its job stands.
CREATE TABLE catalog_export (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    catalog_id   bigint      NOT NULL REFERENCES catalog (id),
    requested_by bigint      NOT NULL REFERENCES app_user (id),
    file_name    text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX catalog_export_of_catalog ON catalog_export (catalog_id);
