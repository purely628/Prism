package com.prism.user.seed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.prism.user.entity.AppEntity;
import com.prism.user.entity.ModelChannelEntity;
import com.prism.user.entity.UserEntity;
import com.prism.user.mapper.AppMapper;
import com.prism.user.mapper.ModelChannelMapper;
import com.prism.user.mapper.UserMapper;
import com.prism.user.service.AppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动种子数据（幂等，可重复执行）：
 * 1) 无 admin 用户时创建 admin/admin123（ADMIN，BCrypt，init.sql 不插假哈希）；
 * 2) 无渠道时写入 init.sql 的两条内置渠道（mock + GLM 预留）；
 * 3) 确保演示应用（sk-prism-demo0000...）存在，且每次启动都同步 Redis hash（自愈）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_DEFAULT_PASSWORD = "admin123";
    public static final String DEMO_APP_KEY = "sk-prism-demo0000demo0000demo0000demo0000";
    public static final String DEMO_APP_NAME = "演示应用";
    public static final long DEMO_APP_QUOTA = 1_000_000L;
    public static final int DEMO_APP_RATE = 10;

    private final UserMapper userMapper;
    private final ModelChannelMapper channelMapper;
    private final AppMapper appMapper;
    private final AppService appService;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        seedChannels();
        seedDemoApp();
    }

    void seedAdmin() {
        Long count = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, ADMIN_USERNAME));
        if (count != null && count > 0) {
            return;
        }
        UserEntity admin = new UserEntity();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPassword(passwordEncoder.encode(ADMIN_DEFAULT_PASSWORD));
        admin.setNickname("管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("seeded default admin user ({}/***), id={}", ADMIN_USERNAME, admin.getId());
    }

    void seedChannels() {
        Long count = channelMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        // 与 sql/init.sql 种子渠道保持一致：mock 可离线演示，GLM 渠道预留（api_key 留空待控制台补）
        ModelChannelEntity mock = new ModelChannelEntity();
        mock.setChannelName("内置模拟渠道");
        mock.setProvider("mock");
        mock.setBaseUrl("mock://local");
        mock.setApiKey("");
        mock.setModelName("demo-mock");
        mock.setAlias("prism-demo");
        mock.setWeight(5);
        mock.setStatus(1);
        channelMapper.insert(mock);

        ModelChannelEntity glm = new ModelChannelEntity();
        glm.setChannelName("GLM-4-Flash");
        glm.setProvider("glm");
        glm.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        glm.setApiKey("");
        glm.setModelName("glm-4-flash");
        glm.setAlias("glm-4-flash");
        glm.setWeight(5);
        glm.setStatus(1);
        channelMapper.insert(glm);
        // 种子渠道写入后同样失效渠道缓存，保证 chat 服务读到最新启用列表
        redis.delete(com.prism.common.constant.RedisKeys.CHANNEL_CACHE);
        log.info("seeded 2 default model channels");
    }

    void seedDemoApp() {
        UserEntity admin = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, ADMIN_USERNAME));
        if (admin == null) {
            log.warn("demo app seed skipped: admin user missing");
            return;
        }
        AppEntity app = appMapper.selectOne(Wrappers.<AppEntity>lambdaQuery()
                .eq(AppEntity::getAppKey, DEMO_APP_KEY));
        if (app == null) {
            app = new AppEntity();
            app.setUserId(admin.getId());
            app.setAppName(DEMO_APP_NAME);
            app.setAppKey(DEMO_APP_KEY);
            app.setMonthlyQuota(DEMO_APP_QUOTA);
            app.setRateLimit(DEMO_APP_RATE);
            app.setStatus(1);
            appMapper.insert(app);
            log.info("seeded demo app, id={}", app.getId());
        }
        // 无论是否新建都全量同步 Redis hash：Redis 清空/漂移后重启即自愈
        appService.syncAppHash(app);
    }
}
