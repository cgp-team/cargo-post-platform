import request from '@/config/axios'

/** 会员用户（对应 MemberUserRespVO） */
export interface MemberUserVO {
  id?: number
  mobile?: string
  status?: number
  email?: string
  nickname?: string
  avatar?: string
  name?: string
  sex?: number
  areaId?: number
  areaName?: string
  birthday?: string
  mark?: string
  tagIds?: number[]
  levelId?: number
  groupId?: number
  registerIp?: string
  loginIp?: string
  loginDate?: string
  createTime?: string
  point?: number
  totalPoint?: number
  tagNames?: string[]
  levelName?: string
  groupName?: string
  experience?: number
}

/** 会员分页查询参数（对应 MemberUserPageReqVO） */
export interface MemberUserPageReqVO extends PageParam {
  nickname?: string
  mobile?: string
  createTime?: string[]
}

/** 会员分页查询 */
export const getMemberUserPage = (params: MemberUserPageReqVO) => {
  return request.get({ url: '/member/user/page', params })
}

/** 会员详情 */
export const getMemberUser = (id: number): Promise<MemberUserVO> => {
  return request.get({ url: '/member/user/get', params: { id } })
}

/** 会员收货地址（对应 AddressRespVO） */
export interface MemberAddressVO {
  id?: number
  name?: string
  mobile?: string
  areaId?: number
  areaName?: string
  detailAddress?: string
  defaultStatus?: boolean
  createTime?: string
}

/** 会员收货地址列表 */
export const getMemberAddressList = (userId: number): Promise<MemberAddressVO[]> => {
  return request.get({ url: '/member/address/list', params: { userId } })
}
