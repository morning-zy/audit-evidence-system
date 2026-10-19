package com.maning.audit.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 *
 * <p>为什么用注解 + AOP 而不是在每个方法里手写 logService.save(...)：
 * ① 日志记录是横切关注点，写进业务代码会污染主流程
 * ② 用注解声明式标注意图，切面统一处理，业务方法保持干净
 * ③ 想给新接口加日志，加个注解就行，不用改任何已有逻辑
 *
 * <p>这是 Spring AOP 最典型的落地场景，面试讲 AOP 时拿它举例最实在。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /** 所属模块 */
    String module() default "";

    /** 操作描述 */
    String value() default "";

    /** 是否记录请求参数 */
    boolean saveParams() default true;

    /** 是否记录返回结果 */
    boolean saveResult() default false;
}
