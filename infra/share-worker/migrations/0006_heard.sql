-- "Heard" marks (contract 2026-11-03, .stash/release/plan-2026-11-02b/heard-contract.md): the app or web player played
-- a song from this post for 30 s with this key. PUT /v1/community/posts/{id}/heard writes one; with HEARD_REQUIRED on,
-- a +1 or -1 vote needs one. Only the key's hash and the post: no IP, no network, no song, no duration.
-- Deleted with its post by the daily cleanup (src/community.js cleanup), so a mark lives at most as long as the post.
-- The primary key is the feed's lookup (post_id, voter), like the votes join.
CREATE TABLE heard (
  post_id  TEXT NOT NULL,
  voter    TEXT NOT NULL,                      -- sha256(key), hex: the same id as votes.voter
  at       INTEGER NOT NULL,                   -- ms, the first time it was sent
  PRIMARY KEY (post_id, voter)
);
