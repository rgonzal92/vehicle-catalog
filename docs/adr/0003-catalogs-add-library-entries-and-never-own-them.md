# Catalogs add library entries and never own them

Trims, regions, and features are defined once in a shared library that only admins maintain. A catalog adds existing entries and sets its own availability and catalog rules; it cannot create, rename, or reorder a definition, and there is no mapping of which entries a vehicle line or model year may use. We chose this over catalog-local trims because two working copies that both add "Sport" then refer to the same trim, so copies and merges match by identity and never have to reconcile names.

## Consequences

- A change to a definition, such as a renamed trim or a retired feature, reaches every working copy at once.
- Retiring or deactivating a definition never deletes catalog content. The entry stays where it is already used and raises an Error until it is removed or reactivated.
