package top.aole.vend.modules.stock.service;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.doc.domain.entity.*;
import top.aole.vend.modules.doc.domain.enums.DocType;
import top.aole.vend.modules.doc.domain.event.DocConfirmedEvent;
import top.aole.vend.modules.doc.dto.*;
import top.aole.vend.modules.doc.mapper.*;
import top.aole.vend.modules.doc.service.*;
import top.aole.vend.modules.period.service.PeriodLockService;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.service.*;
import top.aole.vend.modules.purchase.mapper.PurchasePriceMapper;
import top.aole.vend.modules.settle.service.SettleBillService;
import top.aole.vend.modules.stock.domain.entity.StockLedger;
import top.aole.vend.modules.stock.mapper.StockLedgerMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PurchaseAmountTest {
    DocHeadMapper heads; DocItemMapper items; DocService docs;
    List<DocHead> savedHeads; List<DocItem> savedItems;
    @BeforeEach void setup() {
        heads=mock(DocHeadMapper.class); items=mock(DocItemMapper.class); savedHeads=new ArrayList<>(); savedItems=new ArrayList<>();
        when(heads.selectCount(any())).thenReturn(0L);
        when(heads.insert(any())).thenAnswer(i -> { DocHead h=i.getArgument(0); h.setId((long)savedHeads.size()+1); savedHeads.add(h); return 1; });
        when(heads.selectById(anyLong())).thenAnswer(i -> savedHeads.stream().filter(h -> h.getId().equals(i.getArgument(0))).findFirst().orElse(null));
        when(items.insert(any())).thenAnswer(i -> { DocItem item=i.getArgument(0); item.setId((long)savedItems.size()+1); savedItems.add(item); return 1; });
        docs=new DocService(heads,items,mock(DocStatusGuard.class),mock(OpLogService.class),mock(ProductService.class),mock(StockService.class),mock(PeriodLockService.class),mock(ApplicationEventPublisher.class));
    }
    DocCreateReq request(DocType type, String amount) {
        DocItemReq item=new DocItemReq(); item.setProductId(15L); item.setQty(new BigDecimal("240")); item.setUnitPrice(new BigDecimal("3.3333")); item.setAmount(amount==null?null:new BigDecimal(amount));
        DocCreateReq r=new DocCreateReq(); r.setBizDate(LocalDate.of(2026,10,19)); r.setDocType(type); r.setMachineId(1L); r.setItems(Collections.singletonList(item)); return r;
    }
    void equal(String n, BigDecimal actual) { assertEquals(0,new BigDecimal(n).compareTo(actual)); }

    @Test void originalBoxAmountSurvivesReceiptConversionAndDocumentTotals() {
        ReceiptCreateReq receipt=new ReceiptCreateReq(); receipt.setSupplierId(1L); receipt.setBizDate(LocalDate.of(2026,10,19));
        ReceiptCreateReq.Item item=new ReceiptCreateReq.Item(); item.setProductId(15L); item.setQty(new BigDecimal("240")); item.setUnitPrice(new BigDecimal("3.3333")); item.setAmount(new BigDecimal("800"));
        receipt.setItems(Collections.singletonList(item));
        new PurchaseReceiptService(docs,mock(PurchaseOrderService.class),mock(PurchasePriceMapper.class)).createDirect(receipt,0L);
        equal("800",savedHeads.get(0).getTotalAmount()); equal("800",savedItems.get(0).getAmount()); equal("3.3333",savedItems.get(0).getUnitPrice());
    }
    @Test void arbitraryAmountOverridesAndNonPurchaseOverridesAreRejectedBeforeWrites() {
        assertThrows(BizException.class,() -> docs.createDoc(request(DocType.PURCHASE_IN,"801"),0L));
        assertThrows(BizException.class,() -> docs.createDoc(request(DocType.PURCHASE_IN,"800.001"),0L));
        assertThrows(BizException.class,() -> docs.createDoc(request(DocType.TRANSFER_OUT,"800"),0L));
        verify(heads,never()).insert(any()); verify(items,never()).insert(any());
    }
    @Test void legacyNoAmountUsesOriginalCalculation() {
        docs.createDoc(request(DocType.PURCHASE_IN,null),0L); equal("799.9920",savedHeads.get(0).getTotalAmount());
    }
    @Test void purchaseAndItsReversalPostEqualOppositeAmounts() {
        docs.createDoc(request(DocType.PURCHASE_IN,"800"),0L);
        DocCreateReq reverse=request(DocType.RED_FLUSH,"800"); reverse.setRedFlushOf(1L); docs.createDoc(reverse,0L);
        equal("800",savedHeads.get(1).getTotalAmount()); equal("800",savedItems.get(1).getAmount());
        StockLedgerMapper ledger=mock(StockLedgerMapper.class); when(ledger.sumWarehouse(anyLong())).thenReturn(new BigDecimal("240"));
        StockPostingListener listener=new StockPostingListener(new StockLedgerWriter(ledger),heads);
        for(int i=0;i<2;i++) listener.onDocConfirmed(new DocConfirmedEvent(savedHeads.get(i),Collections.singletonList(savedItems.get(i)),DocConfirmedEvent.PostingPhase.FULL,LocalDateTime.of(2026,10,19,0,0)));
        ArgumentCaptor<StockLedger> capture=ArgumentCaptor.forClass(StockLedger.class); verify(ledger,times(2)).insert(capture.capture());
        equal("800",capture.getAllValues().get(0).getAmount()); equal("-800",capture.getAllValues().get(1).getAmount());
        equal("0",capture.getAllValues().get(0).getChangeQty().add(capture.getAllValues().get(1).getChangeQty()));
    }
    @Test void redFlushServiceCopiesOriginalPurchaseAmount() {
        docs.createDoc(request(DocType.PURCHASE_IN,"800"),0L);
        DocService mockDocs=mock(DocService.class);
        when(mockDocs.itemsOf(1L)).thenReturn(savedItems); when(mockDocs.createDoc(any(),anyLong())).thenReturn(2L);
        RedFlushService reversals=spy(new RedFlushService(mockDocs,heads,mock(DocQueryMapper.class),mock(StockService.class),mock(PeriodLockService.class),mock(SettleBillService.class)));
        RedFlushService.Preview preview=new RedFlushService.Preview(); preview.setExecutable(true); preview.setBlockers(Collections.emptyList()); preview.setBookPeriod("2026-10");
        doReturn(preview).when(reversals).preview(1L); reversals.execute(1L,0L,"测试",false,"老板");
        ArgumentCaptor<DocCreateReq> capture=ArgumentCaptor.forClass(DocCreateReq.class); verify(mockDocs).createDoc(capture.capture(),eq(0L));
        equal("800",capture.getValue().getItems().get(0).getAmount());
    }
    @Test void legacyZeroCostPurchaseRemainsReversible() {
        DocCreateReq old=request(DocType.PURCHASE_IN,null); old.getItems().get(0).setUnitPrice(BigDecimal.ZERO);
        docs.createDoc(old,0L);
        DocService mockDocs=mock(DocService.class);
        when(mockDocs.itemsOf(1L)).thenReturn(savedItems); when(mockDocs.createDoc(any(),anyLong())).thenReturn(2L);
        RedFlushService reversals=spy(new RedFlushService(mockDocs,heads,mock(DocQueryMapper.class),mock(StockService.class),mock(PeriodLockService.class),mock(SettleBillService.class)));
        RedFlushService.Preview preview=new RedFlushService.Preview(); preview.setExecutable(true); preview.setBlockers(Collections.emptyList()); preview.setBookPeriod("2026-10");
        doReturn(preview).when(reversals).preview(1L); reversals.execute(1L,0L,"测试",false,"老板");
        ArgumentCaptor<DocCreateReq> capture=ArgumentCaptor.forClass(DocCreateReq.class); verify(mockDocs).createDoc(capture.capture(),eq(0L));
        assertNull(capture.getValue().getItems().get(0).getAmount());
        docs.createDoc(capture.getValue(),0L); equal("0",savedHeads.get(1).getTotalAmount());
    }
}
