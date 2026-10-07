package top.aole.vend.modules.imports.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 只读归档入口,与导入时的表头映射/业务解析分离。 */
@Service
@RequiredArgsConstructor
public class ImportArchiveService {
    private static final int MAX_PREVIEW_COLUMNS = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ARCHIVE_UNAVAILABLE = "该批次的原始文件未保留或已丢失，暂时无法查看或下载";

    private final ImportBatchMapper batchMapper;

    @Value("${vend.import.storage-dir:../storage/imports}")
    private String storageDir;

    public ImportDtos.FilePreviewResp preview(Long batchId, int sheetIndex, long current, int size) {
        if (sheetIndex < 0 || current < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BizException(400, "预览参数无效，每页可查看 1 至 100 行");
        }
        ArchivedFile archive = file(batchId);
        // 按内容识别 xls/xlsx;只读打开,不会改写原始归档或计算公式。
        try (Workbook workbook = WorkbookFactory.create(archive.getPath().toFile(), null, true)) {
            if (sheetIndex >= workbook.getNumberOfSheets()) {
                throw new BizException(400, "工作表不存在，请重新选择");
            }
            ImportDtos.FilePreviewResp result = new ImportDtos.FilePreviewResp();
            result.setFileName(archive.getFileName());
            result.setSheetIndex(sheetIndex);
            result.setCurrent(current);
            result.setSize(size);
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                result.getSheets().add(new ImportDtos.FileSheet(i, workbook.getSheetName(i)));
            }

            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int total = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
            result.setTotal(total);
            int columnTotal = 0;
            for (Row row : sheet) {
                columnTotal = Math.max(columnTotal, row.getLastCellNum());
            }
            result.setColumnTotal(columnTotal);
            int columnCount = Math.min(columnTotal, MAX_PREVIEW_COLUMNS);
            for (int column = 0; column < columnCount; column++) {
                result.getColumns().add(CellReference.convertNumToColString(column));
            }
            if (columnTotal > MAX_PREVIEW_COLUMNS) {
                result.getWarnings().add("工作表共 " + columnTotal + " 列，在线预览显示前 "
                        + MAX_PREVIEW_COLUMNS + " 列；下载原文件可查看全部内容");
            }
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            formatter.setUseCachedValuesForFormulaCells(true);
            // 先比较页号再做乘法,避免极大 current 溢出并泄漏服务异常。
            if (total > 0 && current <= ((long) total + size - 1) / size) {
                int start = (int) ((current - 1) * size);
                int end = Math.min(start + size, total);
                for (int rowIndex = start; rowIndex < end; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    List<String> cells = new ArrayList<>(columnCount);
                    for (int column = 0; column < columnCount; column++) {
                        cells.add(formatter.formatCellValue(row == null ? null : row.getCell(column)));
                    }
                    result.getRows().add(new ImportDtos.FileRow(rowIndex + 1, cells));
                }
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BizException("原始表格暂时无法预览，文件可能已损坏或加密；可下载原文件查看");
        }
    }

    /** 统一执行可见性与真实路径校验,下载和预览不能绕过历史删除规则。 */
    public ArchivedFile file(Long batchId) {
        ImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !Integer.valueOf(ImportBatch.HISTORY_VISIBLE).equals(batch.getStatus())) {
            throw new BizException("批次不存在或已删除历史");
        }
        String archivePath = batch.getArchivePath();
        if (archivePath == null || archivePath.trim().isEmpty()) {
            throw new BizException(ARCHIVE_UNAVAILABLE);
        }
        try {
            Path root = Paths.get(storageDir).toRealPath();
            Path path = Paths.get(archivePath).toRealPath();
            // toRealPath 同时解析父目录和文件本身的符号链接,不能靠链接读取归档目录外的文件。
            if (!path.startsWith(root) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new BizException(ARCHIVE_UNAVAILABLE);
            }
            String fileName = batch.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = batch.getBatchNo() + (archivePath.toLowerCase(java.util.Locale.ROOT).endsWith(".xls") ? ".xls" : ".xlsx");
            }
            // 老记录也可能带客户端路径或控制字符;响应只携带文件名。
            fileName = fileName.replace('\\', '/');
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
            if (fileName.trim().isEmpty()) {
                fileName = "导入原文件" + (archivePath.toLowerCase(java.util.Locale.ROOT).endsWith(".xls") ? ".xls" : ".xlsx");
            }
            return new ArchivedFile(path, fileName, Files.size(path));
        } catch (IOException | InvalidPathException | SecurityException e) {
            throw new BizException(ARCHIVE_UNAVAILABLE);
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class ArchivedFile {
        private final Path path;
        private final String fileName;
        private final long size;
    }
}
