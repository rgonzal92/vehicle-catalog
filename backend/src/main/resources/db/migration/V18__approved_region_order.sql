-- An Approved version keeps the place each of its regions had in the library's order when it was
-- approved, as it keeps each trim's. The versions approved before this are given the order the
-- library has now, which is the order they have been shown in.
ALTER TABLE catalog_region ADD COLUMN approved_sort_order int;

UPDATE catalog_region c
SET approved_sort_order = r.sort_order
FROM region r
WHERE r.code = c.region_code AND c.approved_name IS NOT NULL;
