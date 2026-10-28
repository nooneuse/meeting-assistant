# Topic collections / 议题集

Status: implemented in both source trees; computer and native Android checks passed. Physical Bluetooth collection-navigation keys remain unverified (2026-10-28).

## Ownership and terminology

The former meeting is now a **topic / 议题**. A **topic collection / 议题集** is a
single-level folder. Topics retain independent speech, OCR, preferences, context,
answers and reading positions. A collection never contributes sibling text to a
model request. The application remains Meeting Assistant / 会议助手.

All existing topics migrate into the default collection. IDs, content, keys,
selection and reading positions survive; no provider call is made on migration.
The default collection has a stable internal ID, may be renamed or emptied, and
cannot be deleted. Its default label follows the UI language until renamed.

## Selection and creation

- The arrow only expands/collapses a folder, without navigation or timestamp edits.
- The folder name selects a collection-level preparation page. A topic selects
  itself and its parent. Speech navigation still confirms stopping and drains the
  tail before selection changes; background analysis remains owned by its topic.
- Only the selected collection and selected visible topic show their own ellipsis.
- There is exactly one New topic button, at the top of the drawer. Its keyboard
  action uses the identical path. Both use the selected collection, or the default
  when there is no selection. Expansion alone never changes the target.
- The new-collection icon opens a name dialog; successful creation selects and
  expands that collection. No capture or model request starts.
- Folder menus rename or delete; nonempty deletion offers move-all-then-delete.
  The default folder offers rename/clear. Topic menus rename, move or delete.
- Deleting the last topic leaves a hidden preparation draft in the same collection,
  not a visible replacement topic. Deleting the selected collection selects the
  default collection. Empty collections are valid and remain visible.

## Date grouping and order

Collections store createdAt and updatedAt. Topic creation, content updates,
renames, moves and deletions update their parent; moving updates both parents.
Selection, reading, scrolling and expansion do not. The default collection also
stores timestamps but is always last, outside date sections and above Settings.

Other collections sort by updatedAt descending, with stable ID tie-breaking.
Sections use the device timezone/calendar: Today, Yesterday, Previous 7 days
(2–6 calendar days ago), then month/year. DST days are calendar days, not fixed
24-hour subtraction. Date sections are labels, not additional folders.

Opening the drawer snapshots collection and topic order; live generation refreshes
content/selection without reordering existing rows. Newly created/moved/deleted
items reconcile with that snapshot. Reopening recalculates dates and ordering.
Expansion is persisted; selecting a topic expands its parent. Default-only legacy
installations expand it initially so existing records remain discoverable.

## Keyboard

Existing meeting.new / meeting.previous / meeting.next action IDs remain stable,
with labels changed to topic terminology. Previous/next topic never crosses a
collection boundary. From preparation, next enters the first visible topic and
previous the last. Neither action wraps. New collection.previous / collection.next
actions default to unbound, traverse date order with default last, and restore the
destination's remembered topic or its empty preparation page. Collapsed folders
are included. Only scrolling actions repeat on key hold, as before.

## Persistence and migration

Topic payload version 3 adds collectionId; versions 1/2 remain readable. A separate
encrypted catalog holds collection names/times, expanded IDs, selected collection
and remembered topic IDs. Names and membership are not plaintext database indexes.
Use the existing records Keystore key and a distinct authenticated catalog ID.

SQLite schema v2 adds the catalog table without dropping any v1 data. After full
validation, catalog creation, legacy membership assignment and selection commit in
one transaction. Invalid/decryption-failed data is retained and exposed as a storage
error, never overwritten with an empty catalog. Moving and bulk deletion similarly
commit records, catalog and selection together. All routine writes use the existing
serial storage worker; late callbacks cannot recreate deleted topics or folders.
Failed structural writes preserve in-memory membership and expose a retryable error.

## Validation

Use mock providers only. Cover default fallback, one-time migration/restart, empty
folders, protected default, selection-vs-expansion, key binding compatibility and
boundaries, encrypted catalog, SQLite rollback, moves retaining content/active owner,
bulk deletion with late callbacks, remembered topics, timestamps, calendar/DST
sections, stable open-drawer order, EN/zh labels and adaptive layouts. Both source
trees receive identical production behavior and tests; private development tools
and public generic URL examples retain their existing separation.
