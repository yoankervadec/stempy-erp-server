-- User preferences (modules/users, docs/ARCHITECTURE.md §27): the desktop client's layout
-- choices (list columns, sort, page size, saved filter views, menu), stored per user so they
-- follow them from one machine to another. Not per company: a key that depends on a company
-- names it itself ('views.product@2').
--
-- The server doesn't read the values: each one is a JSON value under a key the client
-- chooses. It is kept as the text the server wrote (compact JSON), not in a JSON column,
-- which would reorder object keys: the client gets back exactly what it stored.
--
-- Keys are case-sensitive (utf8mb4_bin): 'list.Product' and 'list.product' are two keys.
-- Not audited: layout choices, the last write wins.

CREATE TABLE `user_preferences` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `pref_key` VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  -- At most 64 KB (the server checks it), hence MEDIUMTEXT (TEXT stops one byte short).
  `value_json` MEDIUMTEXT NOT NULL,
  `updated_at` DATETIME NOT NULL,

  PRIMARY KEY (`user_id`, `pref_key`),
  CONSTRAINT `chk_user_preferences_value_json` CHECK (JSON_VALID(`value_json`)),
  FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
);
