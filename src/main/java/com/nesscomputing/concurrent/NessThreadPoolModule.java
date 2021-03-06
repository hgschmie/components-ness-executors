/**
 * Copyright (C) 2012 Ness Computing, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nesscomputing.concurrent;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.mogwee.executors.LoggingExecutor;

import com.nesscomputing.concurrent.ThreadPoolConfiguration.RejectedHandler;
import com.nesscomputing.config.Config;
import com.nesscomputing.lifecycle.Lifecycle;
import com.nesscomputing.lifecycle.LifecycleListener;
import com.nesscomputing.lifecycle.LifecycleStage;
import com.nesscomputing.logging.Log;

import org.apache.commons.lang3.time.StopWatch;
import org.skife.config.TimeSpan;
import org.weakref.jmx.guice.MBeanModule;

/**
 * Guice bindings for a configurable, lifecycled {@link ExecutorService}.
 * The executor service is bound as {@code @Named(threadPoolName) ExecutorService myService}.
 * The service will be shut down during {@link LifecycleStage#STOP_STAGE}.  Configuration
 * has the prefix {@code ness.thread-pool.[pool-name]}.
 * @see ThreadPoolConfiguration Thread pool configuration options
 */
public class NessThreadPoolModule extends AbstractModule
{
    private static final Log LOG = Log.findLog();
    private final String threadPoolName;
    private final Annotation annotation;

    private int defaultMinThreads = ThreadPoolConfiguration.DEFAULT_MIN_THREADS;
    private int defaultMaxThreads = ThreadPoolConfiguration.DEFAULT_MAX_THREADS;
    private TimeSpan defaultTimeout = ThreadPoolConfiguration.DEFAULT_TIMEOUT;
    private int defaultQueueSize = ThreadPoolConfiguration.DEFAULT_QUEUE_SIZE;
    private RejectedExecutionHandler defaultRejectedHandler = ThreadPoolConfiguration.DEFAULT_REJECTED_HANDLER.getHandler();

    private boolean threadDelegatingWrapperEnabled = true;
    private boolean timingWrapperEnabled = true;

    NessThreadPoolModule(String threadPoolName)
    {
        this.threadPoolName = threadPoolName;
        this.annotation = Names.named(threadPoolName);
    }

    /**
     * Create a default thread pool.
     */
    public static NessThreadPoolModule defaultPool(String threadPoolName)
    {
        return new NessThreadPoolModule(threadPoolName);
    }

    /**
     * Create a thread pool for long-running tasks.  The default settings will change to not have a
     * run queue.
     */
    public static NessThreadPoolModule longTaskPool(String threadPoolName, int poolSize)
    {
        return new NessThreadPoolModule(threadPoolName).withDefaultMaxThreads(poolSize).withDefaultQueueSize(0);
    }

    /**
     * Create a thread pool for short-running tasks.  The default settings will change to have one thread
     * available per core, plus a few to pick up slack.
     */
    public static NessThreadPoolModule shortTaskPool(String threadPoolName, int queueSize)
    {
        return new NessThreadPoolModule(threadPoolName).withDefaultMaxThreads(Runtime.getRuntime().availableProcessors() + 2).withDefaultQueueSize(queueSize);
    }

    @Override
    protected void configure()
    {
        Multibinder.newSetBinder(binder(), CallableWrapper.class, annotation);

        PoolProvider poolProvider = new PoolProvider();

        bind (ExecutorService.class).annotatedWith(annotation).toProvider(poolProvider).in(Scopes.SINGLETON);
        bind (ExecutorServiceManagementBean.class).annotatedWith(annotation).toProvider(poolProvider.getManagementProvider());
        MBeanModule.newExporter(binder()).export(ExecutorServiceManagementBean.class).annotatedWith(annotation).as(createMBeanName());

        if (timingWrapperEnabled) {
            bindWrapper(binder()).toProvider(new TimerWrapperProvider(threadPoolName));
        }
        if (threadDelegatingWrapperEnabled) {
            bindWrapper(binder()).toInstance(ThreadDelegatingDecorator.THREAD_DELEGATING_WRAPPER);
        }
    }

    /**
     * Set the default pool core thread count.
     */
    public NessThreadPoolModule withDefaultMinThreads(int defaultMinThreads)
    {
        this.defaultMinThreads = defaultMinThreads;
        return this;
    }

    /**
     * Set the default pool max thread count.  May be 0, in which case the executor will be a
     * {@link MoreExecutors#sameThreadExecutor()}.
     */
    public NessThreadPoolModule withDefaultMaxThreads(int defaultMaxThreads)
    {
        this.defaultMaxThreads = defaultMaxThreads;
        return this;
    }

    /**
     * Set the default worker thread idle timeout.
     */
    public NessThreadPoolModule withDefaultThreadTimeout(long duration, TimeUnit units)
    {
        defaultTimeout = new TimeSpan(duration, units);
        return this;
    }

    /**
     * Set the default queue length.  May be 0, in which case the queue will be a
     * {@link SynchronousQueue}.
     */
    public NessThreadPoolModule withDefaultQueueSize(int defaultQueueSize)
    {
        this.defaultQueueSize = defaultQueueSize;
        return this;
    }

    /**
     * Set the default rejected execution handler.
     */
    public NessThreadPoolModule withDefaultRejectedHandler(RejectedExecutionHandler defaultRejectedHandler)
    {
        this.defaultRejectedHandler = defaultRejectedHandler;
        return this;
    }

    /**
     * Add a CallableWrapper that may decorate this executor service.
     */
    public LinkedBindingBuilder<CallableWrapper> bindWrapper(Binder binder)
    {
        return Multibinder.newSetBinder(binder, CallableWrapper.class, annotation).permitDuplicates().addBinding();
    }

    /**
     * Remove thread delegated wrapper.
     */
    public NessThreadPoolModule disableThreadDelegation()
    {
        this.threadDelegatingWrapperEnabled = false;
        return this;
    }

    /**
     * Remove timing wrapper.
     */
    public NessThreadPoolModule disableTiming()
    {
        this.timingWrapperEnabled = false;
        return this;
    }

    private String createMBeanName()
    {
        return "com.nesscomputing.concurrent:type=ThreadPool,name=" + threadPoolName;
    }

    @Singleton
    class PoolProvider implements Provider<ExecutorService>
    {
        private ThreadPoolConfiguration config;
        private volatile ExecutorService service;
        private volatile ExecutorServiceManagementBean management;
        private Set<CallableWrapper> wrappers;

        @Inject
        public void inject(Config config, Lifecycle lifecycle, Injector injector)
        {
            wrappers = injector.getInstance(Key.get(new TypeLiteral<Set<CallableWrapper>>() {}, annotation));

            this.config = config.getBean("ness.thread-pool." + threadPoolName, ThreadPoolConfiguration.class);

            service = create();

            lifecycle.addListener(LifecycleStage.STOP_STAGE, new LifecycleListener() {
                @Override
                public void onStage(LifecycleStage lifecycleStage)
                {
                    stopExecutor();
                }
            });
        }

        @Override
        public ExecutorService get()
        {
            ExecutorService myService = service;
            Preconditions.checkState(myService != null, "Thread pool %s was injected before lifecycle start or after lifecycle stop.  " +
                    "You might consider injecting a Provider instead, or maybe you forgot a Lifecycle entirely.", threadPoolName);
            return myService;
        }

        void stopExecutor()
        {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();

            ExecutorService myService = service;
            Preconditions.checkState(myService != null, "no service was ever started?");

            myService.shutdown();
            try {
                if (!myService.awaitTermination(20, TimeUnit.SECONDS))
                {
                    LOG.error("Executor service %s did not shut down after 20 seconds of waiting!", threadPoolName);
                    myService.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOG.warn(e, "While awaiting executor %s termination", threadPoolName);
                Thread.currentThread().interrupt();
            }

            LOG.info("Executor service %s shutdown after %s", threadPoolName, stopWatch);
        }

        private ExecutorService create() {
            Preconditions.checkArgument(config != null, "no config injected");

            Integer queueSize = Objects.firstNonNull(config.getQueueSize(), defaultQueueSize);
            Integer minThreads = Objects.firstNonNull(config.getMinThreads(), defaultMinThreads);
            Integer maxThreads = Objects.firstNonNull(config.getMaxThreads(), defaultMaxThreads);
            TimeSpan threadTimeout = Objects.firstNonNull(config.getThreadTimeout(), defaultTimeout);
            RejectedHandler rejectedHandlerEnum = config.getRejectedHandler();
            RejectedExecutionHandler rejectedHandler = rejectedHandlerEnum != null ? rejectedHandlerEnum.getHandler() : defaultRejectedHandler;

            final BlockingQueue<Runnable> queue;
            final ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(threadPoolName + "-%d").build();

            if (queueSize == 0) {
                queue = new SynchronousQueue<Runnable>();
            } else {
                queue = new LinkedBlockingQueue<Runnable>(queueSize);
            }

            final ExecutorService result;

            if (maxThreads <= 0) {
                result = MoreExecutors.sameThreadExecutor();
                management = new GenericExecutorManagementBean(result, new SynchronousQueue<>());
            } else {
                ThreadPoolExecutor executor = new LoggingExecutor(
                        minThreads,
                        maxThreads,
                        threadTimeout.getMillis(),
                        TimeUnit.MILLISECONDS,
                        queue,
                        threadFactory,
                        rejectedHandler);
                management = new ThreadPoolExecutorManagementBean(executor);
                result = executor;
            }

            return DecoratingExecutors.decorate(result, CallableWrappers.combine(wrappers));
        }

        Provider<ExecutorServiceManagementBean> getManagementProvider()
        {
            return new ManagementProvider();
        }

        class ManagementProvider implements Provider<ExecutorServiceManagementBean>
        {
            @Inject
            void setInjector(Injector injector)
            {
                // Ensure that create() has been called so that management is set.
                injector.getInstance(Key.get(ExecutorService.class, annotation));
            }

            @Override
            public ExecutorServiceManagementBean get()
            {
                return management;
            }
        }
    }
}
