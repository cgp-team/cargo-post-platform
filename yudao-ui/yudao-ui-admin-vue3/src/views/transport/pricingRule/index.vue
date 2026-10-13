<template>
  <ContentWrap>
    <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="`计价规则没读到：${loadError}`"
              description="下面这张表是空的，不是"运价为 0"。这一格只说明这次请求没成功，别按空表判断业务。" />
    <el-alert v-else-if="loaded && !form.configured" type="warning" :closable="false" show-icon
              title="库里还没有计价规则配置行，下面显示的是服务侧兜底默认值"
              description="算法侧的收入/成本估算与 ETA 现在就是按这几个默认数走的（契约 Q13：定价只在业务后端）。保存后会新增一行使其生效；不保存不代表"没有定价"。" />
    <el-alert v-else-if="loaded" type="success" :closable="false" show-icon
              title="下面显示的是库里已配置的规则" />

    <el-form v-if="loaded" ref="formRef" :model="form" :rules="rules" label-width="180px" class="mt-16px" v-loading="loading">
      <el-form-item label="客运人公里单价(元)" prop="passengerPricePerKm">
        <el-input-number v-model="form.passengerPricePerKm" :min="0.01" :precision="2" :step="0.1" style="width: 220px" />
      </el-form-item>
      <el-form-item label="货运件单价(元)" prop="cargoPricePerItem">
        <el-input-number v-model="form.cargoPricePerItem" :min="0.01" :precision="2" :step="1" style="width: 220px" />
      </el-form-item>
      <el-form-item label="邮快件件单价(元)" prop="postalPricePerItem">
        <el-input-number v-model="form.postalPricePerItem" :min="0.01" :precision="2" :step="0.5" style="width: 220px" />
      </el-form-item>
      <el-form-item label="车辆公里成本(元)" prop="vehicleCostPerKm">
        <el-input-number v-model="form.vehicleCostPerKm" :min="0" :precision="2" :step="0.1" style="width: 220px" />
      </el-form-item>
      <el-form-item label="班线平均时速(km/h)" prop="avgSpeedKmh">
        <el-input-number v-model="form.avgSpeedKmh" :min="0.1" :precision="1" :step="1" style="width: 220px" />
        <div class="text-12px text-gray-500 mt-4px">ETA 与"耗时"口径都走这个数；填大等于把所有到站时间提前。</div>
      </el-form-item>
      <el-form-item label="作业站停站分钟" prop="stopServiceMinutes">
        <el-input-number v-model="form.stopServiceMinutes" :min="0" :max="180" :step="1" style="width: 220px" />
        <div class="text-12px text-gray-500 mt-4px">接/送/派/揽每站都按这一分钟数计，多段联运会逐站累加。</div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit" v-hasPermi="['transport:pricing-rule:save']">保 存</el-button>
        <el-button @click="getList" :loading="loading">重 读</el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as PricingRuleApi from '@/api/transport/pricingRule'

defineOptions({ name: 'TransportPricingRule' })

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const loadError = ref('')
const formRef = ref()

const form = reactive<PricingRuleApi.PricingRuleVO>({
  id: undefined,
  passengerPricePerKm: undefined,
  cargoPricePerItem: undefined,
  postalPricePerItem: undefined,
  vehicleCostPerKm: undefined,
  avgSpeedKmh: undefined,
  stopServiceMinutes: undefined,
  configured: false
})

const rules = reactive({
  passengerPricePerKm: [{ required: true, message: '客运人公里单价不能为空', trigger: 'blur' }],
  cargoPricePerItem: [{ required: true, message: '货运件单价不能为空', trigger: 'blur' }],
  postalPricePerItem: [{ required: true, message: '邮快件件单价不能为空', trigger: 'blur' }],
  vehicleCostPerKm: [{ required: true, message: '车辆公里成本不能为空', trigger: 'blur' }],
  avgSpeedKmh: [{ required: true, message: '平均时速不能为空', trigger: 'blur' }],
  stopServiceMinutes: [{ required: true, message: '停站分钟不能为空', trigger: 'blur' }]
})

const getList = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const data = await PricingRuleApi.getPricingRule()
    Object.assign(form, data)
    loaded.value = true
  } catch (e: any) {
    // 读失败不许塌成"表是空的"：那会被读成"运价没配"，而真实情况是这次请求没成功
    loaded.value = false
    loadError.value = e?.msg || e?.message || '未知原因（后端没给出原因）'
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  // 这一页改的是钱与时效的口径：算法侧收入/成本估算、ETA、超时判定都会跟着动，所以保存前必须二次确认
  await message.confirm('计价规则一改，方案收入/成本估算、到站 ETA 与超时判定都会按新数走，且对之后的调度生效。确认保存？')
  saving.value = true
  try {
    await PricingRuleApi.savePricingRule({ ...form })
    message.success('保存成功')
    await getList() // 回读一次：让 configured 旗标从"默认值"翻成"库里已配置"，界面与库一致
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  getList()
})
</script>
