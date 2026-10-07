ALTER TABLE yc_vend_machine_stock_snapshot
  ADD COLUMN import_batch_id bigint DEFAULT NULL COMMENT '补货导入批次，用于精确回滚' AFTER snapshot_source,
  ADD KEY idx_snap_import_batch (import_batch_id);
