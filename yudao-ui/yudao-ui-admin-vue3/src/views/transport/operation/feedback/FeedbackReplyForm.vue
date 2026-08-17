<template>
  <Dialog title="回复反馈" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="姓名">
        <el-input :model-value="detail.name" disabled />
      </el-form-item>
      <el-form-item label="手机号">
        <el-input :model-value="detail.mobile" disabled />
      </el-form-item>
      <el-form-item label="反馈内容">
        <el-input :model-value="detail.content" type="textarea" :rows="3" disabled />
      </el-form-item>
      <el-form-item label="回复内容" prop="reply">
        <el-input v-model="formData.reply" type="textarea" :rows="4" placeholder="请输入回复内容" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as FeedbackApi from '@/api/transport/operation/feedback'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const emit = defineEmits(['success'])

const detail = ref<FeedbackApi.FeedbackVO>({})
const formData = ref<{ id?: number; reply: string }>({
  id: undefined,
  reply: '',
})

const formRules = reactive({
  reply: [{ required: true, message: '回复内容不能为空', trigger: 'blur' }],
})

const open = (id: number) => {
  dialogVisible.value = true
  detail.value = {}
  formData.value = { id: undefined, reply: '' }
  formRef.value?.resetFields()
  formLoading.value = true
  FeedbackApi.getFeedback(id).then((data) => {
    detail.value = data
    formData.value = { id: data.id, reply: data.reply || '' }
    formLoading.value = false
  })
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    await FeedbackApi.replyFeedback({ id: formData.value.id!, reply: formData.value.reply })
    message.success('回复成功')
    dialogVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}
</script>
