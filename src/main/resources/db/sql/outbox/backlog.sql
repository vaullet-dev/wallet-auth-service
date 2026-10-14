-- How far behind delivery is: how many entries wait, and since when the oldest has.
--
-- Read on every metrics scrape, so it must stay cheap: both aggregates are over the pending
-- rows only, which outbox_due_ix already selects.
SELECT count(*) AS pending,
       min(queued_at) AS oldest_queued_at
  FROM identity_schema.outbox
 WHERE delivered_at IS NULL
