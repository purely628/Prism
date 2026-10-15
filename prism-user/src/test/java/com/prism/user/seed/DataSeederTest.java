package com.prism.user.seed;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.prism.common.constant.RedisKeys;
import com.prism.user.entity.AppEntity;
import com.prism.user.entity.ModelChannelEntity;
import com.prism.user.entity.UserEntity;
import com.prism.user.mapper.AppMapper;
import com.prism.user.mapper.ModelChannelMapper;
import com.prism.user.mapper.UserMapper;
import com.prism.user.service.AppService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSeeder 幂等逻辑（mock mapper，不依赖 DB/Redis）
 */
@ExtendWith(MockitoExtension.class)
class DataSeederTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private ModelChannelMapper channelMapper;
    @Mock
    private AppMapper appMapper;
    @Mock
    private AppService appService;
    @Mock
    private StringRedisTemplate redis;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private DataSeeder seeder;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯单测环境无 MyBatis 容器，lambda wrapper 需要预初始化实体元数据缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserEntity.class);
        TableInfoHelper.initTableInfo(assistant, AppEntity.class);
        TableInfoHelper.initTableInfo(assistant, ModelChannelEntity.class);
    }

    @BeforeEach
    void setUp() {
        seeder = new DataSeeder(userMapper, channelMapper, appMapper, appService, redis, encoder);
    }

    @Test
    @DisplayName("无 admin 时创建 admin/admin123（ADMIN + BCrypt 明文可校验通过）")
    void shouldSeedAdminWhenAbsent() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        // 渠道/演示应用已有数据，跳过对应分支
        when(channelMapper.selectCount(isNull())).thenReturn(2L);
        UserEntity admin = new UserEntity();
        admin.setId(1L);
        admin.setUsername("admin");
        when(userMapper.selectOne(any())).thenReturn(admin);
        when(appMapper.selectOne(any())).thenReturn(demoApp());

        seeder.run(null);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(captor.capture());
        UserEntity seeded = captor.getValue();
        assertEquals("admin", seeded.getUsername());
        assertEquals("ADMIN", seeded.getRole());
        assertEquals(1, seeded.getStatus());
        assertTrue(encoder.matches("admin123", seeded.getPassword()), "种子密码必须是 admin123 的 BCrypt 哈希");
    }

    @Test
    @DisplayName("admin 已存在时不重复插入")
    void shouldSkipAdminWhenExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(channelMapper.selectCount(isNull())).thenReturn(2L);
        when(userMapper.selectOne(any())).thenReturn(new UserEntity());
        when(appMapper.selectOne(any())).thenReturn(demoApp());

        seeder.run(null);

        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    @DisplayName("无渠道时写入 mock + GLM 两条种子渠道并失效渠道缓存")
    void shouldSeedChannelsWhenEmpty() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(channelMapper.selectCount(isNull())).thenReturn(0L);
        when(userMapper.selectOne(any())).thenReturn(new UserEntity());
        when(appMapper.selectOne(any())).thenReturn(demoApp());

        seeder.run(null);

        ArgumentCaptor<ModelChannelEntity> captor = ArgumentCaptor.forClass(ModelChannelEntity.class);
        verify(channelMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        var channels = captor.getAllValues();
        assertEquals("mock", channels.get(0).getProvider());
        assertEquals("mock://local", channels.get(0).getBaseUrl());
        assertEquals("prism-demo", channels.get(0).getAlias());
        assertEquals("glm", channels.get(1).getProvider());
        assertEquals("glm-4-flash", channels.get(1).getAlias());
        verify(redis).delete(RedisKeys.CHANNEL_CACHE);
    }

    @Test
    @DisplayName("渠道已存在时不重复插入")
    void shouldSkipChannelsWhenExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(channelMapper.selectCount(isNull())).thenReturn(2L);
        when(userMapper.selectOne(any())).thenReturn(new UserEntity());
        when(appMapper.selectOne(any())).thenReturn(demoApp());

        seeder.run(null);

        verify(channelMapper, never()).insert(any(ModelChannelEntity.class));
    }

    @Test
    @DisplayName("演示应用缺失时创建（admin 名下）并同步 Redis hash")
    void shouldCreateDemoAppWhenMissing() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(channelMapper.selectCount(isNull())).thenReturn(2L);
        UserEntity admin = new UserEntity();
        admin.setId(7L);
        when(userMapper.selectOne(any())).thenReturn(admin);
        when(appMapper.selectOne(any())).thenReturn(null);

        seeder.run(null);

        ArgumentCaptor<AppEntity> captor = ArgumentCaptor.forClass(AppEntity.class);
        verify(appMapper).insert(captor.capture());
        AppEntity created = captor.getValue();
        assertEquals(DataSeeder.DEMO_APP_KEY, created.getAppKey());
        assertEquals(7L, created.getUserId());
        assertEquals(1, created.getStatus());
        verify(appService).syncAppHash(any(AppEntity.class));
    }

    @Test
    @DisplayName("演示应用已存在时不重复插入，但每次启动都同步 Redis hash（自愈）")
    void shouldResyncDemoAppHashWhenExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(channelMapper.selectCount(isNull())).thenReturn(2L);
        when(userMapper.selectOne(any())).thenReturn(new UserEntity());
        AppEntity existing = demoApp();
        when(appMapper.selectOne(any())).thenReturn(existing);

        seeder.run(null);

        verify(appMapper, never()).insert(any(AppEntity.class));
        verify(appService).syncAppHash(existing);
    }

    private AppEntity demoApp() {
        AppEntity app = new AppEntity();
        app.setId(100L);
        app.setUserId(1L);
        app.setAppName("演示应用");
        app.setAppKey(DataSeeder.DEMO_APP_KEY);
        app.setMonthlyQuota(1_000_000L);
        app.setRateLimit(10);
        app.setStatus(1);
        return app;
    }
}
