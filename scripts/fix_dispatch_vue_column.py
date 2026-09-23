from pathlib import Path

p = Path(r"yudao-ui/yudao-ui-admin-vue3/src/views/transport/dispatch/index.vue")
t = p.read_text(encoding="utf-8")

# 1) remove misplaced column from pool table
bad = """                    <el-table-column label="里程口径" min-width="100">
              <template #default="scope">
                <el-tag :type="scope.row.routeProvider === 'FORMAL_ROAD' ? 'success' : 'warning'" size="small">
                  {{ routeProviderLabel(scope.row.routeProvider) }}
                </el-tag>
              </template>
            </el-table-column>
<el-table-column type="selection" width="50" align="center" :selectable="poolSelectable" />"""
good = """        <el-table-column type="selection" width="50" align="center" :selectable="poolSelectable" />"""
if bad in t:
    t = t.replace(bad, good, 1)
    print("removed pool column")
else:
    print("pool block not exact, try alt")

# 2) insert into plan table after totalDistance column
old = """        <el-table-column label="总里程(km)" prop="totalDistance" align="center">
          <template #default="scope">{{ totalDistanceText(scope.row.totalDistance) }}</template>
        </el-table-column>"""
new = """        <el-table-column label="总里程(km)" prop="totalDistance" align="center">
          <template #default="scope">{{ totalDistanceText(scope.row.totalDistance) }}</template>
        </el-table-column>
        <el-table-column label="里程口径" min-width="100">
          <template #default="scope">
            <el-tag :type="scope.row.routeProvider === 'FORMAL_ROAD' ? 'success' : 'warning'" size="small">
              {{ routeProviderLabel(scope.row.routeProvider) }}
            </el-tag>
          </template>
        </el-table-column>"""
if old in t and "里程口径" not in t.split("总里程(km)")[1][:400]:
    t = t.replace(old, new, 1)
    print("inserted plan column")
else:
    print("plan insert skip", "里程口径" in t)

p.write_text(t, encoding="utf-8")
print("done")
