import { defineStore } from 'pinia'
import * as StationApi from '@/api/transport/station'

/**
 * 站点精简列表的共享缓存（队列 #75 / 台账 D-128）。
 *
 * 为什么要这一层：`/transport/station/simple-list` 后端是**不分页的全量**
 * （`StationController#simpleList`），而它此前被 6 个地方各自 `await` 一遍
 * （订单页筛选、下单表单、线路表单、发货表单、订单详情、调度可视化弹窗、调度中心）。
 * 每开一个弹窗就重下一遍整张站点表，体量随站点数线性增长 —— 而同一个后台会话里
 * 这份数据本来就是同一份。
 *
 * 三条口径：
 * · TTL 60 秒：站点是低频改动的运营主数据，60 秒内的重复打开直接复用；
 * · 站点被新增/修改/删除后必须 `invalidate()`，否则"刚建的站点在别的页面选不到"
 *   会变成新的错（这一格由 StationForm / station 列表页的写口负责）；
 * · 并发只发一次请求：几个弹窗同帧打开时 `inflight` 复用同一个 promise，
 *   不然缓存会在"都没完成"的时刻被并发打穿。
 */
const TTL_MS = 60_000

interface StationDictState {
  list: StationApi.StationVO[]
  fetchedAt: number
}

export const useStationDictStore = defineStore('stationDict', {
  state: (): StationDictState & { inflight: Promise<StationApi.StationVO[]> | null } => ({
    list: [],
    fetchedAt: 0,
    inflight: null
  }),
  actions: {
    async load(force = false): Promise<StationApi.StationVO[]> {
      const now = Date.now()
      if (!force && this.list.length && now - this.fetchedAt < TTL_MS) {
        return this.list
      }
      if (this.inflight) return this.inflight
      this.inflight = StationApi.getSimpleStationList()
        .then((res) => {
          this.list = res || []
          // 只有真的读到了才盖时间戳：失败时保持"没读过"，下一次调用会重试
          this.fetchedAt = Date.now()
          return this.list
        })
        .finally(() => {
          this.inflight = null
        })
      return this.inflight
    },
    invalidate() {
      this.list = []
      this.fetchedAt = 0
    }
  }
})
