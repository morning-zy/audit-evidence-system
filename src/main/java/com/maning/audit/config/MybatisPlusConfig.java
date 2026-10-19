package com.maning.audit.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置
 *
 * <p>插件顺序很重要（README 里也会写）：
 * 分页插件必须放在最后，否则多插件叠加时可能被前面的插件改写 SQL 导致分页失效。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 乐观锁插件：实体上加 @Version 才生效（本项目未用，保留以便扩展）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 防全表更新/删除：没写 where 条件的 update/delete 直接拦截
        // 这是生产环境的保命插件，强烈建议保留
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        // 分页插件：必须放最后
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大 100 条，防止前端传 size=999999 把库打挂
        pagination.setMaxLimit(100L);
        // 页码超出范围时返回空列表，而不是回到第一页（避免前端误判）
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }

    /**
     * 自动填充：createTime / updateTime 不用手写
     *
     * <p>注意 strictInsertFill 只在字段为 null 时才填充，
     * 所以不要用它处理"业务上必须显式赋值"的字段。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
