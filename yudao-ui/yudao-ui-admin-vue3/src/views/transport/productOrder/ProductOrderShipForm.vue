<template>
  <Dialog title="订单发货" v-model="dialogVisible" width="480px">
    <el-form ref="formRef" :model="formData" label-width="100px" v-loading="formLoading">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="发货即派单给司机"
        description="选定车辆/班次后：司机端出现「待装车」任务（含商城订单），司机装车拍照 → 到站 → 妥投；交付站点=班次线路终点站。"
        style="margin-bottom:12px"
      />
      <el-form-item label="承运车辆" prop="vehicleId" :rules="[{ required: true, message: '请选择承运车辆', trigger: 'change' }]">
        <el-select v-model="formData.vehicleId" placeholder="请选择承运车辆（司机端按人车绑定归属）" filterable style="width:100%">
          <el-option
            v-for="v in vehicleOptions"
            :key="v.id!"
            :label="v.driverName ? `${v.plateNo}（司机 ${v.driverName}）` : `${v.plateNo}（未绑定司机）`"
            :value="v.id!"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="承运班次" prop="shiftId" :rules="[{ required: true, message: '请选择承运班次', trigger: 'change' }]">
        <el-select v-model="formData.shiftId" placeholder="请选择承运班次（决定交付站点）" filterable style="width:100%">
          <el-option v-for="s in shiftOptions" :key="s.id!" :label="shiftLabel(s)" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="交付站点">
        <el-select
          v-model="formData.deliverStationId"
          placeholder="不选则默认班次线路终点站"
          filterable
          clearable
          style="width:100%"
        >
          <el-option
            v-for="s in stationOptions"
            :key="s.id!"
            :label="s.stationName"
            :value="s.id!"
          />
        </el-select>
        <div class="ship-tip">
          快递/包裹的实际交付站点（如「四公里交通换乘枢纽站」集散中心、收件地址最近的村级站「重邮南门货运站」）；
          不选则默认在班次线路终点站交付。
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确认发货</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as ProductOrderApi from '@/api/transport/productOrder'
import * as VehicleApi from '@/api/transport/vehicle'
import * as ShiftApi from '@/api/transport/shift'
import * as StationApi from '@/api/transport/station'
import { Dialog } from '@/components/Dialog'
import { useStationDictStore } from '@/store/modules/stationDict'

const message = useMessage()
const stationDict = useStationDictStore()
const formLoading = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const emit = defineEmits(['success'])

const formData = ref<ProductOrderApi.ProductOrderShipReqVO>({
  id: 0,
  vehicleId: undefined,
  shiftId: undefined,
})

const vehicleOptions = ref<VehicleApi.VehicleVO[]>([])
const shiftOptions = ref<ShiftApi.ShiftVO[]>([])
const stationOptions = ref<StationApi.StationVO[]>([])

const shiftLabel = (s: ShiftApi.ShiftVO) =>
  s.plannedDepartureTime ? `${s.shiftCode} ${s.plannedDepartureTime}` : s.shiftCode

const loadOptions = async () => {
  // 三个字典彼此独立：串成链等于三个 RTT 之和，而且第一个抛错时后面两个根本不执行
  // —— 那一格就永远空着，读起来像"没有可选的车辆/班次"。并发跑，失败的那一格留空，
  // 错误仍由请求层提示，不替后端宣布"没有"。
  const [veh, shift, station] = await Promise.allSettled([
    VehicleApi.getSimpleVehicleList(),
    ShiftApi.getSimpleShiftList(),
    stationDict.load()
  ])
  if (veh.status === 'fulfilled') vehicleOptions.value = veh.value
  if (shift.status === 'fulfilled') shiftOptions.value = shift.value
  if (station.status === 'fulfilled') stationOptions.value = station.value
}

const open = (id: number) => {
  dialogVisible.value = true
  formData.value = { id, vehicleId: undefined, shiftId: undefined, deliverStationId: undefined }
  formRef.value?.resetFields()
  loadOptions()
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  formLoading.value = true
  try {
    await ProductOrderApi.shipProductOrder(formData.value)
    message.success('发货成功')
    dialogVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.ship-tip {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
