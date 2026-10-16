package cn.iocoder.yudao.module.aiassist.service.savedquery;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQuerySaveReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.savedquery.AiSavedQueryDO;
import cn.iocoder.yudao.module.aiassist.dal.mysql.savedquery.AiSavedQueryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保存的查询条件的写口规则（契约 §6 第 4 项）。
 */
@ExtendWith(MockitoExtension.class)
class AiSavedQueryServiceImplTest {

    @Mock private AiSavedQueryMapper mapper;
    @InjectMocks private AiSavedQueryServiceImpl service;

    private static AiSavedQuerySaveReqVO req() {
        AiSavedQuerySaveReqVO v = new AiSavedQuerySaveReqVO();
        v.setName("本周未分配订单");
        v.setScene("DISPATCH");
        v.setConditionsJson("{\"status\":\"UNASSIGNED\"}");
        return v;
    }

    @Test
    void createInsertsAndReturnsGeneratedId() {
        when(mapper.insert(any(AiSavedQueryDO.class))).thenAnswer(inv -> {
            AiSavedQueryDO e = inv.getArgument(0);
            e.setId(42L);
            return 1;
        });

        Long id = service.createQuery(req());

        assertEquals(42L, id);
        ArgumentCaptor<AiSavedQueryDO> cap = ArgumentCaptor.forClass(AiSavedQueryDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals("DISPATCH", cap.getValue().getScene());
    }

    /** 改一条不存在的条件必须红 —— 静默 update 影响 0 行，运营会以为改成了。 */
    @Test
    void updateNonExistentIsRejected() {
        AiSavedQuerySaveReqVO v = req();
        v.setId(999L);
        when(mapper.selectById(999L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.updateQuery(v));
        verify(mapper, never()).updateById(any(AiSavedQueryDO.class));
    }

    @Test
    void updateWithoutIdIsRejected() {
        assertThrows(ServiceException.class, () -> service.updateQuery(req()));
        verify(mapper, never()).updateById(any(AiSavedQueryDO.class));
    }

    @Test
    void getNonExistentIsRejected() {
        when(mapper.selectById(7L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getQuery(7L));
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
    }

}
