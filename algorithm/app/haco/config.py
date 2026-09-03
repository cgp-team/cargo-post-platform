"""HACO-CPS 算法配置。"""

from dataclasses import dataclass


@dataclass
class HacoConfig:
    """HACO-CPS 运行参数。"""
    # ACO 核心参数
    ant_count: int = 24
    max_iterations: int = 40
    alpha: float = 1.0       # 信息素权重
    beta: float = 3.0        # 启发式权重
    rho: float = 0.1         # 蒸发率
    Q: float = 100.0         # 信息素增量常数
    convergence_threshold: int = 20
    random_seed: int = 20260903

    # 候选集
    candidate_size: int = 8

    # 精英蚂蚁
    elite_count: int = 4

    # LNS 参数
    lns_probability: float = 0.25
    destroy_fraction: float = 0.3

    # 局部搜索
    local_search_rounds: int = 2

    # 时间预算（秒）
    haco_time_limit: float = 4.0
    overall_time_limit: float = 5.0

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
    w_distance: float = 0.3
    w_passenger_impact: float = 0.25
    w_detour: float = 0.2
    w_time_risk: float = 0.15
    w_skeleton_penalty: float = 0.1

    @classmethod
    def from_algorithm_config(cls, config) -> "HacoConfig":
        """从 PlanRequest.algorithmConfig 构建。"""
        return cls(
            ant_count=getattr(config, "ant_count", 24),
            max_iterations=getattr(config, "max_iterations", 40),
            alpha=getattr(config, "alpha", 1.0),
            beta=getattr(config, "beta", 3.0),
            rho=getattr(config, "rho", 0.1),
            Q=getattr(config, "Q", 100.0),
            convergence_threshold=getattr(config, "convergence_threshold", 20),
            random_seed=getattr(config, "randomSeed", 20260903),
        )
