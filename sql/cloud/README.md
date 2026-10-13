# 云上数据库初始化脚本

## 执行顺序

| 序号 | 脚本 | 说明 | 前置依赖 |
|------|------|------|----------|
| 1 | `../mysql/ruoyi-vue-pro.sql` | 芋道框架基础表（system_users/system_roles/菜单等） | 无 |
| 2 | `../mysql/transport-schema.sql` | 业务表（transport_driver/transport_vehicle/站点/线路等） | #1 |
| 3 | `../incremental/V001~V027` | 增量迁移（按版本号顺序执行） | #2 |
| 4 | `../mysql/transport-menu.sql` | 业务菜单（管理端左侧导航） | #1 |
| 5 | **`V001__cloud_accounts_init.sql`** | 初始化账号（管理员+司机+用户） | #1~#4 全部完成 |

## 本目录脚本约束

- **只建账号，不含任何业务演示数据**（队友要求）
- 不含：线路、站点、车辆、商品、订单、调度方案
- 密码使用 BCrypt（`$2a$04$` 前缀，strength=4，与框架 SecurityProperties 默认值一致）
- 所有 INSERT 使用 `INSERT IGNORE` 防止重复执行报错

## 账号清单

| 角色 | 登录入口 | 账号 | 密码 |
|------|----------|------|------|
| 后台管理员 | Web 管理端 | `cargo_admin` | `admin123` |
| 普通用户 | Android App | `13900001001` | `123456` |
| 司机 | Android App | `13900001003` | `123456` |

## 司机登录链路

```
Android 输入 13900001003/123456 → 选"我是司机"
  → POST /app-api/member/auth/login → member_user.id=202
  → GET /app-api/transport/driver/profile
  → currentDriverOrNull() 按 memberId 查 member_user.mobile
  → driverMapper.selectByMobile("13900001003")
  → 返回 transport_driver.id=201 的档案
```

## 司机端启用前还需在管理端操作

1. 录入车辆（transport_vehicle）
2. 绑定司机-车辆（transport_driver_vehicle）
3. 创建班次（transport_shift）
4. 创建调度方案并下发给司机
