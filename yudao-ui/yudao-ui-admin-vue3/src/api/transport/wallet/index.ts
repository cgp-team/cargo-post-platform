import request from '@/config/axios'

export interface WalletAccountVO {
  id?: number
  userId?: number
  balance?: number
  frozenAmount?: number
  totalRecharge?: number
  totalSpent?: number
  status?: number
}

/** 交易记录：后端 `/transaction/page` 直接返回 `WalletTransactionDO` 分页（没有专门的 RespVO） */
export interface WalletTransactionVO {
  id?: number
  userId?: number
  transactionNo?: string
  type?: string
  amount?: number
  balanceAfter?: number
  title?: string
  payMethod?: string
  refId?: number
  refNo?: string
  status?: number
  /** LocalDateTime 经 YudaoJackson 注册后是 Epoch 毫秒数字，不是 ISO 串 */
  createTime?: number
}

/**
 * 查询用户钱包。
 * ⚠ 这个 GET 有副作用：`WalletController.getAccount` 走的是 `walletService.getOrCreateAccount(userId)`，
 *   账户不存在时**会建一条**。台账 D-093 已登记，前端这里也不许拿它当"探测账户是否存在"的只读接口用。
 */
export const getWalletAccount = (userId: number): Promise<WalletAccountVO> => {
  return request.get({ url: '/transport/wallet/account/get', params: { userId } })
}

/** 交易记录分页（userId 可不传 = 全量流水） */
export const getWalletTransactionPage = (params: {
  userId?: number
  pageNo?: number
  pageSize?: number
}) => {
  return request.get({ url: '/transport/wallet/transaction/page', params })
}

/**
 * 这里**刻意没有** `adjustBalance()`：`POST /transport/wallet/adjust-balance` 是存在的，
 * 但它没有幂等键（`WalletController.adjustBalance` 只有 userId/amount/reason 三个参数），
 * 重复点击就是重复改钱；且 `amount == 0` 时两条分支都不走、照样返回"调整成功"。
 * 页面不暴露 ≠ 接口安全：这两条要后端补上幂等键与零值拒绝之后再接（见台账 D-093）。
 */
