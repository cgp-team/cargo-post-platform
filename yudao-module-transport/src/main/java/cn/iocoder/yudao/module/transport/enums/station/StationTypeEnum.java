package cn.iocoder.yudao.module.transport.enums.station;

/**
 * 站点类型（公交站 / 货运站 / 混合）。
 */
public enum StationTypeEnum {

    /** 公交站（客运） */
    BUS_STOP,
    /** 货运站 */
    CARGO_STATION,
    /** 客货邮混合站 */
    MIXED;

    public static StationTypeEnum parse(String code) {
        if (code == null) {
            return CARGO_STATION;
        }
        for (StationTypeEnum item : values()) {
            if (item.name().equalsIgnoreCase(code)) {
                return item;
            }
        }
        return CARGO_STATION;
    }

}
