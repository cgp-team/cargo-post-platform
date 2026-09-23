from pathlib import Path

p = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\yudao-module-transport\src\main\java\cn\iocoder\yudao\module\transport\service\dispatch\DispatchServiceImpl.java")
t = p.read_text(encoding="utf-8")

old1 = """    /** 方案总里程解析：算法返回 km（路网距离）时直接使用；degree（或缺省，欧氏直线）时按经停坐标 Haversine 换算 */

    private static BigDecimal resolveTotalDistanceKm(AlgorithmPlanRespDTO result, Map<String, double[]> coordMap) {

        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {

            double km = result.getTotalDistance() != null ? result.getTotalDistance() : 0;

            return BigDecimal.valueOf(Math.round(km * 1000) / 1000.0);

        }

        return computeTotalDistanceKm(result.getVehiclePlans(), coordMap);

    }"""

new1 = """    /** 路线成本来源：FORMAL_ROAD=路网正式；STRAIGHT_ESTIMATE=Haversine 下界参考（非正式） */
    private record DistanceEstimate(BigDecimal km, String routeProvider) {
        boolean formalRoad() {
            return "FORMAL_ROAD".equals(routeProvider);
        }
    }

    private static final String ROUTE_PROVIDER_FORMAL = "FORMAL_ROAD";
    private static final String ROUTE_PROVIDER_STRAIGHT = "STRAIGHT_ESTIMATE";

    /**
     * 方案总里程解析。km→FORMAL_ROAD；degree/缺省→STRAIGHT_ESTIMATE（非正式成本，禁止冒充路网里程）。
     */
    private static DistanceEstimate resolveTotalDistanceKm(AlgorithmPlanRespDTO result, Map<String, double[]> coordMap) {

        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {

            double km = result.getTotalDistance() != null ? result.getTotalDistance() : 0;

            return new DistanceEstimate(BigDecimal.valueOf(Math.round(km * 1000) / 1000.0), ROUTE_PROVIDER_FORMAL);

        }

        return new DistanceEstimate(computeTotalDistanceKm(result.getVehiclePlans(), coordMap), ROUTE_PROVIDER_STRAIGHT);

    }"""

assert old1 in t, "old1 not found"
t = t.replace(old1, new1, 1)
print("replaced1")

t = t.replace(
    "用 Haversine 累加真实公里数",
    "Haversine 累加估算公里（直线参考，非正式道路里程）",
)
print("comment_ok")

old2 = """    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,

                                      String algorithmVersion, String parameterVersion) {

        DispatchPlanDO plan = DispatchPlanDO.builder()

                .taskId(task.getId())

                .planVersion(1)

                .mode(mode.getMode())

                .algorithmVersion(algorithmVersion)

                .parameterVersion(parameterVersion)

                .totalDistance(totalDistance)

                .status(DispatchPlanStatusEnum.PENDING.getStatus())

                .build();"""

new2 = """    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,
                                      String routeProvider, String algorithmVersion, String parameterVersion) {

        DispatchPlanDO plan = DispatchPlanDO.builder()

                .taskId(task.getId())

                .planVersion(1)

                .mode(mode.getMode())

                .algorithmVersion(algorithmVersion)

                .parameterVersion(parameterVersion)

                .totalDistance(totalDistance)

                .routeProvider(routeProvider)

                .status(DispatchPlanStatusEnum.PENDING.getStatus())

                .build();"""

assert old2 in t, "old2 not found"
t = t.replace(old2, new2, 1)
print("replaced2")

old3 = """        dispatchPlanMapper.insert(plan);

        return plan;

    }



    /** 经停明细落库"""
new3 = """        dispatchPlanMapper.insert(plan);

        return plan;

    }

    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,
                                      String algorithmVersion, String parameterVersion) {
        return createPlan(task, mode, totalDistance, null, algorithmVersion, parameterVersion);
    }



    /** 经停明细落库"""
assert old3 in t, "old3 not found"
t = t.replace(old3, new3, 1)
print("replaced3")

p.write_text(t, encoding="utf-8")
print("done", len(t.splitlines()))
