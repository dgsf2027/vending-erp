package top.aole.vend.modules.basedata.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductCodeMapper;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductCodeService {
    private static final Pattern CODE = Pattern.compile("(?i)^SP(\\d{1,18})$");
    private final ProductCodeMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public long lock() {
        Long value = mapper.lockSequence();
        if (value == null) throw new BizException("商品自动编号尚未初始化，请检查数据库迁移");
        return value;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCode() {
        long current = lock();
        for (String code : mapper.existingCodes()) {
            Matcher matcher = CODE.matcher(code);
            if (matcher.matches()) current = Math.max(current, Long.parseLong(matcher.group(1)));
        }
        if (current == Long.MAX_VALUE) throw new BizException("商品编号序列已用尽");
        mapper.advance(current + 1);
        return "SP" + String.format(Locale.ROOT, "%06d", current + 1);
    }

    /** 销售新品使用独立 YQ 序列；先拿全局商品锁，与手工指定编码保持同一锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextSalesCode() {
        lock();
        Long value = mapper.lockSalesSequence();
        if (value == null) throw new BizException("销售商品编号尚未初始化，请检查数据库迁移");
        long current = value;
        Pattern pattern = Pattern.compile("(?i)^YQ(\\d{1,18})$");
        for (String code : mapper.existingSalesCodes()) {
            Matcher matcher = pattern.matcher(code);
            if (matcher.matches()) current = Math.max(current, Long.parseLong(matcher.group(1)));
        }
        if (current == Long.MAX_VALUE) throw new BizException("销售商品编号序列已用尽");
        mapper.advanceSales(current + 1);
        return "YQ" + String.format(Locale.ROOT, "%06d", current + 1);
    }
}
