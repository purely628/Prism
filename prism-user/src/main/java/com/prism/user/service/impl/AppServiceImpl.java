package com.prism.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.prism.common.constant.RedisKeys;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.user.dto.AppAuthInfoVO;
import com.prism.user.dto.AppCreateRequest;
import com.prism.user.dto.AppVO;
import com.prism.user.entity.AppEntity;
import com.prism.user.mapper.AppMapper;
import com.prism.user.service.AppService;
import com.prism.user.util.AppKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppServiceImpl implements AppService {

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private final AppMapper appMapper;
    private final StringRedisTemplate redis;

    @Override
    public List<AppVO> listByUser(long userId) {
        List<AppEntity> apps = appMapper.selectList(Wrappers.<AppEntity>lambdaQuery()
                .eq(AppEntity::getUserId, userId)
                .orderByDesc(AppEntity::getId));
        String month = YYYYMM.format(LocalDate.now());
        return apps.stream()
                .map(app -> AppVO.of(app, readUsedTokens(app.getId(), month)))
                .toList();
    }

    @Override
    public AppVO create(long userId, AppCreateRequest request) {
        AppEntity app = new AppEntity();
        app.setUserId(userId);
        app.setAppName(request.getAppName().trim());
        app.setAppKey(AppKeyGenerator.generate());
        app.setMonthlyQuota(request.getMonthlyQuota());
        app.setRateLimit(request.getRateLimit());
        app.setStatus(1);
        appMapper.insert(app);
        // 回读：createdAt/updatedAt 由数据库默认值填充
        AppEntity saved = appMapper.selectById(app.getId());
        if (saved != null) {
            app = saved;
        }
        syncAppHash(app);
        log.info("app created: id={}, appName={}, owner={}", app.getId(), app.getAppName(), userId);
        return AppVO.of(app, 0L);
    }

    @Override
    public String resetKey(long appId, long userId) {
        AppEntity app = getOwned(appId, userId);
        String oldKey = app.getAppKey();
        String newKey = AppKeyGenerator.generate();
        appMapper.update(null, Wrappers.<AppEntity>lambdaUpdate()
                .eq(AppEntity::getId, appId)
                .set(AppEntity::getAppKey, newKey));
        // 先删旧 key 的 hash（旧 key 立即失效），再写新 key
        redis.delete(RedisKeys.appInfo(oldKey));
        redis.delete(RedisKeys.rateLimit(oldKey));
        app.setAppKey(newKey);
        syncAppHash(app);
        log.info("app key reset: appId={}, old={}", appId, oldKey);
        return newKey;
    }

    @Override
    public void updateStatus(long appId, long userId, int status) {
        AppEntity app = getOwned(appId, userId);
        appMapper.update(null, Wrappers.<AppEntity>lambdaUpdate()
                .eq(AppEntity::getId, appId)
                .set(AppEntity::getStatus, status));
        if (status == 1) {
            app.setStatus(1);
            syncAppHash(app);
        } else {
            // 禁用：删除 hash，网关立即按无效 key 拒绝
            redis.delete(RedisKeys.appInfo(app.getAppKey()));
        }
        log.info("app status changed: appId={}, status={}", appId, status);
    }

    @Override
    public void updateQuota(long appId, long userId, long monthlyQuota) {
        AppEntity app = getOwned(appId, userId);
        appMapper.update(null, Wrappers.<AppEntity>lambdaUpdate()
                .eq(AppEntity::getId, appId)
                .set(AppEntity::getMonthlyQuota, monthlyQuota));
        // 启用状态才存在 hash；禁用状态本就无 hash，无需同步
        if (app.getStatus() != null && app.getStatus() == 1) {
            redis.opsForHash().put(RedisKeys.appInfo(app.getAppKey()), "quota",
                    String.valueOf(monthlyQuota));
        }
        log.info("app quota changed: appId={}, quota={}", appId, monthlyQuota);
    }

    @Override
    public void delete(long appId, long userId) {
        AppEntity app = getOwned(appId, userId);
        appMapper.deleteById(appId);
        redis.delete(RedisKeys.appInfo(app.getAppKey()));
        redis.delete(RedisKeys.rateLimit(app.getAppKey()));
        log.info("app deleted: appId={}", appId);
    }

    @Override
    public AppAuthInfoVO findAuthInfoByKey(String appKey) {
        AppEntity app = appMapper.selectOne(Wrappers.<AppEntity>lambdaQuery()
                .eq(AppEntity::getAppKey, appKey));
        if (app == null) {
            return null;
        }
        // 回填 Redis hash（网关 miss 才会来这里，写全量字段）
        syncAppHash(app);
        return new AppAuthInfoVO(app.getId(), app.getUserId(), app.getStatus(),
                app.getMonthlyQuota(), app.getRateLimit());
    }

    @Override
    public void syncAppHash(AppEntity app) {
        Map<String, String> hash = new HashMap<>();
        hash.put("appId", String.valueOf(app.getId()));
        hash.put("userId", String.valueOf(app.getUserId()));
        hash.put("status", String.valueOf(app.getStatus()));
        hash.put("quota", String.valueOf(app.getMonthlyQuota()));
        hash.put("rate", String.valueOf(app.getRateLimit()));
        redis.delete(RedisKeys.appInfo(app.getAppKey()));
        redis.opsForHash().putAll(RedisKeys.appInfo(app.getAppKey()), hash);
    }

    /** Redis 月已用 token 读取；Redis 异常时降级为 0，不影响列表展示 */
    private long readUsedTokens(Long appId, String month) {
        try {
            String value = redis.opsForValue().get(RedisKeys.quotaUsed(appId, month));
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("read used tokens failed, appId={}: {}", appId, e.getMessage());
            return 0L;
        }
    }

    private AppEntity getOwned(long appId, long userId) {
        AppEntity app = appMapper.selectById(appId);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        if (!app.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作他人的应用");
        }
        return app;
    }
}
