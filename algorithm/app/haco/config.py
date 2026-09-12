"""HACO-CPS 1.4.1 算法配置。"""

from dataclasses import dataclass


def _get(config, name: str, default):
    """从 AlgorithmConfig 安全读取字段；不存在时返回 default。"""
    return getattr(config, name, default)


@dataclass
class HacoConfig:
    """HACO-CPS 运行参数。"""
    # ACO 核心参数
    ant_count: int = 24
    max_iterations: int = 50
    alpha: float = 1.0           # task-to-task 信息素权重
    alpha_gap: float = 0.5       # task-to-gap 信息素权重
    beta: float = 3.0            # 启发式权重
    rho: float = 0.1             # 蒸发率
    Q: float = 100.0             # 信息素增量常数
    convergence_threshold: int = 25
    random_seed: int = 20260903

    # 候选集
    candidate_size: int = 8

    # 精英蚂蚁
    elite_count: int = 4

    # 存档
    archive_size: int = 10

    # LNS 参数
    lns_probability: float = 0.30
    destroy_fraction: float = 0.25

    # 局部搜索
    local_search_rounds: int = 3
    ls_max_moves: int = 50       # 局部搜索最大移动次数

    # 时间预算（秒）
    haco_time_limit: float = 4.0
    overall_time_limit: float = 5.0

    # deterministic greedy seed 的独立时间预算（秒）。
    # greedy seed 不应独占整个 HACO search budget：
    # 超时返回部分 seed（complete=False），由快速 repair 补齐后再进入主搜索。
    greedy_seed_time_limit: float = 2.0

    # MMAS 信息素边界
    tau_min: float = 0.01
    tau_max: float = 10.0

    # 初始解来源比例
    baseline_fraction: float = 0.2

    # SA 接受准则
    initial_temperature: float = 10.0
    cooling_rate: float = 0.995
    temperature_min: float = 0.01

    # 启发式权重
    w_distance: float = 0.25
    w_passenger_impact: float = 0.25
    w_detour: float = 0.15
    w_time_risk: float = 0.15
    w_skeleton_penalty: float = 0.1
    w_capacity_risk: float = 0.1

    # 货运绕行硬约束（km）：车辆为送/取一单偏离运营路线的距离上限。
    # 超出该值的插入直接判不可行，订单留给多段联运（换乘站接力）而非让单条线路绕远。
    # 默认 2km；车辆闲置运力充足时可放宽到 3km（后端按车辆闲置运力传 max_detour_km 覆盖）。
    max_detour_km: float = 2.0

    # 自适应参数
    alpha_min: float = 0.5
    alpha_max: float = 3.0
    beta_min: float = 1.0
    beta_max: float = 5.0
    adaptive_diversity_low: float = 0.2
    adaptive_diversity_high: float = 0.8

    # 惩罚参数
    penalty_capacity: float = 1000.0
    penalty_time: float = 500.0
    penalty_skeleton: float = 2000.0
    target_feasible_ratio: float = 0.35

    # 重启参数
    restart_ratio: float = 0.5

    @classmethod
    def from_algorithm_config(cls, config) -> "HacoConfig":
        """从 PlanRequest.algorithmConfig 构建。

        所有能从 AlgorithmConfig 读取的字段全部读取；
        AlgorithmConfig 中不存在的字段使用 HacoConfig 类级默认值。
        """
        return cls(
            ant_count=_get(config, "ant_count", cls.ant_count),
            max_iterations=_get(config, "max_iterations", cls.max_iterations),
            alpha=_get(config, "alpha", cls.alpha),
            beta=_get(config, "beta", cls.beta),
            rho=_get(config, "rho", cls.rho),
            Q=_get(config, "Q", cls.Q),
            convergence_threshold=_get(config, "convergence_threshold", cls.convergence_threshold),
            random_seed=_get(config, "randomSeed", cls.random_seed),
            # 以下字段 AlgorithmConfig 可能通过 extra="allow" 传入
            candidate_size=_get(config, "candidate_size", cls.candidate_size),
            elite_count=_get(config, "elite_count", cls.elite_count),
            archive_size=_get(config, "archive_size", cls.archive_size),
            lns_probability=_get(config, "lns_probability", cls.lns_probability),
            destroy_fraction=_get(config, "destroy_fraction", cls.destroy_fraction),
            local_search_rounds=_get(config, "local_search_rounds", cls.local_search_rounds),
            ls_max_moves=_get(config, "ls_max_moves", cls.ls_max_moves),
            haco_time_limit=_get(config, "haco_time_limit", cls.haco_time_limit),
            overall_time_limit=_get(config, "overall_time_limit", cls.overall_time_limit),
            greedy_seed_time_limit=_get(config, "greedy_seed_time_limit", cls.greedy_seed_time_limit),
            tau_min=_get(config, "tau_min", cls.tau_min),
            tau_max=_get(config, "tau_max", cls.tau_max),
            initial_temperature=_get(config, "initial_temperature", cls.initial_temperature),
            cooling_rate=_get(config, "cooling_rate", cls.cooling_rate),
            temperature_min=_get(config, "temperature_min", cls.temperature_min),
            alpha_min=_get(config, "alpha_min", cls.alpha_min),
            alpha_max=_get(config, "alpha_max", cls.alpha_max),
            beta_min=_get(config, "beta_min", cls.beta_min),
            beta_max=_get(config, "beta_max", cls.beta_max),
            adaptive_diversity_low=_get(config, "adaptive_diversity_low", cls.adaptive_diversity_low),
            adaptive_diversity_high=_get(config, "adaptive_diversity_high", cls.adaptive_diversity_high),
            penalty_capacity=_get(config, "penalty_capacity", cls.penalty_capacity),
            penalty_time=_get(config, "penalty_time", cls.penalty_time),
            penalty_skeleton=_get(config, "penalty_skeleton", cls.penalty_skeleton),
            target_feasible_ratio=_get(config, "target_feasible_ratio", cls.target_feasible_ratio),
            restart_ratio=_get(config, "restart_ratio", cls.restart_ratio),
            max_detour_km=_get(config, "max_detour_km", cls.max_detour_km),
        )
