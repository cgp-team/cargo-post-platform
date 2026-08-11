import request from '@/config/axios'

export interface ProductVO {
  id?: number
  name: string
  fromVillage?: string
  price?: number
  unit?: string
  image?: string
  badge?: string
  description?: string
  stock?: number
  status?: number
  sort?: number
  createTime?: string
}

export const getProductPage = (params: PageParam & Partial<ProductVO>) => {
  return request.get({ url: '/transport/product/page', params })
}
export const getProduct = (id: number): Promise<ProductVO> => {
  return request.get({ url: '/transport/product/get', params: { id } })
}
export const createProduct = (data: ProductVO) => {
  return request.post({ url: '/transport/product/create', data })
}
export const updateProduct = (data: ProductVO) => {
  return request.put({ url: '/transport/product/update', data })
}
export const deleteProduct = (id: number) => {
  return request.delete({ url: '/transport/product/delete', params: { id } })
}
export const getSimpleProductList = (): Promise<ProductVO[]> => {
  return request.get({ url: '/transport/product/simple-list' })
}
