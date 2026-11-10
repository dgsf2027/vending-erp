-- 共用库存保留原商品档案和销售原名；只有用户确认导入时才建立关联。
ALTER TABLE yc_vend_product ADD COLUMN stock_owner_id bigint DEFAULT NULL COMMENT '已归入的共用库存商品；原编码保留';
ALTER TABLE yc_vend_product ADD COLUMN shared_stock tinyint(1) NOT NULL DEFAULT 0 COMMENT '已确认共用库存';
CREATE TABLE yc_vend_purchase_workbook (
  batch_key char(64) NOT NULL PRIMARY KEY,
  tenant_id varchar(64) NOT NULL DEFAULT '000000',
  file_name varchar(255) NOT NULL,
  source_json mediumtext NOT NULL,
  file_data mediumblob NOT NULL,
  result_json text NULL,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at datetime NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购原表暂存、来源归档与整表幂等入库';
