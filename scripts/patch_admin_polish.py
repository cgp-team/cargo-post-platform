from pathlib import Path

root = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\yudao-module-transport\src\main\java\cn\iocoder\yudao\module\transport")

# Fix healthCheck to GET openapi.json (HEAD may 405)
p = root / "integration/algorithm/AlgorithmClient.java"
t = p.read_text(encoding="utf-8")
t = t.replace(
    'restTemplate.headForHeaders(properties.getBaseUrl() + "/openapi.json");',
    'restTemplate.getForEntity("/openapi.json", String.class);',
)
t = t.replace(
    'restTemplate.headForHeaders(properties.getBaseUrl() + "/api/v1/route");',
    'restTemplate.getForEntity("/api/v1/route", String.class);',
)
# ensure ServiceException import
if "exception.ServiceException" not in t:
    t = t.replace(
        "import lombok.SneakyThrows;",
        "import cn.iocoder.yudao.framework.common.exception.ServiceException;\nimport lombok.SneakyThrows;",
        1,
    )
p.write_text(t, encoding="utf-8")
print("healthCheck fixed")

# Startup runner
runner = root / "integration/algorithm/AlgorithmStartupChecker.java"
runner.write_text(
    """package cn.iocoder.yudao.module.transport.integration.algorithm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检：算法服务连通性与 base-url 告警（P0：避免静默打空/误连 mock）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlgorithmStartupChecker implements ApplicationRunner {

    private final AlgorithmAdapter algorithmAdapter;
    private final AlgorithmProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        boolean ok = algorithmAdapter.healthCheck();
        if (ok) {
            log.info("[AlgorithmStartupChecker] 算法服务可达 baseUrl={}", properties.getBaseUrl());
        } else {
            log.warn("[AlgorithmStartupChecker] 算法服务不可达 baseUrl={} — 智能派单/动态调度将失败或降级；请检查 ALGORITHM_BASE_URL",
                    properties.getBaseUrl());
        }
    }
}
""",
    encoding="utf-8",
)
print("startup checker written")

# UI: show routeProvider in plan table if column exists — patch dispatch/index.vue plan list
ui = Path(r"C:\Users\\袁\\Desktop\\测试\\cargo-post-platform\\yudao-ui\\yudao-ui-admin-vue3\\src\\views\\transport\\dispatch\\index.vue")
if not ui.exists():
    ui = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\yudao-ui\yudao-ui-admin-vue3\src\views\transport\dispatch\index.vue")
tu = ui.read_text(encoding="utf-8")
if "routeProvider" not in tu and "FORMAL_ROAD" not in tu:
    # insert after totalDistance display if present, else after mode tag block
    needle = "planStatusLabel(scope.row.status)"
    # add helper near planStatusLabel import usage — simpler: add a method in script
    if "const routeProviderLabel" not in tu:
        tu = tu.replace(
            "const planLoading = ref(true)",
            """const routeProviderLabel = (p?: string) =>
  p === 'FORMAL_ROAD' ? '路网正式' : p === 'STRAIGHT_ESTIMATE' ? '直线估算' : '—'
const planLoading = ref(true)""",
            1,
        )
    # add column in table before 操作 or status
    col = """            <el-table-column label="里程口径" min-width="100">
              <template #default="scope">
                <el-tag :type="scope.row.routeProvider === 'FORMAL_ROAD' ? 'success' : 'warning'" size="small">
                  {{ routeProviderLabel(scope.row.routeProvider) }}
                </el-tag>
              </template>
            </el-table-column>
"""
    # insert after first el-table-column that has 方案 or similar
    key = '<el-table-column prop="status"'
    if key in tu:
        tu = tu.replace(key, col + key, 1)
        print("ui column inserted")
    else:
        key2 = "<el-table-column"
        tu = tu.replace(key2, col + key2, 1)
        print("ui column inserted at first column")
    ui.write_text(tu, encoding="utf-8")
else:
    print("ui already has routeProvider")
print("DONE")
