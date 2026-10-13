<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="480px">
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="100px"
      v-loading="formLoading"
    >
      <el-form-item label="车牌号" prop="plateNo">
        <el-input v-model="formData.plateNo" placeholder="请输入车牌号" />
      </el-form-item>
      <el-form-item label="车辆类型" prop="vehicleType">
        <el-input-number v-model="formData.vehicleType" :min="0" style="width: 100%" />
      </el-form-item>
      <el-form-item label="载客人数" prop="passengerCapacity">
        <el-input-number
          v-model="formData.passengerCapacity"
          :min="1"
          style="width: 100%"
          placeholder="核定载客数（必填）"
        />
        <div class="form-tip"
          >按核定证件上的载客数填。留空或填 0
          的车辆不会参与智能派单，乘客每站必停的运力也就落空。</div
        >
      </el-form-item>
      <el-form-item label="载货重量(kg)" prop="cargoCapacityKg">
        <el-input-number
          v-model="formData.cargoCapacityKg"
          :min="0"
          :precision="2"
          style="width: 100%"
          placeholder="留空 = 未核定"
        />
        <div class="form-tip"
          >未核定时留空：派单只按货仓件数卡容量，不会替这辆车编一个载重上限。</div
        >
      </el-form-item>
      <el-form-item label="货仓件数" prop="cargoCapacity">
        <el-input-number
          v-model="formData.cargoCapacity"
          :min="1"
          style="width: 100%"
          placeholder="货仓件数上限"
        />
      </el-form-item>
      <el-form-item label="保险到期日" prop="insuranceExpireDate">
        <el-date-picker
          v-model="formData.insuranceExpireDate"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 100%"
          placeholder="请选择保险到期日"
        />
      </el-form-item>
      <el-form-item label="车辆状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio :value="0">可用</el-radio>
          <el-radio :value="1">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as VehicleApi from '@/api/transport/vehicle'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])

const formData = ref<VehicleApi.VehicleVO>({
  plateNo: '',
  vehicleType: undefined,
  passengerCapacity: undefined,
  cargoCapacityKg: undefined,
  cargoCapacity: 4,
  insuranceExpireDate: undefined,
  status: 0
})

// 民用车牌：省份简称 + 发牌机关字母 + 5 位序号；新能源为 6 位序号
const PLATE_PATTERN =
  /^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-HJ-NP-Z0-9]{5,6}$/
/**
 * 表单只管这 7 个字段（`realtimeStatus` 不在内 —— 它由后端随运输段开始/完成同步，不是人填的）。
 * 旧写法是 `formData.value = 整条 GET 记录` 再 `PUT 整条`，于是表单里根本不显示的
 * `realtimeStatus` 也会被回写成"打开表单那一刻"的旧值：并发下司机已经点发车（0→1 在途），
 * 这边保存一次就把状态改回空闲。后端 updateById 忽略 null ⇒ 这里"少带"就是"不改"，
 * 比"带错的回去"安全（同族形状见 DECISION_LOG D-090：后台编辑订单抹掉承运商/件数）。
 */
const MANAGED_FIELDS = ['plateNo', 'vehicleType', 'passengerCapacity', 'cargoCapacityKg',
  'cargoCapacity', 'insuranceExpireDate', 'status'] as const

const pickManaged = (o: any): VehicleApi.VehicleVO => {
  const out: Record<string, any> = {}
  if (o?.id !== undefined) out.id = o.id
  for (const k of MANAGED_FIELDS) if (o?.[k] !== undefined) out[k] = o[k]
  // 这里断言是安全的：plateNo 由表单必填项保证；realtimeStatus 被我们**故意**摘掉
  return out as VehicleApi.VehicleVO
}

const formRules = reactive({
  plateNo: [
    { required: true, message: '车牌号不能为空', trigger: 'blur' },
    {
      pattern: PLATE_PATTERN,
      message: '请输入正确的车牌号（如 渝A12345 / 渝AD12345）',
      trigger: 'blur'
    }
  ],
  // 载货重量：留空/0 都是"未核定"，后端下发时会折算成"不传该约束"，所以只挡负数不强制填
  cargoCapacityKg: [
    { type: 'number', min: 0, message: '载货重量需为不小于 0 的数字', trigger: ['blur', 'change'] }
  ],
  cargoCapacity: [
    { required: true, message: '货仓件数不能为空', trigger: 'blur' },
    { type: 'number', min: 1, message: '货仓件数至少为 1', trigger: 'blur' }
  ],
  // 与后端 @NotNull @Min(1)（VehicleBaseVO）同一口径：0 座的车到了派单侧会被整台剔除，
  // 在这儿挡住，运营拿到的就是"哪个字段没填"而不是派单时一条指向别人车牌的错误。
  passengerCapacity: [
    {
      required: true,
      type: 'number',
      min: 1,
      message: '请填写核定载客数（至少 1 座；未填写的车辆不参与智能派单）',
      trigger: ['blur', 'change']
    }
  ]
})

const resetForm = () => {
  formData.value = {
    plateNo: '',
    vehicleType: undefined,
    passengerCapacity: undefined,
    cargoCapacityKg: undefined,
    cargoCapacity: 4,
    insuranceExpireDate: undefined,
    status: 0
  }
  formRef.value?.resetFields()
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增车辆' : '编辑车辆'
  formType.value = type
  resetForm()
  if (id) {
    formLoading.value = true
    VehicleApi.getVehicle(id).then((data) => {
      formData.value = pickManaged(data)
      formLoading.value = false
    })
  }
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    if (formType.value === 'create') await VehicleApi.createVehicle(pickManaged(formData.value))
    else await VehicleApi.updateVehicle(pickManaged(formData.value))
    message.success(formType.value === 'create' ? '创建成功' : '更新成功')
    dialogVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.form-tip {
  width: 100%;
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
