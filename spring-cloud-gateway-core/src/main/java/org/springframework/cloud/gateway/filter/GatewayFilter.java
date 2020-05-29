/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.gateway.filter;

import org.springframework.cloud.gateway.support.ShortcutConfigurable;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Contract for interception-style, chained processing of Web requests that may be used to
 * implement cross-cutting, application-agnostic requirements such as security, timeouts,
 * and others. Specific to a Gateway
 * <p>
 * 链接处理的Web请求，可用于实现横切，应用程序无关的需求，如安全，超时，和其他。
 * <p>
 * Copied from WebFilter
 *
 * @author Rossen Stoyanchev
 * @since 5.0
 */
public interface GatewayFilter extends ShortcutConfigurable {

	/**
	 * Name key.
	 */
	String NAME_KEY = "name";

	/**
	 * Value key.
	 */
	String VALUE_KEY = "value";

	/**
	 * Process the Web request and (optionally) delegate to the next {@code WebFilter}
	 * through the given {@link GatewayFilterChain}.
	 * <p>
	 * 处理 web 请求并选择调用下一个过滤器
	 *
	 * @param exchange the current server exchange
	 * @param chain    provides a way to delegate to the next filter
	 * @return {@code Mono<Void>} to indicate when request processing is complete
	 */
	Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain);

}
