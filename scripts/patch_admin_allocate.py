from pathlib import Path

root = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\yudao-module-transport\src\main\java\cn\iocoder\yudao\module\transport")

# ---- AlgorithmClient: health + allocate ----
p = root / "integration/algorithm/AlgorithmClient.java"
t = p.read_text(encoding="utf-8")
if "public boolean healthCheck" not in t:
    # insert methods before final closing or before pollResult
    needle = "    public AlgorithmDistanceRespDTO distance(AlgorithmDistanceReqDTO request) {"
    insert = """    /** 启动/巡检：探测算法服务是否可达（openapi 或 plan 路由）。 */
    public boolean healthCheck() {
        try {
            restTemplate.headForHeaders(properties.getBaseUrl() + "/openapi.json");
            return true;
        } catch (Exception ex) {
            try {
                restTemplate.headForHeaders(properties.getBaseUrl() + "/api/v1/route");
                return true;
            } catch (Exception ex2) {
                log.warn("[healthCheck] 算法服务不可达 baseUrl={} : {}", properties.getBaseUrl(), ex2.getMessage());
                return false;
            }
        }
    }

    /** 动态插单调度：POST /api/v1/dispatch/allocate（DISPATCH_CORE_V047）。 */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> allocate(java.util.Map<String, Object> payload) {
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                var response = restTemplate.postForEntity("/api/v1/dispatch/allocate", payload, java.util.Map.class);
                return response.getBody();
            } catch (org.springframework.web.client.HttpServerErrorException ex) {
                int status = ex.getStatusCode().value();
                if (status == 502 || status == 503 || status == 504) {
                    log.warn("[allocate][第 {} 次可重试 status={}]", attempt + 1, status);
                } else {
                    log.warn("[allocate][失败 status={} body={}]", status, ex.getResponseBodyAsString());
                    throw ex;
                }
            } catch (Exception ex) {
                log.warn("[allocate][网络错误 第 {} 次: {}]", attempt + 1, ex.getMessage());
            }
            sleep(properties.getRetryBackoff().toMillis());
        }
        throw new ServiceException("算法动态调度服务暂不可用");
    }

"""
    assert needle in t, "distance method not found"
    t = t.replace(needle, insert + needle, 1)
    if "import cn.hutool.core.util" not in t and "ServiceException" in insert:
        t = t.replace(
            "import lombok.SneakyThrows;",
            "import cn.iocoder.yudao.framework.common.exception.ServiceException;\nimport lombok.SneakyThrows;",
            1,
        )
    p.write_text(t, encoding="utf-8")
    print("AlgorithmClient patched")
else:
    print("AlgorithmClient already has healthCheck")

# ---- DispatchController allocate endpoint ----
pc = root / "controller/admin/dispatch/DispatchController.java"
tc = pc.read_text(encoding="utf-8")
if "/allocate" not in tc:
    needle = '    @PostMapping("/plan/manual")'
    insert = """    @PostMapping("/allocate")
    @Operation(summary = "动态插单调度（新订单 → Candidate/Reachability/TripLock/MarginalCost → DispatchPlan）")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<java.util.Map<String, Object>> allocateOrder(@RequestBody java.util.Map<String, Object> payload) {
        return success(dispatchService.allocateDynamic(payload));
    }

"""
    assert needle in tc
    tc = tc.replace(needle, insert + needle, 1)
    pc.write_text(tc, encoding="utf-8")
    print("DispatchController patched")
else:
    print("DispatchController already has allocate")

# ---- DispatchService interface ----
ps = root / "service/dispatch/DispatchService.java"
ts = ps.read_text(encoding="utf-8")
if "allocateDynamic" not in ts:
    # add before last closing brace
    idx = ts.rstrip().rfind("}")
    method = """
    /** 动态插单：委托算法 /api/v1/dispatch/allocate（DISPATCH_CORE_V047）。 */
    java.util.Map<String, Object> allocateDynamic(java.util.Map<String, Object> payload);
"""
    ts = ts[:idx] + method + ts[idx:]
    ps.write_text(ts, encoding="utf-8")
    print("DispatchService iface patched")
else:
    print("DispatchService iface already")

# ---- DispatchServiceImpl allocateDynamic ----
pi = root / "service/dispatch/DispatchServiceImpl.java"
ti = pi.read_text(encoding="utf-8")
if "allocateDynamic" not in ti:
    idx = ti.rstrip().rfind("}")
    method = """
    @Override
    public java.util.Map<String, Object> allocateDynamic(java.util.Map<String, Object> payload) {
        // 前端不直连算法；此处唯一出口。失败由 AlgorithmClient 重试/冷却语义处理。
        return algorithmAdapter.allocateRaw(payload);
    }
"""
    ti = ti[:idx] + method + ti[idx:]
    pi.write_text(ti, encoding="utf-8")
    print("DispatchServiceImpl allocateDynamic added")
else:
    print("DispatchServiceImpl already")

# ---- AlgorithmAdapter.allocateRaw ----
pa = root / "integration/algorithm/AlgorithmAdapter.java"
ta = pa.read_text(encoding="utf-8")
if "allocateRaw" not in ta:
    idx = ta.rstrip().rfind("}")
    method = """
    /** 动态调度透传（不做 plan 级幂等快照；由算法侧 requestId 幂等）。 */
    public java.util.Map<String, Object> allocateRaw(java.util.Map<String, Object> payload) {
        return algorithmClient.allocate(payload);
    }

    /** 启动自检：算法服务连通性。 */
    public boolean healthCheck() {
        return algorithmClient.healthCheck();
    }
"""
    ta = ta[:idx] + method + ta[idx:]
    pa.write_text(ta, encoding="utf-8")
    print("AlgorithmAdapter allocateRaw added")
else:
    print("AlgorithmAdapter already")

print("ALL_OK")
