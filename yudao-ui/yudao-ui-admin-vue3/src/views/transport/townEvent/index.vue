<template>
  <ContentWrap title="乡镇事件日历">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="乡镇 / 场镇">
          <el-input v-model="queryParams.town" placeholder="如 双龙镇" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="queryParams.eventType" placeholder="全部" clearable style="width:140px">
            <el-option v-for="t in EVENT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="看这天起">
          <el-date-picker v-model="queryParams.from" type="date" value-format="YYYY-MM-DD" placeholder="不限"
            clearable style="width:160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>

    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:town-event:save']" @click="openForm('create')">
        <Icon icon="ep:plus" />登记事件
      </el-button>
      <el-alert
        v-if="listError"
        type="error"
        :closable="false"
        show-icon
        :title="listError"
        description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
      />
      <el-alert v-if="formError" type="warning" :closable="false" show-icon :title="formError" class="mb-8px" />
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="乡镇 / 场镇" prop="town" align="center" min-width="120" />
        <el-table-column label="类型" align="center" width="100">
          <template #default="scope">{{ typeText(scope.row.eventType) }}</template>
        </el-table-column>
        <el-table-column label="起止" align="center" width="200">
          <template #default="scope">{{ scope.row.startDate }} ~ {{ scope.row.endDate }}</template>
        </el-table-column>
        <el-table-column label="周期" align="center" width="150">
          <!-- 这里刻意不写成"每天/每几天"的推测：recurrence 是登记进去的规则，页面只照读 -->
          <template #default="scope">
            <el-tag v-if="scope.row.recurrence === 'EVERY_N_DAYS'" size="small" type="warning">
              每 {{ scope.row.intervalDays ?? '没填' }} 天一轮
            </el-tag>
            <span v-else>单次</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" align="center" min-width="160">
          <template #default="scope">
            <span v-if="scope.row.note">{{ scope.row.note }}</span>
            <span v-else class="text-gray-400">没填</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:town-event:save']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:town-event:delete']" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :image-size="60" description="还没登记过乡镇事件。没有记录不代表这些镇没有场期 —— 这张表要靠运营填。">
            <el-button type="primary" v-hasPermi="['transport:town-event:save']" @click="openForm('create')">登记第一条</el-button>
          </el-empty>
        </template>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <!-- 生效日那一格：这一列才是"赶集提前留舱"真正要读的东西，周期展开由后端做（GET /effective-on）。
         ⚠ 这里刻意**不用 el-form**：判据 `check_admin_query_page_reset.py` 认的是"表单提交"这条回车路径
         （浏览器里回车首先触发的是原生 form 提交），而这一格是另一份独立数据、没有分页对象。
         套上 el-form 就等于凭空长出第三条会带旧页码发请求的路径 —— 要修的是形状，不是把判据改窄。 -->
    <ContentWrap title="某一天生效的事件">
      <div class="flex items-center gap-12px mb-12px">
        <span>日期</span>
        <el-date-picker v-model="effectiveDay" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:160px" />
        <el-button type="primary" :loading="effectiveLoading" @click="loadEffective">查这一天</el-button>
      </div>
      <el-alert v-if="effectiveError" type="error" :closable="false" show-icon :title="effectiveError" />
      <el-table v-else v-loading="effectiveLoading" :data="effectiveList" stripe border size="small">
        <el-table-column label="乡镇" prop="town" align="center" />
        <el-table-column label="类型" align="center">
          <template #default="scope">{{ typeText(scope.row.eventType) }}</template>
        </el-table-column>
        <el-table-column label="规则来源" align="center">
          <template #default="scope">
            {{ scope.row.recurrence === 'EVERY_N_DAYS' ? `每 ${scope.row.intervalDays ?? '没填'} 天那一轮` : '登记的那一天' }}
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :image-size="48"
            :description="`这一天没有读到生效事件${effectiveDay ? `（${effectiveDay}）` : ''}。`"
          >
            <div class="text-gray-500">
              空表有两种可能：<b>日历上确实没登记</b>，或<b>这一天落在周期规则之外</b>。
              平台不替这一格下结论 —— 请把上面那条规则当"待核对"，不要当"这个镇没集"。
            </div>
          </el-empty>
        </template>
      </el-table>
    </ContentWrap>
  </ContentWrap>

  <el-dialog v-model="formVisible" :title="formTitle" width="560px">
    <el-form :model="form" label-width="120px" @submit.prevent="submitForm">
      <el-form-item label="乡镇 / 场镇" required>
        <el-input v-model="form.town" placeholder="如 双龙镇（本期是自由文本）" />
      </el-form-item>
      <el-form-item label="类型" required>
        <el-select v-model="form.eventType" placeholder="选一项" style="width:100%">
          <el-option v-for="t in EVENT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="起始日" required>
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </el-form-item>
      <el-form-item label="结束日（含）" required>
        <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        <div class="text-gray-500">单日事件就填同一天。农忙类通常要圈住一季。</div>
      </el-form-item>
      <el-form-item label="重复规则">
        <el-select v-model="form.recurrence" style="width:100%">
          <el-option label="单次（只占起止那几天）" value="NONE" />
          <el-option label="每 N 天一轮（赶集日）" value="EVERY_N_DAYS" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.recurrence === 'EVERY_N_DAYS'" label="间隔天数" required>
        <el-input-number v-model="form.intervalDays" :min="2" :max="31" />
        <div class="text-gray-500">逢 1/4/7 或 2/5/8 这类场期，间隔就是 3。</div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.note" type="textarea" :rows="2" maxlength="255" placeholder="哪条路、哪个市场口（留空就念「没填」）" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formVisible = false">取消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import * as TownEventApi from '@/api/transport/townEvent'

defineOptions({ name: 'TransportTownEvent' })

const message = useMessage()

/** 类型口径与后端 TownEventTypeEnum 同名（MARKET/FARMING/HOLIDAY/EVENT）—— 自造码会让筛错且不报错 */
const EVENT_TYPES = [
  { value: 'MARKET', label: '赶集' },
  { value: 'FARMING', label: '农忙' },
  { value: 'HOLIDAY', label: '节假日' },
  { value: 'EVENT', label: '活动' }
]
const typeText = (t?: string) => EVENT_TYPES.find((x) => x.value === t)?.label ?? (t || '没填')

const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<TownEventApi.TownEventVO[]>([])

interface TownEventQuery {
  pageNo: number
  pageSize: number
  town?: string
  eventType?: string
  from?: string
}
const queryParams = reactive<TownEventQuery>({
  pageNo: 1,
  pageSize: 10,
  town: '',
  eventType: undefined,
  from: undefined
})

const getList = async () => {
  loading.value = true
  try {
    const res = await TownEventApi.getTownEventPage(queryParams)
    list.value = res.list
    total.value = res.total
    listError.value = ''
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    // （判据 scripts/check_admin_catch_leaves_data.py）
    list.value = []
    total.value = 0
    listError.value = '日历没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有事件」）')
  } finally {
    loading.value = false
  }
}
// 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}
const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, town: '', eventType: undefined, from: undefined })
  getList()
}

const effectiveDay = ref(new Date().toISOString().slice(0, 10))
const effectiveLoading = ref(false)
const effectiveError = ref('')
const effectiveList = ref<TownEventApi.TownEventVO[]>([])

const loadEffective = async () => {
  if (!effectiveDay.value) {
    // 日期被清空时不发请求：后端这个口 day 是必填，缺参数的 400 会被读成"这一天没事件"
    effectiveError.value = '先选一天再查（这不是「这一天没有生效事件」）'
    effectiveList.value = []
    return
  }
  effectiveLoading.value = true
  try {
    effectiveList.value = (await TownEventApi.getEffectiveTownEvents(effectiveDay.value)) || []
    effectiveError.value = ''
  } catch (e: any) {
    effectiveList.value = []
    effectiveError.value = '这一天没读到：' + (e?.msg || e?.message || '未知原因（空表不等于没有事件）')
  } finally {
    effectiveLoading.value = false
  }
}

const formVisible = ref(false)
const formLoading = ref(false)
/** 编辑回填失败要留一条**看得见的**字：只关对话框 + 一闪而过的 toast，用户会读成"这条不存在"或以为已经改过了 */
const formError = ref('')
const formTitle = ref('登记乡镇事件')
const formType = ref('create')
const form = reactive<TownEventApi.TownEventVO>({
  id: undefined,
  town: '',
  eventType: 'MARKET',
  startDate: '',
  endDate: '',
  recurrence: 'NONE',
  intervalDays: 3,
  note: ''
})

const openForm = async (type: string, id?: number) => {
  formVisible.value = true
  formError.value = ''
  formType.value = type
  formTitle.value = type === 'create' ? '登记乡镇事件' : '编辑这条日历记录'
  if (type === 'update' && id) {
    try {
      Object.assign(form, await TownEventApi.getTownEvent(id))
    } catch (e: any) {
      // 读不回来就**关掉对话框并留一条字**：留一张空表单让用户填，保存下去会把原有那条抹成空值
      formVisible.value = false
      formError.value =
        '这条记录没读回来：' + (e?.msg || e?.message || '未知原因') + ' —— 本次没有做任何修改（不是"这条不存在"）'
      return
    }
  } else {
    Object.assign(form, {
      id: undefined,
      town: '',
      eventType: 'MARKET',
      startDate: '',
      endDate: '',
      recurrence: 'NONE',
      intervalDays: 3,
      note: ''
    })
  }
}

const submitForm = async () => {
  if (!form.town || !form.startDate || !form.endDate) {
    message.warning('乡镇名与起止日都要填（后端会拒，这里先拦住，别把"提交失败"念成"存好了"）')
    return
  }
  if (form.recurrence === 'EVERY_N_DAYS' && !form.intervalDays) {
    message.warning('选了「每 N 天一轮」就要给天数')
    return
  }
  formLoading.value = true
  try {
    if (formType.value === 'create') {
      await TownEventApi.createTownEvent(form)
      message.success('已登记')
    } else {
      await TownEventApi.updateTownEvent(form)
      message.success('已保存')
    }
    formVisible.value = false
    // 保存成功后回第 1 页再刷：新登记的那条按创建时间排在前面，停在第 N 页会让人读成"存了但没出现"
    queryParams.pageNo = 1
    getList()
    loadEffective()
  } catch (e: any) {
    // 保存失败**不许关对话框、也不许报成功**：留着让用户改，并把后端那句原话贴出来
    message.error((formType.value === 'create' ? '登记没成功：' : '保存没成功：') + (e?.msg || e?.message || '未知原因（这条还没存进去）'))
  } finally {
    formLoading.value = false
  }
}

const handleDelete = async (row: TownEventApi.TownEventVO) => {
  try {
    await message.confirm(
      `确认删除「${row.town} · ${typeText(row.eventType)}（${row.startDate} ~ ${row.endDate}）」？` +
        '删掉以后，依赖这张日历的情景提示就不会再提示这一条。'
    )
  } catch {
    return // 用户取消：不做任何事，也不谎称已删
  }
  try {
    await TownEventApi.deleteTownEvent(row.id as number)
    message.success('已删除')
    getList()
    loadEffective()
  } catch (e: any) {
    message.error('删除没成功：' + (e?.msg || e?.message || '未知原因（这一条还在表里）'))
  }
}

onMounted(() => {
  getList()
  loadEffective()
})
</script>
