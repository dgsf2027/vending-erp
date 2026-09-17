package top.aole.vend.modules.money.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.aole.vend.modules.basedata.domain.entity.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("yc_vend_account_bill")
public class AccountBill extends BaseEntity {
    private LocalDate billDate;
    private BigDecimal incomeAmount;
    private BigDecimal feeAmount;
    private String sourceAccount;
    private String ownerName;
    private String sourceRole;
    private String channel;
    private String merchantNo;
    private LocalDate arrivalDate;
    private BigDecimal settlementAmount;
    private String settlementStatus;
    private String settlementInfo;
    private Long accountId;
    private String sourceFile;
    private Integer sourceRow;
    private String fileHash;
    private String identityHash;
    private String contentHash;
}
