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

package org.springframework.cloud.gateway.route;

import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.client.discovery.event.HeartbeatMonitor;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.discovery.event.ParentHeartbeatEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.Assert;

/**
 * 监听不同的事件，根据事件类型决定是否要发送路由更新消息
 */
public class RouteRefreshListener implements ApplicationListener<ApplicationEvent> {

	private final ApplicationEventPublisher publisher;

	private HeartbeatMonitor monitor = new HeartbeatMonitor();

	public RouteRefreshListener(ApplicationEventPublisher publisher) {
		Assert.notNull(publisher, "publisher may not be null");
		this.publisher = publisher;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		// 如果是上下文刷新，Scope 刷新或者有新的实例注册，则发送路由更新消息
		if (event instanceof ContextRefreshedEvent
				|| event instanceof RefreshScopeRefreshedEvent
				|| event instanceof InstanceRegisteredEvent) {
			reset();
		} else if (event instanceof ParentHeartbeatEvent) {
			// 如果是父类的心跳消息，查看是否变化，如果变化则更新
			ParentHeartbeatEvent e = (ParentHeartbeatEvent) event;
			resetIfNeeded(e.getValue());
		} else if (event instanceof HeartbeatEvent) {
			// 如果是心跳消息，查看是否变化，如果变化则更新
			HeartbeatEvent e = (HeartbeatEvent) event;
			resetIfNeeded(e.getValue());
		}
	}

	/**
	 * 如果更新了，则发送消息
	 *
	 * @param value
	 */
	private void resetIfNeeded(Object value) {
		if (this.monitor.update(value)) {
			reset();
		}
	}

	/**
	 * 发送路由更新消息
	 */
	private void reset() {
		this.publisher.publishEvent(new RefreshRoutesEvent(this));
	}

}
