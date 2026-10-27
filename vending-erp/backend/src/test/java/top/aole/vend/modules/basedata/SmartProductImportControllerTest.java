package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.aole.vend.common.auth.AuthGateFilter;
import top.aole.vend.common.auth.AuthTokenService;
import top.aole.vend.common.exception.GlobalExceptionHandler;
import top.aole.vend.modules.basedata.application.SmartProductImportService;
import top.aole.vend.modules.basedata.interfaces.SmartProductImportController;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 以生产 context-path=/api + 前端实际 URL 验证完整 HTTP 映射和鉴权，防止双重 /api 回归。 */
class SmartProductImportControllerTest {
    static final String URL = "/api/v1/basedata/products/smart-import";
    static final String BODY = "{\"token\":\"upload-test\",\"rows\":[{\"rowNo\":2,\"productName\":\"测试茶\",\"unit\":\"瓶\",\"boxSpec\":\"12\"}]}";
    SmartProductImportService service;
    MockMvc mvc;
    String auth;
    @BeforeEach void setup() {
        service = mock(SmartProductImportService.class);
        AuthTokenService tokens = new AuthTokenService();
        ReflectionTestUtils.setField(tokens, "secret", "smart-controller-test-only");
        ReflectionTestUtils.setField(tokens, "ttlSeconds", 3600L);
        auth = "Bearer " + tokens.issue(1L, "录入员", "老板");
        mvc = MockMvcBuilders.standaloneSetup(new SmartProductImportController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new AuthGateFilter(tokens)).build();
    }
    @Test void templateAtFrontendUrlIsAReadableExcelFile() throws Exception {
        byte[] content = mvc.perform(get(URL + "/template").contextPath("/api").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition","attachment; filename=product-auto-code-template.xlsx"))
            .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertEquals("商品列表", workbook.getSheetAt(0).getSheetName());
            assertEquals("后台商品编号", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("ERP编码", workbook.getSheetAt(0).getRow(0).getCell(9).getStringCellValue());
            assertEquals("@", workbook.getSheetAt(0).getColumnStyle(2).getDataFormatString());
            assertEquals(2, workbook.getNumberOfSheets());
        }
    }
    @Test void multipartParseAtFrontendUrlRoutesToService() throws Exception {
        ProductImportDtos.ParseResp response = new ProductImportDtos.ParseResp();
        response.setToken("test-token"); response.setRows(Collections.singletonList(ProductCatalogMatcherTest.row("新品", "001")));
        when(service.parse(eq("商品.xlsx"), any())).thenReturn(response);
        mvc.perform(multipart(URL + "/parse").file(new MockMultipartFile("file","商品.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",new byte[]{1,2}))
                .contextPath("/api").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").value("test-token")).andExpect(jsonPath("$.data.rows[0].productName").value("新品"));
        verify(service).parse(eq("商品.xlsx"),eq(new byte[]{1,2}));
    }
    @Test void reviewAndCommitUseSameUrlsAsFrontendWithTrustedOperator() throws Exception {
        when(service.review(anyList())).thenReturn(new ProductImportDtos.ParseResp());
        ProductImportDtos.CommitResp response = new ProductImportDtos.CommitResp(); response.setCreated(1);
        when(service.commit(eq("upload-test"),anyList(),eq("录入员"))).thenReturn(response);
        mvc.perform(post(URL + "/review").contextPath("/api").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(post(URL + "/commit").contextPath("/api").header("Authorization",auth).header("X-User-Name","spoofed")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(1));
        verify(service).commit(eq("upload-test"),anyList(),eq("录入员"));
    }
    @Test void manualEntryRequiresExplicitModeAndCannotMixUploadToken() throws Exception {
        when(service.commitManual(anyList(),eq("录入员"))).thenReturn(new ProductImportDtos.CommitResp());
        String manual = "{\"manualEntry\":true,\"rows\":[{\"rowNo\":1,\"productName\":\"手工新品\",\"unit\":\"袋\",\"boxSpec\":\"1\"}]}";
        mvc.perform(post(URL + "/commit").contextPath("/api").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(manual))
            .andExpect(jsonPath("$.code").value(200));
        verify(service).commitManual(anyList(),eq("录入员"));
        mvc.perform(post(URL + "/commit").contextPath("/api").header("Authorization",auth)
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("{\"token\"", "{\"manualEntry\":true,\"token\"")))
            .andExpect(jsonPath("$.code").value(500));
        verify(service,never()).commit(any(),anyList(),anyString());
    }
    @Test void allOperationsRequireLoginAndWrongDoublePrefixDoesNotMatch() throws Exception {
        mvc.perform(get(URL + "/template").contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(URL + "/parse").file(new MockMultipartFile("file",new byte[]{1})).contextPath("/api"))
            .andExpect(status().isUnauthorized());
        for (String operation : new String[]{"review","commit"}) {
            mvc.perform(post(URL + "/" + operation).contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api" + URL + "/template").contextPath("/api").header("Authorization",auth))
            .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
