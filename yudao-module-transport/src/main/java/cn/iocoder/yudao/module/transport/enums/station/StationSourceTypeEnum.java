package cn.iocoder.yudao.module.transport.enums.station;

/**
 * 站点数据来源（现实交通 / 项目交通 / 模拟，三者必须区分，不互相伪装）。
 */
public enum StationSourceTypeEnum {

    /** 现实公交站点（高德等外部数据） */
    REAL,
    /** 项目自建货运站（平台数据） */
    PROJECT,
    /** 模拟演示站点 */
    SIMULATION;

    public static StationSourceTypeEnum parse(String code) {
        if (code == null) {
            return PROJECT;
        }
        for (StationSourceTypeEnum item : values()) {
            if (item.name().equalsIgnoreCase(code)) {
                return item;
            }
        }
        return PROJECT;
    }

}
