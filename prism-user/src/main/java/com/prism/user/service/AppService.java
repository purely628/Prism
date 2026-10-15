package com.prism.user.service;

import com.prism.user.dto.AppAuthInfoVO;
import com.prism.user.dto.AppCreateRequest;
import com.prism.user.dto.AppVO;
import com.prism.user.entity.AppEntity;

import java.util.List;

/**
 * 应用管理：DB + Redis app hash 双写一致性
 */
public interface AppService {

    /** 当前用户应用列表（含月已用 token 与配额进度） */
    List<AppVO> listByUser(long userId);

    /** 创建应用：生成 appKey，入库并写 Redis hash，返回完整视图 */
    AppVO create(long userId, AppCreateRequest request);

    /** 重置 appKey：更新 DB，删旧 hash 写新 hash，返回新 key */
    String resetKey(long appId, long userId);

    /** 启停：status=1 写 hash，status=0 删 hash */
    void updateStatus(long appId, long userId, int status);

    /** 修改月配额：更新 DB + 同步 hash quota 字段 */
    void updateQuota(long appId, long userId, long monthlyQuota);

    /** 删除应用：删 DB + Redis hash（顺带清限流桶） */
    void delete(long appId, long userId);

    /** 网关回源：按 appKey 查鉴权信息并回填 Redis hash；查无返回 null */
    AppAuthInfoVO findAuthInfoByKey(String appKey);

    /** 把应用当前状态全量写入 Redis hash prism:app:{appKey}（无 TTL） */
    void syncAppHash(AppEntity app);
}
