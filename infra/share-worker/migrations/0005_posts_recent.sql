-- The feed's ?sort=new (newest first, src/community.js FEED_ORDER.new). Without it that order sorts every live post;
-- the Worker still answers correctly before this is applied, only slower. Not "posts_new": 0002 used that name for its
-- rebuild table, and tables and indexes share one namespace. A later rebuild of posts (0002's pattern) drops this index
-- with the old table, so it must create it again.
CREATE INDEX posts_recent ON posts (created_at) WHERE removed_at IS NULL;
