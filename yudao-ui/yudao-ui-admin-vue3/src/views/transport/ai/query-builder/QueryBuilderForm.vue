<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="560px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="名称" prop="name">
        <el-input v-model="formData.name" placeholder="如：本周未分配订单" maxlength="64" show-word-limit />
      </el-form-item>
      <el-form-item label="业务场景" prop="scene">
        <el-select v-model="formData.scene" placeholder="请选择场景" style="width: 100%">
          <el-option v-for="item in SCENE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="筛选条件" prop="conditionsJson">
        <el-input
          v-model="formData.conditionsJson"
          type="textarea"
          :rows="5"
          placeholder="JSON 文本，如：状态等于未分配"
        />
        <div class="form-tip">只存条件，不存结果：下次按这套条件重新取数。</div>
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="formData.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as SavedQueryApi from '@/api/transport/ai/savedQuery'
import { Dialog } from '@/components/Dialog'

const SCENE_OPTIONS = [
  { label: '订单', value: 'ORDER' },
  { label: '调度', value: 'DISPATCH' },
  { label: '反馈', value: 'FEEDBACK' }
]

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])

const formData = ref<SavedQueryApi.AiSavedQueryVO>({
  name: '',
  scene: 'DISPATCH',
  conditionsJson: '',
  remark: ''
})

const validateJson = (_rule: any, value: string, callback: (e?: Error) => void) => {
  if (!value) return callback(new Error('筛选条件不能为空'))
  try {
    JSON.parse(value)
    callback()
  } catch (e) {
    callback(new Error('筛选条件不是合法 JSON'))
  }
}

const formRules = reactive({
  name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
  scene: [{ required: true, message: '业务场景不能为空', trigger: 'change' }],
  conditionsJson: [{ required: true, validator: validateJson, trigger: 'blur' }]
})

const resetForm = () => {
  formData.value = { name: '', scene: 'DISPATCH', conditionsJson: '', remark: '' }
  formRef.value?.resetFields()
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增查询条件' : '编辑查询条件'
  formType.value = type
  resetForm()
  if (id) {
    formLoading.value = true
    SavedQueryApi.getSavedQuery(id)
      .then((data) => {
        formData.value = { ...formData.value, ...data }
      })
      .finally(() => {
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
    if (formType.value === 'create') {
      await SavedQueryApi.createSavedQuery(formData.value)
      message.success('创建成功')
    } else {
      await SavedQueryApi.updateSavedQuery(formData.value)
      message.success('更新成功')
    }
    dialogVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}
</style>
