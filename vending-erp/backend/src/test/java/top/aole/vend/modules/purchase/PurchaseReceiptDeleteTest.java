package top.aole.vend.modules.purchase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.doc.domain.entity.DocHead;
import top.aole.vend.modules.doc.domain.enums.DocStatus;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.mapper.DocItemMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.doc.service.DocStatusGuard;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.purchase.interfaces.PurchaseController;
import top.aole.vend.modules.purchase.mapper.PurchasePriceMapper;
import top.aole.vend.modules.purchase.service.PurchaseOrderService;
import top.aole.vend.modules.purchase.service.PurchaseReceiptService;
import top.aole.vend.modules.stock.service.StockService;

import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the real document state machine and status guard. No production database. */
class PurchaseReceiptDeleteTest {
    DocHeadMapper heads; DocItemMapper items; OpLogService logs;
    ApplicationEventPublisher events; StockService stock; PurchaseReceiptService receipts;

    @BeforeEach void setup() {
        heads=mock(DocHeadMapper.class); items=mock(DocItemMapper.class);
        logs=mock(OpLogService.class); events=mock(ApplicationEventPublisher.class); stock=mock(StockService.class);
        when(items.selectList(any())).thenReturn(Collections.emptyList());
        when(heads.update(any(),any())).thenReturn(1);
        DocService docs=new DocService(heads,items,new DocStatusGuard(heads),logs,mock(ProductService.class),stock,mock(PeriodLockService.class),events);
        receipts=new PurchaseReceiptService(docs,mock(PurchaseOrderService.class),mock(PurchasePriceMapper.class));
    }
    DocHead head(DocType type,DocStatus status) {
        DocHead h=new DocHead();h.setId(1L);h.setDocNo("RK-TEST-001");h.setDocType(type);h.setDocStatus(status);
        return h;
    }

    @ParameterizedTest @EnumSource(value=DocStatus.class,names={"DRAFT","PENDING_CONFIRM"})
    void unpostedReceiptIsVoidedWithAuditAndNoStockMovement(DocStatus status) {
        DocHead h=head(DocType.PURCHASE_IN,status);when(heads.selectById(1L)).thenReturn(h);
        new PurchaseController(mock(PurchaseOrderService.class),receipts).deleteReceipt(1L);
        assertEquals(DocStatus.VOID,h.getDocStatus());
        verify(heads).update(eq(h),any());
        verify(logs).recordJson(eq(0L),eq("作废"),eq("doc_head"),eq(1L),anyString(),anyString());
        verify(items,never()).delete(any());
        verify(heads,never()).delete(any());
        verifyNoInteractions(events,stock);
    }

    @ParameterizedTest @EnumSource(value=DocStatus.class,names={"DRAFT","PENDING_CONFIRM"},mode=EnumSource.Mode.EXCLUDE)
    void postedOrRemovedReceiptCannotBypassReversal(DocStatus status) {
        DocHead h=head(DocType.PURCHASE_IN,status);when(heads.selectById(1L)).thenReturn(h);
        assertThrows(BizException.class,()->receipts.deleteDraft(1L,0L));
        assertEquals(status,h.getDocStatus());verify(heads,never()).update(any(),any());
        verifyNoInteractions(events,stock,logs);
    }

    @Test void anotherDocumentTypeIsRejected() {
        when(heads.selectById(1L)).thenReturn(head(DocType.TRANSFER_OUT,DocStatus.DRAFT));
        assertThrows(BizException.class,()->receipts.deleteDraft(1L,0L));
        verify(heads,never()).update(any(),any());verifyNoInteractions(events,stock,logs);
    }

    @Test void missingReceiptIsRejected() {
        assertThrows(BizException.class,()->receipts.deleteDraft(404L,0L));
        verifyNoInteractions(events,stock,logs);
    }

    @Test void concurrentConfirmationIsRecheckedBeforeVoid() {
        when(heads.selectById(1L)).thenReturn(head(DocType.PURCHASE_IN,DocStatus.DRAFT),head(DocType.PURCHASE_IN,DocStatus.CONFIRMED));
        assertThrows(BizException.class,()->receipts.deleteDraft(1L,0L));
        verify(heads,never()).update(any(),any());verifyNoInteractions(events,stock,logs);
    }

    @Test void lostStateUpdateDoesNotWriteSuccessAuditOrPublishStockEvent() {
        when(heads.selectById(1L)).thenReturn(head(DocType.PURCHASE_IN,DocStatus.DRAFT));
        when(heads.update(any(),any())).thenReturn(0);
        assertThrows(BizException.class,()->receipts.deleteDraft(1L,0L));
        verifyNoInteractions(events,stock,logs);
    }
}
