<template>
  <div :class="prefixCls" class="relative h-[100%]">
    <!-- 右上角的主题、语言选择 -->
    <div
      class="absolute top-20px right-20px z-10 flex items-center space-x-10px"
      style="color: var(--el-text-color-primary)"
    >
      <ThemeSwitch />
      <LocaleDropdown />
    </div>
    <!-- 居中的登录界面 -->
    <div class="relative h-full flex items-center justify-center overflow-x-hidden overflow-y-auto">
      <Transition appear enter-active-class="animate__animated animate__bounceInRight">
        <div class="w-[100%] max-w-420px px-30px">
          <!-- logo + 系统标题 -->
          <div class="mb-20px flex items-center justify-center text-white">
            <img alt="" class="mr-10px h-48px w-48px" src="@/assets/imgs/logo.png" />
            <span class="text-20px font-bold">{{ underlineToHump(appStore.getTitle) }}</span>
          </div>
          <!-- 账号登录 -->
          <LoginForm class="m-auto h-auto" />
          <!-- 注册 -->
          <RegisterForm class="m-auto h-auto" />
          <!-- 忘记密码 -->
          <ForgetPasswordForm class="m-auto h-auto" />
        </div>
      </Transition>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { underlineToHump } from '@/utils'

import { useDesign } from '@/hooks/web/useDesign'
import { useAppStore } from '@/store/modules/app'
import { ThemeSwitch } from '@/layout/components/ThemeSwitch'
import { LocaleDropdown } from '@/layout/components/LocaleDropdown'

import { LoginForm, RegisterForm, ForgetPasswordForm } from './components'

defineOptions({ name: 'Login' })

const appStore = useAppStore()
const { getPrefixCls } = useDesign()
const prefixCls = getPrefixCls('login')
</script>

<style lang="scss" scoped>
$prefix-cls: #{$namespace}-login;

.#{$prefix-cls} {
  overflow: auto;
  background-image: url('@/assets/svgs/login-bg.svg');
  background-position: center;
  background-repeat: no-repeat;
  background-size: cover;

  // 深色背景上的文字可读性
  :deep(.login-form) {
    --el-checkbox-text-color: rgba(255, 255, 255, 0.85);

    h2 {
      color: #fff;
    }
  }
}
</style>

<style lang="scss">
.dark .login-form {
  .el-divider__text {
    background-color: var(--login-bg-color);
  }

  .el-card {
    background-color: var(--login-bg-color);
  }
}
</style>
