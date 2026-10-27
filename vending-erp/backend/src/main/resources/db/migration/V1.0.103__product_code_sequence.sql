-- 商品编号统一由服务器分配；锁定同一行可串行化并发建档和导入匹配。
CREATE TABLE yc_vend_code_sequence (
  sequence_key varchar(32) NOT NULL,
  next_value bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (sequence_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO yc_vend_code_sequence(sequence_key, next_value) VALUES ('product_sku', 0);
