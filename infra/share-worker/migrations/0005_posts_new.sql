-- The feed's ?sort=new (newest first, src/community.js FEED_ORDER.new). Without it that order sorts every live post;
-- the Worker still answers correctly before this is applied, only slower.
CREATE INDEX posts_new ON posts (created_at) WHERE removed_at IS NULL;
