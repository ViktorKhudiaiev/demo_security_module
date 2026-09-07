CREATE TABLE audit_head(id INTEGER PRIMARY KEY CHECK(id=1), next_seq BIGINT NOT NULL);
INSERT INTO audit_head(id,next_seq) VALUES(1,0);
CREATE TABLE audit_events(
 seq BIGINT PRIMARY KEY, event_id UUID NOT NULL UNIQUE, operation_id UUID,
 event_type VARCHAR(64) NOT NULL, detail TEXT NOT NULL,
 created_at_micros BIGINT NOT NULL, leaf_hash VARCHAR(64) NOT NULL
);
CREATE INDEX audit_events_operation ON audit_events(operation_id,seq);
CREATE TABLE audit_checkpoints(tree_size BIGINT PRIMARY KEY, root_hash VARCHAR(64) NOT NULL, checkpoint_json TEXT NOT NULL);
