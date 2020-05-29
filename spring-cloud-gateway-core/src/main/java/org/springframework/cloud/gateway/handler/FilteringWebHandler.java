/*
 * Copyright 2013-2017 the original author or authors.
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

package org.springframework.cloud.gateway.handler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * WebHandler that delegates to a chain of {@link GlobalFilter} instances and
 * {@link GatewayFilterFactory} instances then to the target {@link WebHandler}.
 * <p>
 * WebHandler 委托给 GlobalFilter 链 GatewayFilterFactory 实例，然后委托给目标  WebHandler
 *
 * @author Rossen Stoyanchev
 * @author Spencer Gibb
 * @since 0.1
 */
public class FilteringWebHandler implements WebHandler {

	protected static final Log logger = LogFactory.getLog(FilteringWebHandler.class);

	private final List<GatewayFilter> globalFilters;

	/**
	 * 构造 FilteringWebHandler，初始化全局过滤器
	 *
	 * @param globalFilters
	 */
	public FilteringWebHandler(List<GlobalFilter> globalFilters) {
		this.globalFilters = loadFilters(globalFilters);
	}

	/**
	 * 加载 Filter，将GlobalFilter 变成 GatewayFilter
	 *
	 * @param filters
	 * @return
	 */
	private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
		return filters.stream()
		              .map(filter -> {
			              // GlobalFilter 变成 GatewayFilter
			              GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);
			              // 如果是有序的 Filter，则变成 OrderedGatewayFilter
			              if (filter instanceof Ordered) {
				              int order = ((Ordered) filter).getOrder();
				              return new OrderedGatewayFilter(gatewayFilter, order);
			              }
			              return gatewayFilter;
		              }).collect(Collectors.toList());
	}

	/*
	 * TODO: relocate @EventListener(RefreshRoutesEvent.class) void handleRefresh() {
	 * this.combinedFiltersForRoute.clear();
	 */

	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		// 根据 org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRoute 获取路由
		Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
		// 路由过滤器(路由条件中的)
		List<GatewayFilter> gatewayFilters = route.getFilters();
		// 全局过滤器
		List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
		// 将路由过滤器和全局过滤器合并
		combined.addAll(gatewayFilters);
		// TODO: needed or cached?
		// 根据 Bean 的 Order 给过滤器排序
		AnnotationAwareOrderComparator.sort(combined);

		if (logger.isDebugEnabled()) {
			logger.debug("Sorted gatewayFilterFactories: " + combined);
		}

		// 构造默认的过滤器并开始执行过滤
		return new DefaultGatewayFilterChain(combined).filter(exchange);
	}

	/**
	 * 默认的过滤器链实现类
	 */
	private static class DefaultGatewayFilterChain implements GatewayFilterChain {

		// 过滤器的顺序
		private final int index;

		// 过滤器
		private final List<GatewayFilter> filters;

		DefaultGatewayFilterChain(List<GatewayFilter> filters) {
			this.filters = filters;
			// 默认当前过滤器为第一个
			this.index = 0;
		}

		private DefaultGatewayFilterChain(DefaultGatewayFilterChain parent, int index) {
			this.filters = parent.getFilters();
			this.index = index;
		}

		public List<GatewayFilter> getFilters() {
			return filters;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			return Mono.defer(() -> {
				// 如果不是最后一个则继续执行
				if (this.index < filters.size()) {
					// 获取当前过滤器的 index
					GatewayFilter filter = filters.get(this.index);
					// 构建下一个过滤器
					DefaultGatewayFilterChain chain = new DefaultGatewayFilterChain(this, this.index + 1);
					// 执行调用
					return filter.filter(exchange, chain);
				} else {
					// 如果是最后一个，则执行结束
					return Mono.empty(); // complete
				}
			});
		}

	}

	/**
	 * 过滤器适配器
	 */
	private static class GatewayFilterAdapter implements GatewayFilter {

		private final GlobalFilter delegate;

		GatewayFilterAdapter(GlobalFilter delegate) {
			this.delegate = delegate;
		}

		/**
		 * 调用下一个过滤器
		 *
		 * @param exchange the current server exchange
		 * @param chain    provides a way to delegate to the next filter
		 * @return
		 */
		@Override
		public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
			return this.delegate.filter(exchange, chain);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("GatewayFilterAdapter{");
			sb.append("delegate=").append(delegate);
			sb.append('}');
			return sb.toString();
		}

	}

}
