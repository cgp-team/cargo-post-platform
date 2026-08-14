package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehicleBindReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehiclePageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 司机车辆绑定 Service
 */
public interface DriverVehicleService {

    /** 绑定司机与车辆：同一司机已有有效绑定先自动解绑，再绑新车辆 */
    void bind(@Valid DriverVehicleBindReqVO reqVO);

    /** 解绑：按绑定记录 id 解绑（写 unbindTime + status=0） */
    void unbind(Long id);

    /** 分页查询绑定关系（含司机姓名/车牌号） */
    PageResult<DriverVehicleDO> getPage(DriverVehiclePageReqVO pageReqVO);

    /** 查询司机全部绑定记录（含历史解绑，按绑定时间倒序） */
    List<DriverVehicleDO> listByDriver(Long driverId);

}
