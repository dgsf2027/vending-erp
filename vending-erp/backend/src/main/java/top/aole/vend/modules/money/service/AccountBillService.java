package top.aole.vend.modules.money.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.money.domain.entity.Account;
import top.aole.vend.modules.money.domain.entity.AccountBill;
import top.aole.vend.modules.money.mapper.AccountBillMapper;
import top.aole.vend.modules.money.mapper.AccountMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AccountBillService {
    private final AccountBillParser parser;
    private final AccountBillMapper mapper;
    private final AccountMapper accounts;
    private final OpLogService log;

    @Data
    public static class Totals {
        private long count;
        private BigDecimal incomeAmount = BigDecimal.ZERO;
        private BigDecimal feeAmount = BigDecimal.ZERO;
        private BigDecimal settlementAmount = BigDecimal.ZERO;
        private BigDecimal successfulSettlementAmount = BigDecimal.ZERO;
        void add(AccountBill b) {
            count++;
            incomeAmount = incomeAmount.add(b.getIncomeAmount());
            feeAmount = feeAmount.add(b.getFeeAmount());
            settlementAmount = settlementAmount.add(b.getSettlementAmount());
            if ("成功".equals(b.getSettlementStatus())) successfulSettlementAmount = successfulSettlementAmount.add(b.getSettlementAmount());
        }
    }
    @Data
    public static class Preview {
        private List<AccountBill> rows;
        private int newCount;
        private int duplicateCount;
        private List<String> errors = new ArrayList<>();
        private Totals totals = new Totals();
    }
    @Data
    public static class ImportResult {
        private int inserted;
        private int skipped;
    }
    @Data
    public static class ListResult {
        private Page<AccountBill> page;
        private Totals totals;
    }

    public Preview preview(MultipartFile file, Long accountId) {
        validateAccount(accountId);
        Preview p = new Preview();
        p.setRows(parser.parse(file));
        Map<String, AccountBill> seen = new HashMap<>();
        for (AccountBill b : p.getRows()) {
            b.setAccountId(accountId);
            p.getTotals().add(b);
            AccountBill prior = seen.get(b.getIdentityHash());
            if (prior == null) prior = mapper.selectOne(new LambdaQueryWrapper<AccountBill>().eq(AccountBill::getIdentityHash, b.getIdentityHash()));
            if (prior != null) {
                if (!prior.getContentHash().equals(b.getContentHash())) p.getErrors().add("第 " + b.getSourceRow() + " 行：同一天、渠道、商户号和账号已有不同账单，请先核对，禁止覆盖或重复入账");
                else if (!Objects.equals(prior.getAccountId(), accountId)) p.getErrors().add("第 " + b.getSourceRow() + " 行：已有账单的关联账户不同，请保持原账户选择");
                else p.setDuplicateCount(p.getDuplicateCount() + 1);
            } else p.setNewCount(p.getNewCount() + 1);
            seen.put(b.getIdentityHash(), b);
        }
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importFile(MultipartFile file, Long accountId, String operator) {
        Preview p = preview(file, accountId);
        if (!p.getErrors().isEmpty()) throw new BizException(String.join("；", p.getErrors()));
        ImportResult result = new ImportResult();
        for (AccountBill b : p.getRows()) {
            AccountBill prior = mapper.selectOne(new LambdaQueryWrapper<AccountBill>().eq(AccountBill::getIdentityHash, b.getIdentityHash()));
            if (prior != null) {
                if (!prior.getContentHash().equals(b.getContentHash()) || !Objects.equals(prior.getAccountId(), accountId))
                    throw new BizException("账单在预览后发生变化，本次已回滚，请重新预览");
                result.setSkipped(result.getSkipped() + 1); continue;
            }
            b.setCreateUser(0L);
            try { mapper.insert(b); }
            catch (DuplicateKeyException e) { throw new BizException("账单正在被另一个请求导入，本次已回滚，请刷新预览后重试"); }
            result.setInserted(result.getInserted() + 1);
        }
        if (result.getInserted() > 0) log.record(operator, "导入渠道原始账单", "account_bill", null, null,
            "新增" + result.getInserted() + "条，重复跳过" + result.getSkipped() + "条；未自动过账");
        return result;
    }

    public ListResult list(long current, long size, LocalDate from, LocalDate to, Long accountId, String status) {
        if (from != null && to != null && from.isAfter(to)) throw new BizException("开始日期不能晚于结束日期");
        ListResult r = new ListResult();
        r.setPage(mapper.selectPage(new Page<>(Math.max(1, current), Math.max(1, Math.min(100, size))),
            filter(from, to, accountId, status).orderByDesc(AccountBill::getBillDate).orderByDesc(AccountBill::getId)));
        Totals totals = new Totals();
        // 汇总覆盖整个筛选区间，不限于当前页。
        for (AccountBill b : mapper.selectList(filter(from, to, accountId, status))) totals.add(b);
        r.setTotals(totals);
        return r;
    }
    private LambdaQueryWrapper<AccountBill> filter(LocalDate from, LocalDate to, Long accountId, String status) {
        return new LambdaQueryWrapper<AccountBill>().ge(from != null, AccountBill::getBillDate, from)
            .le(to != null, AccountBill::getBillDate, to).eq(accountId != null, AccountBill::getAccountId, accountId)
            .eq(status != null && !status.isEmpty(), AccountBill::getSettlementStatus, status);
    }
    private void validateAccount(Long id) {
        if (id == null) return;
        Account a = accounts.selectById(id);
        if (a == null || Boolean.TRUE.equals(a.getIsVirtual()) || Integer.valueOf(0).equals(a.getStatus()))
            throw new BizException("请选择启用的真实资金账户，或暂不关联账户");
    }
}
