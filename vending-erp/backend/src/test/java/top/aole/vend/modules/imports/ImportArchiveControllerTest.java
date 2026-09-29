package top.aole.vend.modules.imports;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.aole.vend.common.auth.AuthGateFilter;
import top.aole.vend.common.auth.AuthTokenService;
import top.aole.vend.common.exception.GlobalExceptionHandler;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.interfaces.ImportController;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.service.ImportArchiveService;
import top.aole.vend.modules.imports.service.ImportFixService;
import top.aole.vend.modules.imports.service.ImportService;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImportArchiveControllerTest {
    @TempDir Path storage;
    private ImportBatch batch;
    private Path file;
    private MockMvc mvc;
    private String authorization;

    @BeforeEach
    void setUp() throws Exception {
        ImportBatchMapper batches = mock(ImportBatchMapper.class);
        ImportArchiveService archives = new ImportArchiveService(batches);
        ReflectionTestUtils.setField(archives, "storageDir", storage.toString());
        file = storage.resolve("original.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            workbook.createSheet("原表").createRow(0).createCell(0).setCellValue("表头");
            workbook.write(out);
        }
        batch = new ImportBatch();
        batch.setStatus(ImportBatch.HISTORY_VISIBLE);
        batch.setFileName("九月 出货明细.xlsx");
        batch.setArchivePath(file.toString());
        when(batches.selectById(7L)).thenReturn(batch);
        AuthTokenService tokens = new AuthTokenService();
        ReflectionTestUtils.setField(tokens, "secret", "archive-controller-test");
        ReflectionTestUtils.setField(tokens, "ttlSeconds", 3600L);
        authorization = "Bearer " + tokens.issue(9L, "测试员", "老板");
        mvc = MockMvcBuilders.standaloneSetup(new ImportController(mock(ImportService.class),
                        mock(ImportFixService.class), archives))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AuthGateFilter(tokens)).build();
    }

    @Test
    void downloadsExactOriginalBytesAndUnicodeFilenameWithoutCaching() throws Exception {
        MvcResult result = mvc.perform(get("/v1/imports/batches/7/file").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(file)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, Files.size(file)))
                .andReturn();
        ContentDisposition disposition = ContentDisposition.parse(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("attachment", disposition.getType());
        assertEquals("九月 出货明细.xlsx", disposition.getFilename());
    }

    @Test
    void previewsOriginalSheetWithDefaultsAndRequiresLoginForBothRoutes() throws Exception {
        mvc.perform(get("/v1/imports/batches/7/file-preview").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.rows[0].rowNo").value(1))
                .andExpect(jsonPath("$.data.rows[0].cells[0]").value("表头"))
                .andExpect(jsonPath("$.data.archivePath").doesNotExist());
        mvc.perform(get("/v1/imports/batches/7/file-preview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/imports/batches/7/file")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingArchiveAndHiddenBatchReturnControlledJsonErrorsForBothRoutes() throws Exception {
        Files.delete(file);
        for (String endpoint : new String[]{"file", "file-preview"}) {
            mvc.perform(get("/v1/imports/batches/7/" + endpoint).header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("该批次的原始文件未保留或已丢失，暂时无法查看或下载"));
        }
        batch.setStatus(ImportBatch.HISTORY_HIDDEN);
        for (String endpoint : new String[]{"file", "file-preview"}) {
            mvc.perform(get("/v1/imports/batches/7/" + endpoint).header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("批次不存在或已删除历史"));
        }
    }
}
