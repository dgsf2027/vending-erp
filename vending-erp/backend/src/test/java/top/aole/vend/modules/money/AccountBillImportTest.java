package top.aole.vend.modules.money;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import top.aole.vend.BaseIntegrationTest;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.money.domain.entity.AccountBill;
import top.aole.vend.modules.money.service.AccountBillParser;
import top.aole.vend.modules.money.service.AccountBillService;
import top.aole.vend.modules.money.mapper.CashFlowMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test-money")
class AccountBillImportTest extends BaseIntegrationTest {
    @Autowired private AccountBillService service;
    @Autowired private AccountBillParser parser;
    @Autowired private CashFlowMapper cashFlows;
    private static final String[] HEADERS = {"账单时间","收益金额","支付手续费","账号","姓名","角色","打款渠道","渠道商户号","到账时间","结算金额","结算状态","结算信息"};
    private String[] row(String day, String amount) {
        return new String[]{day,amount,"1.25","180****0000","测试用户","运营商","测试渠道","310000000000000001",LocalDate.parse(day).plusDays(1).toString(),amount,"成功","交易成功"};
    }
    private MockMultipartFile file(boolean xls, String[]... rows) throws Exception {
        try (Workbook book = xls ? new HSSFWorkbook() : new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("账单"); Row header = sheet.createRow(0);
            for (int i=0;i<HEADERS.length;i++) header.createCell(i).setCellValue(HEADERS[i]);
            for (int i=0;i<rows.length;i++) {
                Row row = sheet.createRow(i+1);
                for (int j=0;j<rows[i].length;j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            book.write(out);
            return new MockMultipartFile("file", xls?"test.xls":"test.xlsx", "application/octet-stream", out.toByteArray());
        }
    }
    @Test void bothExcelFormatsPreserveAllSourceFields() throws Exception {
        for (boolean xls : new boolean[]{true,false}) {
            AccountBill b=parser.parse(file(xls,row("2090-01-01","100.20"))).get(0);
            assertEquals(new BigDecimal("100.20"), b.getIncomeAmount()); assertEquals(b.getIncomeAmount(), b.getSettlementAmount());
            assertEquals(new BigDecimal("1.25"),b.getFeeAmount()); assertEquals(LocalDate.of(2090,1,2), b.getArrivalDate());
            assertEquals("310000000000000001",b.getMerchantNo()); assertEquals("成功",b.getSettlementStatus());
            assertEquals("交易成功",b.getSettlementInfo()); assertEquals(2,b.getSourceRow());
        }
    }
    @Test void previewDoesNotWriteAndImportIsIdempotentWithoutPostingMoney() throws Exception {
        long before=cashFlows.selectCount(null);
        MockMultipartFile file=file(true,row("2090-02-01","100.20"),row("2090-02-02","50.30"));
        assertEquals(2,service.preview(file,null).getNewCount());
        assertEquals(0,service.list(1,1,LocalDate.of(2090,2,1),LocalDate.of(2090,2,2),null,null).getTotals().getCount());
        assertEquals(2,service.importFile(file,null,"测试").getInserted());
        assertEquals(2,service.importFile(new MockMultipartFile("file","renamed.xls","",file.getBytes()),null,"测试").getSkipped());
        AccountBillService.ListResult list=service.list(1,1,LocalDate.of(2090,2,1),LocalDate.of(2090,2,2),null,"成功");
        assertEquals(1,list.getPage().getRecords().size()); assertEquals(2,list.getTotals().getCount());
        assertEquals(new BigDecimal("150.50"),list.getTotals().getSettlementAmount()); assertEquals(new BigDecimal("2.50"),list.getTotals().getFeeAmount());
        assertEquals(before,cashFlows.selectCount(null));
    }
    @Test void conflictsRejectWholeFile() throws Exception {
        service.importFile(file(true,row("2090-03-01","100")),null,"测试");
        MockMultipartFile conflict=file(true,row("2090-03-02","20"),row("2090-03-01","101"));
        assertFalse(service.preview(conflict,null).getErrors().isEmpty());
        assertThrows(BizException.class,()->service.importFile(conflict,null,"测试"));
        assertEquals(1,service.list(1,20,LocalDate.of(2090,3,1),LocalDate.of(2090,3,2),null,null).getTotals().getCount());
    }
    @Test void duplicateRowsInOneFileAreSkipped() throws Exception {
        AccountBillService.ImportResult r=service.importFile(file(false,row("2090-04-01","100"),row("2090-04-01","100.00")),null,"测试");
        assertEquals(1,r.getInserted()); assertEquals(1,r.getSkipped());
    }
    @Test void invalidRowReportsExcelLineAndNoPartialWrite() throws Exception {
        String[] invalid=row("2090-05-02","100");invalid[2]="-1";
        BizException ex=assertThrows(BizException.class,()->service.importFile(file(true,row("2090-05-01","20"),invalid),null,"测试"));
        assertTrue(ex.getMessage().contains("第 3 行"));
        assertEquals(0,service.list(1,20,LocalDate.of(2090,5,1),LocalDate.of(2090,5,2),null,null).getTotals().getCount());
    }
    @Test void missingArrivalAndInvalidAccountAreRejected() throws Exception {
        String[] bad=row("2090-06-01","100");bad[8]="";
        assertThrows(BizException.class,()->parser.parse(file(true,bad)));
        bad[10]="处理中";assertNull(parser.parse(file(true,bad)).get(0).getArrivalDate());
        bad[8]="2090-05-31";assertThrows(BizException.class,()->parser.parse(file(true,bad)));
        assertThrows(BizException.class,()->service.preview(file(true,row("2090-06-02","20")),Long.MAX_VALUE));
    }
    @Test void formulasAndNumericLongIdentifiersCannotSilentlyCorruptData() throws Exception {
        try(Workbook book=WorkbookFactory.create(file(true,row("2090-07-01","100")).getInputStream());ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Cell merchant=book.getSheetAt(0).getRow(1).getCell(7);merchant.setCellValue(310000000000000001d);book.write(out);
            assertThrows(BizException.class,()->parser.parse(new MockMultipartFile("file","bad.xls","",out.toByteArray())));
            merchant.setCellValue("310000000000000001");book.getSheetAt(0).getRow(1).getCell(1).setCellFormula("1+2");out.reset();book.write(out);
            assertThrows(BizException.class,()->parser.parse(new MockMultipartFile("file","bad.xls","",out.toByteArray())));
        }
    }
}
