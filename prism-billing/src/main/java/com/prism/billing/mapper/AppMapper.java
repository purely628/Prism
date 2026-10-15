package com.prism.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prism.billing.entity.App;

/** 只读 t_app（topApps 应用名 / quota 月配额）。演示环境共库直读，生产应服务间调用。 */
public interface AppMapper extends BaseMapper<App> {
}
