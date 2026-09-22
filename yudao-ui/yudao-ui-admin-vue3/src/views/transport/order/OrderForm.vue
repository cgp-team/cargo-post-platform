<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="650px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" v-loading="formLoading">
      <el-form-item label="订单类型" prop="orderType">
        <el-select v-model="formData.orderType" placeholder="请选择订单类型" style="width:100%" @change="onOrderTypeChange">
          <el-option label="客运" :value="1" />
          <el-option label="货运" :value="2" />
          <el-option label="邮快件" :value="3" />
        </el-select>
      </el-form-item>
      <el-form-item label="取货站点" prop="pickupStationId">
        <el-select v-model="formData.pickupStationId" placeholder="请选择取货站点" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="送达站点" prop="deliveryStationId">
        <el-select v-model="formData.deliveryStationId" placeholder="请选择送达站点" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="最早取货时间" prop="earliestPickupTime">
        <el-date-picker v-model="formData.earliestPickupTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
      </el-form-item>
      <el-form-item label="最迟送达时间" prop="latestDeliveryTime">
        <el-date-picker v-model="formData.latestDeliveryTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
      </el-form-item>
      <el-form-item label="订单金额" prop="totalAmount">
        <el-input v-model.number="formData.totalAmount" placeholder="请输入订单金额" />
      </el-form-item>

      <!-- 客运子表 -->
      <template v-if="formData.orderType === 1">
        <el-divider content-position="left">客运信息</el-divider>
        <el-form-item label="乘客人数" prop="passengerCount">
          <el-input-number v-model="formData.passengerCount" :min="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactName">
          <el-input v-model="formData.contactName" placeholder="请输入联系人" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactMobile">
          <el-input v-model="formData.contactMobile" placeholder="请输入联系电话" />
        </el-form-item>
      </template>

      <!-- 货运子表 -->
      <template v-if="formData.orderType === 2">
        <el-divider content-position="left">货运信息</el-divider>
        <el-form-item label="货物类别" prop="cargoCategory">
          <el-input v-model="formData.cargoCategory" placeholder="请输入货物类别" />
        </el-form-item>
        <el-form-item label="是否生鲜" prop="freshFlag">
          <el-switch v-model="formData.freshFlag" />
        </el-form-item>
        <el-form-item label="件数" prop="cargoItemCount">
          <el-input-number v-model="formData.cargoItemCount" :min="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="重量(kg)" prop="cargoWeightKg">
          <el-input v-model.number="formData.cargoWeightKg" placeholder="请输入重量(kg)" />
        </el-form-item>
        <el-form-item label="体积(m³)" prop="cargoVolumeM3">
          <el-input v-model.number="formData.cargoVolumeM3" placeholder="请输入体积(m³)" />
        </el-form-item>
      </template>

      <!-- 邮快件子表 -->
      <template v-if="formData.orderType === 3">
        <el-divider content-position="left">邮快件信息</el-divider>
        <el-form-item label="快递单号" prop="mailNo">
          <el-input v-model="formData.mailNo" placeholder="请输入快递单号" />
        </el-form-item>
        <el-form-item label="承运商" prop="carrierCode">
          <el-input v-model="formData.carrierCode" placeholder="请输入承运商编码" />
        </el-form-item>
        <el-form-item label="件数" prop="postalItemCount">
          <el-input-number v-model="formData.postalItemCount" :min="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="重量(kg)" prop="postalWeightKg">
          <el-input v-model.number="formData.postalWeightKg" placeholder="请输入重量(kg)" />
        </el-form-item>
        <el-form-item label="收件人" prop="receiverName">
          <el-input v-model="formData.receiverName" placeholder="请输入收件人姓名" />
        </el-form-item>
        <el-form-item label="收件电话" prop="receiverMobile">
          <el-input v-model="formData.receiverMobile" placeholder="请输入收件电话" />
        </el-form-item>
        <el-form-item label="收件地址" prop="receiverAddress">
          <el-input v-model="formData.receiverAddress" placeholder="请输入收件地址" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as OrderApi from '@/api/transport/order'
import * as StationApi from '@/api/transport/station'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])
const stationList = ref<StationApi.StationVO[]>([])

const formData = ref<any>({
  orderNo: '',
  orderType: null,
  pickupStationId: null,
  deliveryStationId: null,
  earliestPickupTime: null,
  latestDeliveryTime: null,
  totalAmount: null,
  passengerCount: null,
  contactName: '',
  contactMobile: '',
  cargoCategory: '',
  freshFlag: false,
  cargoItemCount: null,
  cargoWeightKg: null,
  cargoVolumeM3: null,
  mailNo: '',
  carrierCode: '',
  postalItemCount: null,
  postalWeightKg: null,
  receiverName: '',
  receiverMobile: '',
  receiverAddress: '',
})

const MOBILE_PATTERN = /^1[3-9]\d{9}$/

// 自环订单（取=送）在算法侧会产生空运输段，前端直接拦住更省事
const validateDeliveryStation = (_rule: any, value: any, callback: any) => {
  if (value != null && value === formData.value.pickupStationId) {
    return callback(new Error('送达站点不能与取货站点相同'))
  }
  callback()
}
// 时间窗倒挂（最早取货晚于最迟送达）在算法侧是无解输入，前端先挡一道
const validateTimeWindow = (_rule: any, value: any, callback: any) => {
  const earliest = formData.value.earliestPickupTime
  if (earliest && value && new Date(value).getTime() < new Date(earliest).getTime()) {
    return callback(new Error('最迟送达时间不能早于最早取货时间'))
  }
  callback()
}

const formRules = reactive({
  orderType: [{ required: true, message: '请选择订单类型', trigger: 'change' }],
  pickupStationId: [{ required: true, message: '请选择取货站点', trigger: 'change' }],
  deliveryStationId: [
    { required: true, message: '请选择送达站点', trigger: 'change' },
    { validator: validateDeliveryStation, trigger: 'change' }
  ],
  latestDeliveryTime: [{ validator: validateTimeWindow, trigger: 'change' }],
  totalAmount: [{ type: 'number', min: 0, message: '订单金额需为不小于 0 的数字', trigger: 'blur' }],
  passengerCount: [{ type: 'number', min: 1, message: '乘客人数至少为 1', trigger: 'blur' }],
  contactMobile: [{ pattern: MOBILE_PATTERN, message: '请输入正确的 11 位手机号', trigger: 'blur' }],
  receiverMobile: [{ pattern: MOBILE_PATTERN, message: '请输入正确的 11 位手机号', trigger: 'blur' }],
  cargoItemCount: [{ type: 'number', min: 1, message: '件数至少为 1', trigger: 'blur' }],
  cargoWeightKg: [{ type: 'number', min: 0, message: '重量需为不小于 0 的数字', trigger: 'blur' }],
  cargoVolumeM3: [{ type: 'number', min: 0, message: '体积需为不小于 0 的数字', trigger: 'blur' }],
  postalItemCount: [{ type: 'number', min: 1, message: '件数至少为 1', trigger: 'blur' }],
  postalWeightKg: [{ type: 'number', min: 0, message: '重量需为不小于 0 的数字', trigger: 'blur' }],
})

const resetForm = () => {
  formData.value = {
    orderNo: '', orderType: null, pickupStationId: null, deliveryStationId: null,
    earliestPickupTime: null, latestDeliveryTime: null, totalAmount: null,
    passengerCount: null, contactName: '', contactMobile: '',
    cargoCategory: '', freshFlag: false, cargoItemCount: null, cargoWeightKg: null, cargoVolumeM3: null,
    mailNo: '', carrierCode: '', postalItemCount: null, postalWeightKg: null,
    receiverName: '', receiverMobile: '', receiverAddress: '',
  }
  formRef.value?.resetFields()
}

const onOrderTypeChange = () => {
  // Clear sub-form data when order type changes
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增订单' : '编辑订单'
  formType.value = type
  resetForm()
  loadStationList()
  if (id) {
    formLoading.value = true
    OrderApi.getOrder(id).then((data) => { formData.value = { ...formData.value, ...data }; formLoading.value = false })
  }
}

const loadStationList = async () => {
  try { stationList.value = await StationApi.getSimpleStationList() } catch (e) { /* ignore */ }
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    if (formType.value === 'create') await OrderApi.createOrder(formData.value)
    else await OrderApi.updateOrder(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功')
    dialogVisible.value = false
    emit('success')
  } finally { formLoading.value = false }
}
</script>
