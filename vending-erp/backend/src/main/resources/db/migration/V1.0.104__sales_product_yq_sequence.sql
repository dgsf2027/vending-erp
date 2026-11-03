-- 预留已交付的商品编码清单 YQ000001–YQ000025；销售新品从 YQ000026 续号。
-- 现有商品编码不变，分配时还会跳过已使用（含逻辑删除）的更大 YQ 编码。
INSERT INTO yc_vend_code_sequence(sequence_key, next_value) VALUES ('sales_product_yq', 25);
