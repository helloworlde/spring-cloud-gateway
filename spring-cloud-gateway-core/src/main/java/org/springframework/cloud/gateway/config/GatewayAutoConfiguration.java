/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.embedded.NettyWebServerFactoryCustomizer;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.cloud.gateway.actuate.GatewayControllerEndpoint;
import org.springframework.cloud.gateway.actuate.GatewayLegacyControllerEndpoint;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.factory.*;
import org.springframework.cloud.gateway.filter.factory.rewrite.*;
import org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.RemoveHopByHopHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.PrincipalNameKeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.handler.predicate.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.StringToZonedDateTimeConverter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.ProxyProvider;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType.DISABLED;
import static org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType.FIXED;

/**
 * @author Spencer Gibb
 * @author Ziemowit Stolarczyk
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)
@EnableConfigurationProperties
@AutoConfigureBefore({HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class})
@AutoConfigureAfter({GatewayReactiveLoadBalancerClientAutoConfiguration.class, GatewayClassPathWarningAutoConfiguration.class})
@ConditionalOnClass(DispatcherHandler.class)
public class GatewayAutoConfiguration {

	/**
	 * 时间转换器
	 *
	 * @return
	 */
	@Bean
	public StringToZonedDateTimeConverter stringToZonedDateTimeConverter() {
		return new StringToZonedDateTimeConverter();
	}

	/**
	 * Route 构建工具
	 *
	 * @param context
	 * @return
	 */
	@Bean
	public RouteLocatorBuilder routeLocatorBuilder(ConfigurableApplicationContext context) {
		return new RouteLocatorBuilder(context);
	}

	/**
	 * 路由配置加载器
	 *
	 * @param properties
	 * @return
	 */
	@Bean
	@ConditionalOnMissingBean
	public PropertiesRouteDefinitionLocator propertiesRouteDefinitionLocator(GatewayProperties properties) {
		return new PropertiesRouteDefinitionLocator(properties);
	}

	/**
	 * 内存路由库
	 *
	 * @return
	 */
	@Bean
	@ConditionalOnMissingBean(RouteDefinitionRepository.class)
	public InMemoryRouteDefinitionRepository inMemoryRouteDefinitionRepository() {
		return new InMemoryRouteDefinitionRepository();
	}

	/**
	 * 路由定义类
	 *
	 * @param routeDefinitionLocators
	 * @return
	 */
	@Bean
	@Primary
	public RouteDefinitionLocator routeDefinitionLocator(List<RouteDefinitionLocator> routeDefinitionLocators) {
		return new CompositeRouteDefinitionLocator(Flux.fromIterable(routeDefinitionLocators));
	}

	@Bean
	public ConfigurationService gatewayConfigurationService(BeanFactory beanFactory,
	                                                        @Qualifier("webFluxConversionService") ObjectProvider<ConversionService> conversionService,
	                                                        ObjectProvider<Validator> validator) {
		return new ConfigurationService(beanFactory, conversionService, validator);
	}

	/**
	 * 路由定义
	 *
	 * @param properties
	 * @param gatewayFilters
	 * @param predicates
	 * @param routeDefinitionLocator
	 * @param configurationService
	 * @return
	 */
	@Bean
	public RouteLocator routeDefinitionRouteLocator(GatewayProperties properties,
	                                                List<GatewayFilterFactory> gatewayFilters,
	                                                List<RoutePredicateFactory> predicates,
	                                                RouteDefinitionLocator routeDefinitionLocator,
	                                                ConfigurationService configurationService) {
		return new RouteDefinitionRouteLocator(routeDefinitionLocator, predicates, gatewayFilters, properties, configurationService);
	}

	@Bean
	@Primary
	@ConditionalOnMissingBean(name = "cachedCompositeRouteLocator")
	// TODO: property to disable composite?
	public RouteLocator cachedCompositeRouteLocator(List<RouteLocator> routeLocators) {
		return new CachingRouteLocator(new CompositeRouteLocator(Flux.fromIterable(routeLocators)));
	}

	/**
	 * 事件监听器
	 *
	 * @param publisher
	 * @return
	 */
	@Bean
	public RouteRefreshListener routeRefreshListener(ApplicationEventPublisher publisher) {
		return new RouteRefreshListener(publisher);
	}

	/**
	 * 加载过滤器处理类
	 *
	 * @param globalFilters
	 * @return
	 */
	@Bean
	public FilteringWebHandler filteringWebHandler(List<GlobalFilter> globalFilters) {
		return new FilteringWebHandler(globalFilters);
	}

	/**
	 * cors 属性
	 *
	 * @return
	 */
	@Bean
	public GlobalCorsProperties globalCorsProperties() {
		return new GlobalCorsProperties();
	}

	@Bean
	public RoutePredicateHandlerMapping routePredicateHandlerMapping(
			FilteringWebHandler webHandler,
			RouteLocator routeLocator,
			GlobalCorsProperties globalCorsProperties,
			Environment environment) {
		return new RoutePredicateHandlerMapping(webHandler, routeLocator, globalCorsProperties, environment);
	}

	@Bean
	public GatewayProperties gatewayProperties() {
		return new GatewayProperties();
	}

	// ConfigurationProperty beans

	@Bean
	public SecureHeadersProperties secureHeadersProperties() {
		return new SecureHeadersProperties();
	}

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.forwarded.enabled",
			matchIfMissing = true)
	public ForwardedHeadersFilter forwardedHeadersFilter() {
		return new ForwardedHeadersFilter();
	}

	// HttpHeaderFilter beans

	@Bean
	public RemoveHopByHopHeadersFilter removeHopByHopHeadersFilter() {
		return new RemoveHopByHopHeadersFilter();
	}

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.x-forwarded.enabled",
			matchIfMissing = true)
	public XForwardedHeadersFilter xForwardedHeadersFilter() {
		return new XForwardedHeadersFilter();
	}

	// GlobalFilter beans

	@Bean
	public AdaptCachedBodyGlobalFilter adaptCachedBodyGlobalFilter() {
		return new AdaptCachedBodyGlobalFilter();
	}

	@Bean
	public RemoveCachedBodyFilter removeCachedBodyFilter() {
		return new RemoveCachedBodyFilter();
	}

	@Bean
	public RouteToRequestUrlFilter routeToRequestUrlFilter() {
		return new RouteToRequestUrlFilter();
	}

	@Bean
	public ForwardRoutingFilter forwardRoutingFilter(
			ObjectProvider<DispatcherHandler> dispatcherHandler) {
		return new ForwardRoutingFilter(dispatcherHandler);
	}

	@Bean
	public ForwardPathFilter forwardPathFilter() {
		return new ForwardPathFilter();
	}

	@Bean
	public WebSocketService webSocketService(
			RequestUpgradeStrategy requestUpgradeStrategy) {
		return new HandshakeWebSocketService(requestUpgradeStrategy);
	}

	@Bean
	public WebsocketRoutingFilter websocketRoutingFilter(WebSocketClient webSocketClient,
	                                                     WebSocketService webSocketService,
	                                                     ObjectProvider<List<HttpHeadersFilter>> headersFilters) {
		return new WebsocketRoutingFilter(webSocketClient, webSocketService,
				headersFilters);
	}

	@Bean
	public WeightCalculatorWebFilter weightCalculatorWebFilter(
			ConfigurationService configurationService,
			ObjectProvider<RouteLocator> routeLocator) {
		return new WeightCalculatorWebFilter(routeLocator, configurationService);
	}

	/*
	 * @Bean //TODO: default over netty? configurable public WebClientHttpRoutingFilter
	 * webClientHttpRoutingFilter() { //TODO: WebClient bean return new
	 * WebClientHttpRoutingFilter(WebClient.routes().build()); }
	 *
	 * @Bean public WebClientWriteResponseFilter webClientWriteResponseFilter() { return
	 * new WebClientWriteResponseFilter(); }
	 */

	// Predicate Factory beans

	@Bean
	public AfterRoutePredicateFactory afterRoutePredicateFactory() {
		return new AfterRoutePredicateFactory();
	}

	@Bean
	public BeforeRoutePredicateFactory beforeRoutePredicateFactory() {
		return new BeforeRoutePredicateFactory();
	}

	@Bean
	public BetweenRoutePredicateFactory betweenRoutePredicateFactory() {
		return new BetweenRoutePredicateFactory();
	}

	@Bean
	public CookieRoutePredicateFactory cookieRoutePredicateFactory() {
		return new CookieRoutePredicateFactory();
	}

	@Bean
	public HeaderRoutePredicateFactory headerRoutePredicateFactory() {
		return new HeaderRoutePredicateFactory();
	}

	@Bean
	public HostRoutePredicateFactory hostRoutePredicateFactory() {
		return new HostRoutePredicateFactory();
	}

	@Bean
	public MethodRoutePredicateFactory methodRoutePredicateFactory() {
		return new MethodRoutePredicateFactory();
	}

	@Bean
	public PathRoutePredicateFactory pathRoutePredicateFactory() {
		return new PathRoutePredicateFactory();
	}

	@Bean
	public QueryRoutePredicateFactory queryRoutePredicateFactory() {
		return new QueryRoutePredicateFactory();
	}

	@Bean
	public ReadBodyPredicateFactory readBodyPredicateFactory(
			ServerCodecConfigurer codecConfigurer) {
		return new ReadBodyPredicateFactory(codecConfigurer.getReaders());
	}

	@Bean
	public RemoteAddrRoutePredicateFactory remoteAddrRoutePredicateFactory() {
		return new RemoteAddrRoutePredicateFactory();
	}

	@Bean
	@DependsOn("weightCalculatorWebFilter")
	public WeightRoutePredicateFactory weightRoutePredicateFactory() {
		return new WeightRoutePredicateFactory();
	}

	@Bean
	public CloudFoundryRouteServiceRoutePredicateFactory cloudFoundryRouteServiceRoutePredicateFactory() {
		return new CloudFoundryRouteServiceRoutePredicateFactory();
	}

	// GatewayFilter Factory beans

	@Bean
	public AddRequestHeaderGatewayFilterFactory addRequestHeaderGatewayFilterFactory() {
		return new AddRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public MapRequestHeaderGatewayFilterFactory mapRequestHeaderGatewayFilterFactory() {
		return new MapRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public AddRequestParameterGatewayFilterFactory addRequestParameterGatewayFilterFactory() {
		return new AddRequestParameterGatewayFilterFactory();
	}

	@Bean
	public AddResponseHeaderGatewayFilterFactory addResponseHeaderGatewayFilterFactory() {
		return new AddResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public ModifyRequestBodyGatewayFilterFactory modifyRequestBodyGatewayFilterFactory(
			ServerCodecConfigurer codecConfigurer) {
		return new ModifyRequestBodyGatewayFilterFactory(codecConfigurer.getReaders());
	}

	@Bean
	public DedupeResponseHeaderGatewayFilterFactory dedupeResponseHeaderGatewayFilterFactory() {
		return new DedupeResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public ModifyResponseBodyGatewayFilterFactory modifyResponseBodyGatewayFilterFactory(
			ServerCodecConfigurer codecConfigurer, Set<MessageBodyDecoder> bodyDecoders,
			Set<MessageBodyEncoder> bodyEncoders) {
		return new ModifyResponseBodyGatewayFilterFactory(codecConfigurer.getReaders(),
				bodyDecoders, bodyEncoders);
	}

	@Bean
	public PrefixPathGatewayFilterFactory prefixPathGatewayFilterFactory() {
		return new PrefixPathGatewayFilterFactory();
	}

	@Bean
	public PreserveHostHeaderGatewayFilterFactory preserveHostHeaderGatewayFilterFactory() {
		return new PreserveHostHeaderGatewayFilterFactory();
	}

	@Bean
	public RedirectToGatewayFilterFactory redirectToGatewayFilterFactory() {
		return new RedirectToGatewayFilterFactory();
	}

	@Bean
	public RemoveRequestHeaderGatewayFilterFactory removeRequestHeaderGatewayFilterFactory() {
		return new RemoveRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public RemoveRequestParameterGatewayFilterFactory removeRequestParameterGatewayFilterFactory() {
		return new RemoveRequestParameterGatewayFilterFactory();
	}

	@Bean
	public RemoveResponseHeaderGatewayFilterFactory removeResponseHeaderGatewayFilterFactory() {
		return new RemoveResponseHeaderGatewayFilterFactory();
	}

	@Bean(name = PrincipalNameKeyResolver.BEAN_NAME)
	@ConditionalOnBean(RateLimiter.class)
	@ConditionalOnMissingBean(KeyResolver.class)
	public PrincipalNameKeyResolver principalNameKeyResolver() {
		return new PrincipalNameKeyResolver();
	}

	@Bean
	@ConditionalOnBean({RateLimiter.class, KeyResolver.class})
	public RequestRateLimiterGatewayFilterFactory requestRateLimiterGatewayFilterFactory(
			RateLimiter rateLimiter, KeyResolver resolver) {
		return new RequestRateLimiterGatewayFilterFactory(rateLimiter, resolver);
	}

	@Bean
	public RewritePathGatewayFilterFactory rewritePathGatewayFilterFactory() {
		return new RewritePathGatewayFilterFactory();
	}

	@Bean
	public RetryGatewayFilterFactory retryGatewayFilterFactory() {
		return new RetryGatewayFilterFactory();
	}

	@Bean
	public SetPathGatewayFilterFactory setPathGatewayFilterFactory() {
		return new SetPathGatewayFilterFactory();
	}

	@Bean
	public SecureHeadersGatewayFilterFactory secureHeadersGatewayFilterFactory(
			SecureHeadersProperties properties) {
		return new SecureHeadersGatewayFilterFactory(properties);
	}

	@Bean
	public SetRequestHeaderGatewayFilterFactory setRequestHeaderGatewayFilterFactory() {
		return new SetRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public SetRequestHostHeaderGatewayFilterFactory setRequestHostHeaderGatewayFilterFactory() {
		return new SetRequestHostHeaderGatewayFilterFactory();
	}

	@Bean
	public SetResponseHeaderGatewayFilterFactory setResponseHeaderGatewayFilterFactory() {
		return new SetResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public RewriteResponseHeaderGatewayFilterFactory rewriteResponseHeaderGatewayFilterFactory() {
		return new RewriteResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public RewriteLocationResponseHeaderGatewayFilterFactory rewriteLocationResponseHeaderGatewayFilterFactory() {
		return new RewriteLocationResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public SetStatusGatewayFilterFactory setStatusGatewayFilterFactory() {
		return new SetStatusGatewayFilterFactory();
	}

	@Bean
	public SaveSessionGatewayFilterFactory saveSessionGatewayFilterFactory() {
		return new SaveSessionGatewayFilterFactory();
	}

	@Bean
	public StripPrefixGatewayFilterFactory stripPrefixGatewayFilterFactory() {
		return new StripPrefixGatewayFilterFactory();
	}

	@Bean
	public RequestHeaderToRequestUriGatewayFilterFactory requestHeaderToRequestUriGatewayFilterFactory() {
		return new RequestHeaderToRequestUriGatewayFilterFactory();
	}

	@Bean
	public RequestSizeGatewayFilterFactory requestSizeGatewayFilterFactory() {
		return new RequestSizeGatewayFilterFactory();
	}

	@Bean
	public RequestHeaderSizeGatewayFilterFactory requestHeaderSizeGatewayFilterFactory() {
		return new RequestHeaderSizeGatewayFilterFactory();
	}

	@Bean
	public GzipMessageBodyResolver gzipMessageBodyResolver() {
		return new GzipMessageBodyResolver();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(HttpClient.class)
	protected static class NettyConfiguration {

		protected final Log logger = LogFactory.getLog(getClass());

		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.httpserver.wiretap")
		public NettyWebServerFactoryCustomizer nettyServerWiretapCustomizer(
				Environment environment, ServerProperties serverProperties) {
			return new NettyWebServerFactoryCustomizer(environment, serverProperties) {
				@Override
				public void customize(NettyReactiveWebServerFactory factory) {
					factory.addServerCustomizers(httpServer -> httpServer.wiretap(true));
					super.customize(factory);
				}
			};
		}

		@Bean
		@ConditionalOnMissingBean
		public HttpClient gatewayHttpClient(HttpClientProperties properties,
		                                    List<HttpClientCustomizer> customizers) {

			// configure pool resources
			HttpClientProperties.Pool pool = properties.getPool();

			ConnectionProvider connectionProvider;
			if (pool.getType() == DISABLED) {
				connectionProvider = ConnectionProvider.newConnection();
			} else if (pool.getType() == FIXED) {
				connectionProvider = ConnectionProvider.fixed(pool.getName(),
						pool.getMaxConnections(), pool.getAcquireTimeout(),
						pool.getMaxIdleTime(), pool.getMaxLifeTime());
			} else {
				connectionProvider = ConnectionProvider.elastic(pool.getName(),
						pool.getMaxIdleTime(), pool.getMaxLifeTime());
			}

			HttpClient httpClient = HttpClient.create(connectionProvider)
			                                  // TODO: move customizations to HttpClientCustomizers
			                                  .httpResponseDecoder(spec -> {
				                                  if (properties.getMaxHeaderSize() != null) {
					                                  // cast to int is ok, since @Max is Integer.MAX_VALUE
					                                  spec.maxHeaderSize(
							                                  (int) properties.getMaxHeaderSize().toBytes());
				                                  }
				                                  if (properties.getMaxInitialLineLength() != null) {
					                                  // cast to int is ok, since @Max is Integer.MAX_VALUE
					                                  spec.maxInitialLineLength(
							                                  (int) properties.getMaxInitialLineLength().toBytes());
				                                  }
				                                  return spec;
			                                  }).tcpConfiguration(tcpClient -> {

						if (properties.getConnectTimeout() != null) {
							tcpClient = tcpClient.option(
									ChannelOption.CONNECT_TIMEOUT_MILLIS,
									properties.getConnectTimeout());
						}

						// configure proxy if proxy host is set.
						HttpClientProperties.Proxy proxy = properties.getProxy();

						if (StringUtils.hasText(proxy.getHost())) {

							tcpClient = tcpClient.proxy(proxySpec -> {
								ProxyProvider.Builder builder = proxySpec
										.type(ProxyProvider.Proxy.HTTP)
										.host(proxy.getHost());

								PropertyMapper map = PropertyMapper.get();

								map.from(proxy::getPort).whenNonNull().to(builder::port);
								map.from(proxy::getUsername).whenHasText()
								   .to(builder::username);
								map.from(proxy::getPassword).whenHasText()
								   .to(password -> builder.password(s -> password));
								map.from(proxy::getNonProxyHostsPattern).whenHasText()
								   .to(builder::nonProxyHosts);
							});
						}
						return tcpClient;
					});

			HttpClientProperties.Ssl ssl = properties.getSsl();
			if ((ssl.getKeyStore() != null && ssl.getKeyStore().length() > 0)
					|| ssl.getTrustedX509CertificatesForTrustManager().length > 0
					|| ssl.isUseInsecureTrustManager()) {
				httpClient = httpClient.secure(sslContextSpec -> {
					// configure ssl
					SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();

					X509Certificate[] trustedX509Certificates = ssl
							.getTrustedX509CertificatesForTrustManager();
					if (trustedX509Certificates.length > 0) {
						sslContextBuilder = sslContextBuilder
								.trustManager(trustedX509Certificates);
					} else if (ssl.isUseInsecureTrustManager()) {
						sslContextBuilder = sslContextBuilder
								.trustManager(InsecureTrustManagerFactory.INSTANCE);
					}

					try {
						sslContextBuilder = sslContextBuilder
								.keyManager(ssl.getKeyManagerFactory());
					} catch (Exception e) {
						logger.error(e);
					}

					sslContextSpec.sslContext(sslContextBuilder)
					              .defaultConfiguration(ssl.getDefaultConfigurationType())
					              .handshakeTimeout(ssl.getHandshakeTimeout())
					              .closeNotifyFlushTimeout(ssl.getCloseNotifyFlushTimeout())
					              .closeNotifyReadTimeout(ssl.getCloseNotifyReadTimeout());
				});
			}

			if (properties.isWiretap()) {
				httpClient = httpClient.wiretap(true);
			}

			if (!CollectionUtils.isEmpty(customizers)) {
				customizers.sort(AnnotationAwareOrderComparator.INSTANCE);
				for (HttpClientCustomizer customizer : customizers) {
					httpClient = customizer.customize(httpClient);
				}
			}

			return httpClient;
		}

		@Bean
		public HttpClientProperties httpClientProperties() {
			return new HttpClientProperties();
		}

		@Bean
		public NettyRoutingFilter routingFilter(HttpClient httpClient,
		                                        ObjectProvider<List<HttpHeadersFilter>> headersFilters,
		                                        HttpClientProperties properties) {
			return new NettyRoutingFilter(httpClient, headersFilters, properties);
		}

		@Bean
		public NettyWriteResponseFilter nettyWriteResponseFilter(
				GatewayProperties properties) {
			return new NettyWriteResponseFilter(properties.getStreamingMediaTypes());
		}

		@Bean
		public ReactorNettyWebSocketClient reactorNettyWebSocketClient(
				HttpClientProperties properties, HttpClient httpClient) {
			ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient(
					httpClient);
			if (properties.getWebsocket().getMaxFramePayloadLength() != null) {
				webSocketClient.setMaxFramePayloadLength(
						properties.getWebsocket().getMaxFramePayloadLength());
			}
			webSocketClient.setHandlePing(properties.getWebsocket().isProxyPing());
			return webSocketClient;
		}

		@Bean
		public ReactorNettyRequestUpgradeStrategy reactorNettyRequestUpgradeStrategy(
				HttpClientProperties httpClientProperties) {
			ReactorNettyRequestUpgradeStrategy requestUpgradeStrategy = new ReactorNettyRequestUpgradeStrategy();

			HttpClientProperties.Websocket websocket = httpClientProperties
					.getWebsocket();
			PropertyMapper map = PropertyMapper.get();
			map.from(websocket::getMaxFramePayloadLength).whenNonNull()
			   .to(requestUpgradeStrategy::setMaxFramePayloadLength);
			map.from(websocket::isProxyPing).to(requestUpgradeStrategy::setHandlePing);
			return requestUpgradeStrategy;
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(Health.class)
	protected static class GatewayActuatorConfiguration {

		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.actuator.verbose.enabled",
				matchIfMissing = true)
		@ConditionalOnAvailableEndpoint
		public GatewayControllerEndpoint gatewayControllerEndpoint(
				List<GlobalFilter> globalFilters,
				List<GatewayFilterFactory> gatewayFilters,
				List<RoutePredicateFactory> routePredicates,
				RouteDefinitionWriter routeDefinitionWriter, RouteLocator routeLocator) {
			return new GatewayControllerEndpoint(globalFilters, gatewayFilters,
					routePredicates, routeDefinitionWriter, routeLocator);
		}

		@Bean
		@Conditional(OnVerboseDisabledCondition.class)
		@ConditionalOnAvailableEndpoint
		public GatewayLegacyControllerEndpoint gatewayLegacyControllerEndpoint(
				RouteDefinitionLocator routeDefinitionLocator,
				List<GlobalFilter> globalFilters,
				List<GatewayFilterFactory> gatewayFilters,
				List<RoutePredicateFactory> routePredicates,
				RouteDefinitionWriter routeDefinitionWriter, RouteLocator routeLocator) {
			return new GatewayLegacyControllerEndpoint(routeDefinitionLocator,
					globalFilters, gatewayFilters, routePredicates, routeDefinitionWriter,
					routeLocator);
		}

	}

	private static class OnVerboseDisabledCondition extends NoneNestedConditions {

		OnVerboseDisabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(name = "spring.cloud.gateway.actuator.verbose.enabled",
				matchIfMissing = true)
		static class VerboseDisabled {

		}

	}

}
