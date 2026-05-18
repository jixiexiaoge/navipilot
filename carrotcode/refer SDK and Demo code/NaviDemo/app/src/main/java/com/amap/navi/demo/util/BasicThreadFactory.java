package com.amap.navi.demo.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 包名： com.amap.api.navi.core
 * <p>
 * 创建时间：2018/3/12
 * 项目名称：AndroidNavigationSDK
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：
 */
public class BasicThreadFactory implements ThreadFactory {

    //计算线程工厂创建的线程数
    private final AtomicLong threadCounter;

    //包装工厂
    private final ThreadFactory wrappedFactory;

    //非捕获异常处理
    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    //线程命名模式
    private final String namingPattern;

    //优先级
    private final Integer priority;

    //后台状态标识
    private final Boolean daemonFlag;

    /**
     * Creates a new instance of {@code ThreadFactoryImpl} and configures it
     * from the specified {@code Builder} object.
     *
     * @param builder the {@code Builder} object
     */
    private BasicThreadFactory(Builder builder) {
        if (builder.wrappedFactory == null) {
            wrappedFactory = Executors.defaultThreadFactory();
        } else {
            wrappedFactory = builder.wrappedFactory;
        }

        namingPattern = builder.namingPattern;
        priority = builder.priority;
        daemonFlag = builder.daemonFlag;
        uncaughtExceptionHandler = builder.exceptionHandler;

        threadCounter = new AtomicLong();
    }

    /**
     * 获取包装工厂
     * @return 不会返回null
     */
    public final ThreadFactory getWrappedFactory() {
        return wrappedFactory;
    }

    /**
     * 获取命名模式
     * @return
     */
    public final String getNamingPattern() {
        return namingPattern;
    }

    /**
     * 获取是否为后台线程标识
     * @return
     */
    public final Boolean getDaemonFlag() {
        return daemonFlag;
    }

    /**
     * 获取优先级
     * @return
     */
    public final Integer getPriority() {
        return priority;
    }

    /**
     * 获取非捕获异常处理器
     * @return
     */
    public final Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler;
    }

    /**
     * 获取创建的线程数量
     * @return
     */
    public long getThreadCount() {
        return threadCounter.get();
    }

    /**
     * 创建新线程
     * @param r
     * @return
     */
    @Override
    public Thread newThread(Runnable r) {
        Thread t = getWrappedFactory().newThread(r);
        initializeThread(t);

        return t;
    }

    /**
     * 初始化线程
     * @param t
     */
    private void initializeThread(Thread t) {

        if (getNamingPattern() != null) {
            Long count = Long.valueOf(threadCounter.incrementAndGet());
            t.setName(String.format(getNamingPattern(), count));
        }

        if (getUncaughtExceptionHandler() != null) {
            t.setUncaughtExceptionHandler(getUncaughtExceptionHandler());
        }

        if (getPriority() != null) {
            t.setPriority(getPriority().intValue());
        }

        if (getDaemonFlag() != null) {
            t.setDaemon(getDaemonFlag().booleanValue());
        }
    }

    /**
     * 创建器类
     */
    public static class Builder implements IBuilder<BasicThreadFactory> {

        //包装工厂
        private ThreadFactory wrappedFactory;

        //非捕获异常处理器
        private Thread.UncaughtExceptionHandler exceptionHandler;

        //命名模式
        private String namingPattern;

        //优先级
        private Integer priority;

        //后台标识
        private Boolean daemonFlag;

        /**
         * 创建包装工厂
         * @param factory
         * @return
         */
        public Builder wrappedFactory(ThreadFactory factory) {
            if (factory == null) {
                throw new NullPointerException(
                        "Wrapped ThreadFactory must not be null!");
            }

            wrappedFactory = factory;
            return this;
        }

        /**
         * 设置命名模式
         * @param pattern
         * @return
         */
        public Builder namingPattern(String pattern) {
            if (pattern == null) {
                throw new NullPointerException(
                        "Naming pattern must not be null!");
            }

            namingPattern = pattern;
            return this;
        }

        /**
         * 设置后台标识
         * @param f
         * @return
         */
        public Builder daemon(boolean f) {
            daemonFlag = Boolean.valueOf(f);
            return this;
        }

        /**
         * 设置优先级
         * @param prio
         * @return
         */
        public Builder priority(int prio) {
            priority = Integer.valueOf(prio);
            return this;
        }

        /**
         * 设置非捕获异常处理器
         * @param handler
         * @return
         */
        public Builder uncaughtExceptionHandler(
                Thread.UncaughtExceptionHandler handler) {
            if (handler == null) {
                throw new NullPointerException(
                        "Uncaught exception handler must not be null!");
            }

            exceptionHandler = handler;
            return this;
        }

        /**
         * 重置构建参数
         */
        public void reset() {
            wrappedFactory = null;
            exceptionHandler = null;
            namingPattern = null;
            priority = null;
            daemonFlag = null;
        }

        /**
         * 构建基类线程工厂
         * @return
         */
        @Override
        public BasicThreadFactory build() {
            BasicThreadFactory factory = new BasicThreadFactory(this);
            reset();
            return factory;
        }
    }
}
