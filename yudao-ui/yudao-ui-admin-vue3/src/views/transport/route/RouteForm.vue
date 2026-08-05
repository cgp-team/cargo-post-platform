<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="线路编码" prop="routeCode">
        <el-input v-model="formData.routeCode" placeholder="请输入线路编码" />
      </el-form-item>
      <el-form-item label="线路名称" prop="routeName">
        <el-input v-model="formData.routeName" placeholder="请输入线路名称" />
      </el-form-item>
      <el-form-item label="起点站点" prop="startStationId">
        <el-select v-model="formData.startStationId" placeholder="请选择起点站点" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id" :label="s.stationName" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="终点站点" prop="endStationId">
        <el-select v-model="formData.endStationId" placeholder="请选择终点站点" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id" :label="s.stationName" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="里程(km)" prop="distanceKm">
        <el-input v-model.number="formData.distanceKm" placeholder="请输入里程(km)" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as RouteApi from '@/api/transport/route'
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
const formData = ref<any>({ routeCode: '', routeName: '', startStationId: null, endStationId: null, distanceKm: null })
const formRules = reactive({
  routeCode: [{ required: true, message: '线路编码不能为空', trigger: 'blur' }],
  routeName: [{ required: true, message: '线路名称不能为空', trigger: 'blur' }],
})
const resetForm = () => {
  formData.value = { routeCode: '', routeName: '', startStationId: null, endStationId: null, distanceKm: null }
  formRef.value?.resetFields()
}
const open = (type: string, id?: number) => {
  dialogVisible.value = true; dialogTitle.value = type === 'create' ? '新增线路' : '编辑线路'; formType.value = type; resetForm()
  loadStationList()
  if (id) { formLoading.value = true; RouteApi.getRoute(id).then((data) => { formData.value = data; formLoading.value = false }) }
}
const loadStationList = async () => {
  try { stationList.value = await StationApi.getSimpleStationList() } catch (e) { /* ignore */ }
}
defineExpose({ open })
const submitForm = async () => {
  const valid = await formRef.value?.validate(); if (!valid) return; formLoading.value = true
  try {
    if (formType.value === 'create') await RouteApi.createRoute(formData.value)
    else await RouteApi.updateRoute(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功'); dialogVisible.value = false; emit('success')
  } finally { formLoading.value = false }
}
</script>
