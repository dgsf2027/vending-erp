package top.aole.vend.modules.basedata;

import org.apache.ibatis.annotations.Select;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.basedata.interfaces.MachineImportController;
import top.aole.vend.modules.basedata.interfaces.dto.MachineImportDtos;
import top.aole.vend.modules.basedata.interfaces.dto.MachineImportDtos.*;
import top.aole.vend.modules.imports.parser.ExcelParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.aole.vend.common.exception.GlobalExceptionHandler;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实 Excel + H2 唯一键及事务；预览只读，建档和审计一起回滚。 */
class MachineImportServiceTest {
    JdbcTemplate db;
    MachineMapper mapper;
    MachineImportService service;
    final String[] headers = {"机子编号（柜子编码）", "点位名称（表内最近记录）"};

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE yc_vend_machine(id bigint auto_increment primary key,tenant_id varchar(64) default '000000',"
            + "machine_code varchar(32) unique,machine_name varchar(64),device_id varchar(64) unique,location varchar(128),"
            + "is_deleted int default 0,machine_status varchar(16))");
        db.execute("CREATE TABLE audit(machine_id bigint)");
        mapper = mock(MachineMapper.class);
        String identitySql = MachineMapper.class.getMethod("importIdentities").getAnnotation(Select.class).value()[0];
        when(mapper.importIdentities()).thenAnswer(i -> db.query(identitySql, new BeanPropertyRowMapper<>(Machine.class)));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any())).thenAnswer(i -> {
            Machine m = i.getArgument(0);
            db.update("INSERT INTO yc_vend_machine(machine_code,machine_name,device_id,location,machine_status) VALUES(?,?,?,?,?)",
                m.getMachineCode(), m.getMachineName(), m.getDeviceId(), m.getLocation(), m.getMachineStatus());
            m.setId(db.queryForObject("SELECT id FROM yc_vend_machine WHERE device_id=?", Long.class, m.getDeviceId()));
            return 1;
        });
        OpLogService logs = mock(OpLogService.class);
        doAnswer(i -> {
            Machine m = i.getArgument(5);
            db.update("INSERT INTO audit VALUES(?)", m.getId());
            if ("FAIL".equals(m.getMachineName())) throw new BizException("审计写入失败");
            return null;
        }).when(logs).record(anyString(), eq("新建"), eq("machine"), anyLong(), isNull(), any());
        MachineService machineService = new MachineService(mapper, mock(SlotMapper.class), mock(ProductService.class), logs);
        service = new MachineImportService(new ExcelParser(), mapper, machineService, new DataSourceTransactionManager(ds));
    }

    byte[] excel(boolean xls, int headerRow, String[] columns, String[][] data) throws Exception {
        try (Workbook wb = xls ? new HSSFWorkbook() : new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("机子编号清单");
            if (headerRow > 0) s.createRow(1).createCell(0).setCellValue("元气森林机子编号清单");
            org.apache.poi.ss.usermodel.Row h = s.createRow(headerRow);
            for (int i = 0; i < columns.length; i++) h.createCell(i).setCellValue(columns[i]);
            for (int i = 0; i < data.length; i++) {
                org.apache.poi.ss.usermodel.Row row = s.createRow(headerRow + i + 1);
                for (int c = 0; c < data[i].length; c++) row.createCell(c).setCellValue(data[i][c]);
            }
            wb.write(out); return out.toByteArray();
        }
    }
    MachineImportDtos.Row row(String id, String name, String code) {
        MachineImportDtos.Row r = new MachineImportDtos.Row(); r.setDeviceId(id); r.setMachineName(name); r.setMachineCode(code); return r;
    }
    int size() { return db.queryForObject("SELECT COUNT(*) FROM yc_vend_machine", Integer.class); }

    @Test void titleRowsAndLeadingZerosArePreservedAndPreviewDoesNotWrite() throws Exception {
        Preview p = service.parse("清单.xlsx", excel(false, 5, headers, new String[][]{{"001234", "一楼1号机"}, {"YQLH-002", "二楼2号机"}}));
        assertEquals(6, p.getHeaderRowNo()); assertEquals(2, p.getCreateCount()); assertEquals(7, p.getRows().get(0).getRowNo());
        assertEquals("001234", p.getRows().get(0).getDeviceId()); assertEquals("一楼1号机", p.getRows().get(0).getLocation());
        assertEquals(0, size()); verify(mapper, never()).insert(any());
        Result saved = service.commit(p.getRows(), "测试");
        assertEquals(2, saved.getCreated()); assertEquals("M000001", saved.getRows().get(0).getMachineCode());
        assertEquals("在线", db.queryForObject("SELECT machine_status FROM yc_vend_machine WHERE device_id='001234'", String.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
    }

    @Test void repeatSkipsExistingAndDoesNotOverwriteNameLocationOrStatus() throws Exception {
        db.update("INSERT INTO yc_vend_machine(machine_code,machine_name,device_id,location,machine_status) VALUES('M01','旧名','DEV-1','旧位置','停用')");
        List<MachineImportDtos.Row> data = Arrays.asList(row(" dev-1 ", "新名", "M999"));
        Preview checked = service.review(data);
        assertEquals(1, checked.getExistingCount()); assertEquals("M01", checked.getRows().get(0).getMachineCode());
        Result saved = service.commit(data, "测试"); assertEquals(0, saved.getCreated()); assertEquals(1, saved.getSkipped());
        assertEquals("旧名", db.queryForObject("SELECT machine_name FROM yc_vend_machine", String.class));
        assertEquals("旧位置", db.queryForObject("SELECT location FROM yc_vend_machine", String.class));
        assertEquals("停用", db.queryForObject("SELECT machine_status FROM yc_vend_machine", String.class));
        verify(mapper, never()).insert(any());
    }

    @Test void salesRowsDeduplicateByDeviceAndUseLatestDatedNameForXls() throws Exception {
        Preview p = service.parse("销售.xls", excel(true, 0, new String[]{"柜子编码", "点位名称", "订单时间"},
            new String[][]{{"D01", "新点位", "2026-11-06 12:00:00"}, {"d01", "旧点位", "2026-10-20 09:00:00"},
                {"D02", "二楼", "2026-10-20 09:00:00"}, {"D01", "更早", "2026-10-19 09:00:00"}}));
        assertEquals(4, p.getSourceRows()); assertEquals(2, p.getCreateCount());
        assertEquals("新点位", p.getRows().get(0).getMachineName()); assertEquals(3, p.getRows().get(0).getSourceRowCount());
        assertNotNull(p.getRows().get(0).getWarning());
        assertEquals(2, service.commit(p.getRows(), "测试").getCreated());
        assertEquals(2, service.commit(p.getRows(), "测试").getSkipped()); assertEquals(2, size());
    }

    @Test void automaticallyAssignedCodesReserveExplicitAndDeletedHistory() {
        db.update("INSERT INTO yc_vend_machine(machine_code,machine_name,device_id,is_deleted) VALUES('M000005','历史','OLD',1)");
        Result saved = service.commit(Arrays.asList(row("A", "一楼", ""), row("B", "二楼", "M000007")), "测试");
        assertEquals("M000008", saved.getRows().get(0).getMachineCode());
        assertEquals("M000007", saved.getRows().get(1).getMachineCode());
        assertEquals(1, service.review(Arrays.asList(row("OLD", "历史", ""))).getErrorCount());
        assertEquals(1, service.review(Arrays.asList(row("NEW", "新", "M000005"))).getErrorCount());
    }

    @Test void invalidFieldsAndDuplicateIdsBlockWholeBatchEvenIfClientClaimsValid() {
        MachineImportDtos.Row bad = row("", "", ""); bad.setAction("待新增");
        assertEquals(1, service.review(Arrays.asList(bad)).getErrorCount());
        assertThrows(BizException.class, () -> service.commit(Arrays.asList(row("GOOD", "好", ""), bad), "测试"));
        assertEquals(0, size());
        assertEquals(2, service.review(Arrays.asList(row("D", "一", ""), row("d", "二", ""))).getErrorCount());
        assertEquals(2, service.review(Arrays.asList(row("D1", "一", "X"), row("D2", "二", "x"))).getErrorCount());
        assertEquals(1, service.review(Arrays.asList(row(String.join("", Collections.nCopies(65, "D")), "长", ""))).getErrorCount());
        assertEquals(1, service.review(Arrays.asList(row("D", String.join("", Collections.nCopies(65, "长")), ""))).getErrorCount());
        assertEquals(1, service.review(Arrays.asList(row("D\n1", "换行", ""))).getErrorCount());
    }

    @Test void failureOnSecondMachineRollsBackMachinesAndAuditTogether() {
        assertThrows(BizException.class, () -> service.commit(Arrays.asList(row("D1", "第一台", ""), row("D2", "FAIL", "")), "测试"));
        assertEquals(0, size()); assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
        assertEquals("M000001", service.commit(Arrays.asList(row("D1", "重试", "")), "测试").getRows().get(0).getMachineCode());
    }

    @Test void databaseCollisionReturnsRetryMessageAndRollsBackEarlierRows() {
        // 模拟预览之后另一请求抢先占用了设备；唯一键仍是最终防线。
        db.update("INSERT INTO yc_vend_machine(machine_code,machine_name,device_id) VALUES('OTHER','已有','D2')");
        when(mapper.importIdentities()).thenReturn(Collections.emptyList());
        BizException e = assertThrows(BizException.class, () -> service.commit(Arrays.asList(row("D1", "一", ""), row("D2", "二", "")), "测试"));
        assertTrue(e.getMessage().contains("全部撤回")); assertEquals(1, size());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
    }

    @Test void malformedFilesAndMissingOrDuplicateColumnsGiveUsefulErrors() throws Exception {
        assertThrows(BizException.class, () -> service.parse("bad.csv", new byte[]{1}));
        assertThrows(BizException.class, () -> service.parse("bad.xlsx", new byte[]{1}));
        assertTrue(assertThrows(BizException.class, () -> service.parse("bad.xlsx", excel(false, 0, new String[]{"商品名称"}, new String[][]{{"汽水"}})))
            .getMessage().contains("未找到机器列表表头"));
        assertThrows(BizException.class, () -> service.parse("重复.xlsx", excel(false, 0, new String[]{"柜子编码", "设备ID", "点位名称"}, new String[][]{{"D", "D", "位置"}})));
    }

    @Test void templateIsRealExcelAndSupportsRoundTripWithTextIdentifiers() throws Exception {
        byte[] bytes = new MachineImportController(service).template().getBody(); assertNotNull(bytes);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0); assertEquals(headers[0], sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(headers[1], sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("@", sheet.getColumnStyle(0).getDataFormatString());
            org.apache.poi.ss.usermodel.Row r = sheet.createRow(1); r.createCell(0).setCellValue("00001"); r.createCell(1).setCellValue("一楼");
            wb.write(out); Preview p = service.parse("模板.xlsx", out.toByteArray());
            assertEquals(1, p.getCreateCount()); assertEquals("00001", p.getRows().get(0).getDeviceId());
        }
    }

    @Test void actualUserMachineListRecognizesAllTenAndImportsIdempotently() throws Exception {
        String file = System.getProperty("vend.test.machineFile"); Assumptions.assumeTrue(file != null);
        Preview p = service.parse("元气森林机子编号清单.xlsx", Files.readAllBytes(Paths.get(file)));
        assertEquals(6, p.getHeaderRowNo()); assertEquals(10, p.getSourceRows()); assertEquals(10, p.getCreateCount()); assertEquals(0, p.getErrorCount());
        assertEquals("YQLH26007695QS-509CRSW", p.getRows().get(0).getDeviceId());
        assertEquals("数智云仓产业园二楼2号机", p.getRows().get(0).getMachineName());
        assertEquals(0, size()); assertEquals(10, service.commit(p.getRows(), "测试").getCreated());
        assertEquals(10, service.commit(p.getRows(), "测试").getSkipped()); assertEquals(10, size());
    }

    @Test void actualUserSalesFileCollapsesToTenMachines() throws Exception {
        String file = System.getProperty("vend.test.salesFile"); Assumptions.assumeTrue(file != null);
        Preview p = service.parse("销售.xlsx", Files.readAllBytes(Paths.get(file)));
        assertEquals(3403, p.getSourceRows()); assertEquals(10, p.getCreateCount()); assertEquals(0, p.getErrorCount()); assertEquals(0, size());
    }

    @Test void httpUploadReviewCommitAndTemplateUseTheSameContract() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MachineImportController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        MockMultipartFile file = new MockMultipartFile("file", "machines.xlsx", "application/octet-stream",
            excel(false, 5, headers, new String[][]{{"0008", "一楼"}, {"0009", "二楼"}}));
        String response = mvc.perform(multipart("/v1/basedata/machines/import/parse").file(file))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.headerRowNo").value(6))
            .andExpect(jsonPath("$.data.createCount").value(2)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, size());
        ObjectMapper json = new ObjectMapper();
        String body = json.createObjectNode().set("rows", json.readTree(response).path("data").path("rows")).toString();
        mvc.perform(post("/v1/basedata/machines/import/review").contentType("application/json").content(body))
            .andExpect(jsonPath("$.data.createCount").value(2));
        assertEquals(0, size());
        mvc.perform(post("/v1/basedata/machines/import/commit").contentType("application/json").content(body))
            .andExpect(jsonPath("$.data.created").value(2)).andExpect(jsonPath("$.data.rows[0].machineCode").value("M000001"));
        mvc.perform(post("/v1/basedata/machines/import/commit").contentType("application/json").content(body))
            .andExpect(jsonPath("$.data.created").value(0)).andExpect(jsonPath("$.data.skipped").value(2));
        mvc.perform(get("/v1/basedata/machines/import/template")).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test void httpEmptySubmissionIsRejectedWithoutWrites() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MachineImportController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/v1/basedata/machines/import/commit").contentType("application/json").content("{\"rows\":[]}"))
            .andExpect(jsonPath("$.code").value(400));
        assertEquals(0, size());
    }
}
