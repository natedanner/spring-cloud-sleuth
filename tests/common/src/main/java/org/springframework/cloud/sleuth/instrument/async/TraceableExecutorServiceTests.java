/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.async;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.BDDMockito;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.sleuth.CurrentTraceContext;
import org.springframework.cloud.sleuth.ScopedSpan;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.TraceContext;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.internal.DefaultSpanNamer;
import org.springframework.cloud.sleuth.internal.SleuthContextListenerAccessor;
import org.springframework.cloud.sleuth.test.TestTracingAwareSupplier;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.BDDAssertions.then;

@ExtendWith(MockitoExtension.class)
public abstract class TraceableExecutorServiceTests implements TestTracingAwareSupplier {

	private static int totalThreads = 10;

	@Mock(lenient = true)
	BeanFactory beanFactory;

	ExecutorService executorService = Executors.newFixedThreadPool(3);

	TraceableExecutorService traceManagerableExecutorService;

	SpanVerifyingRunnable spanVerifyingRunnable = new SpanVerifyingRunnable();

	Tracer tracer = tracerTest().tracing().tracer();

	CurrentTraceContext currentTraceContext = tracerTest().tracing().currentTraceContext();

	@BeforeEach
	public void setup() {
		this.traceManagerableExecutorService = new TraceableExecutorService(beanFactory(true), this.executorService,
				"foo");
		this.spanVerifyingRunnable.clear();
	}

	@AfterEach
	public void tearDown() {
		this.traceManagerableExecutorService.shutdown();
		this.executorService.shutdown();
	}

	@Test
	public void should_propagate_trace_id_and_set_new_span_when_traceable_executor_service_is_executed()
			throws Exception {
		ScopedSpan span = this.tracer.startScopedSpan("http:PARENT");
		try {
			CompletableFuture.allOf(runnablesExecutedViaTraceManagerableExecutorService()).get();
		}
		finally {
			span.end();
		}

		then(this.spanVerifyingRunnable.traceIds.stream().distinct().collect(toList())).hasSize(1);
		then(this.spanVerifyingRunnable.spanIds.stream().distinct().collect(toList())).hasSize(totalThreads);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void should_wrap_methods_in_trace_representation_only_for_non_tracing_callables() throws Exception {
		ExecutorService executorService = Mockito.mock(ExecutorService.class);
		TraceableExecutorService traceExecutorService = new TraceableExecutorService(beanFactory(true),
				executorService);

		traceExecutorService.invokeAll(callables());
		BDDMockito.then(executorService).should().invokeAll(BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()));

		traceExecutorService.invokeAll(callables(), 1L, TimeUnit.DAYS);
		BDDMockito.then(executorService).should().invokeAll(BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()),
				BDDMockito.eq(1L), BDDMockito.eq(TimeUnit.DAYS));

		traceExecutorService.invokeAny(callables());
		BDDMockito.then(executorService).should().invokeAny(BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()));

		traceExecutorService.invokeAny(callables(), 1L, TimeUnit.DAYS);
		BDDMockito.then(executorService).should().invokeAny(BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()),
				BDDMockito.eq(1L), BDDMockito.eq(TimeUnit.DAYS));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void should_not_wrap_methods_in_trace_representation_only_for_non_tracing_callables_when_context_not_ready()
			throws Exception {
		ExecutorService executorService = Mockito.mock(ExecutorService.class);
		TraceableExecutorService traceExecutorService = new TraceableExecutorService(beanFactory(false),
				executorService);

		traceExecutorService.invokeAll(callables());
		BDDMockito.then(executorService).should(BDDMockito.never())
				.invokeAll(BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()));

		traceExecutorService.invokeAll(callables(), 1L, TimeUnit.DAYS);
		BDDMockito.then(executorService).should(BDDMockito.never()).invokeAll(
				BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()), BDDMockito.eq(1L),
				BDDMockito.eq(TimeUnit.DAYS));

		traceExecutorService.invokeAny(callables());
		BDDMockito.then(executorService).should(BDDMockito.never())
				.invokeAny(BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()));

		traceExecutorService.invokeAny(callables(), 1L, TimeUnit.DAYS);
		BDDMockito.then(executorService).should(BDDMockito.never()).invokeAny(
				BDDMockito.argThat(withSpanContinuingTraceCallablesOnly()), BDDMockito.eq(1L),
				BDDMockito.eq(TimeUnit.DAYS));
	}

	private ArgumentMatcher<Collection<? extends Callable<Object>>> withSpanContinuingTraceCallablesOnly() {
		return argument -> {
			try {
				then(argument).flatExtracting(Object::getClass)
						.containsOnlyElementsOf(Collections.singletonList(TraceCallable.class));
			}
			catch (AssertionError e) {
				return false;
			}
			return true;
		};
	}

	private List callables() {
		List list = new ArrayList<>();
		list.add(new TraceCallable<>(this.tracer, new DefaultSpanNamer(), () -> "foo"));
		list.add((Callable) () -> "bar");
		return list;
	}

	@Test
	public void should_propagate_trace_info_when_compleable_future_is_used() throws Exception {
		ExecutorService executorService = this.executorService;
		BeanFactory beanFactory = beanFactory(true);
		// tag::completablefuture[]
		CompletableFuture<Long> completableFuture = CompletableFuture.supplyAsync(() ->
			// perform some logic
			1_000_000L, new TraceableExecutorService(beanFactory, executorService,
				// 'calculateTax' explicitly names the span - this param is optional
				"calculateTax"));
		// end::completablefuture[]

		then(completableFuture.get()).isEqualTo(1_000_000L);
		then(this.tracer.currentSpan()).isNull();
	}

	@Test
	public void should_remove_entries_from_cache_when_executor_service_shutsdown() throws Exception {
		then(TraceableExecutorService.CACHE).doesNotContainKey(executorService);

		TraceableExecutorService.wrap(beanFactory, executorService, "foo").shutdown();

		then(TraceableExecutorService.CACHE).doesNotContainKey(executorService);

		TraceableExecutorService.wrap(beanFactory, executorService, "foo").shutdownNow();

		then(TraceableExecutorService.CACHE).doesNotContainKey(executorService);
	}

	@Test
	public void should_not_propagate_trace_info_when_compleable_future_is_used_when_context_not_refreshed()
			throws Exception {
		ExecutorService executorService = this.executorService;
		BeanFactory beanFactory = beanFactory(false);
		CompletableFuture<Long> completableFuture = CompletableFuture.supplyAsync(() ->
			// perform some logic
			1_000_000L, new TraceableExecutorService(beanFactory, executorService, "calculateTax"));

		then(completableFuture.get()).isEqualTo(1_000_000L);
		then(this.tracer.currentSpan()).isNull();
	}

	private CompletableFuture<?>[] runnablesExecutedViaTraceManagerableExecutorService() {
		List<CompletableFuture<?>> futures = new ArrayList<>();
		for (int i = 0; i < totalThreads; i++) {
			futures.add(CompletableFuture.runAsync(this.spanVerifyingRunnable, this.traceManagerableExecutorService));
		}
		return futures.toArray(new CompletableFuture[futures.size()]);
	}

	BeanFactory beanFactory(boolean refreshed) {
		BDDMockito.given(this.beanFactory.getBean(org.springframework.cloud.sleuth.Tracer.class))
				.willReturn(this.tracer);
		BDDMockito.given(this.beanFactory.getBean(SpanNamer.class)).willReturn(new DefaultSpanNamer());
		SleuthContextListenerAccessor.set(this.beanFactory, refreshed);
		return this.beanFactory;
	}

	class SpanVerifyingRunnable implements Runnable {

		Queue<String> traceIds = new ConcurrentLinkedQueue<>();

		Queue<String> spanIds = new ConcurrentLinkedQueue<>();

		@Override
		public void run() {
			TraceContext context = currentTraceContext.context();
			this.traceIds.add(context.traceId());
			this.spanIds.add(context.spanId());
		}

		void clear() {
			this.traceIds.clear();
			this.spanIds.clear();
		}

	}

}
