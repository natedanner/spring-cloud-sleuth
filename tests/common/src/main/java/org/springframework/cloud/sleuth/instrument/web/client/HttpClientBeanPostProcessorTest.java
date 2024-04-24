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

package org.springframework.cloud.sleuth.instrument.web.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import io.netty.bootstrap.Bootstrap;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;

import org.springframework.cloud.sleuth.TraceContext;
import org.springframework.cloud.sleuth.instrument.web.client.HttpClientBeanPostProcessor.TracingMapConnect;

@ExtendWith(MockitoExtension.class)
public abstract class HttpClientBeanPostProcessorTest {

	@Mock
	Connection connection;

	@Mock
	Bootstrap bootstrap;

	TraceContext traceContext = traceContext();

	public abstract TraceContext traceContext();

	@BeforeEach
	public void setup() {
		Hooks.resetOnEachOperator();
		Hooks.resetOnLastOperator();
		Schedulers.resetOnScheduleHooks();
	}

	@Test
	void mapConnectShouldSetupReactorContextCurrentTraceContext() {
		TracingMapConnect tracingMapConnect = new TracingMapConnect(() -> traceContext);
		AtomicBoolean assertionPassed = new AtomicBoolean();

		Mono<Connection> original = Mono.just(connection)
				.handle(new BiConsumer<Connection, SynchronousSink<Connection>>() {
					@Override
					public void accept(Connection t, SynchronousSink<Connection> ctx) {
						try {
							Assertions.assertThat(ctx.currentContext().get(TraceContext.class)).isSameAs(traceContext);
							Assertions.assertThat((Object) ctx.currentContext().get("sleuth.pending-span")).isNotNull();
							assertionPassed.set(true);
						}
						catch (AssertionError ae) {
						}
					}
				});

		// Wrap and run the assertions
		tracingMapConnect.apply(original).log().subscribe();

		Awaitility.await().atMost(1, TimeUnit.SECONDS).untilTrue(assertionPassed);
	}

	@Test
	void mapConnectShouldSetupReactorContextNoCurrentTraceContext() {
		TracingMapConnect tracingMapConnect = new TracingMapConnect(() -> null);
		AtomicBoolean assertionPassed = new AtomicBoolean();

		Mono<Connection> original = Mono.just(connection)
				.handle(new BiConsumer<Connection, SynchronousSink<Connection>>() {
					@Override
					public void accept(Connection t, SynchronousSink<Connection> ctx) {
						try {
							Assertions.assertThat(ctx.currentContext().getOrEmpty(TraceContext.class)).isEmpty();
							Assertions.assertThat((Object) ctx.currentContext().get("sleuth.pending-span")).isNotNull();
							assertionPassed.set(true);
						}
						catch (AssertionError ae) {
						}
					}
				});

		// Wrap and run the assertions
		tracingMapConnect.apply(original).log().subscribe();

		Awaitility.await().atMost(1, TimeUnit.SECONDS).untilTrue(assertionPassed);
	}

}
