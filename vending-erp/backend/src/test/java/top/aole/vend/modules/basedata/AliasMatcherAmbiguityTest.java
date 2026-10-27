package top.aole.vend.modules.basedata;
import org.junit.jupiter.api.Test;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.imports.service.AliasMatcher;
import java.util.Arrays;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AliasMatcherAmbiguityTest {
    @Test void sharedNameOrBarcodeNeverPicksLastProduct() {
        SkuAliasMapper mapper = mock(SkuAliasMapper.class);
        when(mapper.selectList(null)).thenReturn(Arrays.asList(
            ProductCatalogMatcherTest.alias(1,"a","001","同名茶"),
            ProductCatalogMatcherTest.alias(2,"b","002","同名茶"),
            ProductCatalogMatcherTest.alias(1,"c","003","唯一茶"),
            ProductCatalogMatcherTest.alias(2,"d","003","别名")));
        AliasMatcher.Session session = new AliasMatcher(mapper, mock(AliasPendingMapper.class)).openSession();
        assertNull(session.match(null,"同名茶")); assertNull(session.match("003","唯一茶"));
        assertEquals(1L, session.match("001","同名茶")); assertEquals(1L, session.match(null,"唯一茶"));
    }
}
