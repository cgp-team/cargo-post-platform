package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.*;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmAdapter;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmResultValidator;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.system.api.social.SocialClientApi;
import cn.iocoder.yudao.module.system.api.social.dto.SocialWxaSubscribeMessageSendReqDTO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

@Service
@Validated
@Slf4j
public class DispatchServiceImpl implements DispatchService {

    /** 绠楁硶濂戠害鏃跺尯鍥哄畾 +08:00锛岃�� docs/api/algorithm-api.yaml */
    private static final ZoneOffset BATCH_ZONE_OFFSET = ZoneOffset.ofHours(8);
    /** 鎵嬪伐娲惧崟鍦烘櫙鏍囪�� */
    private static final String SCENARIO_MANUAL = "MANUAL";
    /** 绠楁硶瑙勬ā涓婇檺锛堜笌绠楁硶鏈嶅姟 app.py 濂戠害涓�鑷达級锛?0 绔欑偣 / 25 璁㈠崟 / 3 杞?*/
    private static final int MAX_ALGORITHM_STATIONS = 30;
    private static final int MAX_ALGORITHM_ORDERS = 25;
    private static final int MAX_ALGORITHM_VEHICLES = 3;
    /**
     * 鎵规�¤�勫垝绐楀彛(鍒嗛挓)锛氱畻娉曡�佹�?鏁存壒浠诲姟鎬昏�楁椂 鈮?绐楀彛鏃堕暱"銆?     * 30 鍒嗛挓锛堝師鏉ョ殑鍗婂皬鏃舵壒娆★級瀵圭湡瀹炶矾缃戯紙楂樺痉鏃剁┖鏃堕暱 + 瑁呭嵏浣滀笟锛夊お绱э紝
     * 2 鍗曚互涓婂緢瀹规槗鍒?TIME_WINDOW_EXCEEDED锛涜繖閲屾寜 2 灏忔椂瑙勫垝锛堝彲瑕嗙洊 yudao.dispatch.batch-minutes锛夈�?     */
    private static final int BATCH_MINUTES = 120;

    @Resource private TransportOrderMapper orderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private PassengerOrderMapper passengerOrderMapper;
    @Resource private StationMapper stationMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private TransportDispatchTaskMapper dispatchTaskMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanLogMapper dispatchPlanLogMapper;
    @Resource private DepartureCheckMapper departureCheckMapper;
    @Resource private AlgorithmAdapter algorithmAdapter;
    @Resource private DispatchEstimationService dispatchEstimationService;
    @Resource private MultiLegService multiLegService;
    @Resource private cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService roadPolylineService;
    @Resource private SocialClientApi socialClientApi;
    @Resource private MemberUserApi memberUserApi;
    @Resource private DriverMapper driverMapper;
    @Resource private cn.iocoder.yudao.module.transport.service.notification.UserNotificationService userNotificationService;

    @Override
    public PageResult<TransportOrderDO> getOrderPoolPage(DispatchPoolPageReqVO reqVO) {
        return orderMapper.selectPage(reqVO, new LambdaQueryWrapperX<TransportOrderDO>()
                .inIfPresent(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus(),
                        TransportOrderStatusEnum.POOLED.getStatus())
                .eqIfPresent(TransportOrderDO::getStatus, reqVO.getStatus())
                .eqIfPresent(TransportOrderDO::getOrderType, reqVO.getOrderType())
                .betweenIfPresent(TransportOrderDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(TransportOrderDO::getId));
    }

    @Override
    @Transactional
    public int collectOrders(DispatchCollectReqVO reqVO) {
        // 涓�閿�褰掗泦锛氭妸褰撳墠鎵�鏈夈�屽緟鍏ユ睜銆嶈�㈠崟鍏ㄩ儴鍏ユ睜锛堟紨绀�/鎵归噺鍦烘櫙锛屽厤鍕鹃�夛級
        if (Boolean.TRUE.equals(reqVO.getAll())) {
            return collectAllReadyForPool();
        }
        // 鎺ㄨ崘锛氭寜鍕鹃�夎�㈠崟褰掗泦锛坥rderIds 浼樺厛锛屽墠绔�鍕鹃�夊悗鎸?ID 鍏ユ睜锛?        if (reqVO.getOrderIds() != null && !reqVO.getOrderIds().isEmpty()) {
            return collectByOrderIds(reqVO.getOrderIds());
        }
        // 鍏煎�癸細鎸夋椂闂磋寖鍥村綊闆嗭紙鎵规�″尯闂村唴寰呰皟搴﹁�㈠崟锛岃揣杩愰渶瀹℃牳閫氳繃锛?
        return collectByTimeRange(reqVO);
    }

    /** 涓�閿�褰掗泦鍏ㄩ儴銆屽緟鍏ユ睜銆嶈�㈠崟锛堢姸鎬?8 鈫?1锛夛紝鏃犲彲瑙佽�㈠崟杩斿�?0 */
    private int collectAllReadyForPool() {
        List<TransportOrderDO> orders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));
        if (orders.isEmpty()) {
            return 0;
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());
        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));
    }

    /**
     * 鎸夊嬀閫夎�㈠崟褰掗泦锛氭牎楠屽叏閮ㄥ瓨鍦� / 鎵胯繍瀹℃牳閫氳繃锛圧EADY_FOR_POOL 寰呭叆姹狅級銆?
     * Phase 2锛氬彧鏈夈�屽緟鍏ユ睜銆嶈�㈠崟鍙�鍏ユ睜鈥斺�斿�㈡埛鎻愪�?鈫?鎵胯繍瀹℃牳 鈫?READY_FOR_POOL 鈫?褰掗泦鍏ユ睜銆?
     * 浠讳竴闈炴硶鏄庣‘鎶ラ敊锛堜笉鎮勬倓璺宠繃锛夈�?
     */
    private int collectByOrderIds(List<Long> orderIds) {
        List<TransportOrderDO> orders = orderMapper.selectBatchIds(orderIds);
        if (orders.size() != orderIds.size()) {
            throw exception(ORDER_NOT_EXISTS);
        }
        for (TransportOrderDO order : orders) {
            if (!Objects.equals(order.getStatus(), TransportOrderStatusEnum.READY_FOR_POOL.getStatus())) {
                throw exception(DISPATCH_ORDER_NOT_COLLECTABLE);
            }
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());
        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, orderIds)
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));
    }

    /** 鍏煎�癸細鎸夋椂闂磋寖鍥村綊闆嗭紙鍖洪棿鍐呭緟鍏ユ睜璁㈠崟锛涙湭鎻愪緵鍖洪棿杩斿�?0锛?*/
    private int collectByTimeRange(DispatchCollectReqVO reqVO) {
        if (reqVO.getBatchStart() == null || reqVO.getBatchEnd() == null) {
            return 0;
        }
        List<TransportOrderDO> orders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus())
                .between(TransportOrderDO::getCreateTime, reqVO.getBatchStart(), reqVO.getBatchEnd()));
        List<Long> ids = orders.stream().map(TransportOrderDO::getId).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());
        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, ids)
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));
    }

    @Override
    @Transactional
    public Long createManualPlan(DispatchManualPlanReqVO reqVO) {
        // 鏍￠獙鍦虹珯涓庤�㈠崟锛氳�㈠崟蹇呴』鍏ㄩ儴鍦ㄨ�㈠崟姹犱�?
        StationDO depot = validateDepotExists(reqVO.getDepotStationId());
        Map<Long, TransportOrderDO> orderMap = validatePooledOrders(reqVO.getOrderIds());
        VehicleDO vehicle = validateVehicleExists(reqVO.getVehicleId());

        List<TransportOrderDO> orders = reqVO.getOrderIds().stream().map(orderMap::get).collect(Collectors.toList());
        // 鎸?orderIds 椤哄簭鐢熸垚闂�鐜�缁忓仠锛欴EPART(鍦虹珯) -> 鍚勮�㈠崟浣滀笟鐐� -> RETURN(鍦虹珯)锛?
        // 瀹㈣繍澶氫汉鍗曟寜涔樺�㈡暟鎷嗘垚澶氭潯绠楁硶璁㈠崟锛堝�戠害涓�涓�寮犲�㈣繍鍗� = 1 浜猴級
        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(orders);
        List<AlgorithmRouteStopDTO> stops = new ArrayList<>();
        stops.add(buildStop(depot.getId(), null, AlgorithmRouteStopDTO.ACTION_DEPART));
        for (TransportOrderDO order : orders) {
            if (Objects.equals(order.getOrderType(), 1)) { // 瀹㈣繍锛氫笂杞?+ 涓嬭溅
                for (String algorithmOrderId : passengerAlgorithmOrderIds(order, passengerMap)) {
                    stops.add(buildStop(order.getPickupStationId(), algorithmOrderId, AlgorithmRouteStopDTO.ACTION_BOARD));
                    stops.add(buildStop(order.getDeliveryStationId(), algorithmOrderId, AlgorithmRouteStopDTO.ACTION_ALIGHT));
                }
            } else { // 璐ц繍/閭�蹇�浠讹細缁堢偣涓哄満绔?鈫?鎻芥敹(鏉戔啋鍦虹珯)锛屽惁鍒?鈫?娲鹃�?鍦虹珯鈫掓潙)
                boolean isPickup = Objects.equals(order.getDeliveryStationId(), depot.getId());
                stops.add(buildStop(isPickup ? order.getPickupStationId() : order.getDeliveryStationId(),
                        String.valueOf(order.getId()),
                        isPickup ? AlgorithmRouteStopDTO.ACTION_PICKUP : AlgorithmRouteStopDTO.ACTION_DELIVER));
            }
        }
        stops.add(buildStop(depot.getId(), null, AlgorithmRouteStopDTO.ACTION_RETURN));
        AlgorithmPlanReqDTO algorithmReq = buildPlanRequest(depot, Collections.singletonList(vehicle), orders,
                null, SCENARIO_MANUAL, null);
        AlgorithmPlanRespDTO algorithmResp = AlgorithmPlanRespDTO.builder()
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .vehiclePlans(Collections.singletonList(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(vehicle.getId()).stops(stops).build()))
                .build();
        AlgorithmResultValidator.validate(algorithmReq, algorithmResp);

        // 钀藉簱锛氫换鍔?-> 鏂规�� -> 缁忓仠鏄庣粏 -> 璁㈠崟缃�涓哄凡鍒嗛�?
        LocalDateTime[] batch = currentBatch();
        DispatchTaskDO task = DispatchTaskDO.builder()
                .taskNo(generateTaskNo())
                .snapshotId("manual-" + IdUtil.fastSimpleUUID())
                .planningTime(LocalDateTime.now())
                .batchStart(batch[0]).batchEnd(batch[1])
                .scenario(SCENARIO_MANUAL)
                .status(DispatchTaskStatusEnum.SUCCESS.getStatus())
                .build();
        dispatchTaskMapper.insert(task);
        DispatchPlanDO plan = createPlan(task, DispatchPlanModeEnum.MANUAL, null, null, null);
        insertPlanItems(plan.getId(), vehicle.getId(), stops);
        // 浼扮畻姣忕珯棰勮�″埌杈炬椂闂达紙鍙ｅ緞鍚屾櫤鑳芥淳鍗曪細鎵规�″紑濮嬫椂鍒诲嚭鍙戯紝閫愮珯绱�璁¤�岄┒ + 鍋滅珯浣滀笟鍒嗛挓锛?
        dispatchEstimationService.estimatePlan(plan.getId(), batch[0]);
        updateOrdersStatus(reqVO.getOrderIds(), TransportOrderStatusEnum.ASSIGNED,
                TransportOrderStatusEnum.POOLED);
        return plan.getId();
    }

    /**
     * 鏅鸿兘娲惧崟銆備换鍔＄粓鎬侊紙鏃犲彲琛岃В/澶辫触锛夐渶瑕佸湪寮傚父鎶涘嚭鍚庝粛鐒惰惤搴擄紝鏁?ServiceException 涓嶅洖婊氥�?
     */
    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public Long createSmartPlan(DispatchSmartPlanReqVO reqVO) {
        try {
            return doCreateSmartPlan(reqVO);
        } catch (ServiceException ex) {
            throw ex; // 涓氬姟寮傚父鍘熸牱鎶涘嚭锛堝墠绔�灞曠ず鍏蜂綋鍘熷洜锛屽�傚�归噺瓒婄�?鏃犲彲琛岃В锛?        } catch (Exception ex) {
            // 鍏滃簳锛氭妸鎶�鏈�寮傚父鐨勬牴鍥犲啓杩涗笟鍔￠敊璇�锛岄伩鍏嶅墠绔�鍙�鐪嬪埌"鏈嶅姟鍣ㄩ敊璇�锛岃�疯仈绯荤�＄悊鍛�"
            log.error("[createSmartPlan] 鏅鸿兘璋冨害寮傚父", ex);
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            throw exception(ALGORITHM_RESULT_INVALID, "鏅鸿兘璋冨害鎵ц�屽け璐ワ�? + detail);
        }
    }

    /** 鏅鸿兘娲惧崟涓讳綋锛堝紓甯哥粺涓�鐢?{@link #createSmartPlan} 鍏滃簳杞�鎴愬彲璇讳笟鍔￠敊璇�锛?*/
    private Long doCreateSmartPlan(DispatchSmartPlanReqVO reqVO) {
        // 鍙栬�㈠崟姹狅紱涓虹┖鐩存帴鎶ラ�?        List<TransportOrderDO> pooledOrders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));
        if (pooledOrders.isEmpty()) {
            throw exception(DISPATCH_POOL_EMPTY);
        }
        // 涓�閿�鏅鸿兘璋冨害锛坅uto=true锛夛細鍚庣��鑷�鍔ㄩ�夊満绔?+ 鑷�鍔ㄦ寫鍊欓�夎溅杈嗭紙瀹為檯杞﹁締鏁扮敱绠楁硶鍐冲畾锛?        StationDO depot;
        List<VehicleDO> vehicles;
        if (Boolean.TRUE.equals(reqVO.getAuto())) {
            List<StationDO> stations = stationMapper.selectList();
            Map<Long, StationDO> stationMap = stations.stream()
                    .filter(s -> s.getId() != null)
                    .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity(), (a, b) -> a));
            // 鐗囧尯鍒嗘壒锛氳�㈠崟姹犲彲鑳藉悓鏃舵湁澶氫釜鐗囧尯锛堝�傞噸搴嗛偖鐢靛ぇ瀛︾墖鍖?+ 鎴愰兘鐗囧尯锛夛紝
            // 娣锋壒浼氳�╄溅杈嗚法鍩庤窇鍑犵櫨鍏�閲?鈫?绠楁硶 infeasible锛涜繖閲屽彧鍙?鏈�鏂伴偅鍗曟墍鍦ㄧ墖鍖?锛?            // 鍏朵綑鐗囧尯鐣欏湪姹犻噷锛屽啀娆＄偣鍑汇�屼竴閿�璋冨害銆嶈嚜鍔ㄦ垚涓嬩竴濂楁柟妗堛�?            pooledOrders = AutoDispatchPlanner.selectAutoBatch(pooledOrders, stationMap, MAX_ALGORITHM_ORDERS);
            depot = AutoDispatchPlanner.selectDepot(pooledOrders, stations);
            if (depot == null) {
                throw exception(STATION_NOT_EXISTS);
            }
            // 鍊欓�夎溅杈嗭細浼樺厛閬垮紑"宸茶��鍦ㄩ�旀柟妗堝崰鐢?鐨勮溅锛堝悓涓�鍙拌溅涓嶈兘鍚屾椂璺戜袱濂楁柟妗堬紱
            // 澶氱墖鍖哄悇鍑轰竴濂楁柟妗堟椂锛屼袱濂楅兘鎺掑悓涓�鍙拌溅浼氳�╁徃鏈虹��浠诲姟娣峰湪涓�璧凤級
            List<VehicleDO> allVehicles = autoCandidateVehicles();
            Set<Long> busyVehicleIds = busyVehicleIds();
            vehicles = AutoDispatchPlanner.selectVehicles(allVehicles, MAX_ALGORITHM_VEHICLES, busyVehicleIds);
            if (vehicles.isEmpty()) {
                // 鍏ㄩ儴杞﹁締閮藉湪鎵ц�屽埆鐨勬柟妗� 鈫?閫�鍥炰笉鎺掗櫎锛堜繚璇佽兘鍑烘柟妗堬紝鐢辫皟搴﹀憳浜哄伐鍙栬垗锛?                vehicles = AutoDispatchPlanner.selectVehicles(allVehicles, MAX_ALGORITHM_VEHICLES);
            }
            if (vehicles.isEmpty()) {
                throw exception(VEHICLE_NOT_EXISTS);
            }
        } else {
            if (reqVO.getDepotStationId() == null) {
                throw exception(STATION_NOT_EXISTS);
            }
            if (reqVO.getVehicleIds() == null || reqVO.getVehicleIds().isEmpty()) {
                throw exception(VEHICLE_NOT_EXISTS);
            }
            depot = validateDepotExists(reqVO.getDepotStationId());
            vehicles = vehicleMapper.selectBatchIds(reqVO.getVehicleIds());
            if (vehicles.size() < reqVO.getVehicleIds().size()) {
                throw exception(VEHICLE_NOT_EXISTS);
            }
        }
        List<Long> pooledIds = pooledOrders.stream().map(TransportOrderDO::getId).toList();

        // 鍏堟瀯寤哄揩鐓у苟鍋氳�勬ā棰勬��锛堝彧璇伙紝涓嶅崰鍗曪級锛氭棤鏁堣緭鍏ュ揩閫熷け璐ワ紝閬垮厤鍏?CAS 鎶㈠崰鍚庡啀鎶涢敊闇�瑕佸洖婊氥�?
        // 鑱斿悎璋冨害锛氭寚瀹氱彮娆℃椂杞﹁締鎸夌彮娆＄嚎璺�鍏�浜ら�ㄦ灦缁忓仠锛岃揣杩愪綔涓虹粫琛屾彃鍏ワ紙Phase 5锛?
        List<String> skeleton = resolveSkeleton(reqVO.getShiftId(), depot.getId());
        AlgorithmPlanReqDTO algorithmReq = buildPlanRequest(depot, vehicles, pooledOrders,
                AutoDispatchPlanner.mergeAlgorithmConfig(reqVO.getAlgorithmConfig()), reqVO.getScenario(), skeleton);
        // 瑙勬ā涓婇檺棰勬��锛氬�㈣繍鎸変汉鏁版媶鍗曞悗鍙�鑳借秴 25 鍗曪紝瓒呴檺鐩存帴鎶ラ敊鑰岄潪绛夌畻娉?413
        validateScaleLimit(algorithmReq);

        // P1-004 骞跺彂闃叉姢锛欳AS 鎶㈠崰璁㈠崟姹狅紙浠呭凡鍏ユ睜鍙�鎺ㄨ繘涓哄凡鍒嗛厤锛夈�侷nnoDB 琛岄攣浼氫覆琛屽寲骞跺彂鏅鸿兘娲惧崟锛?
        // 鍚庡埌鐨勬淳鍗曞湪 CAS 涓婇樆濉炶嚦鍏堝埌鎻愪氦锛岄殢鍚庡洜璁㈠崟宸查潪 POOLED 鑰屽奖鍝?0 琛?鈫?璧?DISPATCH_POOL_EMPTY銆?
        // 鎶㈠崰鎴愬姛鍚庤�㈠崟鍗充�?ASSIGNED锛屾柟妗堣惤搴撳悗鏃犻渶鍐嶆敼鐘舵�侊紱绠楁硶澶辫触/鏃犺В闇�鏄惧紡鍥炴粴鎶㈠崰锛堟湰鏂规硶
        // noRollbackFor=ServiceException 涓嶅洖婊氾紝蹇呴』鎵嬪姩閲婃斁锛屽惁鍒欒�㈠崟浼氭粸鐣� ASSIGNED 鑰屾棤鏂规�堬級銆?
        TransportOrderDO claim = new TransportOrderDO();
        claim.setStatus(TransportOrderStatusEnum.ASSIGNED.getStatus());
        int claimed = orderMapper.update(claim, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, pooledIds)
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));
        if (claimed == 0) {
            throw exception(DISPATCH_POOL_EMPTY); // 姹犲凡琚�骞跺彂娲惧崟鎶㈠�?
        }
        if (claimed < pooledIds.size()) {
            // 姹犺��闈炴櫤鑳芥淳鍗曡矾寰勫苟鍙戜慨鏀癸紙閮ㄥ垎璁㈠崟宸查潪 POOLED锛夛細鎶涢潪 ServiceException 瑙﹀彂鏁翠簨鍔″洖婊氾紝
            // 鎾ら攢鏈�娆″凡鎶㈠崰鐨勮�㈠崟锛岄伩鍏嶄笌鍏跺畠璺�寰勭殑璁㈠崟褰掑睘浜х敓姝т箟銆?
            throw new IllegalStateException("璁㈠崟姹犵姸鎬佸苟鍙戝彉鏇达紝璇峰埛鏂板悗閲嶈瘯");
        }

        LocalDateTime[] batch = currentBatch();
        String taskNo = generateTaskNo();
        DispatchTaskDO task = DispatchTaskDO.builder()
                .taskNo(taskNo)
                .snapshotId(taskNo) // 蹇�鐓х紪鍙锋殏鐢ㄤ换鍔″彿锛屼繚璇佸敮涓�绾︽潫
                .planningTime(LocalDateTime.now())
                .batchStart(batch[0]).batchEnd(batch[1])
                .scenario(reqVO.getScenario())
                .status(DispatchTaskStatusEnum.PLANNING.getStatus())
                .build();
        dispatchTaskMapper.insert(task);

        // 璋冪敤绠楁硶锛涘け璐ユ椂浠诲姟缃?FAILED銆佽�㈠崟鍥炴粴鎶㈠崰鍚庨�忎紶寮傚父
        AlgorithmPlanRespDTO result;
        try {
            result = algorithmAdapter.plan(algorithmReq);
        } catch (ServiceException ex) {
            task.setStatus(DispatchTaskStatusEnum.FAILED.getStatus());
            task.setErrorMessage(ex.getMessage());
            dispatchTaskMapper.updateById(task);
            releaseClaimedOrders(pooledIds);
            throw ex;
        }
        if (AlgorithmPlanRespDTO.STATUS_INFEASIBLE.equals(result.getStatus())) {
            task.setStatus(DispatchTaskStatusEnum.INFEASIBLE.getStatus());
            dispatchTaskMapper.updateById(task);
            releaseClaimedOrders(pooledIds);
            throw exception(DISPATCH_NO_FEASIBLE, reasonCodeText(result.getReasonCode()));
        }

        // 鍙�琛岋細浠诲姟缃�鎴愬姛锛屾柟妗堜笌缁忓仠鏄庣粏钀藉簱锛堣�㈠崟宸插�?CAS 鎶㈠崰鏃剁疆涓哄凡鍒嗛厤锛?
        task.setStatus(DispatchTaskStatusEnum.SUCCESS.getStatus());
        task.setAlgorithmJobId(result.getRequestId());
        dispatchTaskMapper.updateById(task);
        // 鎬婚噷绋嬶細鎸夌畻娉曡繑鍥炵殑閲岀▼鍗曚綅澶勭悊鈥斺�攄egree 鏃舵寜缁忓仠绔欑偣鍧愭爣 Haversine 鎹㈢畻鐪熷疄鍏�閲岋紝km 鏃剁洿鎺ヤ娇鐢?
        BigDecimal totalDistanceKm = resolveTotalDistanceKm(result, buildCoordMap(algorithmReq));
        DispatchPlanDO plan = createPlan(task, DispatchPlanModeEnum.SMART,
                totalDistanceKm, result.getAlgorithmVersion(), result.getParameterVersion());
        for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {
            insertPlanItems(plan.getId(), vehiclePlan.getVehicleId(), vehiclePlan.getStops());
        }
        // 浼扮畻姣忕珯棰勮�″埌杈炬椂闂达紙绠楁硶涓嶄骇鍑鸿�楁椂锛屼笟鍔″悗绔�鎸夌粡鍋滃潗鏍囦笌鍧囬�熻嚜浼帮紱
        // distanceUnit=km 鏃舵惡甯︾畻娉曡繑鍥炵殑璺�缃戝垎娈垫椂闀�/閲岀▼锛屾寜鐪熷疄璺�缃戞椂闀跨疮璁★�?
        Map<String, DispatchEstimationService.RoadSegment> roadSegments = new HashMap<>();
        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {
            for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {
                List<AlgorithmRouteStopDTO> stops = vehiclePlan.getStops();
                for (int i = 0; i < stops.size(); i++) {
                    AlgorithmRouteStopDTO stop = stops.get(i);
                    if (stop.getSegmentDuration() != null) {
                        roadSegments.put(vehiclePlan.getVehicleId() + ":" + (i + 1),
                                new DispatchEstimationService.RoadSegment(
                                        stop.getSegmentDuration(), stop.getSegmentDistance()));
                    }
                }
            }
        }
        dispatchEstimationService.estimatePlan(plan.getId(), batch[0], roadSegments);
        // 澶氭�佃仈杩愶細涓烘柟妗堝唴姣忎釜璁㈠崟瑙勫垝杩愯緭娈碉紙MultiLegPlanner 鍐冲畾鐩磋揪/2娈?3娈碉級锛屽苟鍥炲啓鏂规�堣仛鍚堝瓧娈点�?        // 娈佃�勫垝澶辫触涓嶅奖鍝嶅凡鐢熸垚鐨勭洿杈炬柟妗堬紙澶氭�垫槸澧炲己鑳藉姏锛夛紝閫愬崟鍏滃簳璁板綍鏃ュ織銆?        List<TransportLegDO> allLegs = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        // 璁㈠崟 鈫?绠楁硶鍒嗛厤鍒扮殑杞﹁締/鍙告満锛堝悓涓�杈嗚溅鍙�鎷煎�氬崟锛涘彇璐ф�垫�?璇ヨ�㈠崟鎵�灞炶溅杈?娲捐溅锛?        Map<Long, Long[]> orderVehicleMap = new HashMap<>();
        for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {
            Long plannedVehicleId = vehiclePlan.getVehicleId();
            Long plannedDriverId = resolveDriverId(plannedVehicleId);
            for (AlgorithmRouteStopDTO stop : vehiclePlan.getStops()) {
                Long businessOrderId = stop.getOrderId() != null ? toBusinessOrderId(stop.getOrderId()) : null;
                if (businessOrderId != null) {
                    orderVehicleMap.putIfAbsent(businessOrderId, new Long[]{plannedVehicleId, plannedDriverId});
                }
            }
        }
        for (Long orderId : pooledIds) {
            try {
                Long[] plannedVehicle = orderVehicleMap.get(orderId);
                allLegs.addAll(multiLegService.planLegs(orderId, plan.getId(),
                        plannedVehicle != null ? plannedVehicle[0] : null,
                        plannedVehicle != null ? plannedVehicle[1] : null));
                MultiLegPlanner.PlanResult preview = multiLegService.preview(orderId);
                if (!reasons.contains(preview.reason())) {
                    reasons.add(preview.reason());
                }
            } catch (Exception ex) {
                log.warn("[createSmartPlan] 璁㈠崟 {} 杩愯緭娈佃�勫垝澶辫触锛歿}", orderId, ex.getMessage());
            }
        }
        if (!allLegs.isEmpty()) {
            int transferCount = pooledIds.size() == 0 ? 0 : allLegs.size() - (int) allLegs.stream()
                    .map(TransportLegDO::getOrderId).distinct().count();
            // 鏂规�堟�婚噷绋嬪彛寰勭粺涓�锛氬悇杩愯緭娈?*鐪熷疄璺�缃戦噷绋�**涔嬪拰锛圠eg 宸茬敱楂樺痉璺�缃戝洖鍐欙紱鏃犺矾缃戞椂涓轰及绠楀�硷級
            BigDecimal totalLegDistance = allLegs.stream().map(TransportLegDO::getDistanceKm)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            DispatchPlanDO planUpdate = new DispatchPlanDO();
            planUpdate.setId(plan.getId());
            planUpdate.setPlanNo(taskNo + "-P" + plan.getId());
            planUpdate.setPlanningMode(allLegs.size() > pooledIds.size()
                    ? DispatchPlanningModeEnum.MULTI_LEG.getMode() : DispatchPlanningModeEnum.DIRECT.getMode());
            planUpdate.setTotalLegCount(allLegs.size());
            planUpdate.setTransferCount(Math.max(0, transferCount));
            planUpdate.setPlanReason(cn.hutool.core.util.StrUtil.sub(String.join("；", reasons), 0, 2000));
                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null));
            planUpdate.setEstimatedArrivalTime(allLegs.stream().map(TransportLegDO::getEstimatedArrival)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
            if (totalLegDistance.compareTo(BigDecimal.ZERO) > 0) {
                planUpdate.setTotalDistance(totalLegDistance);
            }
            dispatchPlanMapper.updateById(planUpdate);
            plan.setPlanNo(planUpdate.getPlanNo());
            plan.setPlanningMode(planUpdate.getPlanningMode());
            plan.setTotalLegCount(planUpdate.getTotalLegCount());
            plan.setTransferCount(planUpdate.getTransferCount());
            plan.setPlanReason(planUpdate.getPlanReason());
        }
        return plan.getId();
    }

    /** 绠楁硶澶辫触/鏃犺В鏃跺洖婊?CAS 鎶㈠崰锛氬凡鍒嗛厤璁㈠崟鍥炲埌璁㈠崟姹狅紝鍙�閲嶆柊娲惧崟锛圥1-004锛?*/
    private void releaseClaimedOrders(List<Long> orderIds) {
        updateOrdersStatus(orderIds, TransportOrderStatusEnum.POOLED, TransportOrderStatusEnum.ASSIGNED);
    }

    /**
     * 宸茶��"鍦ㄩ�旀柟妗?鍗犵敤鐨勮溅杈嗭細鏂规�堢姸鎬?鈭?{寰呭�℃�? 宸蹭笅鍙? 鎵ц�屼腑} 鐨勭粡鍋滄槑缁嗛噷鍑虹幇杩囩殑杞︺�?     * 涓�閿�璋冨害鎸夌墖鍖鸿繛鍑哄�氬�楁柟妗堟椂鐢ㄥ畠鍋氶伩璁╋紝閬垮厤鍚屼竴鍙拌溅琚�涓ゅ�楀湪閫旀柟妗堝悓鏃跺崰鐢ㄣ�?     */
    private Set<Long> busyVehicleIds() {
        List<DispatchPlanDO> activePlans = dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                .in(DispatchPlanDO::getStatus, DispatchPlanStatusEnum.PENDING.getStatus(),
                        DispatchPlanStatusEnum.ISSUED.getStatus(), DispatchPlanStatusEnum.RUNNING.getStatus()));
        Set<Long> planIds = activePlans.stream().map(DispatchPlanDO::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (planIds.isEmpty()) {
            return Set.of();
        }
        return dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                        .in(DispatchPlanItemDO::getPlanId, planIds)).stream()
                .map(DispatchPlanItemDO::getVehicleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 鑷�鍔ㄨ皟搴﹀�欓�夎溅杈嗭細浼樺厛鍙?鏈夊湪鑱屽徃鏈虹粦瀹?鐨勮溅杈嗐�?     *
     * 鍘熷洜锛氬徃鏈虹��鎸夈�岀櫥褰曚細鍛樻墜鏈哄彿 = 鍙告満妗ｆ�堟墜鏈哄彿銆嶈�ら�嗕换鍔★紙`DriverAppServiceImpl.currentDriverOrNull`锛夛紝
     * 娲剧粰娌℃湁浠讳綍鍙告満缁戝畾鐨勮溅锛屼换鍔″湪鍙告満绔�鏍规湰鐪嬩笉鍒帮紙鍙�鑳界�＄悊鍛樹唬鏍搁獙锛夈�傛病鏈夊彲鐢ㄧ粦瀹氭垨杞﹁締琛ㄤ负绌烘椂
     * 閫�鍥炲叏閮ㄨ溅杈嗭紝淇濊瘉浠嶇劧鑳藉嚭鏂规�堛�?     */
    private List<VehicleDO> autoCandidateVehicles() {
        List<VehicleDO> vehicles = vehicleMapper.selectList();
        if (vehicles == null || vehicles.isEmpty() || driverVehicleMapper == null) {
            return vehicles == null ? List.of() : vehicles;
        }
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();
        if (bindings == null || bindings.isEmpty()) {
            return vehicles;
        }
        Set<Long> boundVehicleIds = bindings.stream().map(DriverVehicleDO::getVehicleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<VehicleDO> bound = vehicles.stream().filter(v -> boundVehicleIds.contains(v.getId())).toList();
        return bound.isEmpty() ? vehicles : bound;
    }

    @Override
    @Transactional
    public void reviewPlan(DispatchPlanReviewReqVO reqVO) {
        DispatchPlanDO plan = validatePlanExists(reqVO.getPlanId());
        if (!Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.PENDING.getStatus())) {
            throw exception(DISPATCH_PLAN_STATUS_ILLEGAL);
        }
        if (Boolean.TRUE.equals(reqVO.getApprove())) {
            plan.setStatus(DispatchPlanStatusEnum.ISSUED.getStatus());
            plan.setApprovedBy(SecurityFrameworkUtils.getLoginUserId());
            plan.setApprovedTime(LocalDateTime.now());
            dispatchPlanMapper.updateById(plan);
            // 娲惧崟涓嬪彂鍚庯細缁欐柟妗堟秹鍙婅溅杈嗗徃鏈哄彂寰�淇¤�㈤槄娑堟伅锛堝彂閫佸け璐ヤ笉褰卞搷娲惧崟涓绘祦绋嬶級
            notifyDriversOfPlan(plan.getId());

              // Notify order users: plan issued
              try {
                  List<Long> orderIds = selectPlanOrderIds(plan.getId(), null);
                  DispatchPlanDO planInfo = dispatchPlanMapper.selectById(plan.getId());
                  String planNo = planInfo != null ? planInfo.getPlanNo() : String.valueOf(plan.getId());
                  for (Long orderId : orderIds) {
                      userNotificationService.sendToOrderUser(orderId, TransportOrderEventTypeEnum.PLAN_ISSUED,
                              cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.SUCCESS, false,
                              "\u8fd0\u8f93\u65b9\u6848\u5df2\u4e0b\u53d1",
                              "\u60a8\u7684\u8ba2\u5355\u5df2\u5b89\u6392\u8fd0\u8f93\uff08\u65b9\u6848" + planNo + "\uff09\uff0c\u53f8\u673a\u5c06\u5728\u6307\u5b9a\u7ad9\u70b9\u53d6\u4ef6\uff0c\u8bf7\u5173\u6ce8\u914d\u9001\u8fdb\u5ea6");
                  }
              } catch (Exception ex) {
                  log.warn("[reviewPlan] notify user plan issued failed", ex);
              }
        } else {
            if (StrUtil.isBlank(reqVO.getReason())) {
                throw exception(BAD_REQUEST);
            }
            plan.setStatus(DispatchPlanStatusEnum.VOID.getStatus());
            dispatchPlanMapper.updateById(plan);
            // 椹冲洖鍚庢柟妗堝唴璁㈠崟鍥炲埌璁㈠崟姹狅紝鍙�閲嶆柊娲惧�?
            updateOrdersStatus(selectPlanOrderIds(reqVO.getPlanId(), null), TransportOrderStatusEnum.POOLED,
                    TransportOrderStatusEnum.ASSIGNED);
        }
        insertPlanLog(plan.getId(), DispatchPlanStatusEnum.PENDING.getStatus(), plan.getStatus(), reqVO.getReason());
    }

    /**
     * 娲惧崟涓嬪彂鍚庯細缁欐柟妗堟秹鍙婅溅杈嗗徃鏈哄彂寰�淇¤�㈤槄娑堟伅銆屾柊娲惧崟浠诲姟銆嶃�?
     *
     * 銆愰�勭暀椤� 路 褰撳墠鏈�鐢熸晥銆戜釜浜轰富浣撳皬绋嬪簭鍦ㄥ井淇″叕浼楀钩鍙扮湅涓嶅埌璁㈤槄娑堟伅鍏ュ彛/妯℃澘锛?
     * 闇�绛夊皬绋嬪簭鎹�銆岀粍缁囦富浣撱�嶅苟鍦ㄥ叕浼楀钩鍙扮敵璇疯�㈤槄娑堟伅妯℃澘鍚庢墠鑳界湡姝ｆ帹閫佸埌鍙告満寰�淇°�?
     * 钀藉湴鏉′欢锛圱ODO锛夛細
     *   1. 灏忕▼搴忔崲缁勭粐涓讳綋锛堜釜浜轰富浣?鈫?浼佷笟/缁勭粐锛夛紱
     *   2. 寰�淇″叕浼楀钩鍙般�屽姛鑳?鈫?璁㈤槄娑堟伅銆嶇敵璇蜂竴娆℃�ц�㈤槄妯℃澘锛屾ā鏉挎爣棰樹笌姝ゅ�?templateTitle 瀵归綈锛?
     *   3. 鍚庣�� wx.miniapp.appid/secret 閰嶆垚鐪熷疄灏忕▼搴忥紙寤鸿��璧扮幆澧冨彉閲?WX_MINIAPP_APPID/SECRET锛宻ecret 涓嶅叆搴擄級銆?
     *
                // Task summary for driver
                List<DispatchPlanItemDO> driverItems = items.stream()
                        .filter(it -> Objects.equals(it.getVehicleId(), vehicleId)).toList();
                int stopCount = driverItems.size();
                int orderCount = (int) driverItems.stream()
                        .map(DispatchPlanItemDO::getOrderId).filter(Objects::nonNull).distinct().count();
                String taskSummary = stopCount + "\u4e2a\u7ad9\u70b9" + (orderCount > 0 ? "\u3001" + orderCount + "\u5355\u8d27\u7269" : "");

                // WeChat subscription message
                socialClientApi.sendWxaSubscribeMessage(new SocialWxaSubscribeMessageSendReqDTO()
                        .setUserId(member.getId())
                        .setUserType(UserTypeEnum.MEMBER.getValue())
                        .setTemplateTitle("\u6d3e\u5355\u901a\u77e5")
                        .setPage("pages/driver/workbench/workbench")
                        .addMessage("thing1", "\u65b0\u4efb\u52a1\uff1a" + taskSummary)
                        .addMessage("thing2", "\u8bf7\u67e5\u770b\u53f8\u673a\u7aef\u5de5\u4f5c\u53f0"));

                // In-app notification for driver message center
                userNotificationService.sendToDriver(driverId,
                        TransportOrderEventTypeEnum.LEG_ASSIGNED,
                        cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.ACTION_REQUIRED,
                        true, "\u60a8\u6709\u65b0\u7684\u8fd0\u8f93\u4efb\u52a1",
                        "\u65b9\u6848#" + planId + "\u5df2\u5206\u914d\u7ed9\u60a8\uff1a" + taskSummary + "\uff0c\u8bf7\u53ca\u65f6\u63a5\u5355",
                        null, planId, null);

            }
        } catch (Exception e) {
            log.warn("[notifyDriversOfPlan][planId={} 鍙戦�佽�㈤槄娑堟伅澶辫触锛屼笉褰卞搷娲惧崟]", planId, e);
        }
    }

    @Override
    @Transactional
    public void departureCheck(DispatchCheckReqVO reqVO) {
        DispatchPlanDO plan = validatePlanExists(reqVO.getPlanId());
        if (!Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.ISSUED.getStatus())
                && !Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.RUNNING.getStatus())) {
            throw exception(DISPATCH_PLAN_STATUS_ILLEGAL);
        }
        departureCheckMapper.insert(DepartureCheckDO.builder()
                .planId(reqVO.getPlanId())
                .vehicleId(reqVO.getVehicleId())
                .result(Boolean.TRUE.equals(reqVO.getPass())
                        ? DepartureCheckResultEnum.PASS.getResult() : DepartureCheckResultEnum.REJECT.getResult())
                .remark(reqVO.getRemark())
                .checker(currentOperator())
                .build());
        if (!Boolean.TRUE.equals(reqVO.getPass())) {
            return;
        }
        // 鏍搁獙閫氳繃锛氭柟妗堣繘鍏ユ墽琛屼腑锛岃�ヨ溅杈嗙殑璁㈠崟缃�涓哄凡鍙戣溅
        if (Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.ISSUED.getStatus())) {
            plan.setStatus(DispatchPlanStatusEnum.RUNNING.getStatus());
            dispatchPlanMapper.updateById(plan);
            insertPlanLog(plan.getId(), DispatchPlanStatusEnum.ISSUED.getStatus(),
                    DispatchPlanStatusEnum.RUNNING.getStatus(), reqVO.getRemark());
        }
        updateOrdersStatus(selectPlanOrderIds(reqVO.getPlanId(), reqVO.getVehicleId()),
                TransportOrderStatusEnum.DEPARTED, TransportOrderStatusEnum.ASSIGNED);
    }

    @Override
    public DispatchPlanRespVO getPlan(Long id) {
        DispatchPlanDO plan = validatePlanExists(id);
        DispatchPlanRespVO respVO = BeanUtils.toBean(plan, DispatchPlanRespVO.class);
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getPlanId, id)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
        fillItemDisplayNames(items);
        respVO.setItems(items);
        // 鎽樿�侊紙涓�閿�鏅鸿兘璋冨害缁撴灉鍗�/鏂规�堝垪琛ㄧ敤锛夛細璁㈠崟鏁般�佽溅杈嗘暟銆佸満绔欏悕
        respVO.setOrderCount((int) items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).distinct().count());
        respVO.setVehicleCount((int) items.stream().map(DispatchPlanItemDO::getVehicleId)
                .filter(Objects::nonNull).distinct().count());
        Long depotStationId = items.stream().filter(i -> Objects.equals(i.getActionType(), 1))
                .map(DispatchPlanItemDO::getStationId).filter(Objects::nonNull).findFirst().orElse(null);
        if (depotStationId != null) {
            StationDO depot = stationMapper.selectById(depotStationId);
            respVO.setDepotStationName(depot != null ? depot.getStationName() : null);
        }
        return respVO;
    }

    @Override
    public PageResult<DispatchPlanDO> getPlanPage(DispatchPlanPageReqVO reqVO) {
        return dispatchPlanMapper.selectPage(reqVO);
    }

    /**
     * 鏂规�堢湡瀹為亾璺�鍦板浘鏁版嵁锛堣皟搴﹀彲瑙嗗寲鐢�锛夛�?     * 鎸夈�岃溅杈?+ 缁忓仠搴忓彿銆嶇粰鍑烘瘡娈碉紙涓婁竴绔欌啋鏈�绔欙級鐨勭湡瀹為亾璺�杞ㄨ抗锛?     * 楂樺痉涓嶅彲鐢?澶辫触鏃惰�ユ�甸��鍖栨垚涓ょ偣鐩寸嚎骞舵爣娉?{@code EUCLIDEAN}锛堜笉浼�瑁呯湡瀹為亾璺�锛夈�?     */
    @Override
    public DispatchRoadmapRespVO getPlanRoadmap(Long id) {
        validatePlanExists(id);
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getPlanId, id)
                .orderByAsc(DispatchPlanItemDO::getVehicleId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
        DispatchRoadmapRespVO respVO = new DispatchRoadmapRespVO();
        respVO.setPlanId(id);
        if (items.isEmpty()) {
            respVO.setProvider("EUCLIDEAN");
            respVO.setSegments(List.of());
            return respVO;
        }
        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, StationDO> stationMap = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectBatchIds(stationIds).stream()
                        .collect(Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));

        List<DispatchRoadmapRespVO.Segment> segments = new ArrayList<>();
        boolean anyReal = false;
        boolean anyFallback = false;
        // 鎸夎溅杈嗗垎缁勶細缁勫唴 visitSequence 鍗囧簭锛岀浉閭讳袱绔欐瀯鎴愪竴娈?        Map<Long, List<DispatchPlanItemDO>> byVehicle = new LinkedHashMap<>();
        for (DispatchPlanItemDO item : items) {
            byVehicle.computeIfAbsent(item.getVehicleId() == null ? 0L : item.getVehicleId(),
                    k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<Long, List<DispatchPlanItemDO>> entry : byVehicle.entrySet()) {
            List<DispatchPlanItemDO> stops = entry.getValue();
            stops.sort(Comparator.comparing(DispatchPlanItemDO::getVisitSequence,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = 1; i < stops.size(); i++) {
                StationDO from = stops.get(i - 1).getStationId() == null ? null
                        : stationMap.get(stops.get(i - 1).getStationId());
                StationDO to = stops.get(i).getStationId() == null ? null
                        : stationMap.get(stops.get(i).getStationId());
                if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                        || to.getLongitude() == null || to.getLatitude() == null) {
                    continue;
                }
                List<double[]> road = roadPolylineService.route(
                        from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                        to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
                boolean real = road != null && road.size() >= 2;
                if (real) {
                    anyReal = true;
                } else {
                    anyFallback = true;
                }
                List<double[]> points = real ? road : List.of(
                        new double[]{from.getLongitude().doubleValue(), from.getLatitude().doubleValue()},
                        new double[]{to.getLongitude().doubleValue(), to.getLatitude().doubleValue()});
                DispatchRoadmapRespVO.Segment segment = new DispatchRoadmapRespVO.Segment();
                segment.setVehicleId(stops.get(i).getVehicleId());
                segment.setVisitSequence(stops.get(i).getVisitSequence());
                segment.setFromStationId(from.getId());
                segment.setToStationId(to.getId());
                segment.setFromStationName(from.getStationName());
                segment.setToStationName(to.getStationName());
                segment.setProvider(real ? "AMAP" : "EUCLIDEAN");
                segment.setPoints(points.stream().map(p -> {
                    DispatchRoadmapRespVO.Point point = new DispatchRoadmapRespVO.Point();
                    point.setLongitude(p[0]);
                    point.setLatitude(p[1]);
                    return point;
                }).toList());
                segments.add(segment);
            }
        }
        respVO.setSegments(segments);
        respVO.setProvider(anyReal && anyFallback ? "MIXED" : (anyReal ? "AMAP" : "EUCLIDEAN"));
        return respVO;
    }

    /**
     * 涓ょ偣涔嬮棿鐨勭湡瀹為亾璺�杞ㄨ抗锛氳皟搴﹀彲瑙嗗寲銆屾寜璁㈠崟瑙嗚�掋�嶇粯鍒剁嚎璺�鐢ㄣ�?     * 杩愯緭娈佃惤搴撴椂鑻ラ珮寰蜂笉鍙�鐢ㄤ細閫�鍖栨垚涓ょ偣鐩寸嚎锛岃繖閲屾寜闇�琛ヤ竴娆＄湡瀹炶矾缃戯紙鏈嶅姟绔�鏈� 10 鍒嗛挓缂撳瓨锛夈�?     */
    @Override
    public List<DispatchRoadmapRespVO.Point> routeBetween(Double fromLongitude, Double fromLatitude,
                                                          Double toLongitude, Double toLatitude) {
        if (fromLongitude == null || fromLatitude == null || toLongitude == null || toLatitude == null) {
            return List.of();
        }
        List<double[]> road = roadPolylineService.route(fromLongitude, fromLatitude, toLongitude, toLatitude);
        if (road == null || road.size() < 2) {
            return List.of();
        }
        return road.stream().map(p -> {
            DispatchRoadmapRespVO.Point point = new DispatchRoadmapRespVO.Point();
            point.setLongitude(p[0]);
            point.setLatitude(p[1]);
            return point;
        }).toList();
    }

    /**
     * 琛ラ綈缁忓仠鏄庣粏鐨勫睍绀哄瓧娈碉紙绔欑偣鍚?璁㈠崟鍙凤紝鍧囦笉钀藉簱锛夛細
     * 鍚庡彴銆屾柟妗堣�︽儏銆嶄笌銆岃皟搴︾粨鏋滃彲瑙嗗寲銆嶄笉闇�瑕佸啀閫愭潯鍥炴煡绔欑偣涓庤�㈠崟銆?     */
    private void fillItemDisplayNames(List<DispatchPlanItemDO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> stationNames = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectList(new LambdaQueryWrapperX<StationDO>().in(StationDO::getId, stationIds))
                        .stream().collect(Collectors.toMap(StationDO::getId, StationDO::getStationName, (a, b) -> a));
        Set<Long> orderIds = items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orderNos = orderIds.isEmpty() ? Map.of()
                : orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>().in(TransportOrderDO::getId, orderIds))
                        .stream().collect(Collectors.toMap(TransportOrderDO::getId, TransportOrderDO::getOrderNo, (a, b) -> a));
        for (DispatchPlanItemDO item : items) {
            item.setStationName(item.getStationId() != null ? stationNames.get(item.getStationId()) : null);
            item.setOrderNo(item.getOrderId() != null ? orderNos.get(item.getOrderId()) : null);
        }
    }

    @Override
    public DispatchValidateRespVO validate(DispatchValidateReqVO reqVO) {
        // 璁㈠崟姹狅紙涓庢櫤鑳芥淳鍗曞彇鏁颁竴鑷达細宸插叆姹犺�㈠崟锛�
        List<TransportOrderDO> pooledOrders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));
        // 鑷�鍔ㄦā寮忥細鍦虹珯涓庡�欓�夎溅杈嗙敱鍚庣��纭�瀹氭�ц�勫垯鎺ㄥ�硷紙UI 鍙�鐐逛竴娆°�屽紑濮嬫櫤鑳借皟搴︺�嶏級
        boolean auto = Boolean.TRUE.equals(reqVO.getAuto());
        StationDO depot;
        List<VehicleDO> vehicles;
        int availableVehicleCount;
        if (auto) {
            List<StationDO> stations = stationMapper.selectList();
            Map<Long, StationDO> stationMapForBatch = stations.stream()
                    .filter(s -> s.getId() != null)
                    .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity(), (a, b) -> a));
            // 涓?createSmartPlan 鍚屼竴鎵规�″彛寰勶細鏍￠獙鐪嬪埌鐨勮�㈠崟鏁板氨鏄�鏈�娆＄湡姝ｄ細琚�璋冨害鐨勮�㈠崟鏁?            pooledOrders = AutoDispatchPlanner.selectAutoBatch(pooledOrders, stationMapForBatch, MAX_ALGORITHM_ORDERS);
            depot = AutoDispatchPlanner.selectDepot(pooledOrders, stations);
            if (depot == null) {
                throw exception(STATION_NOT_EXISTS);
            }
            List<VehicleDO> available = autoCandidateVehicles();
            availableVehicleCount = AutoDispatchPlanner.selectVehicles(available, Integer.MAX_VALUE).size();
            // 涓?createSmartPlan 鍚屽彛寰勶細閬垮紑鍦ㄩ�旀柟妗堝崰鐢ㄧ殑杞﹁締锛屽叏閮ㄥ湪閫旀椂鍥為��
            vehicles = AutoDispatchPlanner.selectVehicles(available, MAX_ALGORITHM_VEHICLES, busyVehicleIds());
            if (vehicles.isEmpty()) {
                vehicles = AutoDispatchPlanner.selectVehicles(available, MAX_ALGORITHM_VEHICLES);
            }
            if (vehicles.isEmpty()) {
                throw exception(VEHICLE_NOT_EXISTS);
            }
        } else {
            if (reqVO.getDepotStationId() == null || reqVO.getVehicleIds() == null || reqVO.getVehicleIds().isEmpty()) {
                throw exception(VEHICLE_NOT_EXISTS);
            }
            depot = validateDepotExists(reqVO.getDepotStationId());
            vehicles = vehicleMapper.selectBatchIds(reqVO.getVehicleIds());
            // 杞﹁締瀛樺湪鎬ф牎楠岋紙鍙ｅ緞瀵归綈 createManualPlan 鐨?validateVehicleExists锛?            if (vehicles.size() < reqVO.getVehicleIds().size()) {
                throw exception(VEHICLE_NOT_EXISTS);
            }
            availableVehicleCount = vehicles.size();
        }
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity()));
        // 瀛愯〃涓�娆″姞杞斤紝鍐呭瓨鍖归厤锛堟秷闄ら�愬崟鏌ヨ�㈢�?N+1锛?
        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(pooledOrders);
        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(pooledOrders);
        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(pooledOrders);

        // 璁㈠崟缁熻�� + 绔欑偣浣滀笟鏍囪�� + 瀹㈣繍鏃跺簭妫�鏌?
        DispatchValidateRespVO.OrderStats stats = new DispatchValidateRespVO.OrderStats();
        stats.setPassengerCount(0);
        stats.setDeliveryCount(0);
        stats.setPickupCount(0);
        stats.setParcelCount(0);
        // 鍑�杞借嵎鍙岀淮搴﹀彛寰勶細娲鹃�佷欢 / 鎻芥敹浠?鍒嗗埆绱�璁★紙鍑虹▼娲鹃�併�佽繑绋嬫徑鏀讹紝璐т粨渚濇�″�嶇敤锛?
        int deliveryItems = 0;
        int pickupItems = 0;
        Map<Long, DispatchValidateRespVO.Marker> markerMap = new LinkedHashMap<>();
        List<DispatchValidateRespVO.TimeSeqIssue> issues = new ArrayList<>();
        for (TransportOrderDO order : pooledOrders) {
            if (Objects.equals(order.getOrderType(), 1)) { // 瀹㈣繍
                stats.setPassengerCount(stats.getPassengerCount() + getPassengerCount(order, passengerMap));
                addMarker(markerMap, order.getPickupStationId(), AlgorithmRouteStopDTO.ACTION_BOARD, stationMap);
                addMarker(markerMap, order.getDeliveryStationId(), AlgorithmRouteStopDTO.ACTION_ALIGHT, stationMap);
                if (order.getPickupStationId() == null || order.getDeliveryStationId() == null) {
                    issues.add(timeSeqIssue(order, "涓婅溅绔欐垨涓嬭溅绔欑己澶?));
                } else if (Objects.equals(order.getPickupStationId(), order.getDeliveryStationId())) {
                    issues.add(timeSeqIssue(order, "涓婅溅绔欎笌涓嬭溅绔欑浉鍚岋紝鍏堜笂鍚庝笅鏃跺簭鏃犳硶鎴愮珛"));
                }
            } else { // 璐ц繍/閭�蹇�浠讹細涓嬭溅绔欎负鍦虹珯 鈫?鎻芥敹(鏉戔啋鍦虹珯)锛屽惁鍒?鈫?娲鹃�?鍦虹珯鈫掓潙)
                boolean isPickup = Objects.equals(order.getDeliveryStationId(), depot.getId());
                if (isPickup) {
                    stats.setPickupCount(stats.getPickupCount() + 1);
                    pickupItems += getItemCount(order, cargoMap, postalMap);
                    addMarker(markerMap, order.getPickupStationId(), AlgorithmRouteStopDTO.ACTION_PICKUP, stationMap);
                } else {
                    stats.setDeliveryCount(stats.getDeliveryCount() + 1);
                    deliveryItems += getItemCount(order, cargoMap, postalMap);
                    addMarker(markerMap, order.getDeliveryStationId(), AlgorithmRouteStopDTO.ACTION_DELIVER, stationMap);
                }
                stats.setParcelCount(stats.getParcelCount() + getItemCount(order, cargoMap, postalMap));
            }
        }

        // 杩愬姏鏍￠獙锛氭�诲�归�?vs 鎬婚渶姹傦紝瓒呭嚭鍗抽�勮�︺�?
        // 杞借揣鎸夊噣杞借嵎鍙岀淮搴﹀彛寰勶細max(娲鹃�佷欢, 鎻芥敹浠? 涓庢�昏揣浠撳�归噺姣旇緝锛堜笌绠楁�?solver 涓�鑷达級锛?
        // 涓嶅啀鎶娿�屾淳閫?鎻芥敹銆嶅叏閮ㄧ疮璁★紙鏃у彛寰勬妸杩旂▼鍙�鐢ㄤ粨浣嶇�?0锛岄棽缃�杩愬姏鏃犳硶鍒╃敤锛夈�?
        DispatchValidateRespVO.CapacityCheck capacityCheck = new DispatchValidateRespVO.CapacityCheck();
        int passengerCapacity = vehicles.stream()
                .mapToInt(v -> v.getPassengerCapacity() != null ? v.getPassengerCapacity() : 0).sum();
        int cargoCapacity = vehicles.stream()
                .mapToInt(v -> v.getCargoCapacity() != null ? v.getCargoCapacity() : 0).sum();
        capacityCheck.setTotalPassengerCapacity(passengerCapacity);
        capacityCheck.setTotalCargoCapacity(cargoCapacity);
        capacityCheck.setPassengerExceed(Math.max(0, stats.getPassengerCount() - passengerCapacity));
        capacityCheck.setCargoExceed(Math.max(0, Math.max(deliveryItems, pickupItems) - cargoCapacity));
        capacityCheck.setOverCapacity(capacityCheck.getPassengerExceed() > 0 || capacityCheck.getCargoExceed() > 0);

        List<DispatchValidateRespVO.VehicleItem> vehicleItems = vehicles.stream().map(v -> {
            DispatchValidateRespVO.VehicleItem item = new DispatchValidateRespVO.VehicleItem();
            item.setVehicleId(v.getId());
            item.setPlateNo(v.getPlateNo());
            item.setPassengerCapacity(v.getPassengerCapacity());
            item.setCargoCapacity(v.getCargoCapacity());
            return item;
        }).toList();

        DispatchValidateRespVO respVO = new DispatchValidateRespVO();
        respVO.setOrderStats(stats);
        respVO.setVehicles(vehicleItems);
        respVO.setCapacityCheck(capacityCheck);
        respVO.setMarkers(markerMap.values().stream().toList());
        respVO.setTimeSeqIssues(issues);
        respVO.setAuto(auto);
        respVO.setDepotStationId(depot.getId());
        respVO.setDepotStationName(depot.getStationName());
        respVO.setAvailableVehicleCount(availableVehicleCount);
        return respVO;
    }

    /** 鑱氬悎绔欑偣浣滀笟鏍囪�帮細鍚岀珯澶氫釜鍔ㄤ綔鍘婚噸锛岃�㈠崟鏁扮疮鍔?*/
    private void addMarker(Map<Long, DispatchValidateRespVO.Marker> markerMap, Long stationId, String action,
                           Map<Long, StationDO> stationMap) {
        if (stationId == null) {
            return;
        }
        DispatchValidateRespVO.Marker marker = markerMap.computeIfAbsent(stationId, k -> {
            DispatchValidateRespVO.Marker m = new DispatchValidateRespVO.Marker();
            m.setStationId(k);
            StationDO station = stationMap.get(k);
            m.setStationName(station != null ? station.getStationName() : null);
            m.setLongitude(station != null ? toDouble(station.getLongitude()) : null);
            m.setLatitude(station != null ? toDouble(station.getLatitude()) : null);
            m.setTypes(new ArrayList<>());
            m.setOrderCount(0);
            return m;
        });
        if (!marker.getTypes().contains(action)) {
            marker.getTypes().add(action);
        }
        marker.setOrderCount(marker.getOrderCount() + 1);
    }

    private DispatchValidateRespVO.TimeSeqIssue timeSeqIssue(TransportOrderDO order, String issue) {
        DispatchValidateRespVO.TimeSeqIssue item = new DispatchValidateRespVO.TimeSeqIssue();
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        item.setIssue(issue);
        return item;
    }

    private static Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    @Override
    public DispatchSettlementRespVO settlement(DispatchSettlementReqVO reqVO) {
        if (reqVO.getBatchStart() == null || reqVO.getBatchEnd() == null
                || !reqVO.getBatchStart().isBefore(reqVO.getBatchEnd())) {
            throw exception(BAD_REQUEST);
        }
        // 鎵ц�屼�?宸插畬鎴愭柟妗堬紙杩旂▼缁撶畻鍙ｅ緞锛氭柟妗堢姸鎬?IN (2 鎵ц�屼�? 3 宸插畬鎴?锛涙柟妗?COMPLETED 娴佽浆鐣欏緟鍚庣画杩�浠ｏ紝褰撳墠缁堟�佷负鎵ц�屼腑锛�
        List<DispatchPlanDO> plans = dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                .in(DispatchPlanDO::getStatus, DispatchPlanStatusEnum.RUNNING.getStatus(),
                        DispatchPlanStatusEnum.COMPLETED.getStatus())
                .between(DispatchPlanDO::getCreateTime, reqVO.getBatchStart(), reqVO.getBatchEnd()));
        DispatchSettlementRespVO respVO = new DispatchSettlementRespVO();
        if (plans.isEmpty()) {
            respVO.setTotalDistance(BigDecimal.ZERO);
            respVO.setPassengerCount(0);
            respVO.setParcelCount(0);
            respVO.setAvgPassengerWaitMinutes(null);
            respVO.setPerVehicle(List.of());
            return respVO;
        }
        List<Long> planIds = plans.stream().map(DispatchPlanDO::getId).toList();
        Map<Long, DispatchPlanDO> planMap = plans.stream()
                .collect(Collectors.toMap(DispatchPlanDO::getId, java.util.function.Function.identity()));
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .in(DispatchPlanItemDO::getPlanId, planIds));

        // 鎬婚噷绋?
        BigDecimal totalDistance = plans.stream()
                .map(DispatchPlanDO::getTotalDistance).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 缁忓仠鏄庣粏 鈫?璁㈠崟銆佽�㈠崟鎵�灞炶溅杈?鏂规��
        Map<Long, Long> orderVehicleMap = new HashMap<>();
        Map<Long, Long> orderPlanMap = new HashMap<>();
        Set<Long> orderIds = new HashSet<>();
        for (DispatchPlanItemDO item : items) {
            if (item.getOrderId() != null) {
                orderVehicleMap.putIfAbsent(item.getOrderId(), item.getVehicleId());
                orderPlanMap.putIfAbsent(item.getOrderId(), item.getPlanId());
                orderIds.add(item.getOrderId());
            }
        }
        Map<Long, TransportOrderDO> orderMap = new HashMap<>();
        if (!orderIds.isEmpty()) {
            orderMapper.selectBatchIds(orderIds).forEach(o -> orderMap.put(o.getId(), o));
        }
        // 瀛愯〃涓�娆″姞杞斤紝鍐呭瓨鍖归厤锛堟秷闄ら�愬崟鏌ヨ�㈢�?N+1锛?
        List<TransportOrderDO> itemOrders = List.copyOf(orderMap.values());
        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(itemOrders);
        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(itemOrders);
        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(itemOrders);

        // 姹囨�讳箻瀹?鍖呰９涓庡钩鍧囩瓑寰?
        Map<Long, Integer> vehiclePassenger = new HashMap<>();
        Map<Long, Integer> vehicleParcel = new HashMap<>();
        Map<Long, Set<Long>> vehiclePlans = new HashMap<>();
        int passengerCount = 0;
        int parcelCount = 0;
        List<Double> waits = new ArrayList<>();
        for (DispatchPlanItemDO item : items) {
            if (item.getVehicleId() != null && item.getPlanId() != null) {
                vehiclePlans.computeIfAbsent(item.getVehicleId(), k -> new HashSet<>()).add(item.getPlanId());
            }
        }
        for (TransportOrderDO order : orderMap.values()) {
            Long vehicleId = orderVehicleMap.get(order.getId());
            if (Objects.equals(order.getOrderType(), 1)) { // 瀹㈣繍
                int count = getPassengerCount(order, passengerMap);
                passengerCount += count;
                vehiclePassenger.merge(vehicleId, count, Integer::sum);
                // 骞冲潎绛夊緟锛氫笅鍗?鈫?鏂规�堝垱寤猴紙杩戜技锛�
                Long planId = orderPlanMap.get(order.getId());
                DispatchPlanDO plan = planId != null ? planMap.get(planId) : null;
                if (order.getCreateTime() != null && plan != null && plan.getCreateTime() != null) {
                    waits.add((double) Duration.between(order.getCreateTime(), plan.getCreateTime()).toMinutes());
                }
            } else { // 璐ц繍/閭�蹇�浠?
                int count = getItemCount(order, cargoMap, postalMap);
                parcelCount += count;
                vehicleParcel.merge(vehicleId, count, Integer::sum);
            }
        }
        // 鍒嗚溅姹囨�伙紙杞﹁締涓�娆℃壒閲忓姞杞斤紝娑堥櫎閫愯溅鏌ヨ��锛?
        Map<Long, VehicleDO> vehicleMap = new HashMap<>();
        if (!vehiclePlans.isEmpty()) {
            vehicleMapper.selectBatchIds(vehiclePlans.keySet()).forEach(v -> vehicleMap.put(v.getId(), v));
        }
        List<DispatchSettlementRespVO.VehicleSettlement> perVehicle = vehiclePlans.entrySet().stream()
                .map(e -> {
                    DispatchSettlementRespVO.VehicleSettlement vs = new DispatchSettlementRespVO.VehicleSettlement();
                    vs.setVehicleId(e.getKey());
                    VehicleDO vehicle = vehicleMap.get(e.getKey());
                    vs.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);
                    vs.setRunCount(e.getValue().size());
                    vs.setPassengerCount(vehiclePassenger.getOrDefault(e.getKey(), 0));
                    vs.setParcelCount(vehicleParcel.getOrDefault(e.getKey(), 0));
                    return vs;
                }).toList();

        respVO.setTotalDistance(totalDistance);
        respVO.setPassengerCount(passengerCount);
        respVO.setParcelCount(parcelCount);
        respVO.setAvgPassengerWaitMinutes(waits.isEmpty() ? null
                : Math.round(waits.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0);
        respVO.setPerVehicle(perVehicle);
        return respVO;
    }

    // ==================== 绉佹湁鏂规硶 ====================

    /** 绠楁硶瑙勬ā涓婇檺棰勬��锛?0 绔欑偣 / 25 璁㈠崟 / 3 杞︼級锛氬�㈣繍鎸変汉鏁版媶鍗曞悗鍙�鑳借秴 25 鍗曪紝瓒呴檺鐩存帴鎶ラ敊閬垮厤绛夌畻娉?413 */
    private void validateScaleLimit(AlgorithmPlanReqDTO algorithmReq) {
        int stationCount = algorithmReq.getStations() != null ? algorithmReq.getStations().size() : 0;
        int orderCount = algorithmReq.getOrders() != null ? algorithmReq.getOrders().size() : 0;
        int vehicleCount = algorithmReq.getVehicles() != null ? algorithmReq.getVehicles().size() : 0;
        if (stationCount > MAX_ALGORITHM_STATIONS
                || orderCount > MAX_ALGORITHM_ORDERS
                || vehicleCount > MAX_ALGORITHM_VEHICLES) {
            throw exception(DISPATCH_SCALE_OVER_LIMIT,
                    "绔欑偣 " + stationCount + " / 璁㈠崟 " + orderCount + " / 杞﹁締 " + vehicleCount);
        }
    }

    /** 浠庣畻娉曞揩鐓ф瀯寤?绔欑偣Id -> {lon, lat} 鍧愭爣琛�锛堝惈鍦虹珯锛夛紝鐢ㄤ簬鎶婂害鏁伴噷绋嬫崲绠椾负鐪熷疄鍏�閲?*/
    private static Map<String, double[]> buildCoordMap(AlgorithmPlanReqDTO algorithmReq) {
        Map<String, double[]> coordMap = new HashMap<>();
        if (algorithmReq.getDepot() != null && algorithmReq.getDepot().getLongitude() != null
                && algorithmReq.getDepot().getLatitude() != null) {
            coordMap.put(algorithmReq.getDepot().getStationId(),
                    new double[]{algorithmReq.getDepot().getLongitude(), algorithmReq.getDepot().getLatitude()});
        }
        if (algorithmReq.getStations() != null) {
            for (AlgorithmStationDTO station : algorithmReq.getStations()) {
                if (station.getLongitude() != null && station.getLatitude() != null) {
                    coordMap.put(station.getStationId(),
                            new double[]{station.getLongitude(), station.getLatitude()});
                }
            }
        }
        return coordMap;
    }

    /** 鏂规�堟�婚噷绋嬭В鏋愶細绠楁硶杩斿洖 km锛堣矾缃戣窛绂伙級鏃剁洿鎺ヤ娇鐢�锛沝egree锛堟垨缂虹渷锛屾�ф皬鐩寸嚎锛夋椂鎸夌粡鍋滃潗鏍� Haversine 鎹㈢畻 */
    private static BigDecimal resolveTotalDistanceKm(AlgorithmPlanRespDTO result, Map<String, double[]> coordMap) {
        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {
            double km = result.getTotalDistance() != null ? result.getTotalDistance() : 0;
            return BigDecimal.valueOf(Math.round(km * 1000) / 1000.0);
        }
        return computeTotalDistanceKm(result.getVehiclePlans(), coordMap);
    }

    /** 鎸夊悇杞︾粡鍋滃簭鍒楃敤 Haversine 绱�鍔犵湡瀹炲叕閲屾暟锛涘潗鏍囩己澶辩殑鍒嗘�佃烦杩囷紙涓嶈�伴噷绋嬶�?*/
    private static BigDecimal computeTotalDistanceKm(List<AlgorithmVehiclePlanDTO> vehiclePlans,
                                                     Map<String, double[]> coordMap) {
        if (vehiclePlans == null || vehiclePlans.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double total = 0;
        for (AlgorithmVehiclePlanDTO vehiclePlan : vehiclePlans) {
            List<AlgorithmRouteStopDTO> stops = vehiclePlan.getStops();
            if (stops == null || stops.size() < 2) {
                continue;
            }
            for (int i = 1; i < stops.size(); i++) {
                double[] from = coordMap.get(stops.get(i - 1).getStationId());
                double[] to = coordMap.get(stops.get(i).getStationId());
                if (from != null && to != null) {
                    total += GeoDistanceUtil.haversineKm(from[0], from[1], to[0], to[1]);
                }
            }
        }
        // 淇濈暀 3 浣嶅皬鏁板叕閲?
        return BigDecimal.valueOf(Math.round(total * 1000) / 1000.0);
    }

    /** 鏃犺В鍘熷洜鐮佽浆鍙�璇绘枃妗堬紙鍓嶇��鐩存帴灞曠ず锛� */
    private static String reasonCodeText(String reasonCode) {
        if (reasonCode == null) {
            return "鏈�鐭ュ師鍥�";
        }
        return switch (reasonCode) {
            case AlgorithmPlanRespDTO.REASON_OVER_CAPACITY -> "杩愬姏涓嶈冻锛堣�㈠崟鎬婚渶姹傝秴鍑哄彲鐢ㄨ溅杈嗘�诲�归噺锛�";
            case AlgorithmPlanRespDTO.REASON_TIMING_CONFLICT -> "瀹㈣繍涓?涓嬭溅鏃跺簭鍐茬獊锛屾棤娉曟帓绋?;
            case AlgorithmPlanRespDTO.REASON_PARTIAL_ONLY -> "褰撳墠璁㈠崟缁勫悎鍙�鑳介儴鍒嗗畬鎴愶紝鏃犳硶鐢熸垚瀹屾暣鏂规�?;
            // 鑷�鐮旂畻娉曟湇鍔★紙ortools锛夋墿灞曞師鍥犵爜锛氬�戠害浠呯害瀹氫笂涓夎�咃紝浠ヤ笅涓?V2 浼樺寲鏂板�炵殑缁嗗垎鍘熷�?
            case "TIME_WINDOW_EXCEEDED" -> "浠诲姟瓒呭嚭鎵规�℃椂闂寸獥锛屾棤娉曞湪鎸囧畾鏃舵�靛唴瀹屾垚";
            case "PRELOAD_INSUFFICIENT" -> "鍦虹珯棰勮�呰揣鐗╀笉瓒筹紝鏃犳硶瀹屾垚娲鹃�?;
            case "DISTANCE_MATRIX_INCOMPLETE" -> "璺�缃戣窛绂荤煩闃典笉瀹屾暣锛屾殏鏃犳硶瑙勫�?;
            case "ROUTE_DURATION_UNAVAILABLE" -> "璺�缃戣�岄┒鏃堕暱缂哄け锛屾殏鏃犳硶瑙勫垝";
            case "VEHICLE_NOT_FOUND" -> "鏂规�堝紩鐢ㄧ殑杞﹁締涓嶅瓨鍦�";
            default -> reasonCode;
        };
    }

    private StationDO validateDepotExists(Long depotStationId) {
        StationDO depot = stationMapper.selectById(depotStationId);
        if (depot == null) {
            throw exception(DISPATCH_DEPOT_NOT_EXISTS);
        }
        // 绔欑偣鍚�鐢� 鈮?鍙�鐢ㄤ簬璋冨害锛氭墜宸ユ淳鍗曞悓鏍疯�佹牎楠?鏄�鍚﹀紑鏀捐皟搴?锛堜笉鐩镐俊鍓嶇��锛?        if (!StationAccessUtil.dispatchEnabled(depot)) {
            throw exception(STATION_NOT_DISPATCH_ENABLED);
        }
        return depot;
    }

    private VehicleDO validateVehicleExists(Long vehicleId) {
        VehicleDO vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw exception(VEHICLE_NOT_EXISTS);
        }
        return vehicle;
    }

    /** 鏍￠獙璁㈠崟鍏ㄩ儴瀛樺湪涓斿凡鍏ユ睜锛岃繑鍥?id -> 璁㈠崟鏄犲皠 */
    private Map<Long, TransportOrderDO> validatePooledOrders(List<Long> orderIds) {
        Map<Long, TransportOrderDO> orderMap = new HashMap<>();
        orderMapper.selectBatchIds(orderIds).forEach(order -> orderMap.put(order.getId(), order));
        for (Long orderId : orderIds) {
            TransportOrderDO order = orderMap.get(orderId);
            if (order == null || !Objects.equals(order.getStatus(), TransportOrderStatusEnum.POOLED.getStatus())) {
                throw exception(DISPATCH_ORDER_NOT_POOLED);
            }
        }
        return orderMap;
    }

    private DispatchPlanDO validatePlanExists(Long planId) {
        DispatchPlanDO plan = dispatchPlanMapper.selectById(planId);
        if (plan == null) {
            throw exception(DISPATCH_PLAN_NOT_EXISTS);
        }
        return plan;
    }

    /**
     * 鐝�娆＄嚎璺�鍏�浜ら�ㄦ灦锛圡andatory Passenger Service锛夛細鐝�娆� 鈫?绾胯矾 鈫?鎸?sequenceNo 鎺掑簭鐨勭珯鐐圭紪鍙凤紝
     * 鍘绘帀鍦虹珯锛堝満绔欑敱绠楁硶鑷�鍔ㄤ綔涓鸿捣缁堢偣锛夈�俿hiftId 涓虹┖/鐝�娆′笉瀛樺�?绾胯矾鏃犵珯鐐规椂杩斿洖 null锛堢函 VRP锛夈�?
     */
    private List<String> resolveSkeleton(Long shiftId, Long depotStationId) {
        if (shiftId == null) {
            return null;
        }
        ShiftDO shift = shiftMapper.selectById(shiftId);
        if (shift == null || shift.getRouteId() == null) {
            return null;
        }
        List<RouteStationDO> routeStations = routeStationMapper.selectListByRouteIds(List.of(shift.getRouteId()));
        if (routeStations.isEmpty()) {
            return null;
        }
        return routeStations.stream()
                .sorted(Comparator.comparing(RouteStationDO::getSequenceNo,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(rs -> String.valueOf(rs.getStationId()))
                .filter(sid -> !sid.equals(String.valueOf(depotStationId)))
                .collect(Collectors.toList());
    }

    /** 鏋勫缓绠楁硶瑙勫垝璇锋眰蹇�鐓э細绔欑�?= 鍦虹珯 + 璁㈠崟寮曠敤绔欑偣鍘婚噸锛泂keleton 闈炵┖鏃惰�ユ壒杞﹁締鎸夊叕浜ら�ㄦ灦缁忓仠锛堣仈鍚堣皟搴︼級 */
    private AlgorithmPlanReqDTO buildPlanRequest(StationDO depot, List<VehicleDO> vehicles,
                                                 List<TransportOrderDO> orders,
                                                 Map<String, Object> algorithmConfig, String scenario,
                                                 List<String> skeleton) {
        Set<Long> stationIds = new LinkedHashSet<>();
        orders.forEach(order -> {
            stationIds.add(order.getPickupStationId());
            stationIds.add(order.getDeliveryStationId());
        });
        stationIds.remove(depot.getId());
        AlgorithmStationDTO depotDTO = toStationDTO(depot);
        List<AlgorithmStationDTO> stations = new ArrayList<>();
        stations.add(depotDTO);
        stationMapper.selectBatchIds(stationIds).stream().map(this::toStationDTO).forEach(stations::add);
        List<AlgorithmVehicleDTO> vehicleDTOs = vehicles.stream()
                .map(vehicle -> AlgorithmVehicleDTO.builder()
                        .vehicleId(vehicle.getId())
                        .passengerCapacity(vehicle.getPassengerCapacity())
                        // 璐т粨浠舵暟鍙栬溅杈嗘。妗堬紝缂虹渷濂戠害榛樿�ゅ�?4
                        .cargoCapacity(vehicle.getCargoCapacity() != null
                                ? vehicle.getCargoCapacity() : AlgorithmVehicleDTO.DEFAULT_CARGO_CAPACITY)
                        // 鍏�浜ら�ㄦ灦锛圡andatory Passenger Service锛夛細鎸囧畾鐝�娆℃椂璇ヨ溅杈嗘寜绾胯矾绔欑偣缁忓�?
                        .skeleton(skeleton)
                        .build())
                .collect(Collectors.toList());
        // 瀛愯〃涓�娆″姞杞斤紝鍐呭瓨鍖归厤锛堟秷闄ら�愬崟鏌ヨ�㈢�?N+1锛?
        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(orders);
        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(orders);
        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(orders);
        List<AlgorithmOrderDTO> orderDTOs = orders.stream()
                .map(order -> toAlgorithmOrders(order, depot.getId(), passengerMap, cargoMap, postalMap))
                .flatMap(List::stream).collect(Collectors.toList());

        LocalDateTime[] batch = currentBatch();
        return AlgorithmPlanReqDTO.builder()
                .batchStart(batch[0].atOffset(BATCH_ZONE_OFFSET))
                .batchEnd(batch[1].atOffset(BATCH_ZONE_OFFSET))
                .depot(depotDTO)
                .stations(stations)
                .vehicles(vehicleDTOs)
                .orders(orderDTOs)
                .algorithmConfig(algorithmConfig)
                .scenario(scenario)
                .build();
    }

    private AlgorithmStationDTO toStationDTO(StationDO station) {
        return AlgorithmStationDTO.builder()
                .stationId(String.valueOf(station.getId()))
                .longitude(station.getLongitude() != null ? station.getLongitude().doubleValue() : null)
                .latitude(station.getLatitude() != null ? station.getLatitude().doubleValue() : null)
                .build();
    }

    /** 璁㈠崟鏄犲皠涓虹畻娉曡�㈠崟锛氬�㈣繍鎸変箻瀹㈡暟鎷嗗崟锛堜竴寮犵畻娉曞�㈣繍鍗� = 1 浜猴級锛?
     *  璐ц繍/閭�蹇�浠舵寜涓嬭溅绔欐槸鍚︿负鍦虹珯鍖哄垎锛氬満绔欌啋绔欑偣涓烘淳閫?DELIVERY)锛岀珯鐐光啋鍦虹珯涓烘徑鏀?PICKUP)銆?*/
    private List<AlgorithmOrderDTO> toAlgorithmOrders(TransportOrderDO order, Long depotStationId,
                                                      Map<Long, PassengerOrderDO> passengerMap,
                                                      Map<Long, CargoOrderDO> cargoMap,
                                                      Map<Long, PostalOrderDO> postalMap) {
        if (Objects.equals(order.getOrderType(), 1)) { // 瀹㈣繍
            List<AlgorithmOrderDTO> result = new ArrayList<>();
            for (String algorithmOrderId : passengerAlgorithmOrderIds(order, passengerMap)) {
                result.add(AlgorithmOrderDTO.builder()
                        .orderId(algorithmOrderId)
                        .orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                        .boardingStationId(String.valueOf(order.getPickupStationId()))
                        .alightingStationId(String.valueOf(order.getDeliveryStationId()))
                        .build());
            }
            return result;
        }
        // 鎻芥敹锛氭潙鈫掑満绔欙紙鏀惰揣绔欎负鍦虹珯锛夛紝绠楁硶缁忓仠涓婅溅绔欑偣骞惰�呰�?
        if (depotStationId != null && Objects.equals(order.getDeliveryStationId(), depotStationId)) {
            return Collections.singletonList(AlgorithmOrderDTO.builder()
                    .orderId(String.valueOf(order.getId()))
                    .orderType(AlgorithmOrderDTO.TYPE_PICKUP)
                    .stationId(String.valueOf(order.getPickupStationId()))
                    .itemCount(getItemCount(order, cargoMap, postalMap))
                    .build());
        }
        // 娲鹃�侊細鍦虹珯鈫掓潙
        return Collections.singletonList(AlgorithmOrderDTO.builder()
                .orderId(String.valueOf(order.getId()))
                .orderType(AlgorithmOrderDTO.TYPE_DELIVERY)
                .stationId(String.valueOf(order.getDeliveryStationId()))
                .itemCount(getItemCount(order, cargoMap, postalMap))
                .build());
    }

    /** 瀹㈣繍澶氫汉鍗曟媶鍒嗕负 "涓氬姟璁㈠崟鍙?搴忓彿" 鐨勭畻娉曡�㈠崟缂栧�?*/
    private List<String> passengerAlgorithmOrderIds(TransportOrderDO order, Map<Long, PassengerOrderDO> passengerMap) {
        int count = getPassengerCount(order, passengerMap);
        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(order.getId() + "#" + i);
        }
        return ids;
    }

    /** 棰勫姞杞藉�㈣繍瀛愯〃锛堟寜璁㈠崟缂栧彿绱㈠紩锛屾秷闄ら�愬崟鏌ヨ�㈢�?N+1锛?*/
    private Map<Long, PassengerOrderDO> preloadPassengerOrders(List<TransportOrderDO> orders) {
        List<Long> orderIds = orders.stream()
                .filter(order -> Objects.equals(order.getOrderType(), 1))
                .map(TransportOrderDO::getId).toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return passengerOrderMapper.selectList(new LambdaQueryWrapperX<PassengerOrderDO>()
                        .in(PassengerOrderDO::getOrderId, orderIds))
                .stream().collect(Collectors.toMap(PassengerOrderDO::getOrderId,
                        java.util.function.Function.identity(), (a, b) -> a));
    }

    /** 棰勫姞杞借揣杩愬瓙琛�锛堟寜璁㈠崟缂栧彿绱㈠紩锛屾秷闄ら�愬崟鏌ヨ�㈢�?N+1锛?*/
    private Map<Long, CargoOrderDO> preloadCargoOrders(List<TransportOrderDO> orders) {
        List<Long> orderIds = orders.stream()
                .filter(order -> Objects.equals(order.getOrderType(), 2))
                .map(TransportOrderDO::getId).toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return cargoOrderMapper.selectList(new LambdaQueryWrapperX<CargoOrderDO>()
                        .in(CargoOrderDO::getOrderId, orderIds))
                .stream().collect(Collectors.toMap(CargoOrderDO::getOrderId,
                        java.util.function.Function.identity(), (a, b) -> a));
    }

    /** 棰勫姞杞介偖蹇�浠跺瓙琛�锛堟寜璁㈠崟缂栧彿绱㈠紩锛屾秷闄ら�愬崟鏌ヨ�㈢�?N+1锛?*/
    private Map<Long, PostalOrderDO> preloadPostalOrders(List<TransportOrderDO> orders) {
        List<Long> orderIds = orders.stream()
                .filter(order -> Objects.equals(order.getOrderType(), 3))
                .map(TransportOrderDO::getId).toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return postalOrderMapper.selectList(new LambdaQueryWrapperX<PostalOrderDO>()
                        .in(PostalOrderDO::getOrderId, orderIds))
                .stream().collect(Collectors.toMap(PostalOrderDO::getOrderId,
                        java.util.function.Function.identity(), (a, b) -> a));
    }

    /** 瀹㈣繍浜烘暟鍙栬嚜棰勫姞杞藉瓙琛�锛岀己鐪� 1 浜?*/
    private int getPassengerCount(TransportOrderDO order, Map<Long, PassengerOrderDO> passengerMap) {
        PassengerOrderDO sub = passengerMap.get(order.getId());
        return sub == null || sub.getPassengerCount() == null ? 1 : sub.getPassengerCount();
    }

    /** 绠楁硶璁㈠崟缂栧彿杩樺師涓氬姟璁㈠崟缂栧彿锛氬幓鎺?"#搴忓彿" 鎷嗗崟鍚庣紑 */
    private static Long toBusinessOrderId(String algorithmOrderId) {
        int suffixIndex = algorithmOrderId.indexOf('#');
        return Long.valueOf(suffixIndex >= 0 ? algorithmOrderId.substring(0, suffixIndex) : algorithmOrderId);
    }

    /** 璐ц繍/閭�蹇�浠朵欢鏁板彇鑷�棰勫姞杞藉瓙琛�锛岀己鐪?1 浠?*/
    private int getItemCount(TransportOrderDO order, Map<Long, CargoOrderDO> cargoMap,
                             Map<Long, PostalOrderDO> postalMap) {
        Integer itemCount = null;
        if (Objects.equals(order.getOrderType(), 2)) { // 璐ц繍
            CargoOrderDO sub = cargoMap.get(order.getId());
            itemCount = sub != null ? sub.getItemCount() : null;
        } else if (Objects.equals(order.getOrderType(), 3)) { // 閭�蹇�浠?
            PostalOrderDO sub = postalMap.get(order.getId());
            itemCount = sub != null ? sub.getItemCount() : null;
        }
        return itemCount != null ? itemCount : 1;
    }

    private AlgorithmRouteStopDTO buildStop(Long stationId, String orderId, String action) {
        return AlgorithmRouteStopDTO.builder()
                .stationId(String.valueOf(stationId))
                .orderId(orderId)
                .action(action)
                .build();
    }

    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,
                                      String algorithmVersion, String parameterVersion) {
        DispatchPlanDO plan = DispatchPlanDO.builder()
                .taskId(task.getId())
                .planVersion(1)
                .mode(mode.getMode())
                .algorithmVersion(algorithmVersion)
                .parameterVersion(parameterVersion)
                .totalDistance(totalDistance)
                .status(DispatchPlanStatusEnum.PENDING.getStatus())
                .build();
        dispatchPlanMapper.insert(plan);
        return plan;
    }

    /** 缁忓仠鏄庣粏钀藉簱锛歷isit_sequence 浠?1 閫掑�烇紱琛ュ～鍙告満褰掑睘锛堟寜杞﹁締褰撳墠鏈夋晥浜鸿溅缁戝畾锛夈�?
     *  棰勮�″埌杈炬椂闂寸�?{@link DispatchEstimationService#estimatePlan} 鍦ㄦ槑缁嗚惤搴撳悗缁熶竴浼扮畻鍥炲啓 */
    private void insertPlanItems(Long planId, Long vehicleId, List<AlgorithmRouteStopDTO> stops) {
        Long driverId = resolveDriverId(vehicleId);
        for (int i = 0; i < stops.size(); i++) {
            AlgorithmRouteStopDTO stop = stops.get(i);
            PlanItemActionEnum action = PlanItemActionEnum.fromCode(stop.getAction());
            var itemBuilder = DispatchPlanItemDO.builder()
                    .planId(planId)
                    .vehicleId(vehicleId)
                    .driverId(driverId)
                    .stationId(stop.getStationId() != null ? Long.valueOf(stop.getStationId()) : null)
                    .orderId(stop.getOrderId() != null ? toBusinessOrderId(stop.getOrderId()) : null)
                    .visitSequence(i + 1)
                    .actionType(action != null ? action.getAction() : null);
            // 绠楁硶瑙ｉ噴锛圥hase 5锛夛細璐ц繍/鎻芥敹缁忓仠鎼哄甫鏈嶅姟鏂瑰紡/鏈嶅姟鐐?缁曡��/涔樺�㈠奖鍝�/鍘熷洜鐮?
            if (Boolean.TRUE.equals(stop.getAccepted()) || stop.getServiceMode() != null || stop.getReasonCode() != null
                    || stop.getPassengerImpact() != null) {
                itemBuilder.serviceMode(stop.getServiceMode())
                        .servicePointStationId(stop.getServicePoint() != null
                                ? Long.valueOf(stop.getServicePoint()) : null)
                        .detourDistanceKm(stop.getDetourDistance() != null
                                ? BigDecimal.valueOf(stop.getDetourDistance()) : null)
                        .detourDurationSeconds(stop.getDetourDuration() != null
                                ? stop.getDetourDuration().intValue() : null)
                        .passengerImpactSeconds(stop.getPassengerImpact() != null
                                ? stop.getPassengerImpact().intValue() : null)
                        .reasonCode(stop.getReasonCode());
            }
            dispatchPlanItemMapper.insert(itemBuilder.build());
        }
    }

    /** 鎸夎溅杈嗗綋鍓嶆湁鏁堜汉杞︾粦瀹氳В鏋愬徃鏈虹紪鍙凤紙绠楁硶娲惧崟缁撴灉鎸夊徃鏈哄彲鏌ョ殑鍓嶆彁锛?*/
    private Long resolveDriverId(Long vehicleId) {
        // 闃插尽锛氬崟娴嬬瓑闈?Spring 涓婁笅鏂囧彲鑳芥湭娉ㄥ叆 mapper锛屾�ゆ椂涓嶆淳鍙告満鍗冲�?
        if (vehicleId == null || driverVehicleMapper == null) {
            return null;
        }
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();
        if (bindings == null) {
            return null;
        }
        return bindings.stream()
                .filter(bind -> Objects.equals(bind.getVehicleId(), vehicleId))
                .map(DriverVehicleDO::getDriverId)
                .findFirst()
                .orElse(null);
    }

    private void insertPlanLog(Long planId, Integer fromStatus, Integer toStatus, String reason) {
        dispatchPlanLogMapper.insert(DispatchPlanLogDO.builder()
                .planId(planId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .operator(currentOperator())
                .reason(reason)
                .build());
    }

    /** 鏂规�堝唴璁㈠崟缂栧彿锛泇ehicleId 涓虹┖琛ㄧず鏂规�堝叏閮ㄨ�㈠崟 */
    private List<Long> selectPlanOrderIds(Long planId, Long vehicleId) {
        return dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                        .eq(DispatchPlanItemDO::getPlanId, planId)
                        .eqIfPresent(DispatchPlanItemDO::getVehicleId, vehicleId)
                        .isNotNull(DispatchPlanItemDO::getOrderId))
                .stream().map(DispatchPlanItemDO::getOrderId).distinct().collect(Collectors.toList());
    }

    /**
     * 鎵归噺鎺ㄨ繘璁㈠崟鐘舵�侊紝骞剁害鏉熸潵婧愮姸鎬侊紙P1-003锛氶槻璺ㄧ姸鎬佽��鍐欙級銆?
     * source 涓?null 鏃朵笉绾︽潫鏉ユ簮锛堜粎鐢ㄤ簬鏃犳槑纭�鏉ユ簮鐨勫吋瀹瑰満鏅�锛夛紱寤鸿��濮嬬粓鏄惧紡浼犳潵婧愩�?
     */
    private void updateOrdersStatus(List<Long> orderIds, TransportOrderStatusEnum status,
                                    TransportOrderStatusEnum source) {
        if (orderIds.isEmpty()) {
            return;
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(status.getStatus());
        LambdaQueryWrapperX<TransportOrderDO> wrapper = new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, orderIds);
        if (source != null) {
            wrapper.eq(TransportOrderDO::getStatus, source.getStatus());
        }
        orderMapper.update(updateObj, wrapper);
    }

    /** 褰撳墠鍗婂皬鏃舵壒娆″尯闂达細鍒嗛挓 < 30 鍒?:00锛屽惁鍒?:30 */
    private static LocalDateTime[] currentBatch() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.withMinute(now.getMinute() < 30 ? 0 : 30).withSecond(0).withNano(0);
        return new LocalDateTime[]{start, start.plusMinutes(BATCH_MINUTES)};
    }

    /** 褰撳墠鎿嶄綔浜猴細浼樺厛鏄电О锛屽叾娆＄敤鎴风紪鍙?*/
    private static String currentOperator() {
        String nickname = SecurityFrameworkUtils.getLoginUserNickname();
        if (StrUtil.isNotBlank(nickname)) {
            return nickname;
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId != null ? String.valueOf(userId) : null;
    }

    private String generateTaskNo() {
        return "DT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

}


