package cn.iocoder.yudao.module.transport.service.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 线路走廊切片单测：站间轨迹必须**沿线路走廊**取，而不是点对点自由路径。
 *
 * <p>背景（用户实测）：站点在道路另一侧/需上下桥时，点对点驾车规划会给出"进隧道 → 绕远 → 掉头"
 * 的走法；正确做法是在线路真实几何上取"离起讫站最近的两个顶点之间"的那一段
 * （例：文峰公社 → 文峰正街路口 → 吉祥路口）。</p>
 */
class RoadPolylineSliceTest {

    private final RoadPolylineService service = new RoadPolylineService();

    /** 一条"线路走廊"：A→B→C→D 顺向排列 */
    private static final List<double[]> CORRIDOR = List.of(
            new double[]{106.6000, 29.5200}, // A 文峰公社
            new double[]{106.6020, 29.5230}, // B 文峰正街路口
            new double[]{106.6040, 29.5260}, // C 吉祥路口
            new double[]{106.6060, 29.5300}); // D 南山路

    @Test
    void slicesForwardAlongCorridor() {
        List<double[]> slice = service.sliceAlong(CORRIDOR,
                106.6000, 29.5200, 106.6040, 29.5260);

        assertEquals(3, slice.size(), "A→C 应取走廊上的 A-B-C 三点（不绕行、不掉头）");
        assertEquals(106.6020, slice.get(1)[0], 1e-6);
    }

    @Test
    void slicesBackwardAlongCorridor() {
        List<double[]> slice = service.sliceAlong(CORRIDOR,
                106.6060, 29.5300, 106.6020, 29.5230);

        assertEquals(3, slice.size());
        // 切片从 from 端开始、沿走廊走到 to 端：D → C → B
        assertEquals(106.6060, slice.get(0)[0], 1e-6, "应以起点站 D 开头");
        assertEquals(106.6040, slice.get(1)[0], 1e-6);
        assertEquals(106.6020, slice.get(2)[0], 1e-6, "应以终点站 B 结尾");
    }

    @Test
    void snapsToNearestVertexForNearbyCoordinates() {
        // 站点坐标与走廊顶点点位有几米偏差（GPS/站牌在路侧）→ 吸附到最近顶点，仍能正确切片
        List<double[]> slice = service.sliceAlong(CORRIDOR,
                106.6001, 29.5201, 106.6021, 29.5231);

        assertEquals(2, slice.size());
    }

    @Test
    void returnsNullWhenNotUsable() {
        assertNull(service.sliceAlong(null, 106.60, 29.52, 106.61, 29.53));
        assertNull(service.sliceAlong(List.of(new double[]{106.60, 29.52}), 106.60, 29.52, 106.61, 29.53));
        // 起讫吸附到同一个顶点（同一站）→ 无法构成一段
        assertNull(service.sliceAlong(CORRIDOR, 106.6000, 29.5200, 106.6000, 29.5200));
    }
}
