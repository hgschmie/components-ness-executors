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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;

class DecoratingExecutorService extends DelegatingExecutorService implements ExecutorService
{
    private final ExecutorService wrappedExcutorService;
    private final CallableWrapper wrapper;

    DecoratingExecutorService(final ExecutorService wrappedExecutorService, final CallableWrapper wrapper)
    {
        super(wrappedExecutorService);
        this.wrappedExcutorService = wrappedExecutorService;
        this.wrapper = wrapper;
    }

    @Override
    public void execute(final Runnable command)
    {
        wrappedExcutorService.execute(wrapper.wrap(command));
    }

    @Override
    public <T> Future<T> submit(final Callable<T> task)
    {
        return wrappedExcutorService.submit(wrapper.wrap(task));
    }

    @Override
    public <T> Future<T> submit(final Runnable task, final T result)
    {
        return wrappedExcutorService.submit(wrapper.wrap(task),result);
    }

    @Override
    public Future<?> submit(final Runnable task)
    {
        return wrappedExcutorService.submit(wrapper.wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) throws InterruptedException
    {
        Preconditions.checkArgument(tasks != null, "collection of tasks can not be null!");
        return wrappedExcutorService.invokeAll(meatify(tasks));

    }

    @Override
    public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) throws InterruptedException
    {
        Preconditions.checkArgument(tasks != null, "collection of tasks can not be null!");
        return wrappedExcutorService.invokeAll(meatify(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException
    {
        return wrappedExcutorService.invokeAny(meatify(tasks));
    }

    @Override
    public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        return wrappedExcutorService.invokeAny(meatify(tasks), timeout, unit);
    }

    private <T> Collection<Callable<T>> meatify(final Collection<? extends Callable<T>> callables)
    {
        return Collections2.transform(callables, new Function<Callable<T>, Callable<T>>() {
            @Override
            public Callable<T> apply(final Callable<T> input) {
                return wrapper.wrap(input);
            }
        });
    }
}
